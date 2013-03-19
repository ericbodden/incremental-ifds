package soot.jimple.interproc.incremental;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import soot.jimple.interproc.ifds.InterproceduralCFG;
import soot.jimple.interproc.ifds.utils.Utils;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.MapMaker;

/**
 * Abstract base class that provides listener registration and deregistration
 * functionality for interprocedural program graphs.
 *
 * @param <N> Nodes in the CFG, typically Unit or Block
 * @param <M> Method representation
 */
public abstract class AbstractUpdatableInterproceduralCFG<N,M> implements InterproceduralCFG<N, M> {

	public static final boolean BROADCAST_NOTIFICATIONS = true;

	private final LoadingCache<Object, UpdatableWrapper<?>> wrappedObjects;
	private final Map<Object, Set<CFGChangeListener>> objectListeners;
	private final Set<CFGChangeListener> globalListeners = new HashSet<CFGChangeListener>();
	
	public AbstractUpdatableInterproceduralCFG() {
		CacheBuilder<Object, Object> cb = CacheBuilder.newBuilder().concurrencyLevel
				(Runtime.getRuntime().availableProcessors()).initialCapacity(100000); //.weakKeys();		
		wrappedObjects = cb.build(new CacheLoader<Object, UpdatableWrapper<?>>() {

			@SuppressWarnings({ "unchecked", "rawtypes" })
			@Override
			public UpdatableWrapper<?> load(Object key) throws Exception {
				UpdatableWrapper wrapped = new DefaultUpdatableWrapper(key);
				registerListener(wrapped, key);
				return wrapped;
			}

		});
		
		objectListeners = new MapMaker().concurrencyLevel(Runtime.getRuntime().availableProcessors()).initialCapacity
			(100000).makeMap();
	}
	
	@Override
	public void registerListener(CFGChangeListener listener, Object reference) {
		if (!BROADCAST_NOTIFICATIONS || listener != reference)
			Utils.addElementToMapSet(objectListeners, reference, listener);			
	}

	@Override
	public void registerListener(CFGChangeListener listener) {
		synchronized (globalListeners) {
			if (!globalListeners.contains(listener))
				globalListeners.add(listener);
		}
	}

	@Override
	public void unregisterListener(CFGChangeListener listener, Object reference) {
		if (!BROADCAST_NOTIFICATIONS || listener != reference)
			Utils.removeElementFromMapSet(objectListeners, reference, listener);
	}

	@Override
	public void unregisterListener(CFGChangeListener listener) {
		synchronized (globalListeners) {
			globalListeners.remove(listener);
		}
	}
	
	/**
	 * Notifies all registered listeners that an object reference has changed.
	 * @param oldObject The old object that is replaced
	 * @param newObject The object that takes the place of the old one
	 */
	protected void notifyReferenceChanged(Object oldObject, Object newObject) {
		// Avoid spurious notifications
		if (oldObject == newObject)
			return;

		Set<CFGChangeListener> invokedListeners = new HashSet<CFGChangeListener>(1000);

		// Get the wrapper for the old object. If we broadcast notifications, we
		// directly inform this object.
		try {
			UpdatableWrapper<?> wrapper = this.wrappedObjects.get(oldObject);
			if (BROADCAST_NOTIFICATIONS && wrapper != null) {
				wrapper.notifyReferenceChanged(oldObject, newObject);
				invokedListeners.add(wrapper);
			}
		
			// Notify all explicitly registered object listeners
			Set<CFGChangeListener> objListeners = objectListeners.get(oldObject);
			if (objListeners != null) {
				for (CFGChangeListener listener : objListeners) {
					if (listener != null && invokedListeners.add(listener))
						listener.notifyReferenceChanged(oldObject, newObject);
				}
					
				// Make sure that we don't loose track of our listeners. Expired
				// listeners for gc'ed objects will automatically be removed by
				// the WeakHashMap.
				objectListeners.put(newObject, objListeners);
			}
				
			// Notify the global listeners that have not yet been notified as
			// object listeners
			for (CFGChangeListener listener : globalListeners)
				if (!invokedListeners.contains(listener))
					listener.notifyReferenceChanged(oldObject, newObject);
			
			// We must also update our list of wrapped objects
			this.wrappedObjects.put(newObject, wrapper);
//			this.wrappedObjects.remove(oldObject);
		} catch (ExecutionException e) {
			System.err.println("Could not wrap object");
			e.printStackTrace();
		}		
	}
	
	/**
	 * Sets a safe point, i.e. creates a backup of all current wrapped object
	 * references so that they can still be accessed after an update.
	 */
	protected void setSafepoint() {
		for (UpdatableWrapper<?> uw : this.wrappedObjects.asMap().values())
			uw.setSafepoint();
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <X> UpdatableWrapper<X> wrapWeak(X obj) {
		assert obj != null;
		try {
			return (UpdatableWrapper<X>) this.wrappedObjects.get(obj);
		} catch (ExecutionException e) {
			System.err.println("Could not wrap object");
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public <X> List<UpdatableWrapper<X>> wrapWeak(List<X> list) {
		assert list != null;
		List<UpdatableWrapper<X>> resList = new ArrayList<UpdatableWrapper<X>>();
		for (X x : list)
			resList.add(wrapWeak(x));
		return resList;
	}

	@Override
	public <X> Set<UpdatableWrapper<X>> wrapWeak(Set<X> set) {
		assert set != null;
		Set<UpdatableWrapper<X>> resSet = new HashSet<UpdatableWrapper<X>>(set.size());
		for (X x : set)
			resSet.add(wrapWeak(x));
		return resSet;
	}

	@Override
	public void mergeWrappers(InterproceduralCFG<N, M> otherCfg) {
		if (!(otherCfg instanceof AbstractUpdatableInterproceduralCFG))
			throw new RuntimeException("Unexpected control flow graph type");
		
		AbstractUpdatableInterproceduralCFG<N, M> other =
				(AbstractUpdatableInterproceduralCFG<N, M>) otherCfg;
		this.wrappedObjects.asMap().putAll(other.wrappedObjects.asMap());
	}

}
