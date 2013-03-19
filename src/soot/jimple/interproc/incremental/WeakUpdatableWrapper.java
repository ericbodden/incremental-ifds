package soot.jimple.interproc.incremental;

import java.lang.ref.WeakReference;

/**
 * Wrapper class for decoupling arbitrary objects and making their references
 * exchangeable. This object holds a weak reference to the object being
 * wrapped, allowing for garbage collection.
 *
 * @param <N> The type of object to wrap.
 */
public class WeakUpdatableWrapper<N> implements UpdatableWrapper<N> {
	
	private WeakReference<N> contents;
	private WeakReference<N> previousContents;
	private int updateCount = 0;
	
	/**
	 * Creates a new instance of the UpdatableWrapper class.
	 * @param n The object to be wrapped
	 */
	public WeakUpdatableWrapper(N n) {
		this.contents = new WeakReference<N>(n);
		this.previousContents = null;
	}
	
	@Override
	public N getContents() {
		return this.contents.get();
	}
	
	@Override
	public N getPreviousContents() {
		if (this.previousContents == null)
			throw new RuntimeException("No safepoint available on this wrapper. Call "
					+ "setSafepoint() first.");
		return this.previousContents.get();
	}

	@SuppressWarnings("unchecked")
	@Override
	public void notifyReferenceChanged(Object oldObject, Object newObject) {
		if (oldObject != newObject && contents.get() == oldObject) {
			contents = new WeakReference<N>((N) newObject);
			updateCount++;
		}
	}

	@Override
	public String toString() {
		return contents.get() == null ? "<null>" : contents.get().toString();
	}

	@Override
	public void setSafepoint() {
		this.previousContents = this.contents;
	}
	
	@Override
	public boolean hasPreviousContents() {
		return this.previousContents != null;
	}

}
