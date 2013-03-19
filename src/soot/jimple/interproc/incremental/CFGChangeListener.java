package soot.jimple.interproc.incremental;

/**
 * Listener interface for CFG change notifications. Classes can implement this
 * interface and register at a change provider to be notified when CFG objects
 * are replaced by semantically equivalent ones (i.e. the references are updated)
 * @see {@link CFGChangeProvider}.
 */
public interface CFGChangeListener {

	/**
	 * Method that is called when an object reference is changed.
	 * Note that notifications may be distributed in an arbitrary order - do not
	 * rely on some notification arriving before or after another one. However,
	 * if is an update sequence for one object, e.g. a->b->c, you are assured to
	 * receive a->b before you receive b->c.
	 * Furthermore, note that notifications may be repeated. Do not assume
	 * uniqueness.
	 * @param oldObject The old object
	 * @param newObject The new object which shall take the place of the old one
	 */
	void notifyReferenceChanged(Object oldObject, Object newObject);

}
