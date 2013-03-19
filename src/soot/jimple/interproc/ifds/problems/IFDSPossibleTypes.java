package soot.jimple.interproc.ifds.problems;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import soot.Local;
import soot.PointsToAnalysis;
import soot.PointsToSet;
import soot.PrimType;
import soot.Scene;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.UnknownType;
import soot.Value;
import soot.jimple.ArrayRef;
import soot.jimple.Constant;
import soot.jimple.DefinitionStmt;
import soot.jimple.InstanceFieldRef;
import soot.jimple.InvokeExpr;
import soot.jimple.Jimple;
import soot.jimple.NewExpr;
import soot.jimple.Ref;
import soot.jimple.ReturnStmt;
import soot.jimple.Stmt;
import soot.jimple.interproc.ifds.FlowFunction;
import soot.jimple.interproc.ifds.FlowFunctions;
import soot.jimple.interproc.ifds.InterproceduralCFG;
import soot.jimple.interproc.ifds.flowfunc.Identity;
import soot.jimple.interproc.ifds.flowfunc.KillAll;
import soot.jimple.interproc.ifds.template.DefaultIFDSTabulationProblem;
import soot.jimple.interproc.incremental.UpdatableWrapper;
import soot.toolkits.scalar.Pair;

@SuppressWarnings("serial")
public class IFDSPossibleTypes extends DefaultIFDSTabulationProblem<UpdatablePossibleType,
		InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>>> {

	private Map<Pair<UpdatableWrapper<Value>, UpdatableWrapper<Type>>, UpdatablePossibleType> wrapperObjects =
			new HashMap<Pair<UpdatableWrapper<Value>, UpdatableWrapper<Type>>, UpdatablePossibleType>();

	public IFDSPossibleTypes(InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>> icfg) {
		super(icfg);
	}

	private UpdatablePossibleType createPossibleType(Value value, Type type) {
		UpdatableWrapper<Value> wrappedValue = this.interproceduralCFG().wrapWeak(value);
		UpdatableWrapper<Type> wrappedType = this.interproceduralCFG().wrapWeak(type);
		Pair<UpdatableWrapper<Value>, UpdatableWrapper<Type>> pair =
				new Pair<UpdatableWrapper<Value>, UpdatableWrapper<Type>>(wrappedValue, wrappedType);

		UpdatablePossibleType upt = this.wrapperObjects.get(pair);
		if (upt == null)
			synchronized (this.wrapperObjects) {
				// Fetch again while we have the lock. This shall ensure that no
				// other object has been created in the meantime.
				upt = this.wrapperObjects.get(pair);
				if (upt == null) {
					upt = new UpdatablePossibleType(value, type);
					this.wrapperObjects.put(pair, upt);
					this.interproceduralCFG().registerListener(upt, value);
					this.interproceduralCFG().registerListener(upt, type);
				}
			}
		return upt;
	}
	
	public FlowFunctions<UpdatableWrapper<Unit>, UpdatablePossibleType, UpdatableWrapper<SootMethod>> createFlowFunctionsFactory() {
		return new FlowFunctions<UpdatableWrapper<Unit>,UpdatablePossibleType,UpdatableWrapper<SootMethod>>() {

			public FlowFunction<UpdatablePossibleType> getNormalFlowFunction(UpdatableWrapper<Unit> src, UpdatableWrapper<Unit> dest) {
				if(src.getContents() instanceof DefinitionStmt) {
					final DefinitionStmt defnStmt = (DefinitionStmt) src.getContents();
					if(defnStmt.containsInvokeExpr()) return Identity.v();
					
					final Value right = defnStmt.getRightOp();
					final Value left = defnStmt.getLeftOp();
					//won't track primitive-typed variables
					if(right.getType() instanceof PrimType) return Identity.v();
					
					if(right instanceof Constant || right instanceof NewExpr) {
						return new FlowFunction<UpdatablePossibleType>() {
							public Set<UpdatablePossibleType> computeTargets(UpdatablePossibleType source) {
								if(source.getContents().equals(new Pair<Value, Type>(Jimple.v().newLocal("<dummy>", UnknownType.v()), UnknownType.v()))) {
									Set<UpdatablePossibleType> res = new LinkedHashSet<UpdatablePossibleType>();
									res.add(createPossibleType(left,right.getType()));
									res.add(createPossibleType(Jimple.v().newLocal("<dummy>", UnknownType.v()), UnknownType.v()));
									return res;
								} else if(source.getValue() instanceof Local && source.getValue().equivTo(left)) {
									//strong update for local variables
									return Collections.emptySet();
								} else {
									return Collections.singleton(source);
								}
							}
						};
					} else if(right instanceof Ref || right instanceof Local) {
						return new FlowFunction<UpdatablePossibleType>() {
							public Set<UpdatablePossibleType> computeTargets(final UpdatablePossibleType source) {
								Value value = source.getValue();
								if(value instanceof Local && value.equivTo(left)) {
									//strong update for local variables
									return Collections.emptySet();
								} else if(maybeSameLocation(value,right)) {
									return new LinkedHashSet<UpdatablePossibleType>() {{
										add(createPossibleType(left,source.getType())); 
										add(source);
									}};
								} else {
									return Collections.singleton(source);
								}
							}

							private boolean maybeSameLocation(Value v1, Value v2) {
								if(!(v1 instanceof InstanceFieldRef && v2 instanceof InstanceFieldRef) &&
								   !(v1 instanceof ArrayRef && v2 instanceof ArrayRef)) {
									return v1.equivTo(v2);
								}
								if(v1 instanceof InstanceFieldRef && v2 instanceof InstanceFieldRef) {
									InstanceFieldRef ifr1 = (InstanceFieldRef) v1;
									InstanceFieldRef ifr2 = (InstanceFieldRef) v2;
									if(!ifr1.getField().getName().equals(ifr2.getField().getName())) return false;
									
									Local base1 = (Local) ifr1.getBase();
									Local base2 = (Local) ifr2.getBase();
									PointsToAnalysis pta = Scene.v().getPointsToAnalysis();
									PointsToSet pts1 = pta.reachingObjects(base1);
									PointsToSet pts2 = pta.reachingObjects(base2);								
									return pts1.hasNonEmptyIntersection(pts2);
								} else { //v1 instanceof ArrayRef && v2 instanceof ArrayRef
									ArrayRef ar1 = (ArrayRef) v1;
									ArrayRef ar2 = (ArrayRef) v2;

									Local base1 = (Local) ar1.getBase();
									Local base2 = (Local) ar2.getBase();
									PointsToAnalysis pta = Scene.v().getPointsToAnalysis();
									PointsToSet pts1 = pta.reachingObjects(base1);
									PointsToSet pts2 = pta.reachingObjects(base2);								
									return pts1.hasNonEmptyIntersection(pts2);
								}
							}
						};
					} 
				}
				return Identity.v();
			}

			public FlowFunction<UpdatablePossibleType> getCallFlowFunction
					(final UpdatableWrapper<Unit> src, final UpdatableWrapper<SootMethod> dest) {
				Stmt stmt = (Stmt) src.getContents();
				InvokeExpr ie = stmt.getInvokeExpr();
				final List<UpdatableWrapper<Value>> callArgs = interproceduralCFG().wrapWeak(ie.getArgs());
				final List<UpdatableWrapper<Local>> paramLocals = new ArrayList<UpdatableWrapper<Local>>();
				for(int i=0;i<dest.getContents().getParameterCount();i++)
					paramLocals.add(interproceduralCFG().wrapWeak(dest.getContents().getActiveBody().getParameterLocal(i)));

				return new FlowFunction<UpdatablePossibleType>() {
					public Set<UpdatablePossibleType> computeTargets(UpdatablePossibleType source) {
						Value value = source.getValue();
						int argIndex = callArgs.indexOf(interproceduralCFG().wrapWeak(value));
						if(argIndex>-1) {
							return Collections.singleton(createPossibleType(paramLocals.get(argIndex).getContents(), source.getType()));
						}
						return Collections.emptySet();
					}
				};
			}

			public FlowFunction<UpdatablePossibleType> getReturnFlowFunction
					(UpdatableWrapper<Unit> callSite,
					UpdatableWrapper<SootMethod> callee,
					UpdatableWrapper<Unit> exitStmt,
					UpdatableWrapper<Unit> retSite) {
				if (exitStmt.getContents() instanceof ReturnStmt) {								
					ReturnStmt returnStmt = (ReturnStmt) exitStmt.getContents();
					Value op = returnStmt.getOp();
					if(op instanceof Local) {
						if(callSite.getContents() instanceof DefinitionStmt) {
							DefinitionStmt defnStmt = (DefinitionStmt) callSite.getContents();
							Value leftOp = defnStmt.getLeftOp();
							if(leftOp instanceof Local) {
								final UpdatableWrapper<Local> tgtLocal = interproceduralCFG().wrapWeak((Local) leftOp);
								final UpdatableWrapper<Local> retLocal = interproceduralCFG().wrapWeak((Local) op);
								return new FlowFunction<UpdatablePossibleType>() {

									public Set<UpdatablePossibleType> computeTargets(UpdatablePossibleType source) {
										if(source.getContents() == retLocal.getContents())
											return Collections.singleton(createPossibleType(tgtLocal.getContents(), source.getType()));
										return Collections.emptySet();
									}
									
								};
							}
						}
					}
				}
				return KillAll.v();
			}

			public FlowFunction<UpdatablePossibleType> getCallToReturnFlowFunction
					(UpdatableWrapper<Unit> call, UpdatableWrapper<Unit> returnSite) {
				return Identity.v();
			}
		};
	}

	public Set<UpdatableWrapper<Unit>> initialSeeds() {
		return Collections.singleton(interproceduralCFG().wrapWeak(Scene.v().getMainMethod().getActiveBody().getUnits().getFirst()));
	}

	public UpdatablePossibleType createZeroValue() {
		return createPossibleType(Jimple.v().newLocal("<dummy>", UnknownType.v()), UnknownType.v());
	}
}