package soot.jimple.interproc.ifds.flowfunc;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import soot.jimple.interproc.ifds.FlowFunction;

public class Transfer<D> implements FlowFunction<D> {
	
	private final D toValue;
	private final D fromValue;
	
	public Transfer(D toValue, D fromValue){
		assert toValue != null;
		assert fromValue != null;
		
		this.toValue = toValue;
		this.fromValue = fromValue;
	} 

	public Set<D> computeTargets(D source) {
		if (source == fromValue || source.equals(fromValue)) {
			HashSet<D> res = new HashSet<D>();
			res.add(source);
			res.add(toValue);
			return res;
		} else if (source.equals(toValue)) {
			return Collections.emptySet();
		} else {
			return Collections.singleton(source);
		}
	}
	
}
