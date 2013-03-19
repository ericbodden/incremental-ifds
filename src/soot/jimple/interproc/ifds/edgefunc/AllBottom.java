package soot.jimple.interproc.ifds.edgefunc;

import soot.jimple.interproc.ifds.EdgeFunction;


public class AllBottom<V> implements EdgeFunction<V> {
	
	private final V bottomElement;

	public AllBottom(V bottomElement){
		this.bottomElement = bottomElement;
	} 

	@Override
	public V computeTarget(V source) {
		return bottomElement;
	}

	@SuppressWarnings("rawtypes")
	@Override
	public EdgeFunction<V> composeWith(EdgeFunction<V> secondFunction) {
		if (secondFunction instanceof AllBottomInverse)
			if (((AllBottomInverse) secondFunction).getBottomElement() == this.bottomElement)
				return EdgeIdentity.v();

		return secondFunction;
	}

	@SuppressWarnings("rawtypes")
	@Override
	public EdgeFunction<V> joinWith(EdgeFunction<V> otherFunction) {
		// For otherFunction in {allTop, allBottom, id}, we always get allBottom
		if(otherFunction == this || otherFunction.equalTo(this)) return this;
		if(otherFunction instanceof AllBottomInverse)
			if (((AllBottomInverse) otherFunction).getBottomElement() == this.bottomElement)
				return this;
		if(otherFunction instanceof AllTop) {
			return this;
		}
		if(otherFunction instanceof EdgeIdentity) {
			return this;
		}
		throw new IllegalStateException("(AllBottom) unexpected edge function: "+otherFunction);
	}

	@Override
	public boolean equalTo(EdgeFunction<V> other) {
		if(other instanceof AllBottom) {
			@SuppressWarnings("rawtypes")
			AllBottom allBottom = (AllBottom) other;
			return allBottom.bottomElement.equals(bottomElement);
		}		
		return false;
	}
	
	@Override
	public String toString() {
		return "allbottom";
	}

	@Override
	public EdgeFunction<V> invert() {
		return new AllBottomInverse<V>(bottomElement);
	}
	
	/**
	 * Gets the bottom element on which this function was constructed
	 * @return The bottom element on which this function was constructed
	 */
	V getBottomElement() {
		return this.bottomElement;
	}

}
