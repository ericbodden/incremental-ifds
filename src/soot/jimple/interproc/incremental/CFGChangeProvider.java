package soot.jimple.interproc.incremental;

/**
 * Provides object reference changs to the control-flow graph to registered
 * listeners. The listeners are called whenever an object is replaced by a
 * semantically equivalent one which is supposed to the place of the old one. 
 */
public interface CFGChangeProvider {
	
	/**
	 * Registers a listener that will be called whenever the given object is
	 * replaced. Note that listeners are unordered - do not rely on receiving
	 * notifications before or after some other listener.
	 * @param listener The listener to be called upon reference update
	 * @param reference The object that shall be monitored for reference updated
	 */
	public void registerListener(CFGChangeListener listener, Object reference);
	
	/**
	 * Registers a listener that will be called whenever any object is replaced.
	 * Note that listeners are unordered - do not rely on receiving notifications
	 * before or after some other listener.
	 * @param listener The listener to be called upon reference update
	 */
	public void registerListener(CFGChangeListener listener);

	/**
	 * Unregisters a listener for a single object
	 * @param listener The listener to be removed
	 * @param reference The object for which the listener shall be removed
	 */
	public void unregisterListener(CFGChangeListener listener, Object reference);
	
	/**
	 * Unregisters a global listener for all objects
	 * @param listener The listener to be removed
	 */
	public void unregisterListener(CFGChangeListener listener);

}
