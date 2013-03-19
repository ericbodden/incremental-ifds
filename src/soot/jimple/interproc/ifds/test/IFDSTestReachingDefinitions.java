package soot.jimple.interproc.ifds.test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import soot.Local;
import soot.PackManager;
import soot.RefType;
import soot.Scene;
import soot.SceneTransformer;
import soot.SootClass;
import soot.SootMethod;
import soot.Transform;
import soot.Unit;
import soot.Value;
import soot.jimple.AssignStmt;
import soot.jimple.DefinitionStmt;
import soot.jimple.InvokeStmt;
import soot.jimple.Jimple;
import soot.jimple.NewExpr;
import soot.jimple.NullConstant;
import soot.jimple.ReturnStmt;
import soot.jimple.StringConstant;
import soot.jimple.interproc.ifds.IFDSTabulationProblem;
import soot.jimple.interproc.ifds.InterproceduralCFG;
import soot.jimple.interproc.ifds.problems.IFDSReachingDefinitions;
import soot.jimple.interproc.ifds.problems.UpdatableReachingDefinition;
import soot.jimple.interproc.ifds.solver.IFDSSolver;
import soot.jimple.interproc.ifds.template.JimpleBasedInterproceduralCFG;
import soot.jimple.interproc.incremental.UpdatableWrapper;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;

public class IFDSTestReachingDefinitions {

	/**
	 * Performs a generic test and calls the extension handler when it is complete.
	 * This method does not create indices for dynamic updates. Instead, updates are
	 * just propagated along the edges until a fix point is reached.
	 * @param handler The handler to call after finishing the generic information
	 * leakage analysis
	 * @param className The name of the test class to use
	 */
	private void performTestDirect(final ITestHandler<UpdatableReachingDefinition> handler, final String className) {
		soot.G.reset();
		handler.initialize();

		PackManager.v().getPack("wjtp").add(new Transform("wjtp.ifds", new SceneTransformer() {
			protected void internalTransform(String phaseName, @SuppressWarnings("rawtypes") Map options) {
				// Make sure to load the bodies of all methods in the old version so
				// that we can diff later
				for (SootClass sc : Scene.v().getApplicationClasses())
					for (SootMethod sm : sc.getMethods())
						sm.retrieveActiveBody();

				long timeBefore = System.nanoTime();
				System.out.println("Running IFDS on initial CFG...");
				
				InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg = new JimpleBasedInterproceduralCFG();
				IFDSTabulationProblem<UpdatableWrapper<Unit>, UpdatableReachingDefinition, UpdatableWrapper<SootMethod>,
							InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>>> problem =
						new IFDSReachingDefinitions(icfg);
				IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
							InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver =
						new IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
							InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>>(problem);	
				
				long nanoBeforeSolve = System.nanoTime();
				System.out.println("Running solver...");
				solver.solve(false);
				System.out.println("Solver done in " + (System.nanoTime() - nanoBeforeSolve) / 10E9 + " seconds.");
				
				if (className.contains("junit")) {
					SootMethod meth = Scene.v().getMainClass().getMethodByName("runMainAndExit"); 
					UpdatableWrapper<Unit> ret = icfg.wrapWeak(meth.getActiveBody().getUnits().getPredOf
							(meth.getActiveBody().getUnits().getLast()));
					Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(ret);
					checkInitialLeaks(results);
				}
				
				if (handler != null) {
					handler.extendBasicTest(icfg, solver);
					for (int i = 0; i < handler.getPhaseCount(); i++) {
						long nanoBeforePatch = System.nanoTime();
						handler.patchGraph(i);
						System.out.println("Graph patched in " + (System.nanoTime() - nanoBeforePatch) / 10E9 + " seconds.");
						
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
				"-exclude", "java",
				className } );
	}

	/**
	 * Performs a generic test and calls the extension handler when it is complete.
	 * This method runs the analysis once, then modifies the program and afterwards
	 * dynamically updates the analysis results.
	 * @param handler The handler to call after finishing the generic information
	 * leakage analysis
	 * @param className The name of the test class to use
	 */
	private void performTestRerun(final ITestHandler<UpdatableReachingDefinition> handler, final String className) {
		soot.G.reset();
		handler.initialize();

		PackManager.v().getPack("wjtp").add(new Transform("wjtp.ifds", new SceneTransformer() {
			protected void internalTransform(String phaseName, @SuppressWarnings("rawtypes") Map options) {
				// Make sure to load the bodies of all methods in the old version so
				// that we can diff later
				for (SootClass sc : Scene.v().getApplicationClasses())
					for (SootMethod sm : sc.getMethods())
						sm.retrieveActiveBody();

				long timeBefore = System.nanoTime();				
				System.out.println("Running IFDS on initial CFG...");
				
				InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg = new JimpleBasedInterproceduralCFG();
				IFDSTabulationProblem<UpdatableWrapper<Unit>, UpdatableReachingDefinition, UpdatableWrapper<SootMethod>,
							InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>>> problem =
					new IFDSReachingDefinitions(icfg);
				IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
							InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver =
					new IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
							InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>>(problem);	

				long nanoBeforeSolve = System.nanoTime();
				System.out.println("Running solver...");
				solver.solve(false);
				System.out.println("Solver done in " + (System.nanoTime() - nanoBeforeSolve) / 10E9 + " seconds.");
				
				if (className.contains("junit")) {
					SootMethod meth = Scene.v().getMainClass().getMethodByName("runMainAndExit"); 
					UpdatableWrapper<Unit> ret = icfg.wrapWeak(meth.getActiveBody().getUnits().getPredOf
							(meth.getActiveBody().getUnits().getLast()));
					Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(ret);
					checkInitialLeaks(results);
				}
				
				if (handler != null) {
					handler.extendBasicTest(icfg, solver);
					for (int i = 0; i < handler.getPhaseCount(); i++) {
						long nanoBeforePatch = System.nanoTime();
						handler.patchGraph(i);
						System.out.println("Graph patched in " + (System.nanoTime() - nanoBeforePatch) / 10E9 + " seconds.");

						IFDSTabulationProblem<UpdatableWrapper<Unit>, UpdatableReachingDefinition, UpdatableWrapper<SootMethod>,
									InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>>> problem2 =
							new IFDSReachingDefinitions(icfg = new JimpleBasedInterproceduralCFG());
						IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
									InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver2 =
							new IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
									InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>>(problem2);	
						
						solver2.solve(false);
						if (handler != null)
							handler.performExtendedTest(icfg, solver2, i);
					}
				}
				System.out.println("Time elapsed: " + ((double) (System.nanoTime() - timeBefore)) / 1E9);
			}
		}));

		try {
			assert Class.forName(className) != null;
		} catch (ClassNotFoundException e) {
			Assert.fail(e.getMessage());
		}
		String udir = System.getProperty("user.dir");
		soot.Main.v().run(new String[] {
				"-W",
				"-main-class", className,
				"-process-path", udir + File.separator + "test",
				"-src-prec", "java",
				"-pp",
				"-cp", "junit-4.10.jar",
				"-no-bodies-for-excluded",
				"-exclude", "java",
				className } );
	}

	/**
	 * Performs a generic test and calls the extension handler when it is complete.
	 * This method runs the analysis once, then modifies the program and afterwards
	 * dynamically updates the analysis results using strongly connected components
	 * (Yilgrim's method).
	 * @param handler The handler to call after finishing the generic information
	 * leakage analysis
	 * @param className The name of the test class to use
	 */
	private void performTestUpdate(final ITestHandler<UpdatableReachingDefinition> handler, final String className) {
		soot.G.reset();
		handler.initialize();

		PackManager.v().getPack("wjtp").add(new Transform("wjtp.ifds", new SceneTransformer() {
			protected void internalTransform(String phaseName, @SuppressWarnings("rawtypes") Map options) {
				// Make sure to load the bodies of all methods in the old version so
				// that we can diff later
				for (SootClass sc : Scene.v().getApplicationClasses())
					for (SootMethod sm : sc.getMethods())
						sm.retrieveActiveBody();

				long timeBefore = System.nanoTime();
				System.out.println("Running IFDS on initial CFG...");

				InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg = new JimpleBasedInterproceduralCFG();
				IFDSTabulationProblem<UpdatableWrapper<Unit>, UpdatableReachingDefinition, UpdatableWrapper<SootMethod>,
							InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>>> problem =
					new IFDSReachingDefinitions(icfg);
				IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
							InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver =
					new IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
							InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>>(problem);	

				long nanoBeforeSolve = System.nanoTime();
				System.out.println("Running solver...");
				solver.solve(true);
				System.out.println("Solver done in " + (System.nanoTime() - nanoBeforeSolve) / 10E9 + " seconds.");
				
				if (className.contains("junit")) {
					SootMethod meth = Scene.v().getMainClass().getMethodByName("runMainAndExit"); 
					UpdatableWrapper<Unit> ret = icfg.wrapWeak(meth.getActiveBody().getUnits().getPredOf
							(meth.getActiveBody().getUnits().getLast()));
					Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(ret);
					checkInitialLeaks(results);
				}
				
				if (handler != null) {
					handler.extendBasicTest(icfg, solver);
					for (int i = 0; i < handler.getPhaseCount(); i++) {
						long nanoBeforePatch = System.nanoTime();
						handler.patchGraph(i);
						System.out.println("Graph patched in " + (System.nanoTime() - nanoBeforePatch) / 10E9 + " seconds.");

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
				"-exclude", "java",
				className } );
	}

	protected void checkInitialLeaks(Set<UpdatableReachingDefinition> results) {
		boolean found = false;
		for (UpdatableReachingDefinition p : results) {
			if (p.getContents().getO1() instanceof Local && ((Local) p.getContents().getO1()).getName().equals("$r2"))
				for (DefinitionStmt def : p.getContents().getO2())
					if (def.toString().equals(("$r2 = new org.junit.runner.JUnitCore"))) {
						found = true;
						break;
					}
			if (found) break;
		}
		Assert.assertTrue(found);
	}

	private ITestHandler<UpdatableReachingDefinition> ITestHandlerSimpleTest() {
		return new ITestHandler<UpdatableReachingDefinition>() {
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
			}
			
			@Override
			public void patchGraph(int phase) {
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMainAndExit"); 
				Unit ret = meth.getActiveBody().getUnits().getPredOf
						(meth.getActiveBody().getUnits().getLast());
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				checkInitialLeaks(results);
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
	 * Performs a simple analysis without any updates to the program graph
	 */
	@Test
	public void simpleTestJU_Rerun() {
		System.out.println("Starting simpleTestJU_Rerun...");
		performTestRerun(ITestHandlerSimpleTest(), "org.junit.runner.JUnitCore");
		System.out.println("simpleTestJU_Rerun finished.");
	}
	
	/**
	 * Performs a simple analysis without any updates to the program graph
	 */
	@Test
	public void simpleTestJU_Propagate() {
		System.out.println("Starting simpleTestJU_Propagate...");
		performTestDirect(ITestHandlerSimpleTest(), "org.junit.runner.JUnitCore");
		System.out.println("simpleTestJU_Propagate finished.");
	}

	/**
	 * Performs a simple analysis without any updates to the program graph
	 */
	@Test
	public void simpleTestJU_Update() {
		System.out.println("Starting simpleTestJU_Update...");
		performTestUpdate(ITestHandlerSimpleTest(), "org.junit.runner.JUnitCore");
		System.out.println("simpleTestJU_Update finished.");
	}

	private ITestHandler<UpdatableReachingDefinition> ITestHandlerAddVarTest() {
		return new ITestHandler<UpdatableReachingDefinition>() {
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMainAndExit");
				Unit ret = meth.getActiveBody().getUnits().getPredOf
						(meth.getActiveBody().getUnits().getLast());
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));

				System.out.println("---OLD RESULTS");
				for (UpdatableReachingDefinition urd : results)
					System.out.println(urd);
			}
			
			@Override
			public void patchGraph(int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMainAndExit");
				Local newLocal = Jimple.v().newLocal("foo", RefType.v("java.lang.String"));
				meth.getActiveBody().getLocals().add(newLocal);

				Unit ret = meth.getActiveBody().getUnits().getPredOf
						(meth.getActiveBody().getUnits().getLast());
				
				AssignStmt assignStmt = Jimple.v().newAssignStmt(newLocal, StringConstant.v("Hello World"));
				meth.getActiveBody().getUnits().insertBefore(assignStmt, ret);
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMainAndExit");
				Unit ret = meth.getActiveBody().getUnits().getPredOf
						(meth.getActiveBody().getUnits().getLast());
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				checkInitialLeaks(results);
				
				System.out.println("---NEW RESULTS");
				for (UpdatableReachingDefinition urd : results)
					System.out.println(urd);

				boolean found = false;
				for (UpdatableReachingDefinition p : results) {
					if (p.getContents().getO1() instanceof Local
							&& ((Local) p.getContents().getO1()).getName().equals("foo")) {
						for (DefinitionStmt def : p.getContents().getO2())
							if (def.toString().equals(("foo = \"Hello World\""))) {
								found = true;
								break;
							}
					}
					if (found) break;
				}
				Assert.assertTrue(found);
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
	 * Performs a simple analysis, then adds a local with an assignment
	 */
	@Test
	public void addLocalJU_Rerun() {
		System.out.println("Starting addLocalJU_Rerun...");
		performTestRerun(ITestHandlerAddVarTest(), "org.junit.runner.JUnitCore");
		System.out.println("addLocalJU_Rerun finished.");
	}
	
	/**
	 * Performs a simple analysis, then adds a local with an assignment
	 */
	@Test
	public void addLocalJU_Propagate() {
		System.out.println("Starting addLocalJU_Propagate...");
		performTestDirect(ITestHandlerAddVarTest(), "org.junit.runner.JUnitCore");
		System.out.println("addLocalJU_Propagate finished.");
	}

	/**
	 * Performs a simple analysis, then adds a local with an assignment
	 */
	@Test
	public void addLocalJU_Update() {
		System.out.println("Starting addLocalJU_Propagate...");
		performTestUpdate(ITestHandlerAddVarTest(), "org.junit.runner.JUnitCore");
		System.out.println("addLocalJU_Propagate finished.");
	}

	private ITestHandler<UpdatableReachingDefinition> ITestHandlerRedefineVarTest() {
		return new ITestHandler<UpdatableReachingDefinition>() {
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
			}
			
			@Override
			public void patchGraph(int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMain");

				Local listener = null;
				for (Local l : meth.getActiveBody().getLocals())
					if (l.getName().equals("$r27")) {
						listener = l;
						break;
					}
				
				Unit ret = meth.getActiveBody().getUnits().getPredOf
						(meth.getActiveBody().getUnits().getLast());

				NewExpr newExpr = Jimple.v().newNewExpr(RefType.v("org.junit.runner.notification.RunListener"));
				AssignStmt initStmt = Jimple.v().newAssignStmt(listener, newExpr);
				meth.getActiveBody().getUnits().insertBefore(initStmt, ret);

				InvokeStmt invokeInitStmt = Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr(listener,
						RefType.v("org.junit.runner.notification.RunListener").getSootClass().getMethodByName("<init>").makeRef()));
				meth.getActiveBody().getUnits().insertAfter(invokeInitStmt, initStmt);
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMain");
				Unit ret = meth.getActiveBody().getUnits().getPredOf
						(meth.getActiveBody().getUnits().getLast());
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				
				boolean found = false;
				for (UpdatableReachingDefinition p : results) {
					if (p.getContents().getO1() instanceof Local
							&& ((Local) p.getContents().getO1()).getName().equals("$r27")) {
						for (DefinitionStmt def : p.getContents().getO2())
							if (def.toString().equals(("$r27 = new org.junit.runner.notification.RunListener"))) {
								found = true;
								break;
							}
					}
					if (found) break;
				}
				Assert.assertTrue(found);
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
	 * Performs a simple analysis, then overwrites a local variable
	 */
	@Test
	public void redefineVarJU_Rerun() {
		System.out.println("Starting redefineVarJU_Rerun...");
		performTestRerun(ITestHandlerRedefineVarTest(), "org.junit.runner.JUnitCore");
		System.out.println("redefineVarJU_Rerun finished.");
	}
	
	/**
	 * Performs a simple analysis, then overwrites a local variable
	 */
	@Test
	public void redefineVarJU_Propagate() {
		System.out.println("Starting redefineVarJU_Propagate...");
		performTestDirect(ITestHandlerRedefineVarTest(), "org.junit.runner.JUnitCore");
		System.out.println("redefineVarJU_Propagate finished.");
	}

	/**
	 * Performs a simple analysis, then overwrites a local variable
	 */
	@Test
	public void redefineVarJU_Update() {
		System.out.println("Starting redefineVarJU_Update...");
		performTestUpdate(ITestHandlerRedefineVarTest(), "org.junit.runner.JUnitCore");
		System.out.println("redefineVarJU_Update finished.");
	}

	private ITestHandler<UpdatableReachingDefinition> ITestHandlerRemoveStmtTest() {
		return new ITestHandler<UpdatableReachingDefinition>() {

			Set<UpdatableReachingDefinition> original = new HashSet<UpdatableReachingDefinition>();
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMain");
				Unit ret = meth.getActiveBody().getUnits().getPredOf
						(meth.getActiveBody().getUnits().getLast());
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				for (UpdatableReachingDefinition p : results)
					original.add(p);
			}
			
			@Override
			public void patchGraph(int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMain");

				boolean found = false;
				for (Unit u : meth.getActiveBody().getUnits())
					if (u.toString().equals("virtualinvoke r0.<org.junit.runner.JUnitCore: void " +
							"addListener(org.junit.runner.notification.RunListener)>(r28)")) {
						meth.getActiveBody().getUnits().remove(u);

						Edge edge = Scene.v().getCallGraph().findEdge(u, Scene.v().getMethod
								("<org.junit.runner.JUnitCore: void "
										+ "addListener(org.junit.runner.notification.RunListener)>"));
						Scene.v().getCallGraph().removeEdge(edge);
						
						found = true;
						break;
					}
				Assert.assertTrue(found);
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMain");
				Unit ret = meth.getActiveBody().getUnits().getPredOf
						(meth.getActiveBody().getUnits().getLast());
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				
				// The next line does not hold when running with Java libraries as
				// a whole chunk of code is no longer reachable with removes many
				// candidates from the points-to analysis, e.g. for the interator
				// invocation in r34. Therefore, we only check a weaker condition.
				for (UpdatableReachingDefinition pr : results) {
					boolean found = false;
					for (UpdatableReachingDefinition po : original)
						if (po.getContents().equals(pr.getContents())) {
							found = true;
							break;
						}
					Assert.assertTrue(found);
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
	 * Performs a simple analysis, then removes a non-assignment statement
	 */
	@Test
	public void removeStmtJU_Rerun() {
		System.out.println("Starting removeStmtJU_Rerun...");
		performTestRerun(ITestHandlerRemoveStmtTest(), "org.junit.runner.JUnitCore");
		System.out.println("removeStmtJU_Rerun finished.");
	}
	
	/**
	 * Performs a simple analysis, then removes a non-assignment statement
	 */
	@Test
	public void removeStmtJU_Propagate() {
		System.out.println("Starting removeStmtJU_Propagate...");
		performTestDirect(ITestHandlerRemoveStmtTest(), "org.junit.runner.JUnitCore");
		System.out.println("removeStmtJU_Propagate finished.");
	}

	/**
	 * Performs a simple analysis, then removes a non-assignment statement
	 */
	@Test
	public void removeStmtJU_Update() {
		System.out.println("Starting removeStmtJU_Update...");
		performTestUpdate(ITestHandlerRemoveStmtTest(), "org.junit.runner.JUnitCore");
		System.out.println("removeStmtJU_Update finished.");
	}

	private ITestHandler<UpdatableReachingDefinition> ITestHandlerRemoveAssignmentTest() {
		return new ITestHandler<UpdatableReachingDefinition>() {

			Set<UpdatableReachingDefinition> original = new HashSet<UpdatableReachingDefinition>();
			Set<UpdatableReachingDefinition> originalHN = new HashSet<UpdatableReachingDefinition>();
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
				
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMain");
				Unit ret = meth.getActiveBody().getUnits().getPredOf
						(meth.getActiveBody().getUnits().getLast());
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				System.out.println("----OLD RESULTS");
				for (UpdatableReachingDefinition p : results) {
					original.add(p);
					System.out.println(p);
				}
				
				Unit hasNext = null;
				for (Unit u : meth.getActiveBody().getUnits())
					if (u.toString().contains("boolean hasNext()")) {
						hasNext = u;
						break;
					}
				Assert.assertNotNull(hasNext);
				
				System.out.println("----OLD RESULTS HN");
				solver.ifdsResultsAt(icfg.wrapWeak(hasNext));
				for (UpdatableReachingDefinition p : results) {
					originalHN.add(p);
					System.out.println(p);
				}
			}
			
			@Override
			public void patchGraph(int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMain");

				Value leftSide = null;
				Unit oldAssignment = null;
				Unit oldInit = null;
				for (Unit u : meth.getActiveBody().getUnits())
					if (u instanceof AssignStmt) {
						if (u.toString().equals("$r27 = new org.junit.internal.TextListener")) {
							leftSide = ((AssignStmt) u).getLeftOp();
							oldAssignment = u;
						}
					}
					else if (u instanceof InvokeStmt && u.toString().contains
							("specialinvoke $r27.<org.junit.internal.TextListener: void <init>(org.junit.internal.JUnitSystem)>"))
						oldInit = u;
				Assert.assertNotNull(oldAssignment);
				Assert.assertNotNull(oldInit);
				Assert.assertNotNull(leftSide);

				AssignStmt assignStmt = Jimple.v().newAssignStmt(leftSide, NullConstant.v());
				meth.getActiveBody().getUnits().insertBefore(assignStmt, oldAssignment);
				meth.getActiveBody().getUnits().remove(oldAssignment);
				meth.getActiveBody().getUnits().remove(oldInit);
				
				Edge edge = Scene.v().getCallGraph().findEdge(oldInit, Scene.v().getMethod
						("<org.junit.internal.TextListener: void <init>(org.junit.internal.JUnitSystem)>"));
				Assert.assertNotNull(edge);
				Assert.assertTrue(Scene.v().getCallGraph().removeEdge(edge));
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMain");
				
				Unit hasNext = null;
				for (Unit u : meth.getActiveBody().getUnits())
					if (u.toString().contains("boolean hasNext()")) {
						hasNext = u;
						break;
					}
				Assert.assertNotNull(hasNext);
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(hasNext));
				
				System.out.println("----NEW RESULTS HN");
				for (UpdatableReachingDefinition pr : results)
					System.out.println(pr);
				Assert.assertEquals(originalHN.size(), results.size());

				Unit ret = meth.getActiveBody().getUnits().getPredOf
						(meth.getActiveBody().getUnits().getLast());
				results = solver.ifdsResultsAt(icfg.wrapWeak(ret));

				System.out.println("----NEW RESULTS");
				for (UpdatableReachingDefinition pr : results)
					System.out.println(pr);
				
				Assert.assertEquals(original.size(), results.size());
				for (UpdatableReachingDefinition pr : results) {
					boolean found = false;
					for (UpdatableReachingDefinition po : original)
						if (pr.getContents().equals(po.getContents())) {
							found = true;
							break;
						}
					if (!found) {
						System.out.println("FOOO");
						Assert.assertEquals("$r27", pr.getContents().getO1().toString());
						Assert.assertEquals("[$r27 = null]", pr.getContents().getO2().toString());
					}
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
	 * Performs a simple analysis, then removes an assignment statement
	 */
	@Test
	public void removeAssignmentJU_Rerun() {
		System.out.println("Starting removeAssignmentJU_Rerun...");
		performTestRerun(ITestHandlerRemoveAssignmentTest(), "org.junit.runner.JUnitCore");
		System.out.println("removeAssignmentJU_Rerun finished.");
	}
	
	/**
	 * Performs a simple analysis, then removes an assignment statement
	 */
	@Test
	public void removeAssignmentJU_Propagate() {
		System.out.println("Starting removeAssignmentJU_Propagate...");
		performTestDirect(ITestHandlerRemoveAssignmentTest(), "org.junit.runner.JUnitCore");
		System.out.println("removeAssignmentJU_Propagate finished.");
	}

	/**
	 * Performs a simple analysis, then removes an assignment statement
	 */
	@Test
	public void removeAssignmentJU_Update() {
		System.out.println("Starting removeAssignmentJU_Update...");
		performTestUpdate(ITestHandlerRemoveAssignmentTest(), "org.junit.runner.JUnitCore");
		System.out.println("removeAssignmentJU_Update finished.");
	}

	private ITestHandler<UpdatableReachingDefinition> ITestHandlerAddCallNoAssignmentTest() {
		return new ITestHandler<UpdatableReachingDefinition>() {

			Set<UpdatableReachingDefinition> original = new HashSet<UpdatableReachingDefinition>();
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMain");
				Unit ret = meth.getActiveBody().getUnits().getPredOf
						(meth.getActiveBody().getUnits().getLast());
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				for (UpdatableReachingDefinition p : results)
					original.add(p);
			}
			
			@Override
			public void patchGraph(int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMainAndExit");
				
				Local r0 = null; 
				Local r1 = null; 
				Local r2 = null;
				for (Local l : meth.getActiveBody().getLocals()) {
					if (l.getName().equals("r0"))
						r0 = l;
					else if (l.getName().equals("r1"))
						r1 = l;
					else if (l.getName().equals("$r2"))
						r2 = l;
				}
				Assert.assertNotNull(r0);
				Assert.assertNotNull(r1);
				Assert.assertNotNull(r2);
				
				SootMethod runMainMethod = meth.getDeclaringClass().getMethodByName("runMain");

				boolean found = false;
				for (Unit u : meth.getActiveBody().getUnits())
					if (u instanceof AssignStmt)
						if (u.toString().equals("r3 = virtualinvoke $r2.<org.junit.runner.JUnitCore: " +
								"org.junit.runner.Result runMain(org.junit.internal.JUnitSystem,java.lang.String[])>(r0, r1)")) {
							found = true;
							
							InvokeStmt invStmt = Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr
									(r2, runMainMethod.makeRef(), r0, r1));
							meth.getActiveBody().getUnits().insertAfter(invStmt, u);
							
							CallGraph cg = Scene.v().getCallGraph();
							Edge edge = new Edge(meth, invStmt, runMainMethod);
							cg.addEdge(edge);

							break;
						}
				Assert.assertTrue(found);
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMain");
				Unit ret = meth.getActiveBody().getUnits().getPredOf
						(meth.getActiveBody().getUnits().getLast());
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				
				Assert.assertEquals(original.size(), results.size());
				for (UpdatableReachingDefinition pr : results) {
					boolean found = false;
					for (UpdatableReachingDefinition po : original)
						if (pr.getContents().equals(po.getContents())) {
							found = true;
							break;
						}
					Assert.assertTrue(found);
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
	 * Performs a simple analysis, then adds a call without creating a new assignment
	 */
	@Test
	public void addCallNoAssignmentJU_Rerun() {
		System.out.println("Starting addCallNoAssignmentJU_Rerun...");
		performTestRerun(ITestHandlerAddCallNoAssignmentTest(), "org.junit.runner.JUnitCore");
		System.out.println("addCallNoAssignmentJU_Rerun finished.");
	}
	
	/**
	 * Performs a simple analysis, then adds a call without creating a new assignment
	 */
	@Test
	public void addCallNoAssignmentJU_Propagate() {
		System.out.println("Starting addCallNoAssignmentJU_Propagate...");
		performTestDirect(ITestHandlerAddCallNoAssignmentTest(), "org.junit.runner.JUnitCore");
		System.out.println("addCallNoAssignmentJU_Propagate finished.");
	}

	/**
	 * Performs a simple analysis, then adds a call without creating a new assignment
	 */
	@Test
	public void addCallNoAssignmentJU_Update() {
		System.out.println("Starting addCallNoAssignmentJU_Update...");
		performTestUpdate(ITestHandlerAddCallNoAssignmentTest(), "org.junit.runner.JUnitCore");
		System.out.println("addCallNoAssignmentJU_Update finished.");
	}

	private ITestHandler<UpdatableReachingDefinition> ITestHandlerAddCallAssignmentTest() {
		return new ITestHandler<UpdatableReachingDefinition>() {

			Set<UpdatableReachingDefinition> original = new HashSet<UpdatableReachingDefinition>();
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMain");
				Unit ret = meth.getActiveBody().getUnits().getPredOf
						(meth.getActiveBody().getUnits().getLast());
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				System.out.println("Original size: " + results.size());
				for (UpdatableReachingDefinition p : results)
					original.add(p);
			}
			
			@Override
			public void patchGraph(int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMainAndExit");
				SootMethod getVersionMethod = meth.getDeclaringClass().getMethodByName("getVersion");
				
				Local newLocal = Jimple.v().newLocal("ver", RefType.v("java.lang.String"));
				meth.getActiveBody().getLocals().add(newLocal);
				Local r2 = null;
				for (Local l : meth.getActiveBody().getLocals())
					if (l.getName().equals("$r2")) {
						r2 = l;
						break;
					}
				Assert.assertNotNull(r2);

				boolean found = false;
				for (Unit u : meth.getActiveBody().getUnits())
					if (u instanceof AssignStmt)
						if (u.toString().equals("r3 = virtualinvoke $r2.<org.junit.runner.JUnitCore: " +
								"org.junit.runner.Result runMain(org.junit.internal.JUnitSystem,java.lang.String[])>(r0, r1)")) {
							found = true;
							
							AssignStmt assignStmt = Jimple.v().newAssignStmt(newLocal,
									Jimple.v().newVirtualInvokeExpr(r2, getVersionMethod.makeRef()));
							meth.getActiveBody().getUnits().insertAfter(assignStmt, u);
							
							CallGraph cg = Scene.v().getCallGraph();
							Edge edge = new Edge(meth, assignStmt, getVersionMethod);
							cg.addEdge(edge);

							break;
						}
				Assert.assertTrue(found);
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMain");
				Unit ret = meth.getActiveBody().getUnits().getPredOf
						(meth.getActiveBody().getUnits().getLast());
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				
				for (UpdatableReachingDefinition p : original)
					if (!results.contains(p))
						System.out.println("Missing: " + p);

				Assert.assertEquals(original.size(), results.size());
				for (UpdatableReachingDefinition pr : results) {
					boolean found = false;
					for (UpdatableReachingDefinition po : original)
						if (pr.getContents().equals(po.getContents())) {
							found = true;
							break;
						}
					Assert.assertTrue(found);
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
	 * Performs a simple analysis, then adds a call inside a new assignment
	 */
	@Test
	public void addCallAssignmentJU_Rerun() {
		System.out.println("Starting addCallAssignmentJU_Rerun...");
		performTestRerun(ITestHandlerAddCallAssignmentTest(), "org.junit.runner.JUnitCore");
		System.out.println("addCallAssignmentJU_Rerun finished.");
	}
	
	/**
	 * Performs a simple analysis, then adds a call inside a new assignment
	 */
	@Test
	public void addCallAssignmentJU_Propagate() {
		System.out.println("Starting addCallAssignmentJU_Propagate...");
		performTestDirect(ITestHandlerAddCallAssignmentTest(), "org.junit.runner.JUnitCore");
		System.out.println("addCallAssignmentJU_Propagate finished.");
	}

	/**
	 * Performs a simple analysis, then adds a call inside a new assignment
	 */
	@Test
	public void addCallAssignmentJU_Update() {
		System.out.println("Starting addCallAssignmentJU_Update...");
		performTestUpdate(ITestHandlerAddCallAssignmentTest(), "org.junit.runner.JUnitCore");
		System.out.println("addCallAssignmentJU_Update finished.");
	}

	private ITestHandler<UpdatableReachingDefinition> ITestHandlerRemoveStmtFromLoopTest() {
		return new ITestHandler<UpdatableReachingDefinition>() {

			Set<UpdatableReachingDefinition> original = new HashSet<UpdatableReachingDefinition>();
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMain");
				Unit ret = meth.getActiveBody().getUnits().getPredOf
						(meth.getActiveBody().getUnits().getLast());
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				System.out.println("---OLD RESULTS");
				for (UpdatableReachingDefinition p : results) {
					original.add(p);
					System.out.println(p);
				}
			}
			
			@Override
			public void patchGraph(int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMain");

				Local r18 = null;
				for (Local l : meth.getActiveBody().getLocals())
					if (l.getName().equals("$r18")) {
						r18 = l;
						break;
					}

				Local newLocal = Jimple.v().newLocal("foo", RefType.v("java.lang.Class"));
				meth.getActiveBody().getLocals().add(newLocal);

				boolean found = false;
				for (Unit u : meth.getActiveBody().getUnits())
					if (u.toString().equals("interfaceinvoke r4.<java.util.List: boolean add(java.lang.Object)>($r18)")) {
						AssignStmt assignStmt = Jimple.v().newAssignStmt(newLocal, r18);
						meth.getActiveBody().getUnits().insertBefore(assignStmt, u);
						
						Edge edge = Scene.v().getCallGraph().findEdge(u, Scene.v().getMethod
								("<java.util.ArrayList: boolean add(java.lang.Object)>"));
						Scene.v().getCallGraph().removeEdge(edge);
						meth.getActiveBody().getUnits().remove(u);

						found = true;
						break;
					}
				Assert.assertTrue(found);
				
				// Print the last value of r18 to the console
				SootMethod getNameMethod = Scene.v().getSootClass("java.lang.Class").getMethodByName("getName");
				Assert.assertNotNull(getNameMethod);
				InvokeStmt getNameStmt = Jimple.v().newInvokeStmt
						(Jimple.v().newVirtualInvokeExpr(r18, getNameMethod.makeRef()));
				found = false;
				for (Unit u : meth.getActiveBody().getUnits())
					if (u instanceof ReturnStmt) {
						meth.getActiveBody().getUnits().insertBefore(getNameStmt,
								meth.getActiveBody().getUnits().getPredOf(u));
						found = true;
						break;
					}
				Assert.assertTrue(found);
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMain");
				Unit ret = meth.getActiveBody().getUnits().getPredOf
						(meth.getActiveBody().getUnits().getLast());
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				
				System.out.println("---NEW RESULTS");
				for (UpdatableReachingDefinition p : results)
					System.out.println(p);

				for (UpdatableReachingDefinition po : original)
				{
					boolean found = false;
					for (UpdatableReachingDefinition pr : results)
						if (po.getContents().getO1().toString().equals(pr.getContents().getO1().toString()))
							if (po.getContents().getO2().toString().equals(pr.getContents().getO2().toString()))
								found = true;
						
					if (!found)
						System.out.println("Missing: " + po);
				}

				Assert.assertEquals(original.size() + 1, results.size());
				for (UpdatableReachingDefinition pr : results) {
					boolean found = false;
					for (UpdatableReachingDefinition po : original)
						if (pr.getContents().equals(po.getContents())) {
							found = true;
							break;
						}
					if (!found) {
						Assert.assertEquals("foo", pr.getContents().getO1().toString());
						Assert.assertEquals("[foo = $r18]", pr.getContents().getO2().toString());
					}
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
	 * Performs a simple analysis, then removes a call from a loop and adds an
	 * assignment to a new variable instead
	 */
	@Test
	public void removeStmtFromLoopJU_Rerun() {
		System.out.println("Starting removeStmtFromLoopJU_Rerun...");
		performTestRerun(ITestHandlerRemoveStmtFromLoopTest(), "org.junit.runner.JUnitCore");
		System.out.println("removeStmtFromLoopJU_Rerun finished.");
	}
	
	/**
	 * Performs a simple analysis, then removes a call from a loop and adds an
	 * assignment to a new variable instead
	 */
	@Test
	public void removeStmtFromLoopJU_Propagate() {
		System.out.println("Starting removeStmtFromLoopJU_Propagate...");
		performTestDirect(ITestHandlerRemoveStmtFromLoopTest(), "org.junit.runner.JUnitCore");
		System.out.println("removeStmtFromLoopJU_Propagate finished.");
	}

	/**
	 * Performs a simple analysis, then removes a call from a loop and adds an
	 * assignment to a new variable instead
	 */
	@Test
	public void removeStmtFromLoopJU_Update() {
		System.out.println("Starting removeStmtFromLoopJU_Update...");
		performTestUpdate(ITestHandlerRemoveStmtFromLoopTest(), "org.junit.runner.JUnitCore");
		System.out.println("removeStmtFromLoopJU_Update finished.");
	}

	private ITestHandler<UpdatableReachingDefinition> ITestHandlerRedefineReturnTest() {
		return new ITestHandler<UpdatableReachingDefinition>() {

			Set<UpdatableReachingDefinition> original = new HashSet<UpdatableReachingDefinition>();
			Set<UpdatableReachingDefinition> originalRet = new HashSet<UpdatableReachingDefinition>();
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMainAndExit");
				Unit ret = meth.getActiveBody().getUnits().getPredOf
						(meth.getActiveBody().getUnits().getLast());
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				
				System.out.println("----------------\nORIGINAL RESULTS\n----------------");
				for (UpdatableReachingDefinition p : results) {
					original.add(p);
					System.out.println(p);
				}

				meth = Scene.v().getMainClass().getMethodByName("runMain");
				ret = meth.getActiveBody().getUnits().getLast();
				results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				
				System.out.println("----------------\nORIGINAL RESULTS AT RESULTS\n----------------");
				for (UpdatableReachingDefinition p : results) {
					originalRet.add(p);
					System.out.println(p);
				}
			}
			
			@Override
			public void patchGraph(int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMain");
				
				Local r32 = null;
				for (Local l : meth.getActiveBody().getLocals())
					if (l.getName().equals("r32")) {
						r32 = l;
						break;
					}
				Assert.assertNotNull(r32);

				AssignStmt assignStmt = Jimple.v().newAssignStmt(r32, NullConstant.v());
				ReturnStmt ret = null;
				for (Unit u : meth.getActiveBody().getUnits()) {
					if (u instanceof ReturnStmt) {
						u.redirectJumpsToThisTo(assignStmt);
						meth.getActiveBody().getUnits().insertBefore(assignStmt, u);
						ret = (ReturnStmt) u;
						break;
					}
				}
				Assert.assertNotNull(ret);
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("runMain");
				Unit ret = meth.getActiveBody().getUnits().getLast();
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				
				System.out.println("----------------\nNEW RESULTS AT RETURN\n----------------");
				for (UpdatableReachingDefinition p : results)
					System.out.println(p);

				Assert.assertEquals(originalRet.size(), results.size());
				for (UpdatableReachingDefinition pr : results) {
					boolean found = false;
					for (UpdatableReachingDefinition po : originalRet)
						if (pr.getContents().equals(po.getContents())) {
							found = true;
							break;
						}
					if (!found) {
						Assert.assertEquals("r32", pr.getContents().getO1().toString());
						Assert.assertEquals("[r32 = null]", pr.getContents().getO2().toString());
					}
				}

				meth = Scene.v().getMainClass().getMethodByName("runMainAndExit");
				ret = meth.getActiveBody().getUnits().getPredOf
						(meth.getActiveBody().getUnits().getLast());
				results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				
				System.out.println("----------------\nNEW RESULTS\n----------------");
				for (UpdatableReachingDefinition p : results)
					System.out.println(p);

				Assert.assertEquals(original.size(), results.size());
				for (UpdatableReachingDefinition pr : results) {
					boolean found = false;
					for (UpdatableReachingDefinition po : original)
						if (pr.getContents().equals(po.getContents())) {
							found = true;
							break;
						}
					if (!found) {
						Assert.assertEquals("r3", pr.getContents().getO1().toString());
						Assert.assertEquals("[r32 = null]", pr.getContents().getO2().toString());
					}
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
	 * Performs a simple analysis, then overwrites the return value of a called
	 * function and checks whether it is returned correctly
	 */
	@Test
	public void redefineReturnJU_Rerun() {
		System.out.println("Starting redefineReturnJU_Rerun...");
		performTestRerun(ITestHandlerRedefineReturnTest(), "org.junit.runner.JUnitCore");
		System.out.println("redefineReturnJU_Rerun finished.");
	}
	
	/**
	 * Performs a simple analysis, then overwrites the return value of a called
	 * function and checks whether it is returned correctly
	 */
	@Test
	public void redefineReturnJU_Propagate() {
		System.out.println("Starting redefineReturnJU_Propagate...");
		performTestDirect(ITestHandlerRedefineReturnTest(), "org.junit.runner.JUnitCore");
		System.out.println("redefineReturnJU_Propagate finished.");
	}

	/**
	 * Performs a simple analysis, then overwrites the return value of a called
	 * function and checks whether it is returned correctly
	 */
	@Test
	public void redefineReturnJU_Update() {
		System.out.println("Starting redefineReturnJU_Update...");
		performTestUpdate(ITestHandlerRedefineReturnTest(), "org.junit.runner.JUnitCore");
		System.out.println("redefineReturnJU_Update finished.");
	}

	private ITestHandler<UpdatableReachingDefinition> ITestHandlerExitTest() {
		return new ITestHandler<UpdatableReachingDefinition>() {

			Set<UpdatableReachingDefinition> original = new HashSet<UpdatableReachingDefinition>();
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("main");
				Unit ret = meth.getActiveBody().getUnits().getLast();
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				for (UpdatableReachingDefinition p : results)
					original.add(p);

				for (Unit u : meth.getActiveBody().getUnits())
					System.out.println(u);

				System.out.println("----------------\nOld results:\n------------------");
				for (UpdatableReachingDefinition urd : results)
					System.out.println(urd);
			}
			
			@Override
			public void patchGraph(int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("a");

				boolean found = false;
				for (Unit u : meth.getActiveBody().getUnits())
					if (u instanceof InvokeStmt) {
						InvokeStmt inv = (InvokeStmt) u;
						if (inv.getInvokeExpr().toString().contains("println")) {
							meth.getActiveBody().getUnits().remove(u);
							
							Edge edge = Scene.v().getCallGraph().findEdge
								(u, Scene.v().getMainClass().getMethodByName("println"));
							Scene.v().getCallGraph().removeEdge(edge);
							
							found = true;
							break;
						}
					}
				Assert.assertTrue(found);
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("main");
				Unit ret = meth.getActiveBody().getUnits().getLast();
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				
				System.out.println("----------------\nNew results:\n------------------");
				for (UpdatableReachingDefinition urd : results)
					System.out.println(urd);
				
				Assert.assertEquals(original.size(), results.size());
				for (UpdatableReachingDefinition pr : results) {
					boolean found = false;
					for (UpdatableReachingDefinition po : original)
						if (pr.getContents().equals(po.getContents())) {
							found = true;
							break;
						}
					Assert.assertTrue(found);
				}

				for (Unit u : meth.getActiveBody().getUnits())
					System.out.println(u);
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
	 * Deletes a statement from a called function and checks whether propagation
	 * still works in the caller afterwards.
	 */
	@Test
	public void exitTestJU_Rerun() {
		System.out.println("Starting exitTestJU_Rerun...");
		performTestRerun(ITestHandlerExitTest(), "ExitTest");
		System.out.println("exitTestJU_Rerun finished.");
	}
	
	/**
	 * Deletes a statement from a called function and checks whether propagation
	 * still works in the caller afterwards.
	 */
	@Test
	public void exitTestJU_Propagate() {
		System.out.println("Starting exitTestJU_Propagate...");
		performTestDirect(ITestHandlerExitTest(), "ExitTest");
		System.out.println("exitTestJU_Propagate finished.");
	}

	/**
	 * Deletes a statement from a called function and checks whether propagation
	 * still works in the caller afterwards.
	 */
	@Test
	public void exitTestJU_Update() {
		System.out.println("Starting exitTestJU_Update...");
		performTestUpdate(ITestHandlerExitTest(), "ExitTest");
		System.out.println("exitTestJU_Update finished.");
	}

	private ITestHandler<UpdatableReachingDefinition> ITestHandlerFuncTypeTest() {
		return new ITestHandler<UpdatableReachingDefinition>() {

			Set<UpdatableReachingDefinition> original = new HashSet<UpdatableReachingDefinition>();
			UpdatableReachingDefinition valueDef = null;
			int originalCount = 0;
			
			@Override
			public void initialize() {
			}
			
			private UpdatableReachingDefinition findDefinition
					(Set<UpdatableReachingDefinition> results, String varName) {
				for (UpdatableReachingDefinition p : results)
					if (p.getValue().toString().equals(varName))
						for (DefinitionStmt def : p.getDefinitions()) {
							String newVar = def.getRightOp().toString();
							if (newVar.equals(varName))	// make sure not to run in loops
								return null;
							UpdatableReachingDefinition urd = findDefinition(results, newVar);
							return urd == null ? p : urd;
						}
				return null;
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("main");
				Unit ret = meth.getActiveBody().getUnits().getLast();
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));

				valueDef = findDefinition(results, "o");
				Assert.assertNotNull(valueDef);
				Assert.assertEquals(1, valueDef.getDefinitions().size());
				Assert.assertTrue(valueDef.getDefinitions().iterator().next().toString().contains("new java.lang.Integer"));

				System.out.println("----------------\nOld results:\n------------------");
				for (UpdatableReachingDefinition urd : results)
					System.out.println(urd);
				originalCount = results.size();
				
				for (Unit u : meth.getActiveBody().getUnits()) {
					if (u.toString().equals("o = temp$1")) {
						results = solver.ifdsResultsAt(icfg.wrapWeak(u));
						System.out.println("----------------\nOld results at RETURN SITE:\n------------------");
						for (UpdatableReachingDefinition urd : results)
							System.out.println(urd);
						break;
					}
				}
				
				meth = Scene.v().getMainClass().getMethodByName("foo");
				ret = meth.getActiveBody().getUnits().getLast();
				results = solver.ifdsResultsAt(icfg.wrapWeak(ret));

				System.out.println("----------------\nOld results at RETURN:\n------------------");
				for (UpdatableReachingDefinition urd : results) {
					System.out.println(urd);
					original.add(urd);
				}
			}
			
			@Override
			public void patchGraph(int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("foo");

				Unit returnStmt = null;
				for (Unit u : meth.getActiveBody().getUnits())
					if (u instanceof ReturnStmt) {
						returnStmt = u;
						break;
					}
				Assert.assertNotNull(returnStmt);
				
				Local strLocal = Jimple.v().newLocal("x", RefType.v("java.lang.String"));
				meth.getActiveBody().getLocals().add(strLocal);
				
				NewExpr newExpr = Jimple.v().newNewExpr(RefType.v("java.lang.String"));
				AssignStmt initStmt = Jimple.v().newAssignStmt(strLocal, newExpr);
				meth.getActiveBody().getUnits().insertBefore(initStmt, returnStmt);

				List<StringConstant> args = Collections.singletonList(StringConstant.v("Hello World"));

				InvokeStmt invokeInitStmt = Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr(strLocal,
						RefType.v("java.lang.String").getSootClass().getMethod("void <init>(java.lang.String)").makeRef(), args));
				meth.getActiveBody().getUnits().insertAfter(invokeInitStmt, initStmt);
				
				ReturnStmt ret = Jimple.v().newReturnStmt(strLocal);
				meth.getActiveBody().getUnits().insertBefore(ret, returnStmt);
				meth.getActiveBody().getUnits().remove(returnStmt);
				
				System.out.println("----CHANGED METHOD");
				for (Unit u : meth.getActiveBody().getUnits())
					System.out.println(u);
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("foo");
				Unit ret = meth.getActiveBody().getUnits().getLast();
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));

				System.out.println("----------------\nNew results at RETURN:\n------------------");
				for (UpdatableReachingDefinition pr : results)
					System.out.println(pr);
				Assert.assertTrue(results.size() > original.size());
				
				meth = Scene.v().getMainClass().getMethodByName("main");
				
				System.out.println("---------------\nmain() method body\n---------------");
				for (Unit u : Scene.v().getMainClass().getMethodByName("foo").getActiveBody().getUnits())
					System.out.println(u);
								
				for (Unit u : meth.getActiveBody().getUnits()) {
					if (u.toString().equals("o = temp$1")) {
						results = solver.ifdsResultsAt(icfg.wrapWeak(u));
						System.out.println("----------------\nNew results at RETURN SITE:\n------------------");
						for (UpdatableReachingDefinition urd : results)
							System.out.println(urd);
						break;
					}
				}

				ret = meth.getActiveBody().getUnits().getLast();
				results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				System.out.println("----------------\nNew results:\n------------------");
				for (UpdatableReachingDefinition urd : results)
					System.out.println(urd);

				Assert.assertEquals(originalCount, results.size());

				UpdatableReachingDefinition urd = findDefinition(results, "o");
				Assert.assertNotNull(urd);
				Assert.assertEquals(1, urd.getDefinitions().size());
				Assert.assertTrue(urd.getDefinitions().iterator().next().toString().contains("new java.lang.String"));
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
	 * Deletes a statement from a called function and checks whether propagation
	 * still works in the caller afterwards.
	 */
	@Test
	public void funcTypeTestJU_Rerun() {
		System.out.println("Starting funcTypeTestJU_Rerun...");
		performTestRerun(ITestHandlerFuncTypeTest(), "FuncTypeTest");
		System.out.println("funcTypeTestJU_Rerun finished.");
	}
	
	/**
	 * Deletes a statement from a called function and checks whether propagation
	 * still works in the caller afterwards.
	 */
	@Test
	public void funcTypeTestJU_Propagate() {
		System.out.println("Starting funcTypeTestJU_Propagate...");
		performTestDirect(ITestHandlerFuncTypeTest(), "FuncTypeTest");
		System.out.println("funcTypeTestJU_Propagate finished.");
	}

	/**
	 * Deletes a statement from a called function and checks whether propagation
	 * still works in the caller afterwards.
	 */
	@Test
	public void funcTypeTestJU_Update() {
		System.out.println("Starting funcTypeTestJU_Update...");
		performTestUpdate(ITestHandlerFuncTypeTest(), "FuncTypeTest");
		System.out.println("funcTypeTestJU_Update finished.");
	}

	private ITestHandler<UpdatableReachingDefinition> ITestHandlerCallerChangeTest() {
		return new ITestHandler<UpdatableReachingDefinition>() {

			Set<UpdatableReachingDefinition> original = new HashSet<UpdatableReachingDefinition>();
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("main");
				Unit ret = meth.getActiveBody().getUnits().getLast();
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));

				System.out.println("----------------\nOld results at RETURN:\n------------------");
				for (UpdatableReachingDefinition urd : results) {
					System.out.println(urd);
					original.add(urd);
				}
			}
			
			@Override
			public void patchGraph(int phase) {
				SootMethod meth = Scene.v().getMainMethod();

				List<Unit> rmList = new ArrayList<Unit>();
				for (Unit u : meth.getActiveBody().getUnits())
					if (u instanceof AssignStmt || u instanceof InvokeStmt)
						if (u.toString().contains("temp$0") || u.toString().contains("temp$3") || u.toString().contains("(z)"))
							rmList.add(u);
				Assert.assertEquals(5, rmList.size());
				for (Unit u : rmList) {
					if (u instanceof InvokeStmt) {
						InvokeStmt inv = (InvokeStmt) u;
						Edge edge = Scene.v().getCallGraph().findEdge(u, inv.getInvokeExpr().getMethod());
						Assert.assertNotNull(edge);
						Scene.v().getCallGraph().removeEdge(edge);
					}
					meth.getActiveBody().getUnits().remove(u);
				}
				
				System.out.println("----CHANGED METHOD");
				for (Unit u : meth.getActiveBody().getUnits())
					System.out.println(u);
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("main");
				Unit ret = meth.getActiveBody().getUnits().getLast();
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				
				System.out.println("---------------\nmain() method body\n---------------");
				for (Unit u : meth.getActiveBody().getUnits())
					System.out.println(u);
				
				System.out.println("----------------\nNew results:\n------------------");
				for (UpdatableReachingDefinition urd : results)
					System.out.println(urd);
				
				Assert.assertEquals(original.size() - 3, results.size());
				for (UpdatableReachingDefinition pr : results) {
					boolean found = false;
					for (UpdatableReachingDefinition po : original)
						if (pr.getContents().equals(po.getContents())) {
							found = true;
							break;
						}
					Assert.assertTrue(found);
				}
				for (UpdatableReachingDefinition po : original) {
					boolean found = false;
					for (UpdatableReachingDefinition pr : results)
						if (pr.getContents().equals(po.getContents())) {
							found = true;
							break;
						}
					if (!found) {
						Assert.assertTrue(po.getValue().toString().equals("temp$0")
								|| po.getValue().toString().equals("temp$3")
								|| po.getValue().toString().equals("z"));
					}
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
	 * Deletes a statement in front of a method call and checks whether
	 * propagation still works - both straight and through the function
	 */
	@Test
	public void callerChangeTestJU_Rerun() {
		System.out.println("Starting callerChangeTestJU_Rerun...");
		performTestRerun(ITestHandlerCallerChangeTest(), "FuncTypeTest");
		System.out.println("callerChangeTestJU_Rerun finished.");
	}
	
	/**
	 * Deletes a statement in front of a method call and checks whether
	 * propagation still works - both straight and through the function
	 */
	@Test
	public void callerChangeTestJU_Propagate() {
		System.out.println("Starting callerChangeTestJU_Propagate...");
		performTestDirect(ITestHandlerCallerChangeTest(), "FuncTypeTest");
		System.out.println("callerChangeTestJU_Propagate finished.");
	}

	/**
	 * Deletes a statement in front of a method call and checks whether
	 * propagation still works - both straight and through the function
	 */
	@Test
	public void callerChangeTestJU_Update() {
		System.out.println("Starting callerChangeTestJU_Update...");
		performTestUpdate(ITestHandlerCallerChangeTest(), "FuncTypeTest");
		System.out.println("callerChangeTestJU_Update finished.");
	}

	private ITestHandler<UpdatableReachingDefinition> ITestHandlerDeleteInOutLoopTest(final boolean newType) {
		return new ITestHandler<UpdatableReachingDefinition>() {

			Set<UpdatableReachingDefinition> original = new HashSet<UpdatableReachingDefinition>();
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("main");
				Unit ret = meth.getActiveBody().getUnits().getLast();
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));

				System.out.println("----------------\nOld results at RETURN:\n------------------");
				for (UpdatableReachingDefinition urd : results) {
					System.out.println(urd);
					original.add(urd);
				}
			}
			
			@Override
			public void patchGraph(int phase) {
				SootMethod meth = Scene.v().getMainMethod();

				Unit initStmt = null;
				AssignStmt assignStmt = null;
				Unit printStmt = null;
				Unit inLoopStmt = null;
				for (Unit u : meth.getActiveBody().getUnits()) {
					if (u instanceof InvokeStmt) {
						if (u.toString().contains("specialinvoke temp$0.<java.lang.Integer: void <init>(int)>")) {							
							// Fix the call graph
							Edge edge = Scene.v().getCallGraph().findEdge(u, Scene.v().getMethod
									("<java.lang.Integer: void <init>(int)>"));
							Assert.assertNotNull(edge);
							Scene.v().getCallGraph().removeEdge(edge);
							
							// Remove the statement
							initStmt = u;
						}
						else if (u.toString().contains("<java.io.PrintStream: void println(java.lang.Object)>(i)")) {
							// Fix the call graph
							Edge edge = Scene.v().getCallGraph().findEdge(u, Scene.v().getMethod
									("<java.io.PrintStream: void println(java.lang.Object)>"));
							Assert.assertNotNull(edge);
							Scene.v().getCallGraph().removeEdge(edge);

							// Remove the statement
							printStmt = u;
						}
						else if (u.toString().contains("<java.io.PrintStream: void println(java.lang.String)>(\"ok\")")) {
							// Fix the call graph
							Edge edge = Scene.v().getCallGraph().findEdge(u, Scene.v().getMethod
									("<java.io.PrintStream: void println(java.lang.String)>"));
							Assert.assertNotNull(edge);
							Scene.v().getCallGraph().removeEdge(edge);

							// Remove the statement
							inLoopStmt = u;
						}
					}
					else if (u instanceof AssignStmt)
						if (u.toString().equals("temp$0 = new java.lang.Integer"))
							assignStmt = (AssignStmt) u;
				}
				Assert.assertNotNull(initStmt);
				Assert.assertNotNull(assignStmt);
				Assert.assertNotNull(printStmt);
				Assert.assertNotNull(inLoopStmt);
				
				if (newType) {
					AssignStmt assi = Jimple.v().newAssignStmt(assignStmt.getLeftOp(), Jimple.v().newNewExpr
							(RefType.v("java.lang.String")));
					meth.getActiveBody().getUnits().insertBefore(assi, assignStmt);
										
					InvokeStmt invokeInitStmt = Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr((Local) assignStmt.getLeftOp(),
							RefType.v("java.lang.String").getSootClass().getMethod("void <init>()").makeRef()));
					meth.getActiveBody().getUnits().insertAfter(invokeInitStmt, assi);
				}

				meth.getActiveBody().getUnits().remove(initStmt);
				meth.getActiveBody().getUnits().remove(assignStmt);
				meth.getActiveBody().getUnits().remove(printStmt);
				meth.getActiveBody().getUnits().remove(inLoopStmt);
				
				System.out.println("----CHANGED METHOD");
				for (Unit u : meth.getActiveBody().getUnits())
					System.out.println(u);
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				SootMethod meth = Scene.v().getMainClass().getMethodByName("main");
				Unit ret = meth.getActiveBody().getUnits().getLast();
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				
				System.out.println("---------------\nmain() method body\n---------------");
				for (Unit u : meth.getActiveBody().getUnits())
					System.out.println(u);
				
				System.out.println("----------------\nNew results:\n------------------");
				for (UpdatableReachingDefinition urd : results)
					System.out.println(urd);
				
				if (newType)
					Assert.assertEquals(original.size(), results.size());
				else
					Assert.assertEquals(original.size() - 1, results.size());
				boolean temp0def = false;
				for (UpdatableReachingDefinition pr : results) {
					boolean found = false;
					for (UpdatableReachingDefinition po : original)
						if (pr.getContents().equals(po.getContents())) {
							found = true;
							break;
						}
					if (!newType)
						Assert.assertTrue(found);
					else if (!found) {
						Assert.assertFalse(temp0def);
						Assert.assertTrue(pr.getValue().toString().equals("temp$0"));
						Assert.assertTrue(pr.getDefinitions().iterator().next().toString().contains("java.lang.String"));
						Assert.assertTrue(pr.getDefinitions().size() == 1);
						temp0def = true;
					}
				}
				for (UpdatableReachingDefinition po : original) {
					boolean found = false;
					for (UpdatableReachingDefinition pr : results)
						if (pr.getContents().equals(po.getContents())) {
							found = true;
							break;
						}
					if (!found)
						Assert.assertTrue(po.getValue().toString().equals("temp$0"));
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
	 * Deletes a statement before and in a loop and checks whether the new
	 * facts are propagated correctly.
	 */
	@Test
	public void deleteInOutLoopTestJU_Rerun() {
		System.out.println("Starting deleteInOutLoopTestJU_Rerun...");
		performTestRerun(ITestHandlerDeleteInOutLoopTest(false), "DeleteInOutLoopTest");
		System.out.println("deleteInOutLoopTestJU_Rerun finished.");
	}
	
	/**
	 * Deletes a statement before and in a loop and checks whether the new
	 * facts are propagated correctly.
	 */
	@Test
	public void deleteInOutLoopTestJU_Propagate() {
		System.out.println("Starting deleteInOutLoopTestJU_Propagate...");
		performTestDirect(ITestHandlerDeleteInOutLoopTest(false), "DeleteInOutLoopTest");
		System.out.println("deleteInOutLoopTestJU_Propagate finished.");
	}

	/**
	 * Deletes a statement before and in a loop and checks whether the new
	 * facts are propagated correctly.
	 */
	/*
	@Test
	public void deleteInOutLoopTestJU_Update() {
		System.out.println("Starting deleteInOutLoopTestJU_Update...");
		performTestUpdate(ITestHandlerDeleteInOutLoopTest(false), "DeleteInOutLoopTest");
		System.out.println("deleteInOutLoopTestJU_Update finished.");
	}
	*/

	/**
	 * Exchanges a variable assignment in front of a loop and removes a
	 * statement from inside the loop to trigger propagation from two sides.
	 */
	@Test
	public void changeInOutLoopTestJU_Rerun() {
		System.out.println("Starting changeInOutLoopTestJU_Rerun...");
		performTestRerun(ITestHandlerDeleteInOutLoopTest(true), "DeleteInOutLoopTest");
		System.out.println("changeInOutLoopTestJU_Rerun finished.");
	}
	
	/**
	 * Exchanges a variable assignment in front of a loop and removes a
	 * statement from inside the loop to trigger propagation from two sides.
	 */
	@Test
	public void changeInOutLoopTestJU_Propagate() {
		System.out.println("Starting changeInOutLoopTestJU_Propagate...");
		performTestDirect(ITestHandlerDeleteInOutLoopTest(true), "DeleteInOutLoopTest");
		System.out.println("changeInOutLoopTestJU_Propagate finished.");
	}

	/**
	 * Exchanges a variable assignment in front of a loop and removes a
	 * statement from inside the loop to trigger propagation from two sides.
	 */
	/*
	@Test
	public void changeInOutLoopTestJU_Update() {
		System.out.println("Starting changeInOutLoopTestJU_Update...");
		performTestUpdate(ITestHandlerDeleteInOutLoopTest(true), "DeleteInOutLoopTest");
		System.out.println("changeInOutLoopTestJU_Update finished.");
	}
	*/

	private ITestHandler<UpdatableReachingDefinition> ITestHandlerDeleteAssignmentInLoopTest() {
		return new ITestHandler<UpdatableReachingDefinition>() {

			Set<UpdatableReachingDefinition> original = new HashSet<UpdatableReachingDefinition>();
			
			@Override
			public void initialize() {
			}

			@Override
			public void extendBasicTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver) {
				SootMethod meth = Scene.v().getMainMethod();
				Unit ret = meth.getActiveBody().getUnits().getLast();
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));

				System.out.println("----------------\nOld results at RETURN:\n------------------");
				for (UpdatableReachingDefinition urd : results) {
					System.out.println(urd);
					original.add(urd);
				}
			}
			
			@Override
			public void patchGraph(int phase) {
				SootMethod meth = Scene.v().getMainMethod();

				boolean found = false;
				for (Unit u : meth.getActiveBody().getUnits())
					if (u instanceof AssignStmt) {
						AssignStmt assi = (AssignStmt) u;
						if (assi.getLeftOp().toString().equals("bar")
								&& !assi.getRightOp().toString().equals("0")) {							
							meth.getActiveBody().getUnits().remove(assi);
							found = true;
							break;
						}
				}
				Assert.assertTrue(found);

				System.out.println("----CHANGED METHOD");
				for (Unit u : meth.getActiveBody().getUnits())
					System.out.println(u);
			}
			
			@Override
			public void performExtendedTest
					(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
					IFDSSolver<UpdatableWrapper<Unit>,UpdatableReachingDefinition,UpdatableWrapper<SootMethod>,
						InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
					int phase) {
				SootMethod meth = Scene.v().getMainMethod();
				Unit ret = meth.getActiveBody().getUnits().getLast();
				Set<UpdatableReachingDefinition> results = solver.ifdsResultsAt(icfg.wrapWeak(ret));
				
				System.out.println("---------------\nmain() method body\n---------------");
				for (Unit u : meth.getActiveBody().getUnits())
					System.out.println(u);
				
				System.out.println("----------------\nNew results:\n------------------");
				for (UpdatableReachingDefinition urd : results)
					System.out.println(urd);
				
				Assert.assertEquals(original.size() - 1, results.size());
				for (UpdatableReachingDefinition pr : results) {
					boolean found = false;
					for (UpdatableReachingDefinition po : original)
						if (pr.getContents().equals(po.getContents())) {
							found = true;
							break;
						}
					Assert.assertTrue(found);
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
	 * Deletes a statement before and in a loop and checks whether the new
	 * facts are propagated correctly.
	 */
	@Test
	public void deleteAssignmentInLoopTestJU_Rerun() {
		System.out.println("Starting deleteAssignmentInLoopTestJU_Rerun...");
		performTestRerun(ITestHandlerDeleteAssignmentInLoopTest(), "DeleteAssignmentInLoopTest");
		System.out.println("deleteAssignmentInLoopTestJU_Rerun finished.");
	}
	
	/**
	 * Deletes a statement before and in a loop and checks whether the new
	 * facts are propagated correctly.
	 */
	@Test
	public void deleteAssignmentInLoopTestJU_Propagate() {
		System.out.println("Starting deleteAssignmentInLoopTestJU_Propagate...");
		performTestDirect(ITestHandlerDeleteAssignmentInLoopTest(), "DeleteAssignmentInLoopTest");
		System.out.println("deleteAssignmentInLoopTestJU_Propagate finished.");
	}

	/**
	 * Deletes a statement before and in a loop and checks whether the new
	 * facts are propagated correctly.
	 */
	/*
	@Test
	public void deleteAssignmentInLoopTestJU_Update() {
		System.out.println("Starting deleteAssignmentInLoopTestJU_Update...");
		performTestUpdate(ITestHandlerDeleteAssignmentInLoopTest(), "DeleteAssignmentInLoopTest");
		System.out.println("deleteAssignmentInLoopTestJU_Update finished.");
	}
	*/

}