package soot.jimple.interproc.ifds;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import soot.Local;
import soot.MethodOrMethodContext;
import soot.PackManager;
import soot.Scene;
import soot.SceneTransformer;
import soot.SootMethod;
import soot.Transform;
import soot.Unit;
import soot.jimple.AssignStmt;
import soot.jimple.Jimple;
import soot.jimple.StaticInvokeExpr;
import soot.jimple.interproc.ifds.problems.IFDSLocalInfoFlow;
import soot.jimple.interproc.ifds.solver.IFDSSolver;
import soot.jimple.interproc.ifds.template.JimpleBasedInterproceduralCFG;
import soot.jimple.interproc.incremental.UpdatableWrapper;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.jimple.toolkits.callgraph.ReachableMethods;

public class Main {

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		
		PackManager.v().getPack("wjtp").add(new Transform("wjtp.ifds", new SceneTransformer() {
			protected void internalTransform(String phaseName, @SuppressWarnings("rawtypes") Map options) {
				System.out.println("Running IFDS on initial CFG...");
				IFDSTabulationProblem<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
					InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> problem =
						new IFDSLocalInfoFlow(new JimpleBasedInterproceduralCFG());
				
				IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
					InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver =
						new IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
							InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>>(problem);	
				solver.solve(false);
				
				for (Unit u : Scene.v().getMainMethod().getActiveBody().getUnits())
					System.out.println(u);

				Unit ret = Scene.v().getMainMethod().getActiveBody().getUnits().getLast();
				for (UpdatableWrapper<Local> l: solver.ifdsResultsAt(problem.interproceduralCFG().wrapWeak(ret)))
					System.err.println(l);
				System.out.println("Done.");

				// Patch the control-flow graph. We insert a new call from the main() method
				// to our artificial helper method "otherMethod".
				System.out.println("Patching cfg...");
				SootMethod helperMethod = Scene.v().getMainClass().getMethodByName("otherMethod");
				helperMethod.retrieveActiveBody();

				Local localTestMe = null;
				Local localFoo = null;
				for (Local l : Scene.v().getMainMethod().getActiveBody().getLocals()) {
					if (l.getName().equals("r0"))
						localTestMe = l;
					else if (l.getName().equals("r1"))
						localFoo = l;
				}
				StaticInvokeExpr invokeExpr = Jimple.v().newStaticInvokeExpr(helperMethod.makeRef(), localTestMe);
				assert localTestMe != null;
				assert localFoo != null;
								
				AssignStmt invokeStmt = Jimple.v().newAssignStmt(localFoo, invokeExpr); 
				for (Unit u : Scene.v().getMainMethod().getActiveBody().getUnits())
					if (u.toString().contains("doFoo")) {
						Scene.v().getMainMethod().getActiveBody().getUnits().insertBefore (invokeStmt, u);
						break;
					}

				CallGraph cg = Scene.v().getCallGraph();
				Edge edge = new Edge(Scene.v().getMainMethod(), invokeStmt, helperMethod);
				cg.addEdge(edge);
								
				// Check that our new method is indeed reachable
				List<MethodOrMethodContext> eps = new ArrayList<MethodOrMethodContext>();
				eps.addAll(Scene.v().getEntryPoints());
				ReachableMethods reachableMethods = new ReachableMethods(cg, eps.iterator());
				reachableMethods.update();
				boolean found = false;
				for(Iterator<MethodOrMethodContext> iter = reachableMethods.listener(); iter.hasNext(); ) {
					SootMethod m = iter.next().method();
					if (m.getName().equals("otherMethod"))
						found = true;
				}
				if (found)
					System.out.println("Patched method found");
				else
					System.err.println("Patched method NOT found");

				// Patch the control-flow graph. We add an assignment to the
				// "foo" variable inside the loop
				/*
				System.out.println("Patching cfg...");
				Local fooLocal = null;
				Local argsLocal = null;
				for (Local l : Scene.v().getMainMethod().getActiveBody().getLocals())
					if (l.getName().equals("r1"))
						fooLocal = l;
					else if (l.getName().equals("r0"))
						argsLocal = l;
				AssignStmt assignStmt = Jimple.v().newAssignStmt(fooLocal, argsLocal);
				JAssignStmt point = null;
				for (Unit unit : Scene.v().getMainMethod().getActiveBody().getUnits()) {
					if (unit instanceof JAssignStmt) {
						JAssignStmt stmt = (JAssignStmt) unit;
						if (stmt.getLeftOp().toString().equals("i0"))
							if (stmt.getRightOp().toString().equals("i0 + -1")) {
								point = stmt;
								break;
							}
					}
				}
				if (point == null) {
					System.err.println("Injection point not found");
					return;
				}
				Scene.v().getMainMethod().getActiveBody().getUnits().insertBefore (assignStmt, point);
				*/

				System.out.println("Running IFDS on patched CFG...");

				JimpleBasedInterproceduralCFG cfg = new JimpleBasedInterproceduralCFG();
				solver.update(cfg);
				
				ret = Scene.v().getMainMethod().getActiveBody().getUnits().getLast();
				for (UpdatableWrapper<Local> l: solver.ifdsResultsAt(problem.interproceduralCFG().wrapWeak(ret))) {
					System.err.println(l);
				}
				System.out.println("Done.");
			}
		}));

		soot.Main.main(args);
	}

}
