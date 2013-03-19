package soot.jimple.interproc.ifds.problems;

import soot.Type;
import soot.Value;
import soot.jimple.interproc.incremental.UpdatableWrapper;
import soot.toolkits.scalar.Pair;

public class UpdatablePossibleType implements UpdatableWrapper<Pair<Value,Type>> {

	private Value value;
	private Type type;
	
	private Value previousValue = null;
	private Type previousType = null;
	
	public UpdatablePossibleType(Pair<Value,Type> possibleType) {
		this.value = possibleType.getO1();
		this.type = possibleType.getO2();
	}
	
	public UpdatablePossibleType(Value value, Type type) {
		this.value = value;
		this.type = type;
	}

	public Value getValue() {
		return this.value;
	}
	
	public Type getType() {
		return this.type;
	}

	@Override
	public void notifyReferenceChanged(Object oldObject, Object newObject) {
		if (oldObject == this.value)
			this.value = (Value) newObject;
		if (oldObject == this.type)
			this.type = (Type) newObject;
	}

	@Override
	public Pair<Value, Type> getContents() {
		return new Pair<Value, Type>(this.value, this.type);
	}

	@Override
	public Pair<Value, Type> getPreviousContents() {
		return new Pair<Value, Type>(this.previousValue, this.previousType);
	}

	@Override
	public boolean hasPreviousContents() {
		return this.previousValue != null && this.previousType != null;
	}

	@Override
	public void setSafepoint() {
		this.previousValue = this.value;
		this.previousType = this.type;
	}

}
