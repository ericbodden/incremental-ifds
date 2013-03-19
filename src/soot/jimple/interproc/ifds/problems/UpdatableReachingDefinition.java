package soot.jimple.interproc.ifds.problems;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import soot.NullType;
import soot.Value;
import soot.jimple.DefinitionStmt;
import soot.jimple.internal.JimpleLocal;
import soot.jimple.interproc.incremental.UpdatableWrapper;
import soot.toolkits.scalar.Pair;

public class UpdatableReachingDefinition implements UpdatableWrapper<Pair<Value, Set<DefinitionStmt>>> {

	private UpdatableWrapper<Value> value = null;
	private Set<UpdatableWrapper<DefinitionStmt>> definitions;

	public static final UpdatableReachingDefinition zero = new UpdatableReachingDefinition();
	
	private static final Value EMPTY_VALUE = new JimpleLocal("<<zero>>", NullType.v());
	
	private UpdatableReachingDefinition() {
	}
	
	public UpdatableReachingDefinition(Pair<UpdatableWrapper<Value>, Set<UpdatableWrapper<DefinitionStmt>>> n) {
		this.value = n.getO1();
		this.definitions = n.getO2();
	}

	public UpdatableReachingDefinition(UpdatableWrapper<Value> value, Set<UpdatableWrapper<DefinitionStmt>> definitions) {
		this.value = value;
		this.definitions = definitions;
	}

	@Override
	public void notifyReferenceChanged(Object oldObject, Object newObject) {
		this.value.notifyReferenceChanged(oldObject, newObject);
		for (UpdatableWrapper<DefinitionStmt> def : this.definitions)
			def.notifyReferenceChanged(oldObject, newObject);
	}

	@Override
	public Pair<Value, Set<DefinitionStmt>> getContents() {
		return new Pair<Value, Set<DefinitionStmt>>(getValue(), getDefinitions());
	}
	
	@Override
	public String toString() {
		return value + " -> " + definitions;
	}

	@Override
	public Pair<Value, Set<DefinitionStmt>> getPreviousContents() {
		Set<DefinitionStmt> defs = new HashSet<DefinitionStmt>(this.definitions.size());
		for (UpdatableWrapper<DefinitionStmt> ds : this.definitions)
			defs.add(ds.getPreviousContents());
		return new Pair<Value, Set<DefinitionStmt>>(this.value.getPreviousContents(), defs);
	}

	@Override
	public void setSafepoint() {
		this.value.setSafepoint();
		for (UpdatableWrapper<DefinitionStmt> ds : this.definitions)
			ds.setSafepoint();
	}

	@Override
	public boolean equals(Object another) {
		if (super.equals(another))
			return true;
		
		if (another == null)
			return false;
		if (!(another instanceof UpdatableReachingDefinition))
			return false;
		UpdatableReachingDefinition urd = (UpdatableReachingDefinition) another;
		
		if (this.value == null)
		{ if (urd.value != null) return false; }
		else { if (!this.value.equals(urd.value)) return false; } 

		if (this.definitions == null)
		{ if (urd.definitions != null) return false; }
		else { if (!this.definitions.equals(urd.value)) return false; }
		
		return true;
	}

	@Override
	public boolean hasPreviousContents() {
		return this.value.hasPreviousContents();
	}
	
	public Value getValue() {
		if (value == null)
			return EMPTY_VALUE;
		return this.value.getContents();
	}
	
	public Set<DefinitionStmt> getDefinitions() {
		if (this.definitions == null)
			return Collections.emptySet();
		
		Set<DefinitionStmt> defs = new HashSet<DefinitionStmt>(this.definitions.size());
		for (UpdatableWrapper<DefinitionStmt> ds : this.definitions)
			defs.add(ds.getContents());
		return defs;
	}
		
}
