package soot.jimple.interproc.ifds.problems;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import soot.Local;
import soot.NullType;
import soot.Scene;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.ValueBox;
import soot.jimple.DefinitionStmt;
import soot.jimple.InvokeExpr;
import soot.jimple.ReturnStmt;
import soot.jimple.Stmt;
import soot.jimple.ThrowStmt;
import soot.jimple.internal.JimpleLocal;
import soot.jimple.interproc.ifds.FlowFunction;
import soot.jimple.interproc.ifds.FlowFunctions;
import soot.jimple.interproc.ifds.InterproceduralCFG;
import soot.jimple.interproc.ifds.flowfunc.Identity;
import soot.jimple.interproc.ifds.flowfunc.Kill;
import soot.jimple.interproc.ifds.flowfunc.KillAll;
import soot.jimple.interproc.ifds.template.DefaultIFDSTabulationProblem;
import soot.jimple.interproc.incremental.UpdatableWrapper;

public class IFDSUninitializedVariables extends DefaultIFDSTabulationProblem<UpdatableWrapper<Local>,
		InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>>> {

	public IFDSUninitializedVariables(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg) {
		super(icfg);		
	}

	@Override
	public FlowFunctions<UpdatableWrapper<Unit>, UpdatableWrapper<Local>, UpdatableWrapper<SootMethod>> 
			createFlowFunctionsFactory() {
		return new FlowFunctions<UpdatableWrapper<Unit>, UpdatableWrapper<Local>, UpdatableWrapper<SootMethod>>() {

			@Override
			public FlowFunction<UpdatableWrapper<Local>> getNormalFlowFunction(UpdatableWrapper<Unit> curr, UpdatableWrapper<Unit> succ) {
				final UpdatableWrapper<SootMethod> m = interproceduralCFG().getMethodOf(curr);
				
				if(Scene.v().getEntryPoints().contains(m) && interproceduralCFG().isStartPoint(curr)) {
					return new FlowFunction<UpdatableWrapper<Local>>() {
						
						@Override
						public Set<UpdatableWrapper<Local>> computeTargets(UpdatableWrapper<Local> source) {
							if (source == zeroValue()) {
								Set<UpdatableWrapper<Local>> res = new LinkedHashSet<UpdatableWrapper<Local>>();
								for (Local l : m.getContents().getActiveBody().getLocals())
									res.add(interproceduralCFG().wrapWeak(l));
								for(int i=0;i<m.getContents().getParameterCount();i++) 
									res.remove(interproceduralCFG().wrapWeak(m.getContents().getActiveBody().getParameterLocal(i)));
								return res;
							}
							return Collections.emptySet();
						}
					};
				}
				
				if (curr.getContents() instanceof DefinitionStmt) {
					final DefinitionStmt definition = (DefinitionStmt) curr.getContents();
					final Value leftOp = definition.getLeftOp();
					if(leftOp instanceof Local) {
						final UpdatableWrapper<Local> leftOpLocal = interproceduralCFG().wrapWeak((Local) leftOp);
						return new FlowFunction<UpdatableWrapper<Local>>() {

							@Override
							public Set<UpdatableWrapper<Local>> computeTargets(final UpdatableWrapper<Local> source) {
								List<ValueBox> useBoxes = definition.getUseBoxes();
								for (ValueBox valueBox : useBoxes) {
									if (valueBox.getValue().equivTo(source.getContents())) {
										LinkedHashSet<UpdatableWrapper<Local>> res = new LinkedHashSet<UpdatableWrapper<Local>>();
										res.add(source);
										res.add(leftOpLocal); 
										return res;
									}
								}

								if (leftOp.equivTo(source))
									return Collections.emptySet();

								return Collections.singleton(source);
							}

						};
					}
				}

				return Identity.v();
			}

			@Override
			public FlowFunction<UpdatableWrapper<Local>> getCallFlowFunction
					(UpdatableWrapper<Unit> callStmt, final UpdatableWrapper<SootMethod> destinationMethod) {
				Stmt stmt = (Stmt) callStmt.getContents();
				InvokeExpr invokeExpr = stmt.getInvokeExpr();
				final List<Value> args = invokeExpr.getArgs();

				final List<UpdatableWrapper<Local>> localArguments = new ArrayList<UpdatableWrapper<Local>>();
				for (Value value : args)
					if (value instanceof Local)
						localArguments.add(interproceduralCFG().wrapWeak((Local) value));

				return new FlowFunction<UpdatableWrapper<Local>>() {

					@Override
					public Set<UpdatableWrapper<Local>> computeTargets(final UpdatableWrapper<Local> source) {
						for (UpdatableWrapper<Local> localArgument : localArguments) {
							if (source.getContents().equivTo(localArgument.getContents())) {
								return Collections.<UpdatableWrapper<Local>>singleton(interproceduralCFG().wrapWeak
									(destinationMethod.getContents().getActiveBody().getParameterLocal(args.indexOf(localArgument))));
							}
						}

						if (source == zeroValue()) {
							//gen all locals that are not parameter locals 
							LinkedHashSet<UpdatableWrapper<Local>> uninitializedLocals = new LinkedHashSet<UpdatableWrapper<Local>>();
							for (Local l : destinationMethod.getContents().getActiveBody().getLocals())
								uninitializedLocals.add(interproceduralCFG().wrapWeak(l));
							for(int i=0;i<destinationMethod.getContents().getParameterCount();i++) {
								uninitializedLocals.remove(interproceduralCFG().wrapWeak
										(destinationMethod.getContents().getActiveBody().getParameterLocal(i)));
							}
							return uninitializedLocals;
						}

						return Collections.emptySet();
					}

				};
			}

			@Override
			public FlowFunction<UpdatableWrapper<Local>> getReturnFlowFunction
					(final UpdatableWrapper<Unit> callSite,
					UpdatableWrapper<SootMethod> calleeMethod,
					final UpdatableWrapper<Unit> exitStmt,
					UpdatableWrapper<Unit> returnSite) {
				if (callSite.getContents() instanceof DefinitionStmt) {
					final DefinitionStmt definition = (DefinitionStmt) callSite.getContents();
					if(definition.getLeftOp() instanceof Local) {
						final UpdatableWrapper<Local> leftOpLocal = interproceduralCFG().wrapWeak((Local) definition.getLeftOp());
						if (exitStmt.getContents() instanceof ReturnStmt) {
							final UpdatableWrapper<ReturnStmt> returnStmt =
									interproceduralCFG().wrapWeak((ReturnStmt) exitStmt.getContents());
							return new FlowFunction<UpdatableWrapper<Local>>() {
		
								@Override
								public Set<UpdatableWrapper<Local>> computeTargets(UpdatableWrapper<Local> source) {
									if (returnStmt.getContents().getOp().equivTo(source.getContents()))
										return Collections.singleton(leftOpLocal);
									return Collections.emptySet();
								}
		
							};
						} else if (exitStmt.getContents() instanceof ThrowStmt) {
							//if we throw an exception, LHS of call is undefined
							return new FlowFunction<UpdatableWrapper<Local>>() {
		
								@Override
								public Set<UpdatableWrapper<Local>> computeTargets(final UpdatableWrapper<Local> source) {
									if (source == zeroValue())
										return Collections.singleton(leftOpLocal);
									else
										return Collections.emptySet();
								}
								
							};
						}
					}
				}
				
				return KillAll.v();
			}

			@Override
			public FlowFunction<UpdatableWrapper<Local>> getCallToReturnFlowFunction
					(UpdatableWrapper<Unit> callSite, UpdatableWrapper<Unit> returnSite) {
				if (callSite.getContents() instanceof DefinitionStmt) {
					DefinitionStmt definition = (DefinitionStmt) callSite.getContents();
					if(definition.getLeftOp() instanceof Local) {
						final Local leftOpLocal = (Local) definition.getLeftOp();
						return new Kill<UpdatableWrapper<Local>>(interproceduralCFG().wrapWeak(leftOpLocal));
					}
				}
				return Identity.v();
			}
		};
	}
	@Override
	public Set<UpdatableWrapper<Unit>> initialSeeds() {
		return Collections.singleton(interproceduralCFG().wrapWeak(Scene.v().getMainMethod().getActiveBody().getUnits().getFirst()));
	}

	@Override
	public UpdatableWrapper<Local> createZeroValue() {
		return interproceduralCFG().wrapWeak((Local) new JimpleLocal("<<zero>>", NullType.v()));
	}

}
