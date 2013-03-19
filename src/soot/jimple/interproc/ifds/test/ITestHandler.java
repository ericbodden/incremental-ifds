package soot.jimple.interproc.ifds.test;

import soot.SootMethod;
import soot.Unit;
import soot.jimple.interproc.ifds.InterproceduralCFG;
import soot.jimple.interproc.ifds.solver.IFDSSolver;
import soot.jimple.interproc.incremental.UpdatableWrapper;

/**
 * Interface for injecting new code into the generic test case
 */
public interface ITestHandler<D extends UpdatableWrapper<?>> {
	
	/**
	 * Method that is called before Soot is run the first time.
	 */
	public void initialize();
	
	/**
	 * Initializes the application classes if necessary.
	 */
	public void initApplicationClasses();
	
	/**
	 * Method that is called when the basic information leakage analysis is complete.
	 * Implement this method to perform additional test tasks.
	 * @param solver The solver that has performed the generic analysis
	 */
	public void extendBasicTest
		(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
		IFDSSolver<UpdatableWrapper<Unit>,D,UpdatableWrapper<SootMethod>,
			InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver);
	
	/**
	 * Method that is called when the CFG shall be patched by the implementer
	 * @param phase The phase of the test being executed. This value starts with zero
	 * and is extended by one up to the number of phases minues one.
	 */
	public void patchGraph(int phase);
	
	/**
	 * Method that is called when the extended test after updating/rerunning shall be
	 * performed.
	 * @param solver The solver containing the results on the modified CFG.
	 * @param phase The phase of the test being executed. This value starts with zero
	 * and is extended by one up to the number of phases minues one.
	 */
	public void performExtendedTest
		(InterproceduralCFG<UpdatableWrapper<Unit>, UpdatableWrapper<SootMethod>> icfg,
		IFDSSolver<UpdatableWrapper<Unit>,D,UpdatableWrapper<SootMethod>,
			InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>> solver,
		int phase);
	
	/**
	 * Gets the number of phases in this test
	 * @return The number of phases in this test
	 */
	public int getPhaseCount();
	
}
