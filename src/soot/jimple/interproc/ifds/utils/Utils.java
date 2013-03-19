package soot.jimple.interproc.ifds.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Table;

/**
 * Generic utility class
 */
public class Utils {

	/**
	 * Adds the given value to a map from keys to lists of values. If there is already
	 * a list for the given key in the map, the value is added. Otherwise, a new list
	 * containing the value is created.
	 * @param edgeList The list map to which to add the new element
	 * @param key The key identifying the list to which to add the element 
	 * @param value The value to be added to the list
	 * @return True if the value was actually inserted, false if not (i.e. the
	 * value has already been there before)
	 */
	public static <X, Y>  boolean addElementToMapList(Map<X, List<Y>> edgeList, X key, Y value) {
		List<Y> list = edgeList.get(key);
		if (list != null) {
			if (list.contains(value))
				return false;
			else {
				list.add(value);
				return true;
			}
		}
		else {
			list = new ArrayList<Y>();
			list.add(value);
			edgeList.put(key, list);
			return true;
		}
	}
	
	/**
	 * Adds the given value to a map from keys to sets of values. If there is already
	 * a set for the given key in the map, the value is added. Otherwise, a new set
	 * containing the value is created.
	 * @param edgeList The set map to which to add the new element
	 * @param key The key identifying the set to which to add the element 
	 * @param value The value to be added to the set
	 * @return True if the value was actually inserted, false if not (i.e. the
	 * value has already been there before)
	 */
	public static <X, Y>  boolean addElementToMapSet(Map<X, Set<Y>> edgeList, X key, Y value) {
		Set<Y> list = edgeList.get(key);
		if (list != null) {
			if (list.contains(value))
				return false;
			else {
				list.add(value);
				return true;
			}
		}
		else {
			list = new HashSet<Y>(15);
			list.add(value);
			edgeList.put(key, list);
			return true;
		}
	}

	/**
	 * Removes the given value from a map from keys to lists of values. If the list
	 * becomes empty after removing the value, it is removed from the map as well. 
	 * @param edgeList The list map from which to remove the element
	 * @param key The key identifying the list from which to remove the element 
	 * @param value The value to be removed from the list
	 * @return True if the value was actually remove, false if not (i.e. the
	 * value was not in the list)
	 */
	public static <X, Y>  boolean removeElementFromMapList(Map<X, List<Y>> edgeList, X key, Y value) {
		List<Y> list = edgeList.get(key);
		if (list == null)
			return false;
		if (!list.remove(value))
			return false;
		if (list.isEmpty())
			edgeList.remove(key);
		return true;
	}

	/**
	 * Removes the given value from a map from keys to sets of values. If the set
	 * becomes empty after removing the value, it is removed from the map as well. 
	 * @param edgeList The set map from which to remove the element
	 * @param key The key identifying the set from which to remove the element 
	 * @param value The value to be removed from the set
	 * @return True if the value was actually remove, false if not (i.e. the
	 * value was not in the list)
	 */
	public static <X, Y>  boolean removeElementFromMapSet(Map<X, Set<Y>> edgeList, X key, Y value) {
		Set<Y> list = edgeList.get(key);
		if (list == null)
			return false;
		if (!list.remove(value))
			return false;
		if (list.isEmpty())
			edgeList.remove(key);
		return true;
	}

	/**
	 * Removes an element from a map from keys to sets of values. If the given
	 * value appears as a key, the whole row is deleted. If it appears inside a
	 * list, it is removed from this list. If a list becomes empty during
	 * processing, the whole row is deleted.
	 * @param edgeList The set map from which to remove the element
	 * @param value The value to be removed
	 */
	public static <X> void removeElementFromMapSet(Map<X, Set<X>> edgeList, X value) {
		edgeList.remove(value);
		List<X> l = new ArrayList<X>(edgeList.keySet());
		for (X x : l)
			removeElementFromMapSet(edgeList, x, value);
	}

	/**
	 * Removes all rows which have the given element as their key from the
	 * given table
	 * @param table The table from which to remove the rows
	 * @param element The key for which which to remove all rows from the table
	 */
	public static <X,Y,Z> void removeElementFromTable(Table<X, Y, Z> table, X element) {
		List<Y> rm = new ArrayList<Y>(table.row(element).keySet());
		for (Y y : rm)
			table.remove(element, y);
	}
	
	public static void copyFile(String sourceFile, String destFile) throws IOException {
		// Code adapted from http://www.javabeat.net/2007/10/copying-file-contents-using-filechannel/
		FileInputStream source = null;
		FileOutputStream destination = null;
		try {
			File f = new File(destFile);
			if (f.exists())
				f.delete();
			
			source = new FileInputStream(sourceFile);
		    destination = new FileOutputStream(destFile);
		    
		    FileChannel sourceFileChannel = source.getChannel();
		    FileChannel destinationFileChannel = destination.getChannel();
		    
		    long size = sourceFileChannel.size();
		    sourceFileChannel.transferTo(0, size, destinationFileChannel);
		}
		finally {
			if (source != null)
				source.close();
			if (destination != null)
				destination.close();
		}
	}
	
	public static void stringToTextFile(String fileName, String data) throws IOException {
		FileWriter fw = null;
		try {
			fw = new FileWriter(fileName);
			fw.write(data);
			fw.flush();
		}
		finally {
			if (fw != null)
				fw.close();
		}
	}

}
