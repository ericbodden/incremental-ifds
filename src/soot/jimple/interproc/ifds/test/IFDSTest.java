package soot.jimple.interproc.ifds.test;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import junit.framework.Assert;

import org.junit.Test;

import soot.ArrayType;
import soot.Body;
import soot.Local;
import soot.MethodOrMethodContext;
import soot.PackManager;
import soot.RefType;
import soot.Scene;
import soot.SceneTransformer;
import soot.SootClass;
import soot.SootFieldRef;
import soot.SootMethod;
import soot.SootMethodRef;
import soot.Transform;
import soot.Unit;
import soot.jimple.AssignStmt;
import soot.jimple.IntConstant;
import soot.jimple.InvokeExpr;
import soot.jimple.InvokeStmt;
import soot.jimple.Jimple;
import soot.jimple.NewExpr;
import soot.jimple.StaticInvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.VirtualInvokeExpr;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.internal.JInvokeStmt;
import soot.jimple.interproc.ifds.IFDSTabulationProblem;
import soot.jimple.interproc.ifds.InterproceduralCFG;
import soot.jimple.interproc.ifds.problems.IFDSLocalInfoFlow;
import soot.jimple.interproc.ifds.solver.IFDSSolver;
import soot.jimple.interproc.ifds.template.JimpleBasedInterproceduralCFG;
import soot.jimple.interproc.incremental.UpdatableWrapper;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.jimple.toolkits.callgraph.ReachableMethods;

/**
 * Test class for the analysis IFDS. The test cases depend on the "SimpleTest"
 * class in the "test" directory.
 */
public class IFDSTest {
	
	/**
	 * Performs a generic test and calls the extension handler when it is complete.
	 * This method uses dynamic updates on the solver to avoid recomputations.
	 * @param handler The handler to call after finishing the generic information
	 * leakage analysis
	 * @param className The name of the test class to use
	 */
	private void performTestUpdate(final ITestHandler<UpdatableWrapper<Local>> handler, final String className) {
		soot.G.reset();

		PackManager.v().getPack("wjtp").add(new Transform("wjtp.ifds", new SceneTransformer() {
			protected void internalTransform(String phaseName, @SuppressWarnings("rawtypes") Map options) {
				// Force method load to avoid spurious changes
				for (SootClass sc : Scene.v().getApplicationClasses())
					for (SootMethod sm : sc.getMethods())
						sm.retrieveActiveBody();

				long timeBefore = System.nanoTime();
				System.out.println("Running IFDS on initial CFG...");
				InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg = new JimpleBasedInterproceduralCFG();
				IFDSTabulationProblem<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
					InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> problem =
						new IFDSLocalInfoFlow(icfg);

				IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
					InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver =
						new IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
							InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>>(problem);	
				solver.solve();
				
				Unit ret = Scene.v().getMainMethod().getActiveBody().getUnits().getLast();
				Set<UpdatableWrapper<Local>> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				checkInitialLeaks(className, results);
				
				if (handler != null) {
					handler.extendBasicTest(icfg, solver);
					for (int i = 0; i < handler.getPhaseCount(); i++) {
						handler.patchGraph(i);
						solver.update(icfg = new JimpleBasedInterproceduralCFG());
						handler.performExtendedTest(icfg, solver, i);
					}
				}
				System.out.println("Time elapsed: " + ((double) (System.nanoTime() - timeBefore)) / 1E9);
			}
		}));
		
		String udir = System.getProperty("user.dir");
		soot.Main.v().run(new String[] {
				"-W",
				"-main-class", className,
				"-process-path", udir + File.separator + "test",
				"-src-prec", "java",
				"-pp",
				"-cp", "junit-4.10.jar",
				"-no-bodies-for-excluded",
				"-exclude", "java.",
				className } );

	}
	
	/**
	 * Performs a generic test and calls the extension handler when it is complete.
	 * This method does not create indices for dynamic updates. Instead, updates are
	 * just propagated along the edges until a fix point is reached.
	 * @param handler The handler to call after finishing the generic information
	 * leakage analysis
	 * @param className The name of the test class to use
	 */
	private void performTestDirect(final ITestHandler<UpdatableWrapper<Local>> handler, final String className) {
		soot.G.reset();

		PackManager.v().getPack("wjtp").add(new Transform("wjtp.ifds", new SceneTransformer() {
			protected void internalTransform(String phaseName, @SuppressWarnings("rawtypes") Map options) {
				// Force method load to avoid spurious changes
				for (SootClass sc : Scene.v().getApplicationClasses())
					for (SootMethod sm : sc.getMethods())
						sm.retrieveActiveBody();

				long timeBefore = System.nanoTime();
				System.out.println("Running IFDS on initial CFG...");
				InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg = new JimpleBasedInterproceduralCFG();
				IFDSTabulationProblem<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
					InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> problem =
						new IFDSLocalInfoFlow(icfg);
				
				IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
					InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver =
						new IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
							InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>>(problem);	
				solver.solve(false);
				
				Unit ret = Scene.v().getMainMethod().getActiveBody().getUnits().getLast();
				Set<UpdatableWrapper<Local>> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				checkInitialLeaks(className, results);
				
				if (handler != null) {
					handler.extendBasicTest(icfg, solver);
					for (int i = 0; i < handler.getPhaseCount(); i++) {
						handler.patchGraph(i);
						solver.update(icfg = new JimpleBasedInterproceduralCFG());
						handler.performExtendedTest(icfg, solver, i);
					}
				}
				System.out.println("Time elapsed: " + ((double) (System.nanoTime() - timeBefore)) / 1E9);
			}
		}));

		String udir = System.getProperty("user.dir");
		soot.Main.v().run(new String[] {
				"-W",
				"-main-class", className,
				"-process-path", udir + File.separator + "test",
				"-src-prec", "java",
				"-pp",
				"-cp", "junit-4.10.jar",
				"-no-bodies-for-excluded",
				"-exclude", "java.",
				className } );
	}

	/**
	 * Performs a generic test and calls the extension handler when it is complete.
	 * This method reruns the complete analysis with the updated data.
	 * @param handler The handler to call after finishing the generic information
	 * leakage analysis
	 * @param className The name of the test class to use
	 */
	private void performTestRerun(final ITestHandler<UpdatableWrapper<Local>> handler, final String className) {
		soot.G.reset();

		PackManager.v().getPack("wjtp").add(new Transform("wjtp.ifds", new SceneTransformer() {
			protected void internalTransform(String phaseName, @SuppressWarnings("rawtypes") Map options) {
				long timeBefore = System.nanoTime();
				System.out.println("Running IFDS on initial CFG...");
				InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg = new JimpleBasedInterproceduralCFG();
				IFDSTabulationProblem<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
					InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> problem =
						new IFDSLocalInfoFlow(icfg);
				
				IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
					InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver =
						new IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
							InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>>(problem);	
				solver.solve(false);
				
				Unit ret = Scene.v().getMainMethod().getActiveBody().getUnits().getLast();
				Set<UpdatableWrapper<Local>> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				checkInitialLeaks(className, results);
				
				if (handler != null) {
					handler.extendBasicTest(icfg, solver);
					for (int i = 0; i < handler.getPhaseCount(); i++) {
						handler.patchGraph(i);
						IFDSTabulationProblem<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
							InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> problem2 =
								new IFDSLocalInfoFlow(icfg = new JimpleBasedInterproceduralCFG());
						
						IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
							InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver2 =
								new IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
									InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>>(problem2);	
						solver2.solve(false);
		
						if (handler != null)
							handler.performExtendedTest(icfg, solver2, i);
					}
				}
				System.out.println("Time elapsed: " + ((double) (System.nanoTime() - timeBefore)) / 1E9);
			}
		}));

		String udir = System.getProperty("user.dir");
		soot.Main.v().run(new String[] {
				"-W",
				"-main-class", className,
				"-process-path", udir + File.separator + "test",
				"-src-prec", "java",
				"-pp",
				"-cp", "junit-4.10.jar",
				"-no-bodies-for-excluded",
				"-exclude", "java.",
				className } );
	}

	private void checkInitialLeaks
			(final String className,
			Set<UpdatableWrapper<Local>> results) {
		List<String> leaks = new ArrayList<String>();
		for (UpdatableWrapper<Local> l : results) {
			String name = l.getContents().getName();
			if (!name.contains("temp$"))
				leaks.add(name);
		}

		if (className.equals("SimpleTest") || className.equals("DynamicTest")
				|| className.equals("org.junit.runner.JUnitCore")) {
			Assert.assertEquals("Invalid number of information leaks found", 1, leaks.size());
			String leakName = results.iterator().next().getContents().getName();
			if (!leakName.equals("r0") && !leakName.equals("args"))
				Assert.fail("Invalid information leak found");
		} else if (className.equals("TestRemoveLeak") || className.equals("TestRemoveLeakInFunction")
				|| className.equals("TestRemoveLeakingCall")) {
			Assert.assertEquals("Invalid number of information leaks found", 2, leaks.size());
			Assert.assertTrue("Invalid information leak found", leaks.contains("args"));
			Assert.assertTrue("Invalid information leak found", leaks.contains("var"));
		} else if (className.equals("TestRemoveNoLeakCall")) {
			Assert.assertEquals("Invalid number of information leaks found", 1, leaks.size());
			Assert.assertTrue("Invalid information leak found", leaks.contains("args"));
		}
		else
			Assert.fail("Invalid test class");
	}

	private ITestHandler<UpdatableWrapper<Local>> ITestHandlerSimpleTest() {
		return new ITestHandler<UpdatableWrapper<Local>>() {
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
			}
			
			@Override
			public void patchGraph(int phase) {
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				Unit ret = Scene.v().getMainMethod().getActiveBody().getUnits().getLast();
				Set<UpdatableWrapper<Local>> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				checkInitialLeaks("SimpleTest", results);
			}

			@Override
			public int getPhaseCount() {
				return 1;
			}

			@Override
			public void initApplicationClasses() {
				
			}
		};
	}

	/**
	 * Performs a simple information leakage analysis without any updates to the program graph
	 */
	@Test
	public void simpleTest_Update() {
		System.out.println("Starting simpleTest_Update...");
		performTestUpdate(ITestHandlerSimpleTest(), "SimpleTest");
		System.out.println("simpleTest_Update finished.");
	}

	/**
	 * Performs a simple information leakage analysis without any updates to the program graph
	 */
	@Test
	public void simpleTest_Propagate() {
		System.out.println("Starting simpleTest_Propagate...");
		performTestDirect(ITestHandlerSimpleTest(), "SimpleTest");
		System.out.println("simpleTest_Propagate finished.");
	}

	/**
	 * Performs a simple information leakage analysis without any updates to the program graph
	 */
	@Test
	public void simpleTest_Rerun() {
		System.out.println("Starting simpleTest_Rerun...");
		performTestRerun(ITestHandlerSimpleTest(), "SimpleTest");
		System.out.println("simpleTest_Rerun finished.");
	}

	/**
	 * Performs a simple information leakage analysis without any updates to the program graph
	 */
	@Test
	public void simpleTestJU_Rerun() {
		System.out.println("Starting simpleTest_Rerun...");
		performTestRerun(ITestHandlerSimpleTest(), "org.junit.runner.JUnitCore");
		System.out.println("simpleTest_Rerun finished.");
	}

	/**
	 * Performs a simple information leakage analysis without any updates to the program graph
	 * @throws ClassNotFoundException 
	 */
	@Test
	public void simpleTestJU_Update() throws ClassNotFoundException {
		System.out.println("Starting simpleTest_Update...");
		performTestUpdate(ITestHandlerSimpleTest(), "org.junit.runner.JUnitCore");
		System.out.println("simpleTest_Update finished.");
	}

	/**
	 * Performs a simple information leakage analysis without any updates to the program graph
	 */
	@Test
	public void simpleTestJU_Propagate() {
		System.out.println("Starting simpleTest_Propagate...");
		performTestDirect(ITestHandlerSimpleTest(), "org.junit.runner.JUnitCore");
		System.out.println("simpleTest_Propagate finished.");
	}

	private ITestHandler<UpdatableWrapper<Local>> ITestHandlerLeakUpdate () {
		return new ITestHandler<UpdatableWrapper<Local>>() {
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
			}
			
			@Override
			public void patchGraph(int phase) {
				System.out.println("Patching graph in phase " + phase + "...");
				
				// Patch the control-flow graph. We add an assignment to the
				// "foo" variable inside the loop
				Local fooLocal = null;
				Local argsLocal = null;
				for (Local l : Scene.v().getMainMethod().getActiveBody().getLocals())
					if (l.getName().equals("foo"))
						fooLocal = l;
					else if (l.getName().equals("args"))
						argsLocal = l;
				Assert.assertNotNull(fooLocal);
				Assert.assertNotNull(argsLocal);
				
				if (phase == 0) {
					AssignStmt assignStmt = Jimple.v().newAssignStmt(fooLocal, argsLocal);
					JAssignStmt point = null;
					for (Unit unit : Scene.v().getMainMethod().getActiveBody().getUnits()) {
						if (unit instanceof JAssignStmt) {
							JAssignStmt stmt = (JAssignStmt) unit;
							if (stmt.getLeftOp().toString().equals("temp$5"))
								if (stmt.getRightOp().toString().equals("temp$4 + -1")) {
									point = stmt;
									break;
								}
						}
					}
					if (point == null)
						Assert.fail("Injection point not found");
					Scene.v().getMainMethod().getActiveBody().getUnits().insertBefore (assignStmt, point);
					
					for (Unit u : Scene.v().getMainMethod().getActiveBody().getUnits())
						System.out.println(u);
				}
				else if (phase == 1) {
					// We add another assignment after the loop
					Local newLocal = Jimple.v().newLocal("assignTest", ArrayType.v(RefType.v("java.lang.String"), 1));
					Scene.v().getMainMethod().getActiveBody().getLocals().add(newLocal);
					AssignStmt invokeStmt = Jimple.v().newAssignStmt(newLocal, fooLocal);
					Scene.v().getMainMethod().getActiveBody().getUnits().insertBefore
						(invokeStmt, Scene.v().getMainMethod().getActiveBody().getUnits().getLast());
				}
				else
					Assert.fail("Invalid phase: " + phase);
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				Unit ret = Scene.v().getMainMethod().getActiveBody().getUnits().getLast();
				Set<UpdatableWrapper<Local>> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				if (phase == 1) {
					Assert.assertEquals("Invalid number of information leaks found", 2, results.size());	
					List<String> locals = new ArrayList<String>();
					for (UpdatableWrapper<Local> l : results)
						locals.add(l.getContents().getName());
					Assert.assertTrue("Invalid information leak found", locals.contains("r0"));
					Assert.assertTrue("Invalid information leak found", locals.contains("r1"));
				}
				else if (phase == 2) {
					Assert.assertEquals("Invalid number of information leaks found", 3, results.size());
					List<String> locals = new ArrayList<String>();
					for (UpdatableWrapper<Local> l : results)
						locals.add(l.getContents().getName());
					Assert.assertTrue("Invalid information leak found", locals.contains("assignTest"));
					Assert.assertTrue("Invalid information leak found", locals.contains("r0"));
					Assert.assertTrue("Invalid information leak found", locals.contains("r1"));
				}
			}

			@Override
			public int getPhaseCount() {
				return 1;
			}

			@Override
			public void initApplicationClasses() {
				// TODO Auto-generated method stub
				
			}
		};
	}
	
	/**
	 * Performs a simple information leakage analysis, then adds a statement that introduces
	 * a new information leak into a loop within the main function
	 */
	@Test
	public void addLeakUpdateTest_Update() {
		System.out.println("Starting addLeakUpdateTest_Update...");
		performTestUpdate(ITestHandlerLeakUpdate(), "SimpleTest");
		System.out.println("addLeakUpdateTest_Update finished.");
	}

	/**
	 * Performs a simple information leakage analysis, then adds a statement that introduces
	 * a new information leak into a loop within the main function
	 */
	@Test
	public void addLeakUpdateTest_Propagate() {
		System.out.println("Starting addLeakUpdateTest_Propagate...");
		performTestDirect(ITestHandlerLeakUpdate(), "SimpleTest");
		System.out.println("addLeakUpdateTest_Propagate finished.");
	}

	/**
	 * Performs a simple information leakage analysis, then adds a statement that introduces
	 * a new information leak into a loop within the main function
	 */
	@Test
	public void addLeakUpdateTest_Rerun() {
		System.out.println("Starting addLeakUpdateTest_Rerun...");
		performTestRerun(ITestHandlerLeakUpdate(), "SimpleTest");
		System.out.println("addLeakUpdateTest_Rerun finished.");
	}

	private ITestHandler<UpdatableWrapper<Local>> ITestHandlerLeakUpdateJU () {
		return new ITestHandler<UpdatableWrapper<Local>>() {
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
			}
			
			@Override
			public void patchGraph(int phase) {
				// Patch the control-flow graph. We add an assignment to the
				// "foo" variable inside the loop
				Local argsLocal = null;
				for (Local l : Scene.v().getMainMethod().getActiveBody().getLocals())
					if (l.getName().equals("r0")) {
						argsLocal = l;
						break;
					}
				Assert.assertNotNull(argsLocal);
				
				Body mb = Scene.v().getMainMethod().getActiveBody();
				Stmt point = null;
				for (Unit unit : mb.getUnits()) {
					if (unit instanceof JInvokeStmt) {
						JInvokeStmt stmt = (JInvokeStmt) unit;
						if (stmt.getInvokeExpr().getMethod().getName().equals("runMainAndExit")) {
							point = stmt;
							break;
						}
					}
				}
				if (point == null)
					Assert.fail("Injection point not found");

				Local varFoo = Jimple.v().newLocal("foo", ArrayType.v(RefType.v("java.lang.String"), 1));
				mb.getLocals().addFirst(varFoo);
				AssignStmt assignStmt = Jimple.v().newAssignStmt(varFoo, argsLocal);
				mb.getUnits().insertBefore(assignStmt, point);
					
				SootFieldRef sysoRef = Scene.v().getSootClass("java.lang.System").getField
						("out", RefType.v("java.io.PrintStream")).makeRef();
				SootClass psClass = RefType.v("java.io.PrintStream").getSootClass();
				SootMethodRef methRef = psClass.getMethod("void println(java.lang.String)").makeRef();

				Local varOut = Jimple.v().newLocal("out", RefType.v("java.io.PrintStream"));
				mb.getLocals().insertAfter(varOut, varFoo);
				AssignStmt assignOutStmt = Jimple.v().newAssignStmt(varOut, Jimple.v().newStaticFieldRef(sysoRef));
				mb.getUnits().insertAfter(assignOutStmt, assignStmt);

				SootClass sClass = RefType.v("java.lang.Object").getSootClass();
				SootMethodRef toStringRef = sClass.getMethod("java.lang.String toString()").makeRef();

				VirtualInvokeExpr toStringExpr = Jimple.v().newVirtualInvokeExpr
						(varFoo, toStringRef);
				Local varS = Jimple.v().newLocal("s", RefType.v("java.lang.String"));
				mb.getLocals().insertAfter(varS, varOut);
				AssignStmt assignSStmt = Jimple.v().newAssignStmt(varS, toStringExpr);
				mb.getUnits().insertAfter(assignSStmt, assignOutStmt);

				VirtualInvokeExpr virtualInvokeExpr = Jimple.v().newVirtualInvokeExpr
						(varOut, methRef, varS);
				InvokeStmt invokeStmt = Jimple.v().newInvokeStmt(virtualInvokeExpr);
				mb.getUnits().insertAfter(invokeStmt, assignSStmt);
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				Unit ret = Scene.v().getMainMethod().getActiveBody().getUnits().getLast();
				Set<UpdatableWrapper<Local>> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));

				Assert.assertEquals("Invalid number of information leaks found", 2, results.size());	
				List<String> locals = new ArrayList<String>();
				for (UpdatableWrapper<Local> l : results)
					locals.add(l.getContents().getName());
				Assert.assertTrue("Invalid information leak found", locals.contains("r0"));
				Assert.assertTrue("Invalid information leak found", locals.contains("foo"));
			}

			@Override
			public int getPhaseCount() {
				return 1;
			}

			@Override
			public void initApplicationClasses() {
				// TODO Auto-generated method stub
				
			}
		};
	}

	/**
	 * Performs a simple information leakage analysis, then adds a statement that introduces
	 * a new information leak into a loop within the main function
	 */
	@Test
	public void addLeakUpdateTestJU_Update() {
		System.out.println("Starting addLeakUpdateTest_Update...");
		performTestUpdate(ITestHandlerLeakUpdateJU(), "org.junit.runner.JUnitCore");
		System.out.println("addLeakUpdateTest_Update finished.");
	}

	/**
	 * Performs a simple information leakage analysis, then adds a statement that introduces
	 * a new information leak into a loop within the main function
	 */
	@Test
	public void addLeakUpdateTestJU_Propagate() {
		System.out.println("Starting addLeakUpdateTest_Propagate...");
		performTestDirect(ITestHandlerLeakUpdateJU(), "org.junit.runner.JUnitCore");
		System.out.println("addLeakUpdateTest_Propagate finished.");
	}

	/**
	 * Performs a simple information leakage analysis, then adds a statement that introduces
	 * a new information leak into a loop within the main function
	 */
	@Test
	public void addLeakUpdateTestJU_Rerun() {
		System.out.println("Starting addLeakUpdateTest_Rerun...");
		performTestRerun(ITestHandlerLeakUpdateJU(), "org.junit.runner.JUnitCore");
		System.out.println("addLeakUpdateTest_Rerun finished.");
	}

	private ITestHandler<UpdatableWrapper<Local>> ITestHandlerCallNoLeakageTest() {
		return new ITestHandler<UpdatableWrapper<Local>>() {
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
			}
			
			@Override
			public void patchGraph(int phase) {
				// Patch the control-flow graph. We insert a new call from the main() method
				// to our artificial helper method "otherMethod".
				System.out.println("Patching cfg...");
				SootMethod helperMethod = Scene.v().getMainClass().getMethodByName("otherMethod");
				Local localFoo = null;
				for (Local l : Scene.v().getMainMethod().getActiveBody().getLocals()) {
					if (l.getName().equals("foo"))
						localFoo = l;
				}
				Assert.assertNotNull(localFoo);
				
				Local newLocal = Jimple.v().newLocal("retVal", ArrayType.v(RefType.v("java.lang.String"), 1));
				Scene.v().getMainMethod().getActiveBody().getLocals().add(newLocal);
				
				AssignStmt initStmt = Jimple.v().newAssignStmt(newLocal, IntConstant.v(42));
				Scene.v().getMainMethod().getActiveBody().getUnits().insertBefore
					(initStmt, Scene.v().getMainMethod().getActiveBody().getUnits().getLast());

				StaticInvokeExpr invokeExpr = Jimple.v().newStaticInvokeExpr(helperMethod.makeRef(), newLocal);

				AssignStmt invokeStmt = Jimple.v().newAssignStmt(localFoo, invokeExpr); 
				for (Unit u : Scene.v().getMainMethod().getActiveBody().getUnits())
					if (u.toString().contains("doFoo")) {
						Scene.v().getMainMethod().getActiveBody().getUnits().insertBefore (invokeStmt, u);
						break;
					}

				helperMethod.retrieveActiveBody();
								
				CallGraph cg = Scene.v().getCallGraph();
				Edge edge = new Edge(Scene.v().getMainMethod(), invokeStmt, helperMethod);
				cg.addEdge(edge);

				// Check that our new method is indeed reachable
				List<MethodOrMethodContext> eps = new ArrayList<MethodOrMethodContext>();
				eps.addAll(Scene.v().getEntryPoints());
				ReachableMethods reachableMethods = new ReachableMethods(Scene.v().getCallGraph(), eps.iterator());
				reachableMethods.update();
				boolean found = false;
				for(Iterator<MethodOrMethodContext> iter = reachableMethods.listener(); iter.hasNext(); ) {
					SootMethod m = iter.next().method();
					if (m.getName().contains("otherMethod"))
						found = true;
				}
				Assert.assertTrue("Patched method NOT found", found);
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				Unit ret = Scene.v().getMainMethod().getActiveBody().getUnits().getLast();
				Set<UpdatableWrapper<Local>> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				
				List<String> leaks = new ArrayList<String>();
				for (UpdatableWrapper<Local> l : results)
					leaks.add(l.getContents().getName());

				Assert.assertEquals("Invalid number of information leaks found", 1, leaks.size());
				Assert.assertTrue("Invalid information leak found", leaks.contains("args"));
			}

			@Override
			public int getPhaseCount() {
				return 1;
			}

			@Override
			public void initApplicationClasses() {
				// TODO Auto-generated method stub
				
			}
		};
	}
	
	/**
	 * Performs a simple information leakage analysis, then adds function call that should not
	 * change information leakage
	 */
	@Test
	public void addCallNoLeakageTest_Update() {
		System.out.println("Starting addCallNoLeakageTest_Update...");
		performTestUpdate(ITestHandlerCallNoLeakageTest(), "SimpleTest");
		System.out.println("addCallNoLeakageTest_Update finished.");
	}

	/**
	 * Performs a simple information leakage analysis, then adds function call that should not
	 * change information leakage
	 */
	@Test
	public void addCallNoLeakageTest_Propagate() {
		System.out.println("Starting addCallNoLeakageTest_Propagate...");
		performTestDirect(ITestHandlerCallNoLeakageTest(), "SimpleTest");
		System.out.println("addCallNoLeakageTest_Propagate finished.");
	}
	
	/**
	 * Performs a simple information leakage analysis, then adds function call that should not
	 * change information leakage
	 */
	@Test
	public void addCallNoLeakageTest_Rerun() {
		System.out.println("Starting addCallNoLeakageTest_Rerun...");
		performTestRerun(ITestHandlerCallNoLeakageTest(), "SimpleTest");
		System.out.println("addCallNoLeakageTest_Rerun finished.");
	}

	private ITestHandler<UpdatableWrapper<Local>> ITestHandlerCallNoLeakageTestJU() {
		return new ITestHandler<UpdatableWrapper<Local>>() {
			
			@Override
			public void initialize() {
			}

			
			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
			}
			
			@Override
			public void patchGraph(int phase) {
				// Patch the control-flow graph. We duplicate the call to "runMain" in the
				// "runMainAndExit" method
				System.out.println("Patching cfg...");
				SootMethod method = Scene.v().getMainClass().getMethodByName("runMainAndExit");
				Body mb = method.getActiveBody();

				Local argsLocal = null;
				for (Local l : mb.getLocals())
					if (l.getName().equals("r0")) {
						argsLocal = l;
						break;
					}

				Stmt point = null;
				for (Unit unit : mb.getUnits()) {
					if (unit instanceof JAssignStmt) {
						JAssignStmt stmt = (JAssignStmt) unit;
						if (stmt.getRightOp().toString().contains("runMain")) {
							point = stmt;
							break;
						}
					}
				}
				if (point == null)
					Assert.fail("Injection point not found");

				Local rsLocal = Jimple.v().newLocal("obj", RefType.v("org.junit.internal.JUnitSystem"));
				mb.getLocals().add(rsLocal);
				
				NewExpr newSysExpr = Jimple.v().newNewExpr(RefType.v("org.junit.internal.RealSystem"));
				AssignStmt initRsStmt = Jimple.v().newAssignStmt(rsLocal, newSysExpr);
				mb.getUnits().insertAfter(initRsStmt, point);

				Local newLocal = Jimple.v().newLocal("obj", RefType.v("org.junit.runner.JUnitCore"));
				mb.getLocals().add(newLocal);
				
				NewExpr newExpr = Jimple.v().newNewExpr(RefType.v("org.junit.runner.JUnitCore"));
				AssignStmt initStmt = Jimple.v().newAssignStmt(newLocal, newExpr);
				mb.getUnits().insertAfter(initStmt, initRsStmt);
				
				// Important: Call init before doing anything else with the newly created object!!!
				// Otherwise the VM will throw a java.lang.VerifyError: Expecting to find object/array on stack
				// specialinvoke $r2.<org.junit.runner.JUnitCore: void <init>()>()
				InvokeStmt invokeInitStmt = Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr(newLocal,
						RefType.v("org.junit.runner.JUnitCore").getSootClass().getMethodByName("<init>").makeRef()));
				mb.getUnits().insertAfter(invokeInitStmt, initStmt);
				
				SootMethodRef methRef = Scene.v().getMainClass().getMethod
						("org.junit.runner.Result runMain(org.junit.internal.JUnitSystem,java.lang.String[])").makeRef();
				InvokeStmt invokeStmt = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(newLocal, methRef, rsLocal,
						argsLocal));
				mb.getUnits().insertAfter(invokeStmt, invokeInitStmt);
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				Unit ret = Scene.v().getMainMethod().getActiveBody().getUnits().getLast();
				Set<UpdatableWrapper<Local>> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));

				List<String> leaks = new ArrayList<String>();
				for (UpdatableWrapper<Local> l : results)
					leaks.add(l.getContents().getName());
				
				Assert.assertEquals("Invalid number of information leaks found", 1, leaks.size());
				Assert.assertTrue("Invalid information leak found", leaks.contains("r0"));
			}

			@Override
			public int getPhaseCount() {
				return 1;
			}


			@Override
			public void initApplicationClasses() {
				// TODO Auto-generated method stub
				
			}
		};
	}

	/**
	 * Performs a simple information leakage analysis, then adds function call that should not
	 * change information leakage
	 */
	@Test
	public void addCallNoLeakageTestJU_Update() {
		System.out.println("Starting addCallNoLeakageTestJU_Update...");
		performTestUpdate(ITestHandlerCallNoLeakageTestJU(), "org.junit.runner.JUnitCore");
		System.out.println("addCallNoLeakageTestJU_Update finished.");
	}

	/**
	 * Performs a simple information leakage analysis, then adds function call that should not
	 * change information leakage
	 */
	@Test
	public void addCallNoLeakageTestJU_Propagate() {
		System.out.println("Starting addCallNoLeakageTestJU_Propagate...");
		performTestDirect(ITestHandlerCallNoLeakageTestJU(), "org.junit.runner.JUnitCore");
		System.out.println("addCallNoLeakageTestJU_Propagate finished.");
	}
	
	/**
	 * Performs a simple information leakage analysis, then adds function call that should not
	 * change information leakage
	 */
	@Test
	public void addCallNoLeakageTestJU_Rerun() {
		System.out.println("Starting addCallNoLeakageTestJU_Rerun...");
		performTestRerun(ITestHandlerCallNoLeakageTestJU(), "org.junit.runner.JUnitCore");
		System.out.println("addCallNoLeakageTestJU_Rerun finished.");
	}

	private ITestHandler<UpdatableWrapper<Local>> ITestHandlerAddLeakTest() {
		return new ITestHandler<UpdatableWrapper<Local>>() {
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
			}

			@Override
			public void patchGraph(int phase) {
				// Patch the control-flow graph. We insert a new call from the main() method
				// to our artificial helper method "otherMethod".
				System.out.println("Patching cfg...");
				SootMethod helperMethod = Scene.v().getMainClass().getMethodByName("otherMethod");
				Local localTestMe = null;
				Local localFoo = null;
				for (Local l : Scene.v().getMainMethod().getActiveBody().getLocals()) {
					if (l.getName().equals("args"))
						localTestMe = l;
					else if (l.getName().equals("foo"))
						localFoo = l;
				}
				Assert.assertNotNull("Parameter local was null", localTestMe);
				Assert.assertNotNull("Variable foo was null", localFoo);
				StaticInvokeExpr invokeExpr = Jimple.v().newStaticInvokeExpr(helperMethod.makeRef(), localTestMe);
				
				AssignStmt invokeStmt = Jimple.v().newAssignStmt(localFoo, invokeExpr); 
				for (Unit u : Scene.v().getMainMethod().getActiveBody().getUnits())
					if (u.toString().contains("doFoo")) {
						Scene.v().getMainMethod().getActiveBody().getUnits().insertBefore (invokeStmt, u);
						break;
					}

				helperMethod.retrieveActiveBody();

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
				Assert.assertTrue("Patched method NOT found", found);
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {				
				Unit ret = Scene.v().getMainMethod().getActiveBody().getUnits().getLast();
				Set<UpdatableWrapper<Local>> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				Assert.assertEquals("Invalid number of information leaks found", 2, results.size());

				List<String> locals = new ArrayList<String>();
				for (UpdatableWrapper<Local> l : results)
					locals.add(l.getContents().getName());
				Assert.assertTrue("Invalid information leak found", locals.contains("args"));
				Assert.assertTrue("Invalid information leak found", locals.contains("foo"));
			}

			@Override
			public int getPhaseCount() {
				return 1;
			}

			@Override
			public void initApplicationClasses() {
				// TODO Auto-generated method stub
				
			}
		};
	}

	/**
	 * Performs a simple information leakage analysis, then adds function call which introduces
	 * a new information leak
	 */
	@Test
	public void addCallAddLeakTest_Update() {
		System.out.println("Starting addCallAddLeakTest_Update...");
		performTestUpdate(ITestHandlerAddLeakTest(), "SimpleTest");
		System.out.println("addCallAddLeakTest_Update finished.");
	}

	/**
	 * Performs a simple information leakage analysis, then adds function call which introduces
	 * a new information leak
	 */
	@Test
	public void addCallAddLeakTest_Propagate() {
		System.out.println("Starting addCallAddLeakTest_Propagate...");
		performTestDirect(ITestHandlerAddLeakTest(), "SimpleTest");
		System.out.println("addCallAddLeakTest_Propagate finished.");
	}
	
	/**
	 * Performs a simple information leakage analysis, then adds function call which introduces
	 * a new information leak
	 */
	@Test
	public void addCallAddLeakTest_Rerun() {
		System.out.println("Starting addCallAddLeakTest_Rerun...");
		performTestRerun(ITestHandlerAddLeakTest(), "SimpleTest");
		System.out.println("addCallAddLeakTest_Rerun finished.");
	}

	private ITestHandler<UpdatableWrapper<Local>> ITestHandlerAddLeakInFunctionTest() {
		return new ITestHandler<UpdatableWrapper<Local>>() {
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
			}

			@Override
			public void patchGraph(int phase) {
				// Patch the control-flow graph. We change the "bar" to add a new assignment
				System.out.println("Patching cfg...");
				SootMethod helperMethod = Scene.v().getMainClass().getMethodByName("bar");
				helperMethod.retrieveActiveBody();

				Local localParam = null;
				Local localVariable = null;
				for (Local l : helperMethod.getActiveBody().getLocals()) {
					if (l.getName().equals("args"))
						localParam = l;
					else if (l.getName().equals("foo"))
						localVariable = l;
				}
				Assert.assertNotNull("Parameter local was null", localParam);
				Assert.assertNotNull("Variable foo was null", localVariable);
				
				AssignStmt invokeStmt = Jimple.v().newAssignStmt(localVariable, localParam); 
				for (Unit u : helperMethod.getActiveBody().getUnits())
					if (u.toString().contains("return")) {
						helperMethod.getActiveBody().getUnits().insertBefore (invokeStmt, u);
						break;
					}
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				Unit ret = Scene.v().getMainMethod().getActiveBody().getUnits().getLast();
				Set<UpdatableWrapper<Local>> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));

				List<String> leaks = new ArrayList<String>();
				for (UpdatableWrapper<Local> l : results) {
					String name = l.getContents().getName();
					if (!name.contains("temp$"))
						leaks.add(name);
				}
				
				Assert.assertEquals("Invalid number of information leaks found", 2, leaks.size());
				Assert.assertTrue("Invalid information leak found", leaks.contains("args"));
				Assert.assertTrue("Invalid information leak found", leaks.contains("barRes"));
			}

			@Override
			public int getPhaseCount() {
				return 1;
			}

			@Override
			public void initApplicationClasses() {
				// TODO Auto-generated method stub
				
			}
		};
	}

	/**
	 * Performs a simple information leakage analysis, then changes a function called from
	 * main() such that a new leak is introduced in this function
	 */
	@Test
	public void addLeakInFunctionTest_Update() {
		System.out.println("Starting addLeakInFunctionTest_Update...");
		performTestUpdate(ITestHandlerAddLeakInFunctionTest(), "SimpleTest");
		System.out.println("addLeakInFunctionTest_Update finished.");
	}

	/**
	 * Performs a simple information leakage analysis, then changes a function called from
	 * main() such that a new leak is introduced in this function
	 */
	@Test
	public void addLeakInFunctionTest_Propagate() {
		System.out.println("Starting addLeakInFunctionTest_Propagate...");
		performTestDirect(ITestHandlerAddLeakInFunctionTest(), "SimpleTest");
		System.out.println("addLeakInFunctionTest_Propagate finished.");
	}

	/**
	 * Performs a simple information leakage analysis, then changes a function called from
	 * main() such that a new leak is introduced in this function
	 */
	@Test
	public void addLeakInFunctionTest_Rerun() {
		System.out.println("Starting addLeakInFunctionTest_Rerun...");
		performTestRerun(ITestHandlerAddLeakInFunctionTest(), "SimpleTest");
		System.out.println("addLeakInFunctionTest_Rerun finished.");
	}

	private ITestHandler<UpdatableWrapper<Local>> ITestHandlerRemoveLeakTest() {
		return new ITestHandler<UpdatableWrapper<Local>>() {
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
			}
			
			@Override
			public void patchGraph(int phase) {
				// Patch the control-flow graph. We remove the assignment to "bar"
				System.out.println("Patching cfg...");
				SootMethod mainMethod = Scene.v().getMainMethod();
				mainMethod.retrieveActiveBody();

				List<Unit> rmList = new ArrayList<Unit>();
				for (Unit u : mainMethod.getActiveBody().getUnits())
					if (u instanceof JAssignStmt) {
						if (((JAssignStmt) u).getLeftOp().toString().equals("var"))
							rmList.add(u);
					}
				for (Unit u : rmList)
					mainMethod.getActiveBody().getUnits().remove(u);
				System.out.println("Deleted " + rmList.size() + " statements");
				Assert.assertTrue("No statements found to delete", rmList.size() > 0);
			}
				
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {				
				Unit ret = Scene.v().getMainMethod().getActiveBody().getUnits().getLast();
				Set<UpdatableWrapper<Local>> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				
				List<String> leaks = new ArrayList<String>();
				for (UpdatableWrapper<Local> l : results) {
					String name = l.getContents().getName();
					if (!name.contains("temp$"))
						leaks.add(name);
				}

				Assert.assertEquals("Invalid number of information leaks found", 1, leaks.size());
				Assert.assertTrue("Invalid information leak found", leaks.contains("args"));
			}

			@Override
			public int getPhaseCount() {
				return 1;
			}

			@Override
			public void initApplicationClasses() {
				// TODO Auto-generated method stub
				
			}
		};
	}

	/**
	 * Performs a simple information leakage analysis, then removes a leak from
	 * the code
	 */
	@Test
	public void removeLeakTest_Rerun() {
		System.out.println("Starting removeLeakTest_Rerun...");
		performTestRerun(ITestHandlerRemoveLeakTest(), "TestRemoveLeak");
		System.out.println("removeLeakTest_Rerun finished.");
	}

	/**
	 * Performs a simple information leakage analysis, then removes a leak from
	 * the code
	 */
	@Test
	public void removeLeakTest_Update() {
		System.out.println("Starting removeLeakTest_Update...");
		performTestUpdate(ITestHandlerRemoveLeakTest(), "TestRemoveLeak");
		System.out.println("removeLeakTest_Update finished.");
	}

	/**
	 * Performs a simple information leakage analysis, then removes a leak from
	 * the code
	 */
	@Test
	public void removeLeakTest_Propagate() {
		System.out.println("Starting removeLeakTest_Propagate...");
		performTestDirect(ITestHandlerRemoveLeakTest(), "TestRemoveLeak");
		System.out.println("removeLeakTest_Propagate finished.");
	}

	private ITestHandler<UpdatableWrapper<Local>> ITestHandlerRemoveLeakInFunctionTest() {
		return new ITestHandler<UpdatableWrapper<Local>>() {
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
			}
			
			@Override
			public void patchGraph(int phase) {
				// Patch the control-flow graph. We remove the assignment to "bar"
				System.out.println("Patching cfg...");
				SootMethod fooMethod = Scene.v().getMainClass().getMethodByName("foo");
				Assert.assertNotNull("Method not found", fooMethod);
				fooMethod.retrieveActiveBody();

				List<Unit> rmList = new ArrayList<Unit>();
				for (Unit u : fooMethod.getActiveBody().getUnits())
					if (u instanceof JAssignStmt) {
						JAssignStmt as = (JAssignStmt) u;
						if (as.getLeftOp().toString().equals("res")
								&& as.getRightOp().toString().equals("args")) {
							rmList.add(u);
						}
					}
				for (Unit u : rmList)
					fooMethod.getActiveBody().getUnits().remove(u);
				System.out.println("Deleted " + rmList.size() + " statements");
				Assert.assertTrue("No statements found to delete", rmList.size() > 0);
			}
				
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				Unit ret = Scene.v().getMainMethod().getActiveBody().getUnits().getLast();
				Set<UpdatableWrapper<Local>> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));

				List<String> leaks = new ArrayList<String>();
				for (UpdatableWrapper<Local> l : results)
					leaks.add(l.getContents().getName());
				
				Assert.assertEquals("Invalid number of information leaks found", 1, leaks.size());
				Assert.assertTrue("Invalid information leak found", leaks.contains("args"));
			}

			@Override
			public int getPhaseCount() {
				return 1;
			}

			@Override
			public void initApplicationClasses() {
				// TODO Auto-generated method stub
				
			}
		};
	}

	/**
	 * Performs a simple information leakage analysis, then removes a leak within
	 * a called function from the code
	 */
	@Test
	public void removeLeakInFunctionTest_Rerun() {
		System.out.println("Starting removeLeakInFunctionTest_Rerun...");
		performTestRerun(ITestHandlerRemoveLeakInFunctionTest(), "TestRemoveLeakInFunction");
		System.out.println("removeLeakInFunctionTest_Rerun finished.");
	}

	/**
	 * Performs a simple information leakage analysis, then removes a leak within
	 * a called function from the code
	 */
	@Test
	public void removeLeakInFunctionTest_Update() {
		System.out.println("Starting removeLeakInFunctionTest_Update...");
		performTestUpdate(ITestHandlerRemoveLeakInFunctionTest(), "TestRemoveLeakInFunction");
		System.out.println("removeLeakInFunctionTest_Update finished.");
	}

	/**
	 * Performs a simple information leakage analysis, then removes a leak within
	 * a called function from the code
	 */
	@Test
	public void removeLeakInFunctionTest_Propagate() {
		System.out.println("Starting removeLeakInFunctionTest_Propagate...");
		performTestDirect(ITestHandlerRemoveLeakInFunctionTest(), "TestRemoveLeakInFunction");
		System.out.println("removeLeakInFunctionTest_Propagate finished.");
	}

	private ITestHandler<UpdatableWrapper<Local>> ITestHandlerRemoveLeakingCallTest() {
		return new ITestHandler<UpdatableWrapper<Local>>() {
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
			}
			
			@Override
			public void patchGraph(int phase) {
				// Patch the control-flow graph. We remove the call to the "foo" function
				System.out.println("Patching cfg...");
				SootMethod mainMethod = Scene.v().getMainMethod();
				mainMethod.retrieveActiveBody();

				for (Unit u : mainMethod.getActiveBody().getUnits())
					System.out.println(u);

				List<Unit> rmList = new ArrayList<Unit>();
				for (Unit u : mainMethod.getActiveBody().getUnits())
					if (u instanceof JAssignStmt) {
						JAssignStmt as = (JAssignStmt) u;
						if (as.getLeftOp().toString().contains("temp")
								&& as.getRightOp().toString().contains("foo")) {
							rmList.add(u);
							if (as.getRightOp() instanceof InvokeExpr) {
								InvokeExpr inv = (InvokeExpr) as.getRightOp();
								Edge edge = Scene.v().getCallGraph().findEdge(u, inv.getMethod());
								Assert.assertNotNull(edge);
								Scene.v().getCallGraph().removeEdge(edge);
							}
						}
					}
				for (Unit u : rmList) {
					mainMethod.getActiveBody().getUnits().remove(u);
					System.out.println("Deleting: " + u);
				}
				System.out.println("Deleted " + rmList.size() + " statements");
				Assert.assertTrue("No statements found to delete", rmList.size() > 0);
				
				for (Unit u : mainMethod.getActiveBody().getUnits())
					System.out.println(u);
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				Unit ret = Scene.v().getMainMethod().getActiveBody().getUnits().getLast();
				Set<UpdatableWrapper<Local>> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));

				List<String> leaks = new ArrayList<String>();
				for (UpdatableWrapper<Local> l : results)
					leaks.add(l.getContents().getName());

				Assert.assertEquals("Invalid number of information leaks found", 1, leaks.size());
				Assert.assertTrue("Invalid information leak found", leaks.contains("args"));
			}

			@Override
			public int getPhaseCount() {
				return 1;
			}

			@Override
			public void initApplicationClasses() {
				// TODO Auto-generated method stub
				
			}
		};
	}

	/**
	 * Performs a simple information leakage analysis, then removes a function call
	 * containing an information leak
	 */
	@Test
	public void removeLeakingCallTest_Rerun() {
		System.out.println("Starting removeLeakingCallTest_Rerun...");
		performTestRerun(ITestHandlerRemoveLeakingCallTest(), "TestRemoveLeakingCall");
		System.out.println("removeLeakingCallTest_Rerun finished.");
	}

	/**
	 * Performs a simple information leakage analysis, then removes a function call
	 * containing an information leak
	 */
	@Test
	public void removeLeakingCallTest_Update() {
		System.out.println("Starting removeLeakingCallTest_Update...");
		performTestUpdate(ITestHandlerRemoveLeakingCallTest(), "TestRemoveLeakingCall");
		System.out.println("removeLeakingCallTest_Update finished.");
	}

	/**
	 * Performs a simple information leakage analysis, then removes a function call
	 * containing an information leak
	 */
	@Test
	public void removeLeakingCallTest_Propagate() {
		System.out.println("Starting removeLeakingCallTest_Propagate...");
		performTestDirect(ITestHandlerRemoveLeakingCallTest(), "TestRemoveLeakingCall");
		System.out.println("removeLeakingCallTest_Propagate finished.");
	}

	private ITestHandler<UpdatableWrapper<Local>> ITestHandlerRemoveNoLeakCallTest() {
		return new ITestHandler<UpdatableWrapper<Local>>() {
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
			}
			
			@Override
			public void patchGraph(int phase) {
				// Patch the control-flow graph. We remove the call to the "foo" function
				System.out.println("Patching cfg...");
				SootMethod mainMethod = Scene.v().getMainMethod();
				mainMethod.retrieveActiveBody();

				List<Unit> rmList = new ArrayList<Unit>();
				for (Unit u : mainMethod.getActiveBody().getUnits())
					if (u instanceof JAssignStmt) {
						JAssignStmt as = (JAssignStmt) u;
						if (as.getLeftOp().toString().contains("temp")
								&& as.getRightOp().toString().contains("bar")) {
							rmList.add(u);
							if (as.getRightOp() instanceof InvokeExpr) {
								InvokeExpr inv = (InvokeExpr) as.getRightOp();
								Edge edge = Scene.v().getCallGraph().findEdge(u, inv.getMethod());
								Assert.assertNotNull(edge);
								Scene.v().getCallGraph().removeEdge(edge);
							}
						}
					}
				for (Unit u : mainMethod.getActiveBody().getUnits())
					System.out.println(u);

				for (Unit u : rmList)
					mainMethod.getActiveBody().getUnits().remove(u);
				System.out.println("Deleted " + rmList.size() + " statements");
				Assert.assertTrue("No statements found to delete", rmList.size() > 0);
			}
				
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableWrapper<Local>,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				Unit ret = Scene.v().getMainMethod().getActiveBody().getUnits().getLast();
				Set<UpdatableWrapper<Local>> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				
				List<String> leaks = new ArrayList<String>();
				for (UpdatableWrapper<Local> l : results) {
					String name = l.getContents().getName();
					if (!name.contains("temp$"))
						leaks.add(name);
				}
				
				Assert.assertEquals("Invalid number of information leaks found", 2, results.size());
				Assert.assertEquals("Invalid number of information leaks found", 1, leaks.size());
				Assert.assertTrue("Invalid information leak found", leaks.contains("args"));
				// the newly produced leak is a temporary variable
			}

			@Override
			public int getPhaseCount() {
				return 1;
			}

			@Override
			public void initApplicationClasses() {
				// TODO Auto-generated method stub
				
			}
		};
	}

	/**
	 * Performs a simple information leakage analysis, then removes a function call not
	 * containing any information leak
	 */
	@Test
	public void removeNoLeakCallTest_Rerun() {
		System.out.println("Starting removeNoLeakCallTest_Rerun...");
		performTestRerun(ITestHandlerRemoveNoLeakCallTest(), "TestRemoveNoLeakCall");
		System.out.println("removeNoLeakCallTest_Rerun finished.");
	}

	/**
	 * Performs a simple information leakage analysis, then removes a function call not
	 * containing any information leak
	 */
	@Test
	public void removeNoLeakCallTest_Update() {
		System.out.println("Starting removeNoLeakCallTest_Update...");
		performTestUpdate(ITestHandlerRemoveNoLeakCallTest(), "TestRemoveNoLeakCall");
		System.out.println("removeNoLeakCallTest_Update finished.");
	}

	/**
	 * Performs a simple information leakage analysis, then removes a function call not
	 * containing any information leak
	 */
	@Test
	public void removeNoLeakCallTest_Propagate() {
		System.out.println("Starting removeNoLeakCallTest_Propagate...");
		performTestDirect(ITestHandlerRemoveNoLeakCallTest(), "TestRemoveNoLeakCall");
		System.out.println("removeNoLeakCallTest_Propagate finished.");
	}

}
