package soot.jimple.interproc.ifds.solver;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import soot.jimple.interproc.ifds.EdgeFunction;
import soot.jimple.interproc.ifds.SynchronizedBy;
import soot.jimple.interproc.ifds.ThreadSafe;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;

/**
 * A data structure to record summary functions in an indexed fashion, for fast retrieval.
 */
@ThreadSafe
public class SummaryFunctions<N,D,V> {
	
	@SynchronizedBy("consistent lock on this")
	protected Table<N,D,Table<N,D,EdgeFunction<V>>> table = HashBasedTable.create();
	
	/**
	 * Inserts a summary function.
	 * @param callSite The call site with which this function is associated.
	 * @param sourceVal The source value at the call site. 
	 * @param retSite The return site (in the caller) with which this function is associated.
	 * @param targetVal The target value at the return site.
	 * @param function The edge function used to compute V-type values from the source node to the target node.  
	 */
	public synchronized void insertFunction(N callSite,D sourceVal, N retSite,
			D targetVal, EdgeFunction<V> function) {
		assert callSite!=null;
		assert sourceVal!=null;
		assert retSite!=null;
		assert targetVal!=null;
		assert function!=null;
		
		Table<N, D, EdgeFunction<V>> targetAndTargetValToFunction = table.get(callSite,sourceVal);
		if(targetAndTargetValToFunction==null) {
			targetAndTargetValToFunction = HashBasedTable.create();
			table.put(callSite,sourceVal,targetAndTargetValToFunction);
		}
		targetAndTargetValToFunction.put(retSite, targetVal, function);
	}

	/**
	 * Removes a summary function.
	 * @param callSite The call site with which this function is associated.
	 * @param sourceVal The source value at the call site. 
	 * @param retSite The return site (in the caller) with which this function is associated.
	 * @param targetVal The target value at the return site.
	 */
	public synchronized void removeFunction(N callSite,D sourceVal, N retSite, D targetVal) {
		assert callSite!=null;
		assert sourceVal!=null;
		assert retSite!=null;
		assert targetVal!=null;

		Table<N,D, EdgeFunction<V>> targetAndTargetValToFunction = table.get(callSite, sourceVal);
		if (targetAndTargetValToFunction != null) {
			targetAndTargetValToFunction.remove(retSite, targetVal);			
		}
	}
	
	/**
	 * Retrieves all summary functions for a given call site, source value and
	 * return site (in the caller).
	 * The result contains a mapping from target value to associated edge function.
	 */
	public synchronized Map<D,EdgeFunction<V>> summariesFor(N callSite, D sourceVal, N returnSite) {
		assert callSite!=null;
		assert sourceVal!=null;
		assert returnSite!=null;

		Table<N,D,EdgeFunction<V>> res = table.get(callSite,sourceVal);
		if(res==null) return Collections.emptyMap();
		else {
			return res.row(returnSite);
		}
	}
	
	/**
	 * Removes all summary functions linking the specified call site and fact with
	 * the given return site.
	 * @param callSite The call site, i.e. the statement that performs the function
	 * call
	 * @param sourceVal The fact at the call statement
	 * @param returnSite The statement to which the control flow returns after the
	 * function has been executed
	 * @return The number of summary functions that have been deleted
	 */
	public synchronized int removeFunctions(N callSite, D sourceVal, N returnSite) {
		assert callSite!=null;
		assert sourceVal!=null;
		assert returnSite!=null;

		Table<N, D, EdgeFunction<V>> targetAndTargetValToFunction = table.get(callSite,sourceVal);
		if (targetAndTargetValToFunction == null)
			return 0;
		
		int delCnt = 0;
		Map<D, EdgeFunction<V>> row = targetAndTargetValToFunction.row(returnSite);
		while (!row.isEmpty()) {
			targetAndTargetValToFunction.remove(returnSite, row.keySet().iterator().next());
			delCnt++;
		}
		return delCnt;
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
		Map<D, Table<N, D, EdgeFunction<V>>> addMap = new HashMap<D, Table<N,D,EdgeFunction<V>>>();
		for (Table.Cell<N, D, Table<N, D, EdgeFunction<V>>> cell : table.cellSet()) {
			// Update the outer table to have the correct source statement
			if (cell.getRowKey() == oldStmt) {
				addMap.put(cell.getColumnKey(), cell.getValue());
			}
			
			// Update the inner table to have the correct target statement
			Table<N, D, EdgeFunction<V>> tblInner = cell.getValue();
			Map<D,EdgeFunction<V>> innerMap = new HashMap<D, EdgeFunction<V>>();
			for (Entry<D, EdgeFunction<V>> entry : tblInner.row(oldStmt).entrySet())
				innerMap.put(entry.getKey(), entry.getValue());
			for (Entry<D,EdgeFunction<V>> entry : innerMap.entrySet()) {
				tblInner.remove(oldStmt, entry.getKey());
				tblInner.put(newStmt, entry.getKey(), entry.getValue());
			}
		}
		for (Entry<D, Table<N, D, EdgeFunction<V>>> entry : addMap.entrySet()) {
			table.remove(oldStmt, entry.getKey());
			table.put(newStmt, entry.getKey(), entry.getValue());
		}
	}
	
	/**
	 * Removes all summary functions
	 */
	public void clear() {
		this.table.clear();
	}
}
