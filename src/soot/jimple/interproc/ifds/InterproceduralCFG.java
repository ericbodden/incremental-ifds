package soot.jimple.interproc.ifds;

import java.util.List;
import java.util.Map;
import java.util.Set;

import soot.jimple.interproc.incremental.CFGChangeProvider;
import soot.jimple.interproc.incremental.UpdatableWrapper;

/**
 * An interprocedural control-flow graph.
 * 
 * @param <N> Nodes in the CFG, typically {@link Unit} or {@link Block}
 * @param <M> Method representation
 */
public interface InterproceduralCFG<N,M> extends CFGChangeProvider  {
	
	/**
	 * Returns the method containing a node.
	 * @param n The node for which to get the parent method
	 */
	public M getMethodOf(N n);

	/**
	 * Returns the successor nodes.
	 */
	public List<N> getSuccsOf(N n);

	/**
	 * Returns the predecessor nodes.
	 */
	public List<N> getPredsOf(N n);

	/**
	 * Returns all callee methods for a given call.
	 */
	public Set<M> getCalleesOfCallAt(N n);

	/**
	 * Returns all caller statements/nodes of a given method.
	 */
	public Set<N> getCallersOf(M m);

	/**
	 * Returns all call sites within a given method.
	 */
	public Set<N> getCallsFromWithin(M m);

	/**
	 * Returns all start points of a given method. There may be
	 * more than one start point in case of a backward analysis.
	 */
	public Set<N> getStartPointsOf(M m);

	/**
	 * Returns all statements to which a call could return.
	 * In the RHS paper, for every call there is just one return site.
	 * We, however, use as return site the successor statements, of which
	 * there can be many in case of exceptional flow.
	 */
	public List<N> getReturnSitesOfCallAt(N n);

	/**
	 * Returns <code>true</code> if the given statement is a call site.
	 */
	public boolean isCallStmt(N stmt);

	/**
	 * Returns <code>true</code> if the given statement leads to a method return
	 * (exceptional or not). For backward analyses may also be start statements.
	 */
	public boolean isExitStmt(N stmt);
	
	/**
	 * Returns true is this is a method's start statement. For backward analyses
	 * those may also be return or throws statements.
	 */
	public boolean isStartPoint(N stmt);
	
	/**
	 * Returns the set of all nodes that are neither call nor start nodes.
	 */
	public Set<N> allNonCallStartNodes();
	
	/**
	 * Returns whether succ is the fall-through successor of stmt,
	 * i.e., the unique successor that is be reached when stmt
	 * does not branch.
	 */
	public boolean isFallThroughSuccessor(N stmt, N succ);
	
	/**
	 * Returns whether succ is a branch target of stmt. 
	 */
	public boolean isBranchTarget(N stmt, N succ);
	
	/**
	 * Returns whether this control-flow graph contains the given statement.
	 * This function is used for matching graphs.
	 */
	public boolean containsStmt(N stmt);
	
	/**
	 * Returns all nodes in this control-flow graph
	 */
	public List<N> getAllNodes();
	
	/**
	 * Computes a change set of added and removed edges in the control-flow
	 * graph
	 * @param newCFG The control flow graph after the update
	 * @param expiredEdges A list which receives the edges that are no longer
	 * present in the updated CFG
	 * @param newEdges A list which receives the edges that have been newly
	 * introduced in the updated CFG
	 * @param newNodes A list which receives the nodes that have been newly
	 * introduced in the updated CFG
	 * @param expiredNodes A list which receives the nodes that have been newly
	 * introduced in the updated CFG
	 */
	public void computeCFGChangeset(InterproceduralCFG<N, M> newCFG,
			Map<N, List<N>> expiredEdges,
			Map<N, List<N>> newEdges,
			Set<N> newNodes,
			Set<N> expiredNodes);

	/**
	 * Finds a statement equivalent to the given one in the given method. The
	 * equivalence relation is left implicit here - in case multiple statements
	 * are equivalent to the given one, the "best/closest" pick shall be chosen.
	 * @param oldMethod The method in which to look for the statement
	 * @param newStmt The statement for which to look
	 * @return A statement in the given method which is equivalent to the
	 * given statement or NULL if no such statement could be found.
	 */
	public N findStatement(M oldMethod, N newStmt);

	/**
	 * Finds a statement equivalent to the given one in the given list of
	 * statements. The equivalence relation is left implicit here - in case
	 * multiple statements are equivalent to the given one, the "best/closest"
	 * pick shall be chosen which is usually the first option (the statement
	 * that will be executed first).
	 * @param oldMethod The list in which to look for the statement
	 * @param newStmt The statement for which to look
	 * @return A statement in the given list which is equivalent to the
	 * given statement or NULL if no such statement could be found.
	 */
	public N findStatement(Iterable<N> oldMethod, N newStmt);
	
	/**
	 * Wraps an object and registers a listener so that the reference can be
	 * updated automatically when necessary. Implementations are required to
	 * return stable objects, i.e.:
	 * <ul>
	 * <li>Two calls to wrap(x) must return the same object for the same x</li>
	 * <li>The hash code of the returned wrapper must not depend on the
	 * wrapped object. More specifically, the hash code of y = wrap(x)
	 * must not change when y.notifyReferenceChanged(z) is called.</li>
	 * </ul>
	 * This function creates a weak reference so that both the wrapped object
	 * and the wrapper can be garbage-collected.
	 * @param obj The object to be wrapped
	 * @return The wrapped object
	 */
	public <X> UpdatableWrapper<X> wrapWeak(X obj);

	/**
	 * Wraps an object and registers a listener so that the reference can be
	 * updated automatically when necessary. Implementations are required to
	 * return stable objects, i.e.:
	 * <ul>
	 * <li>Two calls to wrap(x) must return the same object for the same x</li>
	 * <li>The hash code of the returned wrapper must not depend on the
	 * wrapped object. More specifically, the hash code of y = wrap(x)
	 * must not change when y.notifyReferenceChanged(z) is called.</li>
	 * </ul>
	 * This function creates a weak reference so that both the wrapped object
	 * and the wrapper can be garbage-collected.
	 * @param obj The list of objects to be wrapped
	 * @return The list of wrapped objects
	 */
	public <X> List<UpdatableWrapper<X>> wrapWeak(List<X> obj);

	/**
	 * Wraps an object and registers a listener so that the reference can be
	 * updated automatically when necessary. Implementations are required to
	 * return stable objects, i.e.:
	 * <ul>
	 * <li>Two calls to wrap(x) must return the same object for the same x</li>
	 * <li>The hash code of the returned wrapper must not depend on the
	 * wrapped object. More specifically, the hash code of y = wrap(x)
	 * must not change when y.notifyReferenceChanged(z) is called.</li>
	 * </ul>
	 * This function creates a weak reference so that both the wrapped object
	 * and the wrapper can be garbage-collected.
	 * @param obj The set of objects to be wrapped
	 * @return The set of wrapped objects
	 */
	public <X> Set<UpdatableWrapper<X>> wrapWeak(Set<X> obj);

	/**
	 * Merges the wrappers controlled by this control flow graph with the
	 * ones of another CFG.
	 * <p>
	 * This means that this.wrap(x) must return the same object as b.wrap(x)
	 * after this.mergeWrappers(b) has been called. If both CFGs originally
	 * produce different wrappers for the same object, implementors may
	 * resolve to either value (or a  completely new one) as long as both
	 * objects afterwards agree on the same wrapper.
	 * </p> 
	 * @param otherCfg The other control flow graph with which to merge
	 * the wrappers. 
	 */
	public void mergeWrappers(InterproceduralCFG<N, M> otherCfg);
	
	/**
	 * Gets the start point of the outermost loop containing the given
	 * statement. This functions only considers intraprocedural loops.
	 * @param stmt The statement for which to get the loop start point.
	 * @return The start point of the outermost loop containing the given
	 * statement, or NULL if the given statement is not contained in a
	 * loop.
	 */
	public N getLoopStartPointFor(N stmt);
	
	/**
	 * Gets all exit nodes that can transfer the control flow to the given
	 * return site.
	 * @param stmt The return site for which to get the exit nodes
	 * @return The set of exit nodes that transfer the control flow to the
	 * given return site.
	 */
	public Set<N> getExitNodesForReturnSite(N stmt);

}
