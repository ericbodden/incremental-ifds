package soot.jimple.interproc.ifds.solver;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import soot.jimple.interproc.ifds.DontSynchronize;
import soot.jimple.interproc.ifds.EdgeFunction;
import soot.jimple.interproc.ifds.SynchronizedBy;
import soot.jimple.interproc.ifds.ThreadSafe;
import soot.jimple.interproc.ifds.utils.Utils;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;

/**
 * The IDE algorithm uses a list of jump functions. Instead of a list, we use a set of three
 * maps that are kept in sync. This allows for efficient indexing: the algorithm accesses
 * elements from the list through three different indices.
 */
@ThreadSafe
public class JumpFunctions<N,D,L> {
	
	/**
	 * mapping from target node and value to a list of all source values and associated functions
	 * where the list is implemented as a mapping from the source value to the function
	 * we exclude empty default functions
	 */
	@SynchronizedBy("consistent lock on this")
	protected Table<N,D,Map<D,EdgeFunction<L>>> nonEmptyReverseLookup = HashBasedTable.create();

	/**
	 * mapping from source value and target node to a list of all target values and associated functions
	 * where the list is implemented as a mapping from the source value to the function
	 * we exclude empty default functions
	 */
	@SynchronizedBy("consistent lock on this")
	protected Table<D,N,Map<D,EdgeFunction<L>>> nonEmptyForwardLookup = HashBasedTable.create();

	/**
	 * a mapping from target node to a list of triples consisting of source value,
	 * target value and associated function; the triple is implemented by a table
	 * we exclude empty default functions
	 */
	@SynchronizedBy("consistent lock on this")
	protected Map<N,Table<D,D,EdgeFunction<L>>> nonEmptyLookupByTargetNode = new HashMap<N,Table<D,D,EdgeFunction<L>>>();

	@DontSynchronize("immutable")	
	private final EdgeFunction<L> allTop;
	
	public JumpFunctions(EdgeFunction<L> allTop) {
		this.allTop = allTop;
	}

	/**
	 * Records a jump function. The source statement is implicit.
	 * @see PathEdge
	 */
	public synchronized void addFunction(D sourceVal, N target, D targetVal, EdgeFunction<L> function) {
		assert sourceVal!=null;
		assert target!=null;
		assert targetVal!=null;
		assert function!=null;
		
		//we do not store the default function (all-top)
		if(function.equalTo(allTop)) return;
		
		Map<D,EdgeFunction<L>> sourceValToFunc = nonEmptyReverseLookup.get(target, targetVal);
		if(sourceValToFunc==null) {
			sourceValToFunc = new LinkedHashMap<D,EdgeFunction<L>>();
			nonEmptyReverseLookup.put(target,targetVal,sourceValToFunc);
		}
		sourceValToFunc.put(sourceVal, function);
		
		Map<D, EdgeFunction<L>> targetValToFunc = nonEmptyForwardLookup.get(sourceVal, target);
		if(targetValToFunc==null) {
			targetValToFunc = new LinkedHashMap<D,EdgeFunction<L>>();
			nonEmptyForwardLookup.put(sourceVal,target,targetValToFunc);
		}
		targetValToFunc.put(targetVal, function);

		Table<D,D,EdgeFunction<L>> table = nonEmptyLookupByTargetNode.get(target);
		if(table==null) {
			table = HashBasedTable.create();
			nonEmptyLookupByTargetNode.put(target,table);
		}
		table.put(sourceVal, targetVal, function);
	}
	
	/**
	 * Removes a jump function. The source statement is implicit.
	 * @see PathEdge
	 * @return True if the function has actually been removed. False if it was not
	 * there anyway.
	 */
	public synchronized boolean removeFunction(D sourceVal, N target, D targetVal) {
		assert sourceVal!=null;
		assert target!=null;
		assert targetVal!=null;
		
		Map<D,EdgeFunction<L>> sourceValToFunc = nonEmptyReverseLookup.get(target, targetVal);
		if (sourceValToFunc == null)
			return false;
		if (sourceValToFunc.remove(sourceVal) == null)
			return false;
		if (sourceValToFunc.isEmpty())
			nonEmptyReverseLookup.remove(targetVal, targetVal);
		
		Map<D, EdgeFunction<L>> targetValToFunc = nonEmptyForwardLookup.get(sourceVal, target);
		if (targetValToFunc == null)
			return false;
		if (targetValToFunc.remove(targetVal) == null)
			return false;
		if (targetValToFunc.isEmpty())
			nonEmptyForwardLookup.remove(sourceVal, target);

		Table<D,D,EdgeFunction<L>> table = nonEmptyLookupByTargetNode.get(target);
		if (table == null)
			return false;
		if (table.remove(sourceVal, targetVal) == null)
			return false;
		if (table.isEmpty())
			nonEmptyLookupByTargetNode.remove(target);
		
		return true;
	}

	/**
	 * Removes all jump function with the given target
	 * @see target The target for which to remove all jump functions
	 */
	public synchronized void removeByTarget(N target) {
		Map<D,List<D>> rmList = new HashMap<D,List<D>>();
		for (Cell<D, D, EdgeFunction<L>> cell : lookupByTarget(target))
			Utils.addElementToMapList(rmList, cell.getRowKey(), cell.getColumnKey());
		for (Entry<D,List<D>> entry : rmList.entrySet())
			for (D d : entry.getValue())
				removeFunction(entry.getKey(), target, d);
	}
	
	/**
     * Returns, for a given target statement and value all associated
     * source values, and for each the associated edge function.
     * The return value is a mapping from source value to function.
	 */
	public synchronized Map<D,EdgeFunction<L>> reverseLookup(N target, D targetVal) {
		assert target!=null;
		assert targetVal!=null;
		Map<D,EdgeFunction<L>> res = nonEmptyReverseLookup.get(target,targetVal);
		if(res==null) return Collections.emptyMap();
		return res;
	}
	
	/**
	 * Returns, for a given source value and target statement all
	 * associated target values, and for each the associated edge function. 
     * The return value is a mapping from target value to function.
	 */
	public synchronized Map<D,EdgeFunction<L>> forwardLookup(D sourceVal, N target) {
		assert sourceVal!=null;
		assert target!=null;
		Map<D, EdgeFunction<L>> res = nonEmptyForwardLookup.get(sourceVal, target);
		if(res==null) return Collections.emptyMap();
		return res;
	}
	
	/**
	 * Returns for a given target statement all jump function records with this target.
	 * The return value is a set of records of the form (sourceVal,targetVal,edgeFunction).
	 */
	public synchronized Set<Cell<D,D,EdgeFunction<L>>> lookupByTarget(N target) {
		assert target!=null;
		Table<D, D, EdgeFunction<L>> table = nonEmptyLookupByTargetNode.get(target);
		if(table==null) return Collections.emptySet();
		Set<Cell<D, D, EdgeFunction<L>>> res = table.cellSet();
		if(res==null) return Collections.emptySet();
		return res;
	}
	
	/**
	 * Clears all elements in this index.
	 */
	public void clear() {
		nonEmptyReverseLookup.clear();
		nonEmptyForwardLookup.clear();
		nonEmptyLookupByTargetNode.clear();
	}

	/**
	 * Replaces an old statement object with a new one without impacting
	 * semantics. This method is intended for graph updates that exchange all
	 * nodes in the program graph even if they are semantically unchanged.
	 * You can then use this method to fix the references.
	 * @param oldStmt The old statement object to be replaced
	 * @param newStmt The replacement for the old object
	 */
	public void replaceNode(N oldStmt, N newStmt) {
		assert oldStmt != null;
		assert newStmt != null;

		// nonEmptyReverseLookup
		Map<D, Map<D, EdgeFunction<L>>> nerl = new HashMap<D, Map<D,EdgeFunction<L>>>();
		for (Map.Entry<D, Map<D, EdgeFunction<L>>> entry :
				nonEmptyReverseLookup.row(oldStmt).entrySet())
			nerl.put(entry.getKey(), entry.getValue());
		for (Entry<D, Map<D, EdgeFunction<L>>> entry : nerl.entrySet()) {
			nonEmptyReverseLookup.put(newStmt, entry.getKey(), entry.getValue());
			nonEmptyReverseLookup.remove(oldStmt, entry.getKey());
		}
		
		// nonEmptyForwardLookup
		nerl.clear();
		for (Entry<D, Map<D, EdgeFunction<L>>> entry :
				nonEmptyForwardLookup.column(oldStmt).entrySet())
			nerl.put(entry.getKey(), entry.getValue());
		for (Entry<D, Map<D, EdgeFunction<L>>> entry : nerl.entrySet()) {
			nonEmptyForwardLookup.put(entry.getKey(), newStmt, entry.getValue());
			nonEmptyForwardLookup.remove(entry.getKey(), oldStmt);
		}
		
		// nonEmptyLookupByTargetNode
		Table<D, D, EdgeFunction<L>> oldByTarget = nonEmptyLookupByTargetNode.get(oldStmt);
		if (oldByTarget != null) {
			nonEmptyLookupByTargetNode.put(newStmt, oldByTarget);
			nonEmptyLookupByTargetNode.remove(oldStmt);
		}
	}

	/**
	 * Gets all jump functions registered in this object.
	 * @return A table containing all jump functions in this object. The row key
	 * is the source fact, the column key is the target statement and value is
	 * a mapping from target facts to the respective edge functions.
	 */
	public Table<D, N, Map<D, EdgeFunction<L>>> getAllFunctions() {
		return HashBasedTable.create(this.nonEmptyForwardLookup);
	}
	
	/**
	 * Gets the set of target statements for which this object contains jump
	 * functions.
	 * @return The set of target statements for which this object contains jump
	 * functions.
	 */
	public Set<N> getTargets() {
		return this.nonEmptyLookupByTargetNode.keySet();
	}
	
	/**
	 * Gets the number of target statements for which there are fact mappings.
	 * @return The number of target statements
	 */
	public int targetCount() {
		return this.nonEmptyLookupByTargetNode.size();
	}
	
	/**
	 * Gets the number of distinct source facts for which there are fact
	 * mappings.
	 * @return The number of distinct source facts
	 */
	public int sourceFactCount() {
		return this.nonEmptyForwardLookup.size();
	}

}
