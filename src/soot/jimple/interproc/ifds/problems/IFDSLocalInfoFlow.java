package soot.jimple.interproc.ifds.problems;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import soot.Local;
import soot.NullType;
import soot.Scene;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.AssignStmt;
import soot.jimple.DefinitionStmt;
import soot.jimple.IdentityStmt;
import soot.jimple.InvokeExpr;
import soot.jimple.ParameterRef;
import soot.jimple.ReturnStmt;
import soot.jimple.Stmt;
import soot.jimple.internal.JimpleLocal;
import soot.jimple.interproc.ifds.FlowFunction;
import soot.jimple.interproc.ifds.FlowFunctions;
import soot.jimple.interproc.ifds.InterproceduralCFG;
import soot.jimple.interproc.ifds.flowfunc.Gen;
import soot.jimple.interproc.ifds.flowfunc.Identity;
import soot.jimple.interproc.ifds.flowfunc.Kill;
import soot.jimple.interproc.ifds.flowfunc.KillAll;
import soot.jimple.interproc.ifds.flowfunc.Transfer;
import soot.jimple.interproc.ifds.template.DefaultIFDSTabulationProblem;
import soot.jimple.interproc.incremental.DefaultUpdatableWrapper;
import soot.jimple.interproc.incremental.UpdatableWrapper;

public class IFDSLocalInfoFlow extends
		DefaultIFDSTabulationProblem<UpdatableWrapper<Local>,
		InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>>> {

	private static final UpdatableWrapper<Local> zeroValue = new DefaultUpdatableWrapper<Local>
		(new JimpleLocal("zero", NullType.v()));
	
	public IFDSLocalInfoFlow(InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>> icfg) {
		super(icfg);
	}

	public FlowFunctions<UpdatableWrapper<Unit>, UpdatableWrapper<Local>, UpdatableWrapper<SootMethod>> createFlowFunctionsFactory() {		
		return new FlowFunctions<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>>() {

			@Override
			public FlowFunction<UpdatableWrapper<Local>> getNormalFlowFunction
					(UpdatableWrapper<Unit> src, UpdatableWrapper<Unit> dest) {
				if (src.getContents() instanceof IdentityStmt
						&& interproceduralCFG().getMethodOf(src)==interproceduralCFG().wrapWeak(Scene.v().getMainMethod())) {
					IdentityStmt is = (IdentityStmt) src.getContents();
					Local leftLocal = (Local) is.getLeftOp();
					Value right = is.getRightOp();
					if (right instanceof ParameterRef) {
						return new Gen<UpdatableWrapper<Local>>
							(interproceduralCFG().wrapWeak(leftLocal), zeroValue());
					}
				}
				
				if(src.getContents() instanceof AssignStmt) {
					AssignStmt assignStmt = (AssignStmt) src.getContents();
					Value right = assignStmt.getRightOp();
					if(assignStmt.getLeftOp() instanceof Local) {
						final Local leftLocal = (Local) assignStmt.getLeftOp();
						if(right instanceof Local) {
							final Local rightLocal = (Local) right;
							return new Transfer<UpdatableWrapper<Local>>
								(interproceduralCFG().wrapWeak(leftLocal), interproceduralCFG().wrapWeak(rightLocal));
						} else {
							return new Kill<UpdatableWrapper<Local>>(interproceduralCFG().wrapWeak(leftLocal));
						}
					}
				}
				return Identity.v();
			}

			@Override
			public FlowFunction<UpdatableWrapper<Local>> getCallFlowFunction
					(UpdatableWrapper<Unit> src, final UpdatableWrapper<SootMethod> dest) {
				Stmt stmt = (Stmt) src.getContents();
				InvokeExpr ie = stmt.getInvokeExpr();
				final List<UpdatableWrapper<Value>> callArgs = interproceduralCFG().wrapWeak(ie.getArgs());
				final List<UpdatableWrapper<Local>> paramLocals = new ArrayList<UpdatableWrapper<Local>>();
				for (int i = 0; i < dest.getContents().getParameterCount(); i++)
					paramLocals.add(interproceduralCFG().wrapWeak(dest.getContents().getActiveBody().getParameterLocal(i)));
				
				return new FlowFunction<UpdatableWrapper<Local>>() {

					public Set<UpdatableWrapper<Local>> computeTargets(UpdatableWrapper<Local> source) {
						int argIndex = callArgs.indexOf(interproceduralCFG().wrapWeak(source.getContents()));
						if(argIndex>-1) {
							return Collections.singleton(paramLocals.get(argIndex));
						}
						return Collections.emptySet();
					}
				};
			}

			@Override
			public FlowFunction<UpdatableWrapper<Local>> getReturnFlowFunction
					(UpdatableWrapper<Unit> callSite, UpdatableWrapper<SootMethod> callee,
					UpdatableWrapper<Unit> exitStmt, UpdatableWrapper<Unit> retSite) {
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
								return new FlowFunction<UpdatableWrapper<Local>>() {

									public Set<UpdatableWrapper<Local>> computeTargets(UpdatableWrapper<Local> source) {
										if(source == retLocal)
											return Collections.singleton(tgtLocal);
										return Collections.emptySet();
									}
									
								};
							}
						}
					}
				} 
				return KillAll.v();
			}

			@Override
			public FlowFunction<UpdatableWrapper<Local>> getCallToReturnFlowFunction
					(UpdatableWrapper<Unit> call, UpdatableWrapper<Unit> returnSite) {
				return Identity.v();
			}
		};						
	}

	@Override
	public UpdatableWrapper<Local> createZeroValue() {
		return zeroValue;
	}

	@Override
	public Set<UpdatableWrapper<Unit>> initialSeeds() {
		return Collections.singleton(interproceduralCFG().wrapWeak
				(Scene.v().getMainMethod().getActiveBody().getUnits().getFirst()));
	}
}