package soot.jimple.interproc.incremental;

/**
 * Wrapper class for decoupling arbitrary objects and making their references
 * exchangeable. This class holds a strong reference to object being wrapped.
 *
 * @param <N> The type of object to wrap.
 */
public class DefaultUpdatableWrapper<N> implements UpdatableWrapper<N> {
	
	private N contents;
	private N previousContents;
	private int updateCount = 0;
	
	/**
	 * Creates a new instance of the UpdatableWrapper class.
	 * @param n The object to be wrapped
	 */
	public DefaultUpdatableWrapper(N n) {
		this.contents = n;
		this.previousContents = null;
	}
	
	@Override
	public N getContents() {
		return this.contents;
	}
	
	@Override
	public N getPreviousContents() {
		return this.previousContents;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void notifyReferenceChanged(Object oldObject, Object newObject) {
		if (oldObject != newObject && contents == oldObject) {
			contents = (N) newObject;
			updateCount++;
		}
	}

	@Override
	public String toString() {
		return contents == null ? "<null>" : contents.toString();
	}
	
	/*
	 * The idea that two wrappers are equal if the respective wrapped objects
	 * are equal has the nasty consequence that the hash code depends on the
	 * object's state - if the object is used as a key in a hash map, the
	 * behavior of the map becomes undefined.
	 * 
	@Override
	public boolean equals(Object another) {
		if (super.equals(another))
			return true;
		if (another == null)
			return false;
		if (!(another instanceof UpdatableWrapper))
			return false;
		@SuppressWarnings("unchecked")
		UpdatableWrapper<N> other = (UpdatableWrapper<N>) another;
		return this.contents.equals(other.contents);
	}
	
	@Override
	public int hashCode() {
		return contents == null ? 0 : contents.hashCode();
	}
	*/

	@Override
	public void setSafepoint() {
		this.previousContents = this.contents;
	}

	@Override
	public boolean hasPreviousContents() {
		return this.previousContents != null;
	}
	
}
