package soot.jimple.interproc.ifds.edgefunc;

import soot.jimple.interproc.ifds.EdgeFunction;


public class AllTopInverse<V> implements EdgeFunction<V> {
	
	private final V topElement; 

	public AllTopInverse(V topElement){
		this.topElement = topElement;
	} 

	@Override
	public V computeTarget(V source) {
		return topElement;
	}

	@SuppressWarnings("rawtypes")
	@Override
	public EdgeFunction<V> composeWith(EdgeFunction<V> secondFunction) {
		if (secondFunction instanceof AllTop)
			if (((AllTop) secondFunction).getTopElement() == this.topElement)
				return EdgeIdentity.v();
		
		throw new RuntimeException("Unknown function composition");
	}

	@Override
	public EdgeFunction<V> joinWith(EdgeFunction<V> otherFunction) {
		return otherFunction;
	}

	@Override
	public boolean equalTo(EdgeFunction<V> other) {
		if(other instanceof AllTopInverse) {
			@SuppressWarnings("rawtypes")
			AllTopInverse allTop = (AllTopInverse) other;
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
		return new AllTop<V>(this.topElement);
	}
	
	/**
	 * Gets the top element on which this function was constructed
	 * @return The top element on which this function was constructed
	 */
	V getTopElement() {
		return this.topElement;
	}

}
