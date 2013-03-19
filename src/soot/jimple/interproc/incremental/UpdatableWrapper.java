package soot.jimple.interproc.incremental;

/**
 * Common interface for all sorts of wrappers which decouple arbitrary objects
 * and make their references exchangeable.
 *
 * @param <N> The type of object to wrap.
 */
public interface UpdatableWrapper<N> extends CFGChangeListener {

	
	/**
	 * Gets the object being wrapped.
	 * @return The object inside this wrapper
	 */
	public N getContents();
	
	/**
	 * Gets the object that was wrapped at the last safe point, i.e. when
	 * setSafepoint() was last called.
	 * @return The previous contents of this wrapper.
	 */
	public N getPreviousContents();
	
	/**
	 * Gets whether this wrapper contains the previous reference. This is
	 * equivalent to checking whether a safe point has been created.
	 * @return True if this wrapper stores a previous reference, otherwise
	 * false.
	 */
	boolean hasPreviousContents();
	
	/**
	 * Creates a new safe point. This copies the current contents into the
	 * "previousContents" property for later access.
	 */
	public void setSafepoint();
	
}
