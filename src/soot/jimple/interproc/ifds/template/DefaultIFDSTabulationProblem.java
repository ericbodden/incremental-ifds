package soot.jimple.interproc.ifds.template;

import soot.SootMethod;
import soot.Unit;
import soot.jimple.interproc.ifds.FlowFunctions;
import soot.jimple.interproc.ifds.IFDSTabulationProblem;
import soot.jimple.interproc.ifds.InterproceduralCFG;
import soot.jimple.interproc.incremental.UpdatableWrapper;

/**
 * This is a template for {@link IFDSTabulationProblem}s that automatically caches values
 * that ought to be cached. This class uses the Factory Method design pattern.
 * The {@link InterproceduralCFG} is passed into the constructor so that it can be conveniently
 * reused for solving multiple different {@link IFDSTabulationProblem}s.
 * This class is specific to Soot. 
 * 
 * @param <D> The type of data-flow facts to be computed by the tabulation problem.
 */
public abstract class DefaultIFDSTabulationProblem
	<D extends UpdatableWrapper<?>, I extends InterproceduralCFG<UpdatableWrapper<Unit>,UpdatableWrapper<SootMethod>>>
		implements IFDSTabulationProblem<UpdatableWrapper<Unit>, D, UpdatableWrapper<SootMethod>,I> {

	private final FlowFunctions<UpdatableWrapper<Unit>, D, UpdatableWrapper<SootMethod>> flowFunctions;
	private I icfg;
	private final D zeroValue;
	
	public DefaultIFDSTabulationProblem(I icfg) {
		this.icfg = icfg;
		this.flowFunctions = createFlowFunctionsFactory();
		this.zeroValue = createZeroValue();
	}
	
	protected abstract FlowFunctions<UpdatableWrapper<Unit>, D, UpdatableWrapper<SootMethod>> createFlowFunctionsFactory();

	protected abstract D createZeroValue();

	@Override
	public final FlowFunctions<UpdatableWrapper<Unit>, D, UpdatableWrapper<SootMethod>> flowFunctions() {
		return flowFunctions;
	}

	@Override
	public final I interproceduralCFG() {
		return icfg;
	}
	
	@Override
	public final void updateCFG(I cfg) {
		this.icfg = cfg;
	}

	@Override
	public final D zeroValue() {
		return zeroValue;
	}

}
