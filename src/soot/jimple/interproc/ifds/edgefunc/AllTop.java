package soot.jimple.interproc.ifds.edgefunc;

import soot.jimple.interproc.ifds.EdgeFunction;


public class AllTop<V> implements EdgeFunction<V> {
	
	private final V topElement; 

	public AllTop(V topElement){
		this.topElement = topElement;
	} 

	@Override
	public V computeTarget(V source) {
		return topElement;
	}

	@SuppressWarnings("rawtypes")
	@Override
	public EdgeFunction<V> composeWith(EdgeFunction<V> secondFunction) {
		if (secondFunction instanceof AllTopInverse)
			if (((AllTopInverse) secondFunction).getTopElement() == this.topElement)
				return EdgeIdentity.v();

		return secondFunction;
	}

	@Override
	public EdgeFunction<V> joinWith(EdgeFunction<V> otherFunction) {
		return otherFunction;
	}

	@Override
	public boolean equalTo(EdgeFunction<V> other) {
		if(other instanceof AllTop) {
			@SuppressWarnings("rawtypes")
			AllTop allTop = (AllTop) other;
			return allTop.topElement.equals(topElement);
		}		
		return false;
	}

	@Override
	public String toString() {
		return "alltop";
	}

	@Override
	public EdgeFunction<V> invert() {
		return new AllTopInverse<V>(this.topElement);
	}
	
	/**
	 * Gets the top element on which this function was constructed
	 * @return The top element on which this function was constructed
	 */
	V getTopElement() {
		return this.topElement;
	}

}
