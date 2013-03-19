package soot.jimple.interproc.ifds.problems;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import soot.EquivalentValue;
import soot.Scene;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.DefinitionStmt;
import soot.jimple.InvokeExpr;
import soot.jimple.Jimple;
import soot.jimple.ReturnStmt;
import soot.jimple.ReturnVoidStmt;
import soot.jimple.Stmt;
import soot.jimple.interproc.ifds.FlowFunction;
import soot.jimple.interproc.ifds.FlowFunctions;
import soot.jimple.interproc.ifds.InterproceduralCFG;
import soot.jimple.interproc.ifds.flowfunc.Identity;
import soot.jimple.interproc.ifds.flowfunc.KillAll;
import soot.jimple.interproc.ifds.template.DefaultIFDSTabulationProblem;
import soot.jimple.interproc.incremental.UpdatableWrapper;
import soot.toolkits.scalar.Pair;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

public class IFDSReachingDefinitions extends DefaultIFDSTabulationProblem
		<UpdatableReachingDefinition,InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>>> {
	
	private static final UpdatableReachingDefinition zeroValue = UpdatableReachingDefinition.zero;
	private final LoadingCache<Pair<UpdatableWrapper<Value>, Set<UpdatableWrapper<DefinitionStmt>>>,
		UpdatableReachingDefinition> wrapperObjects;
	
	public IFDSReachingDefinitions(final InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg) {
		super(icfg);
		
		CacheBuilder<Object, Object> cb = CacheBuilder.newBuilder().concurrencyLevel
				(Runtime.getRuntime().availableProcessors()).initialCapacity(100000); //.weakKeys();
		this.wrapperObjects = cb.build(new CacheLoader<Pair<UpdatableWrapper<Value>, Set<UpdatableWrapper<DefinitionStmt>>>, UpdatableReachingDefinition>() {

			@Override
			public UpdatableReachingDefinition load
					(Pair<UpdatableWrapper<Value>, Set<UpdatableWrapper<DefinitionStmt>>> key) throws Exception {
				UpdatableReachingDefinition urd = new UpdatableReachingDefinition(key.getO1(), key.getO2());
				return urd;
			}

		});
	}
	
	private UpdatableReachingDefinition createReachingDefinition(Value value, Set<DefinitionStmt> definitions) {
		UpdatableWrapper<Value> wrappedValue = this.interproceduralCFG().wrapWeak(value);
		Set<UpdatableWrapper<DefinitionStmt>> wrappedDefs = this.interproceduralCFG().wrapWeak(definitions);
		Pair<UpdatableWrapper<Value>, Set<UpdatableWrapper<DefinitionStmt>>> pair =
				new Pair<UpdatableWrapper<Value>, Set<UpdatableWrapper<DefinitionStmt>>>(wrappedValue, wrappedDefs);
		
		try {
			return this.wrapperObjects.get(pair);
		} catch (ExecutionException e) {
			System.err.println("Could not wrap object");
			e.printStackTrace();
			return null;
		}
	}
	
	@Override
	public FlowFunctions<UpdatableWrapper<Unit>, UpdatableReachingDefinition, UpdatableWrapper<SootMethod>> createFlowFunctionsFactory() {
		return new FlowFunctions<UpdatableWrapper<Unit>, UpdatableReachingDefinition, UpdatableWrapper<SootMethod>>() {

			@Override
			public FlowFunction<UpdatableReachingDefinition> getNormalFlowFunction
					(final UpdatableWrapper<Unit> curr, UpdatableWrapper<Unit> succ) {
				if (curr.getContents() instanceof DefinitionStmt) {
					final UpdatableWrapper<DefinitionStmt> assignment = interproceduralCFG().wrapWeak
							((DefinitionStmt) curr.getContents());

					return new FlowFunction<UpdatableReachingDefinition>() {
						@Override
						public Set<UpdatableReachingDefinition> computeTargets(UpdatableReachingDefinition source) {
							if (!source.equals(zeroValue())) {
								if (source.getContents().getO1().equivTo(assignment.getContents().getLeftOp())) {
									return Collections.emptySet();
								}
								return Collections.singleton(source);
							} else {
								LinkedHashSet<UpdatableReachingDefinition> res = new LinkedHashSet<UpdatableReachingDefinition>();
								res.add(createReachingDefinition(assignment.getContents().getLeftOp(),
									Collections.<DefinitionStmt> singleton(assignment.getContents())));
								return res;
							}
						}
					};
				}

				return Identity.v();
			}

			@Override
			public FlowFunction<UpdatableReachingDefinition> getCallFlowFunction
					(UpdatableWrapper<Unit> callStmt,
					final UpdatableWrapper<SootMethod> destinationMethod) {
				Stmt stmt = (Stmt) callStmt.getContents();
				InvokeExpr invokeExpr = stmt.getInvokeExpr();
				final List<UpdatableWrapper<Value>> args = interproceduralCFG().wrapWeak(invokeExpr.getArgs());

				return new FlowFunction<UpdatableReachingDefinition>() {

					@Override
					public Set<UpdatableReachingDefinition> computeTargets(UpdatableReachingDefinition source) {
						UpdatableWrapper<Value> value = interproceduralCFG().wrapWeak(source.getValue());
						if(args.contains(value)) {
							int paramIndex = args.indexOf(value);
							UpdatableReachingDefinition pair = createReachingDefinition
									(new EquivalentValue(Jimple.v().newParameterRef
											(destinationMethod.getContents().getParameterType(paramIndex), paramIndex)),
									source.getContents().getO2());
							return Collections.singleton(pair);
						}

						return Collections.emptySet();
					}
				};
			}

			@Override
			public FlowFunction<UpdatableReachingDefinition> getReturnFlowFunction
					(final UpdatableWrapper<Unit> callSite,
					UpdatableWrapper<SootMethod> calleeMethod,
					final UpdatableWrapper<Unit> exitStmt,
					UpdatableWrapper<Unit> returnSite) {
				if (!(callSite.getContents() instanceof DefinitionStmt))
					return KillAll.v();

				if (exitStmt.getContents() instanceof ReturnVoidStmt)
					return KillAll.v();

				return new FlowFunction<UpdatableReachingDefinition>() {

					@Override
					public Set<UpdatableReachingDefinition> computeTargets(UpdatableReachingDefinition source) {
						if (exitStmt.getContents() instanceof ReturnStmt) {
							ReturnStmt returnStmt = (ReturnStmt) exitStmt.getContents();
							if (returnStmt.getOp().equivTo(source.getContents().getO1())) {
								DefinitionStmt definitionStmt = (DefinitionStmt) callSite.getContents();
								UpdatableReachingDefinition pair = createReachingDefinition
										(definitionStmt.getLeftOp(), source.getContents().getO2());
								return Collections.singleton(pair);
							}
						}
						return Collections.emptySet();
					}
				};
			}

			@Override
			public FlowFunction<UpdatableReachingDefinition> getCallToReturnFlowFunction
					(UpdatableWrapper<Unit> callSite, UpdatableWrapper<Unit> returnSite) {
				if (!(callSite.getContents() instanceof DefinitionStmt))
					return Identity.v();
				
				final UpdatableWrapper<DefinitionStmt> definitionStmt = interproceduralCFG().wrapWeak
						((DefinitionStmt) callSite.getContents());
				return new FlowFunction<UpdatableReachingDefinition>() {

					@Override
					public Set<UpdatableReachingDefinition> computeTargets(UpdatableReachingDefinition source) {
						if(source.getContents().getO1().equivTo(definitionStmt.getContents().getLeftOp())) {
							return Collections.emptySet();
						} else {
							return Collections.singleton(source);
						}
					}
				};
			}
		};
	}

	@Override
	public Set<UpdatableWrapper<Unit>> initialSeeds() {
		return Collections.singleton(this.interproceduralCFG().wrapWeak
				(Scene.v().getMainMethod().getActiveBody().getUnits().getFirst()));
	}

	public UpdatableReachingDefinition createZeroValue() {
		return zeroValue;
	}

}
