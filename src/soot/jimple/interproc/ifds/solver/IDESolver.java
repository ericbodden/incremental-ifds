package soot.jimple.interproc.ifds.solver;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import soot.PatchingChain;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.interproc.ifds.DontSynchronize;
import soot.jimple.interproc.ifds.EdgeFunction;
import soot.jimple.interproc.ifds.EdgeFunctionCache;
import soot.jimple.interproc.ifds.EdgeFunctions;
import soot.jimple.interproc.ifds.FlowFunction;
import soot.jimple.interproc.ifds.FlowFunctionCache;
import soot.jimple.interproc.ifds.FlowFunctions;
import soot.jimple.interproc.ifds.IDETabulationProblem;
import soot.jimple.interproc.ifds.InterproceduralCFG;
import soot.jimple.interproc.ifds.JoinLattice;
import soot.jimple.interproc.ifds.SynchronizedBy;
import soot.jimple.interproc.ifds.ZeroedFlowFunctions;
import soot.jimple.interproc.ifds.edgefunc.EdgeIdentity;
import soot.jimple.interproc.ifds.utils.Utils;
import soot.jimple.interproc.incremental.UpdatableWrapper;
import soot.toolkits.scalar.Pair;

import com.google.common.base.Predicate;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;

/**
 * Solves the given {@link IDETabulationProblem} as described in the 1996 paper by Sagiv,
 * Horwitz and Reps. To solve the problem, call {@link #solve()}. Results can then be
 * queried by using {@link #resultAt(Object, Object)} and {@link #resultsAt(Object)}.
 * 
 * Note that this solver and its data structures internally use mostly {@link LinkedHashSet}s
 * instead of normal {@link HashSet}s to fix the iteration order as much as possible. This
 * is to produce, as much as possible, reproducible benchmarking results. We have found
 * that the iteration order can matter a lot in terms of speed.
 *
 * @param <N> The type of nodes in the interprocedural control-flow graph. Typically {@link Unit}.
 * @param <D> The type of data-flow facts to be computed by the tabulation problem.
 * @param <M> The type of objects used to represent methods. Typically {@link SootMethod}.
 * @param <V> The type of values to be computed along flow edges.
 * @param <I> The type of inter-procedural control-flow graph being used.
 */
public class IDESolver<N extends UpdatableWrapper<?>,D extends UpdatableWrapper<?>,M extends UpdatableWrapper<?>,V,
		I extends InterproceduralCFG<N,M>> {
	
	/**
	 * Enumeration containing all possible modes in which this solver can work
	 */
	private enum OperationMode
	{
		/**
		 * The data is computed from scratch, either initially or because of a
		 * full re-computation
		 */
		Compute,
		/**
		 * An incremental update is performed on the data
		 */
		Update
	};

	@DontSynchronize("only used by single thread")
	private OperationMode operationMode = OperationMode.Compute;
	
	public static CacheBuilder<Object, Object> DEFAULT_CACHE_BUILDER =
			CacheBuilder.newBuilder().concurrencyLevel(Runtime.getRuntime().availableProcessors()).initialCapacity(10000).softValues();
	
	private static final boolean DEBUG = false;
	
	private static final boolean DUMP_RESULTS = false;
	
	//executor for dispatching individual compute jobs (may be multi-threaded)
	@DontSynchronize("only used by single thread")
	private ExecutorService executor;
	
	@DontSynchronize("only used by single thread")
	private int numThreads;
	
	//the number of currently running tasks
	private final AtomicInteger numTasks = new AtomicInteger();

	@SynchronizedBy("consistent lock on field")
	//We are using a LinkedHashSet here to enforce FIFO semantics, which leads to a breath-first construction
	//of the exploded super graph. As we observed in experiments, this can speed up the construction.
	private final Collection<PathEdge<N,D,M>> pathWorklist = new LinkedHashSet<PathEdge<N,D,M>>();
	
	@SynchronizedBy("thread safe data structure, consistent locking when used")
	private final JumpFunctions<N,D,V> jumpFn;
	private Table<N,D,Map<D, EdgeFunction<V>>> jumpSave = null;

	@SynchronizedBy("thread safe data structure, consistent locking when used")
	private final SummaryFunctions<N,D,V> summaryFunctions = new SummaryFunctions<N,D,V>();

	@SynchronizedBy("thread safe data structure, only modified internally")
	private I icfg;	// not final, see update(I newCFG) method
	
	@SynchronizedBy("thread safe data structure, only modified internally")
	private I oldcfg = null;	// not final, see update(I newCFG) method

	//stores summaries that were queried before they were computed
	//see CC 2010 paper by Naeem, Lhotak and Rodriguez
	@SynchronizedBy("consistent lock on 'incoming'")
	private final Table<N,D,Table<N,D,EdgeFunction<V>>> endSummary = HashBasedTable.create();

	//edges going along calls
	//see CC 2010 paper by Naeem, Lhotak and Rodriguez
	@SynchronizedBy("consistent lock on field")
	private final Table<N,D,Map<N,Set<D>>> incoming = HashBasedTable.create();
	
	private Set<N> changedNodes = null;
	
	@DontSynchronize("stateless")
	private final FlowFunctions<N, D, M> flowFunctions;

	@DontSynchronize("stateless")
	private final EdgeFunctions<N,D,M,V> edgeFunctions;

	@DontSynchronize("only used by single thread")
	// can be changed during update()
	private Set<N> initialSeeds;

	@DontSynchronize("stateless")
	private final JoinLattice<V> valueLattice;
	
	@DontSynchronize("stateless")
	private final EdgeFunction<V> allTop;
	
	@DontSynchronize("only used by single thread - phase II not parallelized (yet)")
	private final List<Pair<N,D>> nodeWorklist = new LinkedList<Pair<N,D>>();

	@DontSynchronize("only used by single thread - phase II not parallelized (yet)")
	private final Table<N,D,V> val = HashBasedTable.create();
	
	@DontSynchronize("benign races")
	public long flowFunctionApplicationCount;

	@DontSynchronize("benign races")
	public long flowFunctionConstructionCount;
	
	@DontSynchronize("benign races")
	public long propagationCount;
	
	@DontSynchronize("benign races")
	public long durationFlowFunctionConstruction;
	
	@DontSynchronize("benign races")
	public long durationFlowFunctionApplication;

	@DontSynchronize("stateless")
	private final D zeroValue;

	@DontSynchronize("readOnly")
	private final FlowFunctionCache<N,D,M> ffCache; 

	@DontSynchronize("readOnly")
	private final EdgeFunctionCache<N,D,M,V> efCache;
		
	@DontSynchronize("readOnly")
	private final IDETabulationProblem<N,D,M,V,I> tabulationProblem;
		
	private Map<M, Set<N>> changeSet = null; 
	
	/**
	 * Creates a solver for the given problem, which caches flow functions and edge functions.
	 * The solver must then be started by calling {@link #solve()}.
	 */
	public IDESolver(IDETabulationProblem<N,D,M,V,I> tabulationProblem) {
		this(tabulationProblem, DEFAULT_CACHE_BUILDER, DEFAULT_CACHE_BUILDER);
	}
	
	/**
	 * Creates a solver for the given problem, constructing caches with the given {@link CacheBuilder}. The solver must then be started by calling
	 * {@link #solve()}.
	 * @param flowFunctionCacheBuilder A valid {@link CacheBuilder} or <code>null</code> if no caching is to be used for flow functions.
	 * @param edgeFunctionCacheBuilder A valid {@link CacheBuilder} or <code>null</code> if no caching is to be used for edge functions.
	 */
	public IDESolver(IDETabulationProblem<N,D,M,V,I> tabulationProblem, @SuppressWarnings("rawtypes") CacheBuilder flowFunctionCacheBuilder, @SuppressWarnings("rawtypes") CacheBuilder edgeFunctionCacheBuilder) {
		if(DEBUG) {
			flowFunctionCacheBuilder = flowFunctionCacheBuilder.recordStats();
			edgeFunctionCacheBuilder = edgeFunctionCacheBuilder.recordStats();
		}
		this.icfg = tabulationProblem.interproceduralCFG();
		FlowFunctions<N, D, M> flowFunctions = new ZeroedFlowFunctions<N,D,M>
			(tabulationProblem.flowFunctions(), tabulationProblem.zeroValue());
		EdgeFunctions<N, D, M, V> edgeFunctions = tabulationProblem.edgeFunctions();
		if(flowFunctionCacheBuilder!=null) {
			ffCache = new FlowFunctionCache<N,D,M>(flowFunctions, flowFunctionCacheBuilder);
			flowFunctions = ffCache;
		} else {
			ffCache = null;
		}
		if(edgeFunctionCacheBuilder!=null) {
			efCache = new EdgeFunctionCache<N,D,M,V>(edgeFunctions, edgeFunctionCacheBuilder);
			edgeFunctions = efCache;
		} else {
			efCache = null;
		}
		this.flowFunctions = flowFunctions;
		this.edgeFunctions = edgeFunctions;
		this.initialSeeds = tabulationProblem.initialSeeds();
		this.valueLattice = tabulationProblem.joinLattice();
		this.zeroValue = tabulationProblem.zeroValue();
		this.allTop = tabulationProblem.allTopFunction();
		this.jumpFn = new JumpFunctions<N,D,V>(allTop);
		this.tabulationProblem = tabulationProblem;
	}

	/**
	 * Runs the solver on the configured problem. This can take some time.
	 * Uses a number of threads equal to the return value of
	 * <code>Runtime.getRuntime().availableProcessors()</code>.
	 */
	public void solve() {		
		solve(false);
	}
	
	/**
	 * Runs the solver on the configured problem. This can take some time.
	 * Uses a number of threads equal to the return value of
	 * <code>Runtime.getRuntime().availableProcessors()</code>.
	 * @param enableUpdates Specifies whether indices for dynamic program graph
	 * updates shall be generated.
	 */
	public void solve(boolean enableUpdates) {
		solve(Runtime.getRuntime().availableProcessors(), enableUpdates);
	}

	/**
	 * Runs the solver on the configured problem. This can take some time.
	 * @param numThreads The number of threads to use.
	 */
	public void solve(int numThreads) {
		solve(numThreads, true);
	}
	
	/**
	 * Runs the solver on the configured problem. This can take some time.
	 * @param numThreads The number of threads to use.
	 * @param enableUpdates Specifies whether indices for dynamic program graph
	 * updates shall be generated.
	 */
	public void solve(int numThreads, boolean enableUpdates) {
		System.out.println("IDE solver started.");
		
		// Clean up any leftovers from previous runs on a problem that might have been
		// updated in the meantime.
		this.jumpFn.clear();
		this.summaryFunctions.table.clear();
		this.endSummary.clear();
		this.incoming.clear();
		this.val.clear();
		this.ffCache.invalidateAll();
		this.efCache.invalidateAll();
		this.propagationCount = 0;
		this.operationMode = OperationMode.Compute;

		System.out.println("Running with " + numThreads + " threads");
		
		for(N startPoint: initialSeeds) {
			assert icfg.containsStmt(startPoint);
			propagate(zeroValue, startPoint, zeroValue, allTop);
			pathWorklist.add(new PathEdge<N,D,M>(zeroValue, startPoint, zeroValue));
			jumpFn.addFunction(zeroValue, startPoint, zeroValue, EdgeIdentity.<V>v());
		}
		
		solveOnWorklist(numThreads, true, true);
		System.out.println("IDE solver done, " + propagationCount + " edges propagated.");
	}
	
	/**
	 * Runs the solver based on the already filled work list. This can take some
	 * time.
	 * Note that the caller is responsible for filling the path work list with the
	 * initial seeds / edges to be processed. This function can be used for graph
	 * updates, it therefore does not perform any cleanup on the control-flow
	 * graph prior to execution. 
	 * @param numThreads The number of threads to use.
	 * @param computeEdges Specifies if the edges (jump functions) shall be computed
	 * @param computeValues Specifies if the values (phase 2) shall be computed
	 */
	private void solveOnWorklist(int numThreads, boolean computeEdges, boolean computeValues) {
		if(numThreads<2) {
			this.executor = Executors.newSingleThreadExecutor();
			this.numThreads = 1;
		} else {
			this.executor = Executors.newFixedThreadPool(numThreads);
			this.numThreads = numThreads;
		}
		if (computeEdges) {
			final long before = System.currentTimeMillis();
			forwardComputeJumpFunctionsSLRPs();
			durationFlowFunctionConstruction = System.currentTimeMillis() - before;
		}
		val.clear();
		if (computeValues) {
			final long before = System.currentTimeMillis();
			computeValues();
			durationFlowFunctionApplication = System.currentTimeMillis() - before;
		}
		if(DEBUG)
			printStats();
		
		if(DUMP_RESULTS)
			dumpResults("ideSolverDump"+System.currentTimeMillis()+".csv");
		
		executor.shutdown();
		if (DEBUG)
			System.out.println(propagationCount + " edges propagated");
	}

	/**
	 * Forward-tabulates the same-level realizable paths and associated functions.
	 * Note that this is a little different from the original IFDS formulations because
	 * we can have statements that are, for instance, both "normal" and "exit" statements.
	 * This is for instance the case on a "throw" statement that may on the one hand
	 * lead to a catch block but on the other hand exit the method depending
	 * on the exception being thrown.
	 */
	private void forwardComputeJumpFunctionsSLRPs() {
		//final Table<D,N,Map<D, EdgeFunction<V>>> oldJumpFn = this.jumpFn.getAllFunctions();
		
		if (this.changedNodes != null)
			this.changedNodes.clear();
		
		forwardComputeJumpFunctionsSLRPs(pathWorklist);
		if (operationMode == OperationMode.Compute)
			return;
		
		/* START OF Check-and-continue */
		/*
		boolean processing = true;
		while (processing) {
			processing = false;
			
			// Make sure to also process new entries for which we don't have any
			// saved facts yet.
			for (N n : jumpFn.getTargets())
				for (Cell<D, D, EdgeFunction<V>> cell : jumpFn.lookupByTarget(n))
					if (!oldJumpFn.contains(cell.getRowKey(), n)) {
						addToWorkList(new PathEdge<N, D, M>(cell.getRowKey(), n, cell.getColumnKey()));
						processing = true;
						
						Map<D,EdgeFunction<V>> functions = new HashMap<D, EdgeFunction<V>>();
						oldJumpFn.put(cell.getRowKey(), n, functions);
						functions.put(cell.getColumnKey(), cell.getValue());
					}

			for (Cell<D, N, Map<D, EdgeFunction<V>>> cell : oldJumpFn.cellSet()) {
				// Check whether the new facts at this node are the same as the old ones.
				Map<D, EdgeFunction<V>> newFacts = new HashMap<D,EdgeFunction<V>>
					(jumpFn.forwardLookup(cell.getRowKey(), cell.getColumnKey()));
				Map<D, EdgeFunction<V>> oldFacts = cell.getValue();
				
				if (!oldFacts.equals(newFacts)) {
					if (DEBUG)
						System.out.println("Updating " + cell.getColumnKey() + " at " + cell.getRowKey()
								+ " in method " + icfg.getMethodOf(cell.getColumnKey()));

					// The facts at this node have changed in the last round, so we
					// need to propagate the changes down
					for (D d : newFacts.keySet())
						addToWorkList(new PathEdge<N, D, M>(cell.getRowKey(), cell.getColumnKey(), d));
					if (newFacts.isEmpty())
						addToWorkList(new PathEdge<N, D, M>(cell.getRowKey(), cell.getColumnKey(), null));
					oldJumpFn.put(cell.getRowKey(), cell.getColumnKey(), newFacts);
					processing = true;
				}
			}
						
			if (processing) {
				this.summaryFunctions.clear();
				forwardComputeJumpFunctionsSLRPs(pathWorklist);
			}
		}
		*/
		/* END OF Check-and-continue */
		
		// Make sure that all incoming edges to join points are considered
		long prePhase2 = System.nanoTime();
		operationMode = OperationMode.Compute;
		for (N n : this.changedNodes) {
			// If this is an exit node and we have an old end summary, we
			// need to delete it as well
			if (icfg.isExitStmt(n))
				for (N sP : icfg.getStartPointsOf(icfg.getMethodOf(n))) {
					 Map<D, Table<N, D, EdgeFunction<V>>> endRow = endSummary.row(sP);
					 for (D d1 : endRow.keySet()) {
						 Table<N, D, EdgeFunction<V>> entryTbl = endRow.get(d1);
						 Utils.removeElementFromTable(entryTbl, n);
					 }
				}
			
			// Get all predecessors of the changed node. Predecessors include
			// direct ones (the statement above, goto origins) as well as return
			// edges for which the current statement is the return site.
			Set<N> preds = null;
			Set<N> exitStmts = icfg.getExitNodesForReturnSite(n);
			if (exitStmts != null)
				preds = new HashSet<N>(exitStmts);
			if (icfg.containsStmt(n))
				if (preds == null)
					preds = new HashSet<N>(icfg.getPredsOf(n));
				else
					preds.addAll(icfg.getPredsOf(n));

			// If we have only one predecessor, there is no second path we need
			// to consider. We have already recreated all facts at the return
			// site.
			if (preds == null || preds.size() < 2)
				continue;
			
			List<N> rmList = new ArrayList<N>();
			for (N pred : preds) {
				if (!icfg.containsStmt(pred)) {
					rmList.add(pred);
					continue;
				}
				for (Cell<D, D, EdgeFunction<V>> cell : jumpFn.lookupByTarget(pred))
					addToWorkList(new PathEdge<N,D,M>(cell.getRowKey(), pred, cell.getColumnKey()));
			}
			if (exitStmts != null)
				for (N rm : rmList)
					exitStmts.remove(rm);
		}
		this.summaryFunctions.clear();
		forwardComputeJumpFunctionsSLRPs(pathWorklist);
		System.out.println("Phase 2 took " + (System.nanoTime() - prePhase2) / 1E9 + " seconds");
	}

	/**
	 * Forward-tabulates the same-level realizable paths and associated functions.
	 * Note that this is a little different from the original IFDS formulations because
	 * we can have statements that are, for instance, both "normal" and "exit" statements.
	 * This is for instance the case on a "throw" statement that may on the one hand
	 * lead to a catch block but on the other hand exit the method depending
	 * on the exception being thrown.
	 * @param workList A list containing the edges still to be processed
	 */
	private void forwardComputeJumpFunctionsSLRPs(Collection<PathEdge<N, D, M>> workList) {
		while(true) {
			synchronized (pathWorklist) {
				if (!workList.isEmpty()) {
					//pop edge
					Iterator<PathEdge<N,D,M>> iter = workList.iterator();
					PathEdge<N,D,M> edge = iter.next();
					iter.remove();
					numTasks.getAndIncrement();
	
					//dispatch processing of edge (potentially in a different thread)
					executor.execute(new PathEdgeProcessingTask(edge));
					propagationCount++;
				} else if(numTasks.intValue()==0){
					//path worklist is empty; no running tasks, we are done
					return;
				} else {
					//the path worklist is empty but we still have running tasks
					//wait until woken up, then try again
					try {
						pathWorklist.wait();
					} catch (InterruptedException e) {
						throw new RuntimeException(e);
					}
				}
			}
		}
	}
	
	/**
	 * Computes the final values for edge functions.
	 */
	private void computeValues() {	
		//Phase II(i)
		for(N startPoint: initialSeeds) {
			assert icfg.containsStmt(startPoint);
			setVal(startPoint, zeroValue, valueLattice.bottomElement());
			Pair<N, D> superGraphNode = new Pair<N,D>(startPoint, zeroValue); 
			nodeWorklist.add(superGraphNode);
		}
		while(true) {
			synchronized (nodeWorklist) {
				if(!nodeWorklist.isEmpty()) {
					//pop job
					Pair<N,D> nAndD = nodeWorklist.remove(0);	
					numTasks.getAndIncrement();
					
					//dispatch processing of job (potentially in a different thread)
					executor.execute(new ValuePropagationTask(nAndD));
				} else if(numTasks.intValue()==0) {
					//node worklist is empty; no running tasks, we are done
					break;
				} else {
					//the node worklist is empty but we still have running tasks
					//wait until woken up, then try again
					try {
						nodeWorklist.wait();
					} catch (InterruptedException e) {
						throw new RuntimeException(e);
					}
				}
			}
		}
		//Phase II(ii)
		//we create an array of all nodes and then dispatch fractions of this array to multiple threads
		Set<N> allNonCallStartNodes = icfg.allNonCallStartNodes();
		if (allNonCallStartNodes.size() > 0) {
			@SuppressWarnings("unchecked")
			N[] nonCallStartNodesArray = (N[]) Array.newInstance
				(allNonCallStartNodes.iterator().next().getClass(), allNonCallStartNodes.size());
			int i=0;
			for (N n : allNonCallStartNodes) {
				nonCallStartNodesArray[i] = n;
				i++;
			}		
			for(int t=0;t<numThreads; t++) {
				executor.execute(new ValueComputationTask(nonCallStartNodesArray, t));
			}
			//wait until done
			executor.shutdown();
			try {
				executor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
	}

	private void propagateValueAtStart(Pair<N, D> nAndD, N n) {
		assert icfg.containsStmt(n);
		D d = nAndD.getO2();
		M p = icfg.getMethodOf(n);
		for(N c: icfg.getCallsFromWithin(p)) {
			assert icfg.containsStmt(c) : "Statement does not exist in graph: " + c;
			Set<Entry<D, EdgeFunction<V>>> entries;
			entries = jumpFn.forwardLookup(d,c).entrySet();
			for(Map.Entry<D,EdgeFunction<V>> dPAndFP: entries) {
				D dPrime = dPAndFP.getKey();
				EdgeFunction<V> fPrime = dPAndFP.getValue();
				N sP = n;
				propagateValue(c,dPrime,fPrime.computeTarget(val(sP,d)));
				flowFunctionApplicationCount++;
			}
		}
	}
	
	private void propagateValueAtCall(Pair<N, D> nAndD, N n) {
		D d = nAndD.getO2();
		for(M q: icfg.getCalleesOfCallAt(n)) {
			FlowFunction<D> callFlowFunction = flowFunctions.getCallFlowFunction(n, q);
			flowFunctionConstructionCount++;
			for(D dPrime: callFlowFunction.computeTargets(d)) {
				EdgeFunction<V> edgeFn = edgeFunctions.getCallEdgeFunction(n, d, q, dPrime);
				for(N startPoint: icfg.getStartPointsOf(q)) {
					assert icfg.containsStmt(startPoint);
					propagateValue(startPoint,dPrime, edgeFn.computeTarget(val(n,d)));
					flowFunctionApplicationCount++;
				}
			}
		}
	}
	
	private void propagateValue(N nHashN, D nHashD, V v) {
		synchronized (val) {
			V valNHash = val(nHashN, nHashD);
			V vPrime = valueLattice.join(valNHash,v);
			if(!vPrime.equals(valNHash)) {
				setVal(nHashN, nHashD, vPrime);
				synchronized (nodeWorklist) {
					nodeWorklist.add(new Pair<N,D>(nHashN,nHashD));
				}
			}
		}
	}

	private V val(N nHashN, D nHashD){ 
		V l = val.get(nHashN, nHashD);
		if(l==null) return valueLattice.topElement(); //implicitly initialized to top; see line [1] of Fig. 7 in SRH96 paper
		else return l;
	}
	
	/**
	 * Sets the computed value for the given pair of statement and flow fact
	 * @param nHashN The statement/node for which to set the value
	 * @param nHashD The flow fact for which to set the value
	 * @param l The value to set
	 */
	private void setVal(N nHashN, D nHashD,V l){
		assert icfg.containsStmt(nHashN);
		if (l == valueLattice.topElement())		// do not save the default element
			return;
		val.put(nHashN, nHashD,l);
		if(DEBUG)
			System.err.println("VALUE: "+((SootMethod)icfg.getMethodOf(nHashN).getContents()).getSignature()
					+" "+nHashN+" "+nHashD+ " " + l);
	}

		
	/**
	 * Lines 13-20 of the algorithm; processing a call site in the caller's context.
	 * This overload allows to restrict processing to a specific return site.
	 * @param edge an edge whose target node resembles a method call
	 * @param returnSite The return site to which to restrict processing. Pass null
	 * to process all return sites.
	 */
	private void processCall(PathEdge<N,D,M> edge) {
		final D d1 = edge.factAtSource();
		final N n = edge.getTarget(); // a call node; line 14...
		final D d2 = edge.factAtTarget();
		
		assert icfg.containsStmt(edge.getTarget());
		
		Collection<N> returnSites = icfg.getReturnSitesOfCallAt(n);
		if (d2 == null) {
			for (N retSite : returnSites)
				clearAndPropagate(d1, retSite);
			return;
		}

		Set<M> callees = icfg.getCalleesOfCallAt(n);
		for(M sCalledProcN: callees) { //still line 14
			FlowFunction<D> function = flowFunctions.getCallFlowFunction(n, sCalledProcN);
			flowFunctionConstructionCount++;
			Set<D> res = function.computeTargets(d2);
			for(N sP: icfg.getStartPointsOf(sCalledProcN)) {
				for(D d3: res) {
//					if (operationMode == OperationMode.Update)
//						clearAndPropagate(d3, sP, d3, EdgeIdentity.<V>v()); //line 15
//					else
						propagate(d3, sP, d3, EdgeIdentity.<V>v()); //line 15
	
					Set<Cell<N, D, EdgeFunction<V>>> endSumm;
					synchronized (incoming) {
						//line 15.1 of Naeem/Lhotak/Rodriguez
						addIncoming(sP,d3,n,d2);

						//line 15.2, copy to avoid concurrent modification exceptions by other threads
						endSumm = new HashSet<Table.Cell<N,D,EdgeFunction<V>>>(endSummary(sP, d3));						
					}
					
					//still line 15.2 of Naeem/Lhotak/Rodriguez
					for(Cell<N, D, EdgeFunction<V>> entry: endSumm) {
						N eP = entry.getRowKey();
						D d4 = entry.getColumnKey();
						EdgeFunction<V> fCalleeSummary = entry.getValue();
						for(N retSiteN: returnSites) {
							FlowFunction<D> retFunction = flowFunctions.getReturnFlowFunction(n, sCalledProcN, eP, retSiteN);
							assert retFunction != null;
							flowFunctionConstructionCount++;
							for(D d5: retFunction.computeTargets(d4)) {
								EdgeFunction<V> f4 = edgeFunctions.getCallEdgeFunction(n, d2, sCalledProcN, d3);
								EdgeFunction<V> f5 = edgeFunctions.getReturnEdgeFunction(n, sCalledProcN, eP, d4, retSiteN, d5);
								synchronized (summaryFunctions) {
									EdgeFunction<V> summaryFunction = summaryFunctions.summariesFor(n, d2, retSiteN).get(d5);			
									if(summaryFunction==null) summaryFunction = allTop; //SummaryFn initialized to all-top, see line [4] in SRH96 paper
									EdgeFunction<V> fPrime = f4.composeWith(fCalleeSummary).composeWith(f5).joinWith(summaryFunction);
									if(!fPrime.equalTo(summaryFunction))
										summaryFunctions.insertFunction(n,d2,retSiteN,d5,fPrime);
								}
							}
						}
					}
				}
			}
		}
		//line 17-19 of Naeem/Lhotak/Rodriguez
		EdgeFunction<V> f = jumpFunction(edge);
		for (N returnSiteN : returnSites) {
			assert icfg.containsStmt(returnSiteN);
			FlowFunction<D> callToReturnFlowFunction = flowFunctions.getCallToReturnFlowFunction(n, returnSiteN);
			assert callToReturnFlowFunction != null;
			flowFunctionConstructionCount++;
			Set<D> targets = callToReturnFlowFunction.computeTargets(d2);
			for(D d3: targets) {
				EdgeFunction<V> edgeFnE = edgeFunctions.getCallToReturnEdgeFunction(n, d2, returnSiteN, d3);
				if (operationMode == OperationMode.Update)
					clearAndPropagate(d1, returnSiteN, d3, f.composeWith(edgeFnE));
				else
					propagate(d1, returnSiteN, d3, f.composeWith(edgeFnE));
			}
			if (operationMode == OperationMode.Update && targets.isEmpty())
				clearAndPropagate(d1, returnSiteN);

			synchronized (summaryFunctions) {	// TODO : Locking
				Map<D,EdgeFunction<V>> d3sAndF3s = summaryFunctions.summariesFor(n, d2, returnSiteN);
				for (Map.Entry<D,EdgeFunction<V>> d3AndF3 : d3sAndF3s.entrySet()) {
					D d3 = d3AndF3.getKey();
					EdgeFunction<V> f3 = d3AndF3.getValue();
					if(f3==null) f3 = allTop; //SummaryFn initialized to all-top, see line [4] in SRH96 paper
					if (operationMode == OperationMode.Update)
						clearAndPropagate(d1, returnSiteN, d3, f.composeWith(f3));
					else
						propagate(d1, returnSiteN, d3, f.composeWith(f3));
				}
			}
		}
	}

	private EdgeFunction<V> jumpFunction(PathEdge<N, D, M> edge) {
		EdgeFunction<V> function = jumpFn.forwardLookup(edge.factAtSource(), edge.getTarget()).get(edge.factAtTarget());
		if(function==null) return allTop; //JumpFn initialized to all-top, see line [2] in SRH96 paper
		return function;
	}

	/**
	 * Lines 21-32 of the algorithm.	
	 */
	private void processExit(PathEdge<N,D,M> edge) {
		final N n = edge.getTarget(); // an exit node; line 21...
		assert n != null;
		assert icfg.containsStmt(n);
		
		M methodThatNeedsSummary = icfg.getMethodOf(n);
		assert methodThatNeedsSummary != null;

		final EdgeFunction<V> f = jumpFunction(edge);
		assert f != null;
		
		final D d1 = edge.factAtSource();
		assert d1 != null;

		final D d2 = edge.factAtTarget();
		
		assert icfg.containsStmt(edge.target);
		
		for(N sP: icfg.getStartPointsOf(methodThatNeedsSummary)) {
			assert icfg.containsStmt(sP);
			//line 21.1 of Naeem/Lhotak/Rodriguez
			Set<Entry<N, Set<D>>> inc;
			synchronized (incoming) {
				if (d2 != null)
					addEndSummary(sP, d1, n, d2, f);
				//copy to avoid concurrent modification exceptions by other threads
				inc = new HashSet<Map.Entry<N,Set<D>>>(incoming(d1, sP));
			}
			
			for (Entry<N,Set<D>> entry: inc) {
				//line 22
				N c = entry.getKey();
				assert icfg.containsStmt(c);
				for(N retSiteC: icfg.getReturnSitesOfCallAt(c)) {
					boolean doPropagate = true;
					// Do not return into a method if there is a predecessor that
					// will be changed later anyway
					if (operationMode == OperationMode.Update)
						if (predecessorRepropagated(this.changeSet.get(icfg.getMethodOf(retSiteC)), retSiteC))
							doPropagate = false;

					assert icfg.containsStmt(retSiteC);
					if (d2 == null) {
						clearAndPropagate(d1, retSiteC);
						continue;
					}
					
					assert icfg.containsStmt(retSiteC);
					FlowFunction<D> retFunction = flowFunctions.getReturnFlowFunction(c, methodThatNeedsSummary,n,retSiteC);
					flowFunctionConstructionCount++;
					Set<D> targets = retFunction.computeTargets(d2);
					for(D d4: entry.getValue()) {
						//line 23
						for(D d5: targets) {
							EdgeFunction<V> f4 = edgeFunctions.getCallEdgeFunction(c, d4, icfg.getMethodOf(n), d1);
							EdgeFunction<V> f5 = edgeFunctions.getReturnEdgeFunction(c, icfg.getMethodOf(n), n, d2, retSiteC, d5);
							EdgeFunction<V> fPrime;
							synchronized (summaryFunctions) {
								EdgeFunction<V> summaryFunction = summaryFunctions.summariesFor(c,d4,retSiteC).get(d5);
								if(summaryFunction==null) summaryFunction = allTop; //SummaryFn initialized to all-top, see line [4] in SRH96 paper
								fPrime = f4.composeWith(f).composeWith(f5).joinWith(summaryFunction);
								if(!fPrime.equalTo(summaryFunction)) {
									summaryFunctions.insertFunction(c,d4,retSiteC,d5,fPrime);
								}
							}
							if (doPropagate)
								for(Map.Entry<D,EdgeFunction<V>> valAndFunc: jumpFn.reverseLookup(c,d4).entrySet()) {
									EdgeFunction<V> f3 = valAndFunc.getValue();
									if(!f3.equalTo(allTop)) {
										D d3 = valAndFunc.getKey();
										
										String meth = icfg.getMethodOf(retSiteC).toString();
										
										if (DEBUG)
											System.out.println("leaving method " + methodThatNeedsSummary + " for" +
													" return site " + meth + "." + retSiteC + " on " + d3 +
													" called by " + c);
										
										if (operationMode == OperationMode.Update)
											clearAndPropagate(d3, retSiteC, d5, f3.composeWith(fPrime));
										else
											propagate(d3, retSiteC, d5, f3.composeWith(fPrime));
									}
								}
						}
						if (operationMode == OperationMode.Update && targets.isEmpty() && doPropagate)
							for(D d3 : jumpFn.reverseLookup(c,d4).keySet())
								clearAndPropagate(d3, retSiteC);
					}
				}
			}
		}
	}
	
	/**
	 * Lines 33-37 of the algorithm.
	 * @param edge
	 */
	private void processNormalFlow(PathEdge<N, D, M> edge) {
		assert edge != null;
		
		final D d1 = edge.factAtSource();
		final N n = edge.getTarget();
		final D d2 = edge.factAtTarget();
		
		if (d2 == null) {
			assert operationMode == OperationMode.Update;
			for (N m : icfg.getSuccsOf(edge.getTarget()))
				clearAndPropagate(d1, m);
			return;
		}
		
		assert d1 != null;
		assert n != null;
		assert d2 != null;
		
		EdgeFunction<V> f = jumpFunction(edge);
		for (N m : icfg.getSuccsOf(edge.getTarget())) {
			FlowFunction<D> flowFunction = flowFunctions.getNormalFlowFunction(n,m);
			flowFunctionConstructionCount++;
			Set<D> res = flowFunction.computeTargets(d2);
			for (D d3 : res) {
				EdgeFunction<V> fprime = f.composeWith(edgeFunctions.getNormalEdgeFunction(n, d2, m, d3));
				assert fprime != null;
				if (operationMode == OperationMode.Update)
					clearAndPropagate(d1, m, d3, fprime);
				else
					propagate(d1, m, d3, fprime);
			}
			if (operationMode == OperationMode.Update && res.isEmpty())
				clearAndPropagate(d1, m);
		}
	}

	private void propagate(D sourceVal, N target, D targetVal, EdgeFunction<V> f) {
		assert sourceVal != null;
		assert target != null;
		assert targetVal != null;
		assert f != null;
				
		assert icfg.containsStmt(target) : "Propagated statement not found in graph. Is your "
			+ "call graph valid? Offending statement: " + target;
//		assert operationMode == OperationMode.Compute;
		
		// Check whether we have changed a path edge. If so, immediately update it
		// and release the monitor
		boolean added = false;
		synchronized (jumpFn) {
			EdgeFunction<V> jumpFnE = jumpFn.reverseLookup(target, targetVal).get(sourceVal);
			if(jumpFnE==null) jumpFnE = allTop; //JumpFn is initialized to all-top (see line [2] in SRH96 paper)
			EdgeFunction<V> fPrime = jumpFnE.joinWith(f);
			if (!fPrime.equalTo(jumpFnE)) {
				jumpFn.addFunction(sourceVal, target, targetVal, fPrime);	// synchronized function
				added = true;

				if(DEBUG) {
					if(targetVal!=zeroValue) {
						StringBuilder result = new StringBuilder();
						result.append("EDGE:  <");
						result.append(icfg.getMethodOf(target));
						result.append(",");
						result.append(sourceVal);
						result.append("> -> <");
						result.append(target);
						result.append(",");
						result.append(targetVal);
						result.append("> - ");
						result.append(fPrime);
						System.out.println(result.toString());
					}
				}
			}
		}
		
		if (added) {
			PathEdge<N,D,M> edge = new PathEdge<N,D,M>(sourceVal, target, targetVal);
			addToWorkList(edge);	// thread-safe, includes all necessary synchronization
		}
	}
	
	private void clearAndPropagate(D sourceVal, N target, D targetVal, EdgeFunction<V> f) {
		assert icfg.containsStmt(target) : "Propagated statement not found in graph. Is your "
			+ "call graph valid? Offending statement: " + target;
		assert operationMode == OperationMode.Update;
		
		boolean added = false;
		synchronized (jumpFn) {
			synchronized (jumpSave) {
				Map<D, EdgeFunction<V>> savedFacts = this.jumpSave.get(target, sourceVal);
				if (savedFacts == null) {
					// We have not processed this edge yet. Record the original data
					// so that we can later check whether our re-processing has changed
					// anything
					Map<D, EdgeFunction<V>> targetDs = new HashMap<D, EdgeFunction<V>>
						(this.jumpFn.forwardLookup(sourceVal, target));
					this.jumpSave.put(target, sourceVal, targetDs);
	
					// Delete the original facts
					for (D d : targetDs.keySet())
						this.jumpFn.removeFunction(sourceVal, target, d);
					synchronized (changedNodes) {
						this.changedNodes.add(target);
					}
				}
		
				// If the function with which we are coming in right now is different
				// from what we already have as the "new" jump function, we need to
				// record it.
				EdgeFunction<V> jumpFnE = jumpFn.reverseLookup(target, targetVal).get(sourceVal);
				if(jumpFnE==null) jumpFnE = allTop; //JumpFn is initialized to all-top (see line [2] in SRH96 paper)
				EdgeFunction<V> fPrime = jumpFnE.joinWith(f);
				if (!fPrime.equalTo(jumpFnE)) {
					jumpFn.addFunction(sourceVal, target, targetVal, fPrime);
					added = true;
				}
			}
		}
		if (added)
			addToWorkList(new PathEdge<N, D, M>(sourceVal, target, targetVal));	// thread-safe function
	}

	private void clearAndPropagate(D sourceVal, N target) {
		assert icfg.containsStmt(target) : "Propagated statement not found in graph. Is your "
			+ "call graph valid? Offending statement: " + target;
		assert operationMode == OperationMode.Update;
		
		synchronized (jumpFn) {
			synchronized (jumpSave) {
				if (!this.jumpSave.contains(target, sourceVal)) {
					// We have not processed this edge yet. Record the original data
					// so that we can later check whether our re-processing has changed
					// anything
					Map<D, EdgeFunction<V>> targetDs = new HashMap<D, EdgeFunction<V>>
						(this.jumpFn.forwardLookup(sourceVal, target));
					this.jumpSave.put(target, sourceVal, targetDs);
	
					// Delete the original facts
					for (D d : targetDs.keySet())
						this.jumpFn.removeFunction(sourceVal, target, d);
					synchronized (changedNodes) {
						this.changedNodes.add(target);
						addToWorkList(new PathEdge<N, D, M>(sourceVal, target, null));
					}
				}
			}
		}
	}

	private void addToWorkList(PathEdge<N, D, M> edge) {
		assert icfg.containsStmt(edge.getTarget()) :
			"Statement not found in graph: " + edge.getTarget();
		synchronized (pathWorklist) {
//			if (!pathWorklist.contains(edge))
				pathWorklist.add(edge);
		}
	}

	private Set<Cell<N, D, EdgeFunction<V>>> endSummary(N sP, D d3) {
		Table<N, D, EdgeFunction<V>> map = endSummary.get(sP, d3);
		if(map==null) return Collections.emptySet();
		return map.cellSet();
	}

	private void addEndSummary(N sP, D d1, N eP, D d2, EdgeFunction<V> f) {
		assert icfg.containsStmt(sP);
		assert icfg.containsStmt(eP);
		
		Table<N, D, EdgeFunction<V>> summaries = endSummary.get(sP, d1);
		if(summaries==null) {
			summaries = HashBasedTable.create();
			endSummary.put(sP, d1, summaries);
		}
		summaries.put(eP,d2,f);
	}	
	
	private Set<Entry<N, Set<D>>> incoming(D d1, N sP) {
		Map<N, Set<D>> map = incoming.get(sP, d1);
		if(map==null) return Collections.emptySet();
		return map.entrySet();
	}
	
	private void addIncoming(N sP, D d3, N n, D d2) {
		Map<N, Set<D>> summaries = incoming.get(sP, d3);
		if(summaries==null) {
			summaries = new HashMap<N, Set<D>>();
			incoming.put(sP, d3, summaries);
		}
		Set<D> set = summaries.get(n);
		if(set==null) {
			set = new HashSet<D>();
			summaries.put(n,set);
		}
		set.add(d2);
	}
	
	/**
	 * Returns the V-type result for the given value at the given statement. 
	 */
	public V resultAt(N stmt, D value) {
		return val.get(stmt, value);
	}
	
	/**
	 * Returns the resulting environment for the given statement.
	 * The artificial zero value is automatically stripped.
	 */
	public Map<D,V> resultsAt(N stmt) {
		//filter out the artificial zero-value
		return Maps.filterKeys(val.row(stmt), new Predicate<D>() {

			public boolean apply(D val) {
				return val!=zeroValue;
			}
		});
	}

	public void dumpResults(String fileName) {
		try {
			PrintWriter out = new PrintWriter(new FileOutputStream(fileName));
			List<String> res = new ArrayList<String>();
			for(Cell<N, D, V> entry: val.cellSet()) {
				SootMethod methodOf = (SootMethod) icfg.getMethodOf(entry.getRowKey()).getContents();
				PatchingChain<Unit> units = methodOf.getActiveBody().getUnits();
				int i=0;
				for (Unit unit : units) {
					if(unit==entry.getRowKey())
						break;
					i++;
				}
				
				res.add(methodOf+";"+entry.getRowKey()+"@"+i+";"+entry.getColumnKey()+";"+entry.getValue());
			}
			Collections.sort(res);
			for (String string : res) {
				out.println(string);
			}
			out.flush();
			out.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}

	public void printStats() {
		if(DEBUG) {
			if(ffCache!=null)
				ffCache.printStats();
			if(efCache!=null)
				efCache.printStats();
		} else {
			System.err.println("No statistics were collected, as DEBUG is disabled.");
		}
	}
	
	/**
	 * Worker class for processing a single edge in the control-flow graph
	 */
	private class PathEdgeProcessingTask implements Runnable {
		private final PathEdge<N, D, M> edge;

		/**
		 * Creates a new instance of the PathEdgeProcessingTask class.
		 * @param edge The edge that shall be processed by this worker object
		 */
		public PathEdgeProcessingTask
				(PathEdge<N, D, M> edge) {
			assert edge != null;
			
			this.edge = edge;
		}

		public void run() {
			processSingleEdge(this.edge);
			synchronized (pathWorklist) {
				numTasks.getAndDecrement();
				//potentially wake up waiting broker thread
				//(see forwardComputeJumpFunctionsSLRPs())
				pathWorklist.notify();
			}
		}
	}
	
	private void processSingleEdge(PathEdge<N,D,M> edge) {
		if (edge.getTarget().toString().equals("$r3 = virtualinvoke listener.<java.lang.Object: java.lang.String toString()>()"))
			System.out.println("x");
		
		assert icfg.containsStmt(edge.getTarget());
		if(icfg.isCallStmt(edge.getTarget())) {
			try {
			processCall(edge);
			}
			catch (Exception ex) {
				System.out.println(ex);
				ex.printStackTrace();
			}
		} else {
			//note that some statements, such as "throw" may be
			//both an exit statement and a "normal" statement
			try {
			if(icfg.isExitStmt(edge.getTarget())) {
				processExit(edge);
			}
			} catch (Exception ex) {
				System.out.println(ex);
				ex.printStackTrace();
			}
			try {
				if(!icfg.getSuccsOf(edge.getTarget()).isEmpty())
					processNormalFlow(edge);
			} catch (Exception ex) {
				System.out.println(ex + " - " + edge);
				ex.printStackTrace();
			}
		}
	}

	private class ValuePropagationTask implements Runnable {
		private final Pair<N, D> nAndD;

		public ValuePropagationTask(Pair<N,D> nAndD) {
			this.nAndD = nAndD;
		}

		public void run() {
			N n = nAndD.getO1();
			assert icfg.containsStmt(n);

			if(icfg.isStartPoint(n)) {
				propagateValueAtStart(nAndD, n);
			}
			if(icfg.isCallStmt(n)) {
				propagateValueAtCall(nAndD, n);
			}
			synchronized (nodeWorklist) {
				numTasks.getAndDecrement();
				//potentially wake up waiting broker thread
				//(see forwardComputeJumpFunctionsSLRPs())
				nodeWorklist.notify();
			}
		}
	}
	
	private class ValueComputationTask implements Runnable {
		private final N[] values;
		final int num;

		public ValueComputationTask(N[] values, int num) {
			this.values = values;
			this.num = num;
		}

		public void run() {
			int sectionSize = (int) Math.floor(values.length / numThreads) + numThreads;
			for(int i = sectionSize * num; i < Math.min(sectionSize * (num+1),values.length); i++) {
				N n = values[i];
				
				Set<N> startPoints = icfg.getStartPointsOf(icfg.getMethodOf(n));
				assert !startPoints.isEmpty();
				for(N sP: startPoints) {					
					Set<Cell<D, D, EdgeFunction<V>>> lookupByTarget;
					lookupByTarget = jumpFn.lookupByTarget(n);
					for(Cell<D, D, EdgeFunction<V>> sourceValTargetValAndFunction : lookupByTarget) {
						D dPrime = sourceValTargetValAndFunction.getRowKey();
						D d = sourceValTargetValAndFunction.getColumnKey();						
						EdgeFunction<V> fPrime = sourceValTargetValAndFunction.getValue();
						
						V vP = val(sP,dPrime);
						if (vP == valueLattice.topElement())
							continue;
						V v2 = fPrime.computeTarget(vP);
						if (v2 == valueLattice.topElement())
							continue;

						synchronized (val) {
							setVal(n,d,valueLattice.join(val(n,d),fPrime.computeTarget(val(sP,dPrime))));
						}
						flowFunctionApplicationCount++;
					}
				}
			}
		}
	}
	
	/**
	 * Updates an already generated solution based on changes to the underlying
	 * control flow graph
	 * @param newCFG The new control flow graph with which to update the
	 * analysis results
	 * @param numThreads The number of threads to use.
	 */
	public void update(I newCFG) {
		update(Runtime.getRuntime().availableProcessors(), newCFG);
	}
	
	/**
	 * Updates an already generated solution based on changes to the underlying
	 * control flow graph
	 * @param numThreads The number of threads to use.
	 * @param newCFG The new control flow graph with which to update the
	 * analysis results
	 */
	public void update(int numTreads, I newCFG) {
		assert newCFG != null;
		System.out.println("Performing IDE update...");
		
		this.jumpSave = HashBasedTable.create(this.jumpFn.targetCount(), this.jumpFn.sourceFactCount());
		this.numThreads = numTreads;
		System.out.println("Running with " + numThreads + " threads");
		
		// Update the stored control-flow graph. We save the old CFG so that
		// we are still able to inversely propagate the expired facts along
		// the deleted edges.
		oldcfg = icfg;
		icfg = newCFG;
		tabulationProblem.updateCFG(newCFG);

		// Next, we need to create a changeset on the control flow graph
		Map<N, List<N>> expiredEdges = new HashMap<N, List<N>>(5000);
		Map<N, List<N>> newEdges = new HashMap<N, List<N>>(5000);
		Set<N> newNodes = new HashSet<N>(100);
		Set<N> expiredNodes = new HashSet<N>(100);
		if (DEBUG)
			System.out.println("Computing changeset...");
		computeCFGChangeset(expiredEdges, newEdges, newNodes, expiredNodes);
		if (DEBUG)
			System.out.println("Changeset computed.");
		
		// If we have not computed any graph changes, we are done
		if (expiredEdges.size() == 0 && newEdges.size() == 0) {
			System.out.println("CFG is unchanged, aborting update...");
			
			// Nevertheless, update the object references
			icfg = newCFG;
			tabulationProblem.updateCFG(newCFG);
			return;
		}

		this.changedNodes = new HashSet<N>((int) this.propagationCount);
		this.propagationCount = new Long(0);
		
		// Make sure we don't cache any expired nodes
		long beforeRemove = System.nanoTime();
		System.out.println("Removing " + expiredNodes.size() + " expired nodes...");
		for (N n : expiredNodes) {
			this.jumpFn.removeByTarget(n);
			Utils.removeElementFromTable(this.incoming, n);
			Utils.removeElementFromTable(this.endSummary, n);

			for (Cell<N, D, Map<N, Set<D>>> cell : incoming.cellSet())
				cell.getValue().remove(n);
			for (Cell<N, D, Table<N, D, EdgeFunction<V>>> cell : endSummary.cellSet())
				Utils.removeElementFromTable(cell.getValue(), n);
		}
		System.out.println("Expired nodes removed in "
				+ (System.nanoTime() - beforeRemove) / 1E9
				+ " seconds.");
		
		// Process edge insertions. This will only do the incoming edges of new
		// nodes as the outgoing ones will only be available after the incoming
		// ones have been processed and the graph has been extended.
		this.operationMode = OperationMode.Update;
		changeSet = new HashMap<M, Set<N>>(newEdges.size() + expiredEdges.size());
		if (!newEdges.isEmpty()) {
			System.out.println("Updating " + newEdges.size() + " new edges...");
			changeSet.putAll(updateNewEdges(newEdges, newNodes));
			System.out.println("New edges updated");
		}
		
		// Process edge deletions
		if (!expiredEdges.isEmpty()) {
			System.out.println("Deleting " + expiredEdges.size() + " expired edges...");
			changeSet.putAll(deleteExpiredEdges(expiredNodes, expiredEdges));
			System.out.println("Expired edges deleted.");
		}

		Set<N> totalChangedNodes = new HashSet<N>((int) this.propagationCount);
		System.out.println("Processing worklist for edges...");
		int edgeIdx = 0;
		long beforeEdges = System.nanoTime();
		for (M m : changeSet.keySet())
			for (N preLoop : changeSet.get(m)) {
				// If a predecessor in the same method has already been
				// the start point of a propagation, we can skip this one.
				if (this.predecessorRepropagated(changeSet.get(m), preLoop))
					continue;
				// If another propagation has already visited this node,
				// starting a new propagation from here cannot create
				// any fact changes.
				if (totalChangedNodes.contains(preLoop))
					continue;
				edgeIdx++;
				
				for (Cell<D, D, EdgeFunction<V>> srcEntry : jumpFn.lookupByTarget(preLoop)) {
					D srcD = srcEntry.getRowKey();
					D tgtD = srcEntry.getColumnKey();
	
					if (DEBUG)
						System.out.println("Reprocessing edge: <" + srcD
								+ "> -> <" + preLoop + ", " + tgtD + ">");
					addToWorkList(new PathEdge<N,D,M>(srcD, preLoop, tgtD));	
				}
			
				if (DEBUG)
					System.out.println("Processing worklist for method " + m + "...");
				this.operationMode = OperationMode.Update;
				this.jumpSave.clear();
				solveOnWorklist(numThreads, true, false);
				
				totalChangedNodes.addAll(this.changedNodes);
			}
		
		System.out.println("Actually processed " + edgeIdx + " of "
				+ (newEdges.size() + expiredEdges.size()) + " expired edges in "
				+ (System.nanoTime() - beforeEdges) / 1E9 + " seconds");

		System.out.println("Processing worklist for values...");
		this.operationMode = OperationMode.Compute;
		solveOnWorklist(numThreads, false, true);
		System.out.println("Worklist processing done, " + propagationCount + " edges processed.");
		
		this.oldcfg = null; // allow for garbage collection
		this.changedNodes = null;
	}

	/**
	 * Deletes the expired edges from the program analysis results and updates
	 * the DAG accordingly
	 * @param expiredNodes The nodes that have been deleted from the program
	 * graph
	 * @param expiredEdges A list containing all expired edges
	 * @return A mapping from changed methods to all statements that need to
	 * be reprocessed in the method
	 */
	private Map<M, Set<N>> deleteExpiredEdges(Set<N> expiredNodes, Map<N, List<N>> expiredEdges) {
		// Start a new propagation on the deleted edges' source node
		Map<M, Set<N>> methodExpiredEdges = new HashMap<M, Set<N>>();
		for (Entry<N, List<N>> entry : expiredEdges.entrySet()) {
			N srcN = entry.getKey();
			if (expiredNodes.contains(srcN))
				continue;
			
			N loopStart = icfg.getLoopStartPointFor(srcN);
			if (loopStart != null && expiredNodes.contains(loopStart))
				continue;
			
			List<N> preds = new ArrayList<N>();
			if (loopStart == null)
				preds.add(srcN);
			else
				preds.addAll(icfg.getPredsOf(loopStart));

			for (N preLoop : preds) {
				// Do not propagate a node more than once
				M m = icfg.getMethodOf(preLoop);
				Utils.addElementToMapSet(methodExpiredEdges, m, preLoop);
			}
		}
		return methodExpiredEdges;
	}

	private boolean predecessorRepropagated(Set<N> srcNodes, N srcN) {
		if (srcNodes == null)
			return false;
		List<N> curNodes = new ArrayList<N>();
		Set<N> doneSet = new HashSet<N>(100);
		curNodes.addAll(icfg.getPredsOf(srcN));
		while (!curNodes.isEmpty()) {
			N n = curNodes.remove(0);
			if (!doneSet.add(n))
				continue;
			
			if (srcNodes.contains(n) && n != srcN)
				return true;
			curNodes.addAll(icfg.getPredsOf(n));
		}
		return false;
	}

	/**
	 * Adds a given list of new edges to the DAG graph. This can require a re-
	 * evaluation of one or more edges to check whether new (N,D) nodes are
	 * introduced subsequently.
	 * @param newEdges The list of edges to add to the DAG.
	 * @param newNodes The list of new nodes in the program graph
	 * @return A mapping from changed methods to all statements that need to
	 * be reprocessed in the method
	 */
	private Map<M, Set<N>> updateNewEdges(Map<N, List<N>> newEdges, Set<N> newNodes) {
		// Process edge insertions. Nodes are processed along with their edges
		// which implies that new unconnected nodes (unreachable statements)
		// will automatically be ignored.
		Map<M, Set<N>> newMethodEdges = new HashMap<M, Set<N>>(newEdges.size());
		for (N srcN : newEdges.keySet()) {
			if (newNodes.contains(srcN))
				continue;
			
			N loopStart = icfg.getLoopStartPointFor(srcN);

			List<N> preds = new ArrayList<N>();
			if (loopStart == null)
				preds.add(srcN);
			else
				preds.addAll(icfg.getPredsOf(loopStart));
			
			for (N preLoop : preds) {
				// Do not propagate a node more than once
				M m = icfg.getMethodOf(preLoop);
				Utils.addElementToMapSet(newMethodEdges, m, preLoop);
			}
		}
		return newMethodEdges;
	}

	/**
	 * Computes a change set of added and removed edges in the control-flow
	 * graph and updates the flow functions to remap unchanged old nodes to
	 * new ones and remove expired nodes.
	 * @param expiredEdges A list which receives the edges that are no longer
	 * present in the updated CFG
	 * @param newEdges A list which receives the edges that have been newly
	 * introduced in the updated CFG
	 * @param newNodes A list which receives the nodes that have been newly
	 * introduced in the updated CFG
	 * @param expiredNodes A list which receives the nodes that have been
	 * removed from the graph.
	 */
	private void computeCFGChangeset(Map<N, List<N>> expiredEdges,
			Map<N, List<N>> newEdges, Set<N> newNodes, Set<N> expiredNodes) {
		long startTime = System.nanoTime();

		oldcfg.computeCFGChangeset(icfg, expiredEdges, newEdges, newNodes,
				expiredNodes);
		
		// Print out the expired edges
//		if (DEBUG) {
			for (N key : expiredEdges.keySet())
				for (N val : expiredEdges.get(key))
					System.out.println("expired edge: (" + (oldcfg.containsStmt(key) ? oldcfg.getMethodOf(key)
							: icfg.getMethodOf(key)) + ") " + key + " -> " + val);
			for (N key : newEdges.keySet())
				for (N val : newEdges.get(key))
					System.out.println("new edge: (" + icfg.getMethodOf(key) + ") " + key + " -> " + val);
			for (N key : expiredNodes) {
				if (!oldcfg.containsStmt(key))
					System.out.println("foo " + icfg.getMethodOf(key));
				System.out.println("expired node: (" + oldcfg.getMethodOf(key) + ") " + key);
			}
			for (N key : newNodes)
				System.out.println("new node: (" + icfg.getMethodOf(key) + ") " + key);
//		}
		
		// Merge the wrap lists
		icfg.mergeWrappers(oldcfg);
		
		
		// Invalidate all cached functions
		ffCache.invalidateAll();
		efCache.invalidateAll();
		
		System.out.println("CFG changeset computation took " + (System.nanoTime() - startTime) / 1E9
				+ " seconds");
	}
	
}
