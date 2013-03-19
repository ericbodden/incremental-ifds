package soot.jimple.interproc.ifds.template;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import soot.Body;
import soot.Local;
import soot.MethodOrMethodContext;
import soot.PatchingChain;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Unit;
import soot.UnitBox;
import soot.jimple.Stmt;
import soot.jimple.interproc.ifds.DontSynchronize;
import soot.jimple.interproc.ifds.InterproceduralCFG;
import soot.jimple.interproc.ifds.SynchronizedBy;
import soot.jimple.interproc.ifds.ThreadSafe;
import soot.jimple.interproc.ifds.solver.IDESolver;
import soot.jimple.interproc.ifds.utils.Utils;
import soot.jimple.interproc.incremental.AbstractUpdatableInterproceduralCFG;
import soot.jimple.interproc.incremental.DefaultUpdatableWrapper;
import soot.jimple.interproc.incremental.SceneDiff;
import soot.jimple.interproc.incremental.SceneDiff.ClassDiffNode;
import soot.jimple.interproc.incremental.SceneDiff.DiffType;
import soot.jimple.interproc.incremental.SceneDiff.MethodDiffNode;
import soot.jimple.interproc.incremental.SceneDiff.ProgramDiffNode;
import soot.jimple.interproc.incremental.UpdatableWrapper;
import soot.jimple.toolkits.annotation.logic.Loop;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.jimple.toolkits.callgraph.EdgePredicate;
import soot.jimple.toolkits.callgraph.Filter;
import soot.jimple.toolkits.callgraph.ReachableMethods;
import soot.toolkits.exceptions.UnitThrowAnalysis;
import soot.toolkits.graph.DirectedGraph;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.graph.LoopNestTree;
import soot.toolkits.scalar.Pair;
import soot.util.Chain;

import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * Default implementation for the {@link InterproceduralCFG} interface.
 * Includes all statements reachable from {@link Scene#getEntryPoints()} through
 * explicit call statements or through calls to {@link Thread#start()}.
 * 
 * This class is designed to be thread safe, and subclasses of this class must be designed
 * in a thread-safe way, too.
 */
@ThreadSafe
public class JimpleBasedInterproceduralCFG extends AbstractUpdatableInterproceduralCFG
		<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>> {
	
	private static final boolean DEBUG = true;
	
	//retains only callers that are explicit call sites or Thread.start()
	protected static class EdgeFilter extends Filter {		
		protected EdgeFilter() {
			super(new EdgePredicate() {
				public boolean want(Edge e) {
					return e.kind().isExplicit() || e.kind().isThread();
				}
			});
		}
	}
	
	@DontSynchronize("readonly")
	protected final CallGraph cg;
	
	@DontSynchronize("written by single thread; read afterwards")
	protected final Map<Unit,Body> unitToOwner = new HashMap<Unit,Body>();	
	
	@SynchronizedBy("by use of synchronized LoadingCache class")
	protected final LoadingCache<Body,DirectedGraph<Unit>> bodyToUnitGraph =
			IDESolver.DEFAULT_CACHE_BUILDER.build( new CacheLoader<Body,DirectedGraph<Unit>>() {
				public DirectedGraph<Unit> load(Body body) throws Exception {
					return makeGraph(body);
				}
			});
	
	@SynchronizedBy("by use of synchronized LoadingCache class")
	protected final LoadingCache<Unit,Set<SootMethod>> unitToCallees =
			IDESolver.DEFAULT_CACHE_BUILDER.build( new CacheLoader<Unit,Set<SootMethod>>() {
				public Set<SootMethod> load(Unit u) throws Exception {
					Set<SootMethod> res = new LinkedHashSet<SootMethod>();
					//only retain callers that are explicit call sites or Thread.start()
					Iterator<Edge> edgeIter = new EdgeFilter().wrap(cg.edgesOutOf(u));
					while(edgeIter.hasNext()) {
						Edge edge = edgeIter.next();
						if(edge.getTgt()==null) {
							System.err.println();
						}
						SootMethod m = edge.getTgt().method();
						if(m.hasActiveBody()) {
							assert m.getDeclaringClass().isInScene();
							res.add(m);
						}
					}
					return res; 
				}
			});

	@SynchronizedBy("by use of synchronized LoadingCache class")
	protected final LoadingCache<SootMethod,Set<Unit>> methodToCallers =
			IDESolver.DEFAULT_CACHE_BUILDER.build( new CacheLoader<SootMethod,Set<Unit>>() {
				public Set<Unit> load(SootMethod m) throws Exception {
					Set<Unit> res = new LinkedHashSet<Unit>();					
					//only retain callers that are explicit call sites or Thread.start()
					Iterator<Edge> edgeIter = new EdgeFilter().wrap(cg.edgesInto(m));					
					while(edgeIter.hasNext()) {
						Edge edge = edgeIter.next();
						res.add(edge.srcUnit());			
					}
					return res;
				}
			});

	@SynchronizedBy("by use of synchronized LoadingCache class")
	protected final LoadingCache<SootMethod,Set<Unit>> methodToCallsFromWithin =
			IDESolver.DEFAULT_CACHE_BUILDER.build( new CacheLoader<SootMethod,Set<Unit>>() {
				public Set<Unit> load(SootMethod m) throws Exception {
					Set<Unit> res = new LinkedHashSet<Unit>();
					//only retain calls that are explicit call sites or Thread.start()
					Iterator<Edge> edgeIter = new EdgeFilter().wrap(cg.edgesOutOf(m));
					while(edgeIter.hasNext()) {
						Edge edge = edgeIter.next();
						res.add(edge.srcUnit());			
					}
					return res;
				}
			});

	@DontSynchronize("written by single thread only")
	protected final SceneDiff sceneDiff = new SceneDiff();
	
	protected final Map<SootClass, List<SootMethod>> applicationMethods = new HashMap<SootClass, List<SootMethod>>();
	
	/**
	 * Set to true after references have been updated. Operations working on
	 * Soot objects must from then on use the previous contents of mutable
	 * objects.
	 */
	protected boolean afterUpdate = false;
	
	public JimpleBasedInterproceduralCFG() {
		this(true);
	}
	
	/**
	 * Creates a new interprocedural program graph based on the information in
	 * Soot's scene objects and CFG.
	 * @param updatable True if this program graph shall provide differential
	 * analysis information. This is necessary when using the CFG in a solver
	 * and then calling the solver's "update" function.
	 */
	public JimpleBasedInterproceduralCFG(boolean updatable) {
		System.out.println("Obtaining call graph...");
		cg = Scene.v().getCallGraph();
		
		System.out.println("Computing reachable methods...");
		List<MethodOrMethodContext> eps = new ArrayList<MethodOrMethodContext>();
		eps.addAll(Scene.v().getEntryPoints());
		ReachableMethods reachableMethods = new ReachableMethods(cg, eps.iterator(), new EdgeFilter());
		reachableMethods.update();
		
		System.out.println("Collecting bodies for reachable methods...");
		for(Iterator<MethodOrMethodContext> iter = reachableMethods.listener(); iter.hasNext(); ) {
			SootMethod m = iter.next().method();
			if(m.hasActiveBody()) {
				Body b = m.getActiveBody();
				PatchingChain<Unit> units = b.getUnits();
				for (Unit unit : units) {
					unitToOwner.put(unit, b);
				}
				assert m.getDeclaringClass().isInScene();
				Utils.addElementToMapList(this.applicationMethods, m.getDeclaringClass(), m);
			}
		}
		System.out.println("Interprocedural CFG created.");

		if (updatable) {
			System.out.println("Building scene diff information...");
			this.sceneDiff.fullBuild();
			System.out.println("Scene diff information created.");
		}
	}

	@Override
	public UpdatableWrapper<SootMethod> getMethodOf(UpdatableWrapper<Unit> u) {
		assert u != null;
		Body body = unitToOwner.get(afterUpdate ? u.getPreviousContents() : u.getContents());
		if (body == null)
			throw new RuntimeException("Unit has no associated body: " + u);
		return wrapWeak(body.getMethod());
	}

	@Override
	public List<UpdatableWrapper<Unit>> getSuccsOf(UpdatableWrapper<Unit> u) {
		assert u != null;
		Body body = unitToOwner.get(afterUpdate ? u.getPreviousContents() : u.getContents());
		if (body == null)
			throw new RuntimeException("Unit has no associated body: " + u);
		DirectedGraph<Unit> unitGraph = getOrCreateUnitGraph(body);
		return wrapWeak(unitGraph.getSuccsOf(afterUpdate ? u.getPreviousContents() : u.getContents()));
	}

	@Override
	public List<UpdatableWrapper<Unit>> getPredsOf(UpdatableWrapper<Unit> u) {
		Body body = unitToOwner.get(afterUpdate ? u.getPreviousContents() : u.getContents());
		if (body == null)
			throw new RuntimeException("Unit has no associated body: " + u);
		DirectedGraph<Unit> unitGraph = getOrCreateUnitGraph(body);
		return wrapWeak(unitGraph.getPredsOf(afterUpdate ? u.getPreviousContents() : u.getContents()));
	}

	private DirectedGraph<Unit> getOrCreateUnitGraph(Body body) {
		assert body != null;
		return bodyToUnitGraph.getUnchecked(body);
	}

	protected synchronized DirectedGraph<Unit> makeGraph(Body body) {
		return new ExceptionalUnitGraph(body, UnitThrowAnalysis.v() ,true);
	}

	@Override
	public Set<UpdatableWrapper<SootMethod>> getCalleesOfCallAt(UpdatableWrapper<Unit> u) {
		return wrapWeak(unitToCallees.getUnchecked(afterUpdate ? u.getPreviousContents() : u.getContents()));
	}

	@Override
	public List<UpdatableWrapper<Unit>> getReturnSitesOfCallAt(UpdatableWrapper<Unit> u) {
		return getSuccsOf(u);
	}

	@Override
	public boolean isCallStmt(UpdatableWrapper<Unit> u) {
		return u == null ? false : ((Stmt)u.getContents()).containsInvokeExpr();
	}

	@Override
	public boolean isExitStmt(UpdatableWrapper<Unit> u) {
		if (u == null) return false;
		Body body = unitToOwner.get(afterUpdate ? u.getPreviousContents() : u.getContents());
		assert body != null;
		DirectedGraph<Unit> unitGraph = getOrCreateUnitGraph(body);
		return unitGraph.getTails().contains(u.getContents());
	}

	@Override
	public Set<UpdatableWrapper<Unit>> getCallersOf(UpdatableWrapper<SootMethod> m) {
		return wrapWeak(methodToCallers.getUnchecked(afterUpdate ? m.getPreviousContents() : m.getContents()));
	}
	
	@Override
	public Set<UpdatableWrapper<Unit>> getCallsFromWithin(UpdatableWrapper<SootMethod> m) {
		return wrapWeak(methodToCallsFromWithin.getUnchecked(afterUpdate ? m.getPreviousContents() : m.getContents()));
	}

	@Override
	public Set<UpdatableWrapper<Unit>> getStartPointsOf(UpdatableWrapper<SootMethod> m) {
		if(m.getContents().hasActiveBody()) {
			Body body = m.getContents().getActiveBody();
			DirectedGraph<Unit> unitGraph = getOrCreateUnitGraph(body);
			return wrapWeak(new LinkedHashSet<Unit>(unitGraph.getHeads()));
		}
		return null;
	}

	@Override
	public boolean isStartPoint(UpdatableWrapper<Unit> u) {
		return unitToOwner.get(afterUpdate ? u.getPreviousContents() : u.getContents()).getUnits().getFirst()==u.getContents();
	}

	@Override
	public Set<UpdatableWrapper<Unit>> allNonCallStartNodes() {
		Set<Unit> res = new LinkedHashSet<Unit>(unitToOwner.keySet());
		for (Iterator<Unit> iter = res.iterator(); iter.hasNext();) {
			Unit u = iter.next();
			if(isStartPoint(new DefaultUpdatableWrapper<Unit>(u))
					|| isCallStmt(new DefaultUpdatableWrapper<Unit>(u))) iter.remove();
		}
		return wrapWeak(res);
	}

	@Override
	public boolean isFallThroughSuccessor(UpdatableWrapper<Unit> u, UpdatableWrapper<Unit> succ) {
		assert getSuccsOf(u).contains(succ);
		if(!u.getContents().fallsThrough()) return false;
		Body body = unitToOwner.get(u.getContents());
		return body.getUnits().getSuccOf(u.getContents()) == succ.getContents();
	}

	@Override
	public boolean isBranchTarget(UpdatableWrapper<Unit> u, UpdatableWrapper<Unit> succ) {
		assert getSuccsOf(u).contains(succ);
		if(!u.getContents().branches()) return false;
		for (UnitBox ub : succ.getContents().getUnitBoxes()) {
			if(ub.getUnit()==succ.getContents()) return true;
		}
		return false;
	}
	
	@Override
	public boolean containsStmt(UpdatableWrapper<Unit> stmt) {
		return unitToOwner.containsKey(afterUpdate ? stmt.getPreviousContents() : stmt.getContents());
	}

	@Override
	public List<UpdatableWrapper<Unit>> getAllNodes() {
		return wrapWeak(new ArrayList<Unit>(unitToOwner.keySet()));
	}
	
	@Override
	public boolean equals(Object another) {
		if (super.equals(another))
			return true;
		if (!(another instanceof JimpleBasedInterproceduralCFG))
			return false;
		
		JimpleBasedInterproceduralCFG anotherCFG = (JimpleBasedInterproceduralCFG) another;
		return this.cg == anotherCFG.cg || this.cg.equals(anotherCFG.cg);
	}
	
	@Override
	public int hashCode() {
		return super.hashCode() * 31 + cg.hashCode();
	}
	
	@Override
	public void computeCFGChangeset
			(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> newCFG,
			Map<UpdatableWrapper<Unit>, List<UpdatableWrapper<Unit>>> expiredEdges,
			Map<UpdatableWrapper<Unit>, List<UpdatableWrapper<Unit>>> newEdges,
			Set<UpdatableWrapper<Unit>> newNodes,
			Set<UpdatableWrapper<Unit>> expiredNodes) {
		
		if (!(newCFG instanceof JimpleBasedInterproceduralCFG))
			throw new RuntimeException("Cannot compare graphs of different type");
		
		System.out.println("Computing code diff...");
		ProgramDiffNode diffRoot = sceneDiff.incrementalBuild();
		if (diffRoot.isEmpty())
			System.out.println("Program is unchanged");
		System.out.println("Incremental build done.");			
		
		// Check for removed classes. All statements in all methods in all
		// removed classes are automatically expired
		for (ClassDiffNode cd : diffRoot)
			if (cd.getDiffType() == DiffType.REMOVED) {
				System.out.println("Removed class: " + cd.getOldClass().getName());
				for (SootMethod sm : this.applicationMethods.get(cd.getOldClass()))
					if (sm.hasActiveBody())
						for (Unit u : sm.getActiveBody().getUnits()) {
							UpdatableWrapper<Unit> wrapper = wrapWeak(u);
							expiredNodes.add(wrapper);
							assert this.containsStmt(wrapper);
						}
			}
		
		// Iterate over the new scene to find the changes inside the retained
		// classes. This includes finding new methods in existing classes. We
		// don't find any removed classes as we start with the new ones.
		this.setSafepoint();
		for (SootClass newClass : ((JimpleBasedInterproceduralCFG) newCFG).applicationMethods.keySet()) {
			List<SootMethod> newMethods = ((JimpleBasedInterproceduralCFG) newCFG).applicationMethods.get(newClass);
			ClassDiffNode cd = diffRoot.getClassChanges(newClass);
			
			// If the class diff is null, the class has not changed
			if (cd == null) {
				for (SootMethod sm : newMethods)
					updateUnchangedMethodPointers(diffRoot.getOldMethodFor(sm), sm);
			}
			else {
				// If the class is new, all its methods are new
				if (cd.getDiffType() == DiffType.ADDED) {
					System.out.println("Added class: " + cd.getNewClass().getName());
					for (SootMethod sm : newMethods)
						if (sm.hasActiveBody())
							for (Unit u : sm.getActiveBody().getUnits()) {
								UpdatableWrapper<Unit> wrapper = wrapWeak(u);
								newNodes.add(wrapper);
								assert newCFG.containsStmt(wrapper);
								wrapper.setSafepoint();
							}
					continue;
				}
				
				// The class has been retained.
				assert cd.getDiffType() == DiffType.CHANGED;
				List<SootMethod> oldMethods = this.applicationMethods.get(cd.getOldClass());
				
				// Check for removed methods. All statements in removed methods are
				// automatically expired
				for (SootMethod oldMethod : oldMethods) {
					MethodDiffNode md = cd.getMethodDiff(oldMethod);
					// If we have no diff, the method is retained unchanged. We handle
					// that below, so we can ignore the case here.
					if (md != null && md.getDiffType() == DiffType.REMOVED) {
						System.out.println("Removed method: "
								+ md.getOldMethod().getDeclaringClass().getName() + ": "
								+ md.getOldMethod().getSubSignature()
								+ " (" + md.getDiffType() + ")");
						if (md.getOldMethod().hasActiveBody())
							for (Unit u : md.getOldMethod().getActiveBody().getUnits()) {
								UpdatableWrapper<Unit> wrapper = wrapWeak(u);
								expiredNodes.add(wrapper);
								assert this.containsStmt(wrapper);
								wrapper.setSafepoint();
							}
					}
				}
				
				// Check for added and changed methods
				for (SootMethod newMethod : newMethods) {
					MethodDiffNode md = cd.getMethodDiff(newMethod);
					if (md == null) {
						// This method has been retained, we need to update the pointers
						updateUnchangedMethodPointers(diffRoot.getOldMethodFor(newMethod), newMethod);
					}
					else if (md.getDiffType() == DiffType.ADDED) {
						System.out.println("Added method: "
								+ md.getNewMethod().getDeclaringClass().getName() + ": "
								+ md.getNewMethod().getSubSignature()
								+ " (" + md.getDiffType() + ")");
						if (md.getNewMethod().hasActiveBody())
							for (Unit u : md.getNewMethod().getActiveBody().getUnits()) {
								UpdatableWrapper<Unit> wrapper = wrapWeak(u);
								newNodes.add(wrapWeak(u));
								assert newCFG.containsStmt(wrapper);
								wrapper.setSafepoint();
							}
					}
					else {
						// The method has been changed
						assert md.getDiffType() == DiffType.CHANGED;
						System.out.println("Changed method: "
								+ md.getNewMethod().getDeclaringClass().getName() + ": "
								+ md.getNewMethod().getSubSignature()
								+ " (" + md.getDiffType() + ")");

						// For changed methods, we need to find the edges that have
						// been added or removed
						computeMethodChangeset(newCFG, md.getOldMethod(), md.getNewMethod(),
								expiredEdges, newEdges, newNodes, expiredNodes);
						
						// Compute the changes to the local variables
						for (Local lold : md.getOldMethod().getActiveBody().getLocals())
							for (Local lnew : md.getNewMethod().getActiveBody().getLocals()) 
								if (lold.getName().equals(lnew.getName())) {
									notifyReferenceChanged(lold, lnew);
									break;
								}
					}
				}
			}
		}
		
		// Iterate over the old classes and mark all statements in all methods
		// of removed classes as expired
		for (SootClass oldClass : applicationMethods.keySet()) {			
			SootClass newClass = null;
			for (SootClass nc : ((JimpleBasedInterproceduralCFG) newCFG).applicationMethods.keySet())
				if (oldClass.getName().equals(nc.getName())) {
					newClass = nc;
					break;
				}
			
			if (newClass == null) {
				assert diffRoot.getClassChanges(oldClass) != null;
				continue;
			}
			
			for (SootMethod oldMethod : oldClass.getMethods())
				if (this.applicationMethods.get(oldClass).contains(oldMethod)) {
					boolean found = false; 
					for (SootMethod newMethod : newClass.getMethods())
						if (oldMethod.getName().equals(newMethod.getName())) {
							found = true;
							break;
						}
					if (!found)
						assert diffRoot.getClassChanges(oldClass).getMethodDiff(oldMethod) != null;
				}
		}
		/*
			for (SootMethod oldMethod : applicationMethods.get(oldClass)) {
				boolean found = false;
				for (SootClass newClass : ((JimpleBasedInterproceduralCFG) newCFG).applicationMethods.keySet())
					if (newMethod.getName().equals(oldMethod.getName())) {
						found = true;
						break;
					}
				
				if (!found)
					for (Unit u : oldMethod.getActiveBody().getUnits())  {
						UpdatableWrapper<Unit> wrapper = wrapWeak(u);
						assert this.containsStmt(wrapper);
						expiredNodes.add(wrapper);
						wrapper.setSafepoint();
					}
			}
		*/
		this.afterUpdate = true;
	}

	/**
	 * Updates the statement points for an unchanged method. All updateable
	 * references to statements in the old method are changed to point to
	 * their counterparts in the new method.
	 * @param oldMethod The old method before the update
	 * @param newMethod The new method after the update
	 */
	private void updateUnchangedMethodPointers(SootMethod oldMethod, SootMethod newMethod) {
		// If one of the two methods has no body, we cannot match anything
		if (oldMethod == null || !oldMethod.hasActiveBody()
				|| newMethod == null || !newMethod.hasActiveBody()
				|| oldMethod == newMethod)
			return;
		
		// As we expect the method to be unchanged, there should be the same
		// number of statements in both the old and the new version
		assert oldMethod.getActiveBody().getUnits().size() ==
				newMethod.getActiveBody().getUnits().size();
		
		// Update the statement references
		updatePointsFromChain(oldMethod.getActiveBody().getUnits(),
			newMethod.getActiveBody().getUnits());
		updatePointsFromChain(oldMethod.getActiveBody().getLocals(),
			newMethod.getActiveBody().getLocals());
		
	}
	
	private <X> void updatePointsFromChain(Chain<X> oldChain, Chain<X> newChain) {
		if (oldChain.isEmpty() || newChain.isEmpty())
			return;
		
		X uold = oldChain.getFirst();
		X unew = newChain.getFirst();
		while (uold != null && unew != null) {
			assert uold.toString().contains("tmp$") || uold.toString().contains("access$")
				|| uold.toString().equals(unew.toString());
			notifyReferenceChanged(uold, unew);
			uold = oldChain.getSuccOf(uold);
			unew = newChain.getSuccOf(unew);
		}		
	}

	/**
	 * Computes the edge differences between two Soot methods.
	 * @param newCFG The new program graph. The current object is assumed to
	 * hold the old program graph.
	 * @param oldMethod The method before the changes were made
	 * @param newMethod The method after the changes have been made
	 * @param expiredEdges The map that receives the expired edge targets. If
	 * two edges a->b and a->c have expired, the entry (a,(b,c)) will be put in
	 * the map.
	 * @param newEdges The map that receives the new edge targets.
	 * @param newNodes The list that receives all nodes which have been added to
	 * the method 
	 * @param expiredNodes The list that receives all nodes which have been
	 * deleted from the method
	 * @param nodeReplaceSet The map that receives the mapping between old nodes
	 * and new ones. If a statement is left unchanged by the modifications to the
	 * method, it may nevertheless be represented by a new object in the program
	 * graph. Use this map to update any references you might hold to the old
	 * objects.
	 */
	private void computeMethodChangeset
			(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> newCFG,
			SootMethod oldMethod,
			SootMethod newMethod,
			Map<UpdatableWrapper<Unit>, List<UpdatableWrapper<Unit>>> expiredEdges,
			Map<UpdatableWrapper<Unit>, List<UpdatableWrapper<Unit>>> newEdges,
			Set<UpdatableWrapper<Unit>> newNodes,
			Set<UpdatableWrapper<Unit>> expiredNodes) {
		
		assert newCFG != null;
		assert oldMethod != null;
		assert newMethod != null;
		assert expiredEdges != null;
		assert newEdges != null;
		assert newNodes != null;
		
		// Delay reference changes until the end for not having to cope with
		// changing references inside our analysis
		Map<Unit, Unit> refChanges = new HashMap<Unit, Unit>();

		// For all entry points of the new method, try to find the corresponding
		// statements in the old method. If we don't a corresponding statement,
		// we record a NULL value.
		List<Pair<UpdatableWrapper<Unit>,UpdatableWrapper<Unit>>> workQueue =
				new ArrayList<Pair<UpdatableWrapper<Unit>,UpdatableWrapper<Unit>>>();
		List<UpdatableWrapper<Unit>> doneList = new ArrayList<UpdatableWrapper<Unit>>(10000);
		for (UpdatableWrapper<Unit> spNew : newCFG.getStartPointsOf
				(new DefaultUpdatableWrapper<SootMethod>(newMethod))) {
			UpdatableWrapper<Unit> spOld = findStatement
					(new DefaultUpdatableWrapper<SootMethod>(oldMethod), spNew);
			workQueue.add(new Pair<UpdatableWrapper<Unit>,UpdatableWrapper<Unit>>(spNew, spOld));
			if (spOld == null)
				newNodes.add(spNew);
			else
				refChanges.put(spOld.getContents(), spNew.getContents());
		}
		
		while (!workQueue.isEmpty()) {
			// Dequeue the current element and make sure we don't run in circles
			Pair<UpdatableWrapper<Unit>,UpdatableWrapper<Unit>> ns = workQueue.remove(0);
			UpdatableWrapper<Unit> newStmt = ns.getO1();
			UpdatableWrapper<Unit> oldStmt = ns.getO2();
			if (doneList.contains(newStmt))
				continue;
			doneList.add(newStmt);
			
			// If the current point is unreachable, we skip the remainder of the method
			if (!newCFG.containsStmt(newStmt))
				continue;
			
			// Find the outgoing edges and check whether they are new
			boolean isNewStmt = newNodes.contains(newStmt);
			for (UpdatableWrapper<Unit> newSucc : newCFG.getSuccsOf(newStmt)) {
				UpdatableWrapper<Unit> oldSucc = oldStmt == null ? null : findStatement(getSuccsOf(oldStmt), newSucc);
				if (oldSucc == null || !getSuccsOf(oldStmt).contains(oldSucc) || isNewStmt)
					Utils.addElementToMapList(newEdges, oldStmt == null ? newStmt : oldStmt,
							oldSucc == null ? newSucc : oldSucc);
				if (oldSucc == null)
					newNodes.add(newSucc);
				workQueue.add(new Pair<UpdatableWrapper<Unit>,UpdatableWrapper<Unit>>
					(newSucc, oldSucc == null ? oldStmt : oldSucc));
//				workQueue.add(new Pair<UpdatableWrapper<Unit>,UpdatableWrapper<Unit>>(newSucc, oldSucc));
				
				if (oldSucc != null)
					refChanges.put(oldSucc.getContents(), newSucc.getContents());
			}
		}
		
		// For all entry points of the old method, check whether we can reach a
		// statement that is no longer present in the new method.
		doneList.clear();
		for (UpdatableWrapper<Unit> spOld : getStartPointsOf(new DefaultUpdatableWrapper<SootMethod>(oldMethod))) {
			UpdatableWrapper<Unit> spNew = newCFG.findStatement(new DefaultUpdatableWrapper<SootMethod>(newMethod), spOld);
			workQueue.add(new Pair<UpdatableWrapper<Unit>,UpdatableWrapper<Unit>>(spNew, spOld));
			if (spNew == null)
				expiredNodes.add(spOld);
		}

		while (!workQueue.isEmpty()) {
			// Dequeue the current element and make sure we don't run in circles
			Pair<UpdatableWrapper<Unit>,UpdatableWrapper<Unit>> ns = workQueue.remove(0);
			UpdatableWrapper<Unit> newStmt = ns.getO1();
			UpdatableWrapper<Unit> oldStmt = ns.getO2();
			if (doneList.contains(oldStmt))
				continue;
			doneList.add(oldStmt);

			// If the current point is unreachable, we skip the remainder of the method
			if (!containsStmt(oldStmt))
				continue;

			// Find the outgoing edges and check whether they are expired
			boolean isExpiredStmt = expiredNodes.contains(oldStmt);
			for (UpdatableWrapper<Unit> oldSucc : getSuccsOf(oldStmt)) {
				UpdatableWrapper<Unit> newSucc = newStmt == null ? null : newCFG.findStatement
						(newCFG.getSuccsOf(newStmt), oldSucc);
				if (newSucc == null || !newCFG.getSuccsOf(newStmt).contains(newSucc) || isExpiredStmt)
					Utils.addElementToMapList(expiredEdges, oldStmt, oldSucc);
				if (newSucc == null)
					expiredNodes.add(oldSucc);
				workQueue.add(new Pair<UpdatableWrapper<Unit>,UpdatableWrapper<Unit>>
					(newSucc == null ? newStmt : newSucc, oldSucc));
//				workQueue.add(new Pair<UpdatableWrapper<Unit>,UpdatableWrapper<Unit>>(newSucc, oldSucc));

				if (newSucc != null)
					refChanges.put(oldSucc.getContents(), newSucc.getContents());
			}
		}
		
		// Make sure that every statement is either added or removed or remapped
		if (DEBUG) {
			doneList.clear();
			List<UpdatableWrapper<Unit>> checkQueue = new ArrayList<UpdatableWrapper<Unit>>();
			checkQueue.addAll(this.getStartPointsOf(wrapWeak(oldMethod)));
			while (!checkQueue.isEmpty()) {
				UpdatableWrapper<Unit> curUnit = checkQueue.remove(0);
				if (doneList.contains(curUnit))
					continue;
				doneList.add(curUnit);
				assert expiredNodes.contains(curUnit)
						|| newNodes.contains(curUnit)
						|| refChanges.containsKey(curUnit.getContents());
			}
		}

		for (Entry<Unit,Unit> entry : refChanges.entrySet())
			notifyReferenceChanged(entry.getKey(), entry.getValue());
	}

	@Override
	public UpdatableWrapper<Unit> findStatement
			(UpdatableWrapper<SootMethod> oldMethod,
			UpdatableWrapper<Unit> newStmt) {
		return findStatement(getStartPointsOf(oldMethod), newStmt);
	}
	
	@Override
	public UpdatableWrapper<Unit> findStatement
			(Iterable<UpdatableWrapper<Unit>> oldMethod,
			UpdatableWrapper<Unit> newStmt) {
		List<UpdatableWrapper<Unit>> doneList = new ArrayList<UpdatableWrapper<Unit>>();
		return findStatement(oldMethod, newStmt, doneList);
	}
	
	private UpdatableWrapper<Unit> findStatement
			(Iterable<UpdatableWrapper<Unit>> oldMethod,
			UpdatableWrapper<Unit> newStmt,
			List<UpdatableWrapper<Unit>> doneList) {
		List<UpdatableWrapper<Unit>> workList = new ArrayList<UpdatableWrapper<Unit>>();
		for (UpdatableWrapper<Unit> u : oldMethod)
			workList.add(u);
		
		while (!workList.isEmpty()) {
			UpdatableWrapper<Unit> sp = workList.remove(0);
			if (doneList.contains(sp))
				continue;
			doneList.add(sp);
			
			if (sp == newStmt || sp.equals(newStmt) || sp.toString().equals
					(newStmt.toString()))
				return sp;
			workList.addAll(getSuccsOf(sp));
		}
		return null;
	}

	@Override
	public UpdatableWrapper<Unit> getLoopStartPointFor
			(UpdatableWrapper<Unit> stmt) {
		Body body = this.unitToOwner.get(afterUpdate ? stmt.getPreviousContents() : stmt.getContents());
		assert body != null;
		LoopNestTree loopTree = new LoopNestTree(body);
		Unit loopHead = null;
		for (Loop loop : loopTree) {
			if (loop.getLoopStatements().contains(stmt.getContents()))
				loopHead = loop.getHead();
		}
		return loopHead == null ? null : wrapWeak(loopHead);
	}

	@Override
	public Set<UpdatableWrapper<Unit>> getExitNodesForReturnSite
			(UpdatableWrapper<Unit> stmt) {
		List<UpdatableWrapper<Unit>> preds = this.getPredsOf(stmt);
		Set<UpdatableWrapper<Unit>> exitNodes = new HashSet<UpdatableWrapper<Unit>>(preds.size() * 2);
		for (UpdatableWrapper<Unit> pred : preds)
			for (UpdatableWrapper<SootMethod> sm : this.getCalleesOfCallAt(pred))
				if (sm.getContents().hasActiveBody())
					for (Unit u : sm.getContents().getActiveBody().getUnits())
						if (this.isExitStmt(this.wrapWeak(u)))
							exitNodes.add(this.wrapWeak(u));
		return exitNodes;
	}
}
