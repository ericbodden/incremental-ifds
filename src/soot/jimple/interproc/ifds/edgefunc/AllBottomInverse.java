package soot.jimple.interproc.ifds.edgefunc;

import soot.jimple.interproc.ifds.EdgeFunction;

/**
 * Inverse function of {@link AllBottom}
 */
public class AllBottomInverse<V> implements EdgeFunction<V> {
	
	private final V bottomElement;

	public AllBottomInverse(V bottomElement){
		this.bottomElement = bottomElement;
	} 

	@Override
	public V computeTarget(V source) {
		return bottomElement;
	}

	@SuppressWarnings("rawtypes")
	@Override
	public EdgeFunction<V> composeWith(EdgeFunction<V> secondFunction) {
		if (secondFunction instanceof AllBottom)
			if (((AllBottom) secondFunction).getBottomElement() == this.bottomElement)
				return this;
		if (secondFunction instanceof EdgeIdentity)
			return this;
		
		throw new RuntimeException("Unknown function composition");
	}

	@Override
	public EdgeFunction<V> joinWith(EdgeFunction<V> otherFunction) {
		// For otherFunction in {allTop, allBottom, id}, we always get allBottom
		if(otherFunction == this || otherFunction.equalTo(this)) return this;
		if(otherFunction instanceof AllBottom)
			return EdgeIdentity.v();
		if(otherFunction instanceof AllTopInverse) {
			return this;
		}
		if(otherFunction instanceof EdgeIdentity) {
			return this;
		}
		throw new IllegalStateException("(AllBottomInverse) unexpected edge function: "+otherFunction);
	}

	@Override
	public boolean equalTo(EdgeFunction<V> other) {
		if(other instanceof AllBottomInverse) {
			@SuppressWarnings("rawtypes")
			AllBottomInverse allBottom = (AllBottomInverse) other;
			return allBottom.bottomElement.equals(bottomElement);
		}		
		return false;
	}
	
	@Override
	public String toString() {
		return "allbottominverse";
	}

	@Override
	public EdgeFunction<V> invert() {
		return new AllBottom<V>(bottomElement);
	}

	/**
	 * Gets the bottom element on which this function was constructed
	 * @return The bottom element on which this function was constructed
	 */
	V getBottomElement() {
		return this.bottomElement;
	}

}
