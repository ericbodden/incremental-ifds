package soot.jimple.interproc.incremental;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import soot.MethodOrMethodContext;
import soot.Scene;
import soot.SootClass;
import soot.SootField;
import soot.SootMethod;
import soot.jimple.interproc.ifds.utils.Utils;
import soot.jimple.toolkits.callgraph.Edge;
import soot.jimple.toolkits.callgraph.EdgePredicate;
import soot.jimple.toolkits.callgraph.Filter;
import soot.jimple.toolkits.callgraph.ReachableMethods;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;

/**
 * Class that captures the differences in the scene during an incremental build. Also provides some methods to test for equality.
 */
public class SceneDiff {

	private final static boolean DIFF_ALL_CLASSES = false;
	
	/**
	 * Map between the names of all classes in the scene and the actual classes. It is updated with every build. Classes in here are said to be equal if their names are equal.
	 */
	private Map<String, SootClass> classNameToClass = new HashMap<String, SootClass>();
	private Table<SootClass, SootMethod, Integer> methodBodies = HashBasedTable.create();

	/**
	 * Returns true if the SceneDiff was initialized, false otherwise. A SceneDiff is initialized iff a full build was started in the past or an incremental build has succeeded.
	 * 
	 * @return true if the SceneDiff was initialized, false otherwise
	 */
	public boolean isInitialized() {
		// TODO actually we should persist this data so that it is still available when we close and re-open a project
		return classNameToClass != null;
	}

	/**
	 * Does a full build on the SceneDiff. Since there is nothing to diff, the method only initializes the SceneDiff.
	 */
	public void fullBuild() {
		classNameToClass.clear();
		List<MethodOrMethodContext> eps = new ArrayList<MethodOrMethodContext>();
		eps.addAll(Scene.v().getEntryPoints());
		ReachableMethods reachableMethods = new ReachableMethods(Scene.v().getCallGraph(), eps.iterator(), new EdgeFilter());
		reachableMethods.update();

		for(Iterator<MethodOrMethodContext> iter = reachableMethods.listener(); iter.hasNext(); ) {
			SootMethod m = iter.next().method();
			SootClass c = m.getDeclaringClass();
			assert c.isInScene() : "Class not in scene: " + c.getName();
			
			if (m.hasActiveBody()) {
				SootClass oldClass = classNameToClass.put(c.getName(), c);
				if (oldClass != null && oldClass != c && !oldClass.equals(c))
					throw new RuntimeException("Class name conflict for " + c + " <> " + oldClass);
				this.methodBodies.put(c, m, m.getActiveBody().toString().hashCode());
			}
		}
	}
	
	/**
	 * Gets all classes that are part of the saved snapshot
	 * @return All classes known to this SceneDiff object
	 */
	public Collection<SootClass> getClassesInDiff() {
		return this.classNameToClass.values();
	}

	//retains only callers that are explicit call sites or Thread.start()
	protected static class EdgeFilter extends Filter {		
		protected EdgeFilter() {
			super(new EdgePredicate() {
				public boolean want(Edge e) {				
					return e.kind().isExplicit() || e.kind().isThread();
				}
			});
		}
	}

	/**
	 * Does an incremental build on the SceneDiff. Returns the difference to the last program that was built.
	 * 
	 * @return A ProgramDiffNode that contains the differences to the last program that was built
	 */
	public ProgramDiffNode incrementalBuild() {
		List<MethodOrMethodContext> eps = new ArrayList<MethodOrMethodContext>();
		eps.addAll(Scene.v().getEntryPoints());
		ReachableMethods reachableMethods = new ReachableMethods(Scene.v().getCallGraph(), eps.iterator(), new EdgeFilter());
		reachableMethods.update();

		HashMap<String, SootClass> newClassNameToClass = new HashMap<String, SootClass>();
		for(Iterator<MethodOrMethodContext> iter = reachableMethods.listener(); iter.hasNext(); ) {
			SootMethod m = iter.next().method();
			SootClass c = m.getDeclaringClass();
			assert c.isInScene();
			if (m.hasActiveBody()) {
				SootClass oldClass = newClassNameToClass.put(c.getName(), c);
				if (oldClass != null && oldClass != c)
					throw new RuntimeException("Class name inconsistency");
			}
		}

		/*
		HashMap<String, SootClass> newClassNameToClass = new HashMap<String, SootClass>();
		for (SootClass c : Scene.v().getClasses())
			newClassNameToClass.put(c.getName(), c);
		*/
		
		Map<SootClass, List<SootMethod>> oldMethods = new HashMap<SootClass, List<SootMethod>>(methodBodies.size());
		for (Cell<SootClass, SootMethod, Integer> cell : methodBodies.cellSet())
			Utils.addElementToMapList(oldMethods, cell.getRowKey(), cell.getColumnKey());
		ProgramDiffNode programDiff = new ProgramDiffNode(this.classNameToClass, oldMethods);
		
		// check for added classes
		for (SootClass newClass : newClassNameToClass.values())
			if (!classNameToClass.containsKey(newClass.getName()))
				programDiff.addDiffNode(new ClassDiffNode(null, newClass, DiffType.ADDED));
		for (SootClass oldClass : classNameToClass.values()) {
			// check for removed classes
			if (!newClassNameToClass.containsKey(oldClass.getName()))
				programDiff.addDiffNode(new ClassDiffNode(oldClass, null, DiffType.REMOVED));
			else {
				// check changed classes
				SootClass newClass = newClassNameToClass.get(oldClass.getName());
				/* only diff contents of "application classes" because other classes cannot change anyway.
				 * SootClass.isApplicationClass() returns true only if the class is in the scene.
				 * Only one of oldClass and newClass is in the scene (should be newClass, but oldClass.isInScene() == true),
				 * hence the "or" in the if clause.
				 */
				if (DIFF_ALL_CLASSES || newClass.isApplicationClass() || oldClass.isApplicationClass()) {
					ClassDiffNode classDiff = diffClasses(oldClass, newClass, reachableMethods);
					if (classDiff != null)
						programDiff.addDiffNode(classDiff);
				}
			}
		}
		// update our cache
		classNameToClass = newClassNameToClass;
		return programDiff;
	}

	/**
	 * Generates a ClassDiffNode that contains the difference between the two given classes.
	 * 
	 * @param oldClass the old class
	 * @param newClass the new class
	 * @param reachableMethods Listener for the reachable methods in the new class
	 * @return a ClassDiffNode that contains the difference between the two given classes, or <code>null</code> if there is none
	 */
	private ClassDiffNode diffClasses(SootClass oldClass, SootClass newClass, ReachableMethods reachableMethods) {
		ClassDiffNode classDiff = new ClassDiffNode(oldClass, newClass, DiffType.CHANGED);
		diffFields(oldClass, newClass, classDiff);
		diffMethods(oldClass, newClass, reachableMethods, classDiff);
		diffSuperClass(oldClass, newClass, classDiff);
		diffInterfaces(oldClass, newClass, classDiff);
		if (classDiff.getFieldDiffs().isEmpty()
				&& classDiff.getMethodDiffs().isEmpty()
				&& classDiff.getSuperClassDiffs().isEmpty()
				&& classDiff.getInterfacesDiffs().isEmpty())
			return null;
		return classDiff;
	}
	
	/**
	 * Generates the differences between the methods in the two given classes (methods added, changed or removed).
	 * 
	 * @param oldClass the old class
	 * @param newClass the new class
	 * @param reachableMethods Listener for the reachable methods in the new class
	 * @param classDiff the ClassDiffNode that should contain the differences
	 */
	private void diffMethods(SootClass oldClass, SootClass newClass, ReachableMethods reachableMethods, ClassDiffNode classDiff) {
		// construct the wrapper maps used for tracking similar, but not necessarily equal methods
		Map<SootMethodEqualsWrapper, SootMethod> oldSootMethods =
				new HashMap<SootMethodEqualsWrapper, SootMethod>();
		Map<SootMethodEqualsWrapper, SootMethod> newSootMethods =
				new HashMap<SootMethodEqualsWrapper, SootMethod>();
		
		for (SootMethod oldMethod : this.methodBodies.row(oldClass).keySet()) {
			SootMethodEqualsWrapper wrapper = new SootMethodEqualsWrapper(oldMethod);
			oldSootMethods.put(wrapper, oldMethod);
		}
		for (SootMethod newMethod : newClass.getMethods())
			if (newMethod.hasActiveBody() && reachableMethods.contains(newMethod)) {
				SootMethodEqualsWrapper wrapper = new SootMethodEqualsWrapper(newMethod);
				newSootMethods.put(wrapper, newMethod);
			}
		
		// check for addition and removal
		for (SootMethodEqualsWrapper wrapper : newSootMethods.keySet()) {
			SootMethod newMethod = wrapper.getMethod();
			SootMethod matchingOldMethod = oldSootMethods.get(wrapper);
			if (matchingOldMethod == null && !newMethod.hasActiveBody())
				continue;
			
			boolean isNewMethod = (matchingOldMethod == null);
			if (!isNewMethod) {
				Integer oldBody = this.methodBodies.get(oldClass, matchingOldMethod);
				if (newMethod.hasActiveBody() && oldBody == null)
					isNewMethod = true;
				else if (!equal(newMethod, oldBody, matchingOldMethod.getSubSignature())) {
					classDiff.addMethodDiff(new MethodDiffNode(matchingOldMethod, newMethod, DiffType.CHANGED));
					
					// Remove the old body and method reference
					this.methodBodies.remove(oldClass, matchingOldMethod);
					
					if(newMethod.getName().contains("addListener"))
						System.out.println("x");
				}
			}
			if (isNewMethod)
				// did not find a matching old method, hence the method must have been added
				classDiff.addMethodDiff(new MethodDiffNode(null, newMethod, DiffType.ADDED));
			
			// Put the new body and class reference
			if (newMethod.hasActiveBody())
				this.methodBodies.put(newClass, newMethod, newMethod.getActiveBody().toString().hashCode());
		}
		// check for removal
		for (SootMethod oldMethod : oldSootMethods.values()) {
			if (!oldMethod.hasActiveBody())
				continue;
			SootMethod newMethod = newSootMethods.get(new SootMethodEqualsWrapper(oldMethod));
			if (newMethod == null) {
				// did not find a matching new method, hence the method must have been removed
				classDiff.addMethodDiff(new MethodDiffNode(oldMethod, null, DiffType.REMOVED));
				
				// Remove the old body and method reference
				this.methodBodies.remove(oldClass, oldMethod);
			}
		}
	}

	/**
	 * Generates the differences between the fields in the two given classes (fields added, changed or removed).
	 * 
	 * @param oldClass the old class
	 * @param newClass the new class
	 * @param classDiff the ClassDiffNode that should contain the differences
	 */
	private void diffFields(SootClass oldClass, SootClass newClass, ClassDiffNode classDiff) {
		// construct the wrapper maps used for tracking similar, but not necessarily equal fields
		Map<SootFieldEqualsWrapper, SootFieldEqualsWrapper> oldSootFields = new HashMap<SootFieldEqualsWrapper, SootFieldEqualsWrapper>();
		Map<SootFieldEqualsWrapper, SootFieldEqualsWrapper> newSootFields = new HashMap<SootFieldEqualsWrapper, SootFieldEqualsWrapper>();
		for (SootField oldField : oldClass.getFields()) {
			SootFieldEqualsWrapper wrapper = new SootFieldEqualsWrapper(oldField);
			oldSootFields.put(wrapper, wrapper);
		}
		for (SootField newField : newClass.getFields()) {
			SootFieldEqualsWrapper wrapper = new SootFieldEqualsWrapper(newField);
			newSootFields.put(wrapper, wrapper);
		}
		// check for addition and change
		for (SootField newField : newClass.getFields()) {
			SootFieldEqualsWrapper wrapper = new SootFieldEqualsWrapper(newField);
			SootFieldEqualsWrapper matchingOldFieldWrapper = oldSootFields.get(wrapper);
			if (matchingOldFieldWrapper != null) {
				SootField matchingOldField = matchingOldFieldWrapper.getField();
				if (!equal(matchingOldField, newField))
					classDiff.addFieldDiff(new FieldDiffNode(matchingOldField, newField, DiffType.CHANGED));
			} else
				// did not find a matching old field, hence the field must have been added
				classDiff.addFieldDiff(new FieldDiffNode(null, newField, DiffType.ADDED));
		}
		// check for removal
		for (SootField oldField : oldClass.getFields()) {
			SootFieldEqualsWrapper wrapper = new SootFieldEqualsWrapper(oldField);
			if (!newSootFields.containsKey(wrapper))
				// did not find a matching new field, hence the field must have been removed
				classDiff.addFieldDiff(new FieldDiffNode(oldField, null, DiffType.REMOVED));
		}
	}

	/**
	 * Generates the differences between the interfaces implemented by the two given classes (implemented interfaces added or removed and if the interfaces themselves changed).
	 * 
	 * @param oldClass the old class
	 * @param newClass the new class
	 * @param classDiff the ClassDiffNode that should contain the differences
	 */
	private void diffInterfaces(SootClass oldClass, SootClass newClass, ClassDiffNode classDiff) {
		// construct the wrapper maps used for tracking similar, but not necessarily equal interfaces
		Map<SootClassEqualsWrapper, SootClassEqualsWrapper> oldSootInterfaces = new HashMap<SootClassEqualsWrapper, SootClassEqualsWrapper>();
		Map<SootClassEqualsWrapper, SootClassEqualsWrapper> newSootInterfaces = new HashMap<SootClassEqualsWrapper, SootClassEqualsWrapper>();
		for (SootClass oldInterface : oldClass.getInterfaces()) {
			SootClassEqualsWrapper wrapper = new SootClassEqualsWrapper(oldInterface);
			oldSootInterfaces.put(wrapper, wrapper);
		}
		for (SootClass newInterface : newClass.getInterfaces()) {
			SootClassEqualsWrapper wrapper = new SootClassEqualsWrapper(newInterface);
			newSootInterfaces.put(wrapper, wrapper);
		}
		// check for addition and change
		for (SootClass newInterface : newClass.getInterfaces()) {
			SootClassEqualsWrapper wrapper = new SootClassEqualsWrapper(newInterface);
			SootClassEqualsWrapper matchingOldClassWrapper = oldSootInterfaces.get(wrapper);
			if (matchingOldClassWrapper != null) {
				SootClass matchingOldInterface = matchingOldClassWrapper.getClazz();
				if (!equal(matchingOldInterface, newInterface))
					classDiff.addInterfacesDiff(new DependencyDiffNode(matchingOldInterface, newInterface, DiffType.CHANGED));
			} else
				// did not find a matching old interface, hence the interface must have been added
				classDiff.addInterfacesDiff(new DependencyDiffNode(null, newInterface, DiffType.ADDED));
		}
		// check for removal
		for (SootClass oldInterface : oldClass.getInterfaces()) {
			SootClassEqualsWrapper wrapper = new SootClassEqualsWrapper(oldInterface);
			if (!newSootInterfaces.containsKey(wrapper))
				// did not find a matching new interface, hence the interface must have been removed
				classDiff.addInterfacesDiff(new DependencyDiffNode(oldInterface, null, DiffType.REMOVED));
		}
	}

	/**
	 * Generates the differences between the superclasses of the two given classes (superclass added or removed and if the superclass itself changed).<br>
	 * <br>
	 * NOTE: The implicit superclass java.lang.Object is treated like every other superclass.
	 * 
	 * @param oldClass the old class
	 * @param newClass the new class
	 * @param classDiff the ClassDiffNode that should contain the differences
	 */
	private void diffSuperClass(SootClass oldClass, SootClass newClass, ClassDiffNode classDiff) {
		if (!oldClass.hasSuperclass()) {
			if (newClass.hasSuperclass()) {
				SootClass newSuperClass = newClass.getSuperclass();
				classDiff.addSuperClassDiff(new DependencyDiffNode(null, newSuperClass, DiffType.ADDED));
			}
			// if both classes do not have a super class, nothing changed
		} else {
			SootClass oldSuperClass = oldClass.getSuperclass();
			if (!newClass.hasSuperclass())
				// the old class had a super class, but the new one has not
				classDiff.addSuperClassDiff(new DependencyDiffNode(oldSuperClass, null, DiffType.REMOVED));
			else {
				// both classes have a super class
				SootClass newSuperClass = newClass.getSuperclass();
				if (!equal(oldSuperClass, newSuperClass))
					// the super classes differ
					classDiff.addSuperClassDiff(new DependencyDiffNode(oldSuperClass, newSuperClass, DiffType.CHANGED));
				// if the super classes are equal, nothing changed
			}
		}
	}

	/**
	 * Tests the two given SootMethods for equality. Two SootMethods are considered
	 * equal iff their sub signatures and their active bodies (if any) match.
	 * 
	 * @param m1 the first SootMethod to test
	 * @param body The hash of the second SootMethod's body
	 * @param subSignature The second SootMethod's sub-signature 
	 * @return true if the methods are considered equal, false otherwise
	 */
	public boolean equal(SootMethod m1, Integer body, String subSiganture) {
		if (!m1.getSubSignature().equals(subSiganture))
			return false;
		if ((m1.hasActiveBody() && body == null) || (!m1.hasActiveBody() && body != null))
			return false;
		if (!m1.hasActiveBody())
			return true;
		return body.equals(m1.getActiveBody().toString().hashCode());
	}

	/**
	 * Tests the two given SootFields for equality. Two SootFields are considered equal iff their signatures match.
	 * 
	 * @param f1 the first SootField to test
	 * @param f2 the second SootField to test
	 * @return true, if the fields are considered equal, false otherwise
	 */
	public boolean equal(SootField f1, SootField f2) {
		return f1.getSignature().equals(f2.getSignature());
	}

	/**
	 * Tests the two given SootClasses for equality. Two SootClasses are considered equal iff their names, fields and methods match.
	 * 
	 * @param oldClass the first SootClass to test
	 * @param newClass the second SootClass to test
	 * @return true, if the classes are considered equal, false otherwise
	 */
	public boolean equal(SootClass oldClass, SootClass newClass) {
		if (!oldClass.getName().equals(newClass.getName()))
			return false;
		if (oldClass.getFieldCount() != newClass.getFieldCount()
				|| oldClass.getMethodCount() != newClass.getMethodCount())
			return false;
		for (SootField f1 : oldClass.getFields()) {
			SootField f2 = newClass.getField(f1.getSubSignature());
			if (f2 == null || !equal(f1, f2))
				return false;
		}
		for (SootMethod m1 : oldClass.getMethods()) {
			SootMethod m2 = newClass.getMethod(m1.getSubSignature());
			if (m2 == null || !equal(m2, this.methodBodies.get(oldClass, m1), m1.getSubSignature()))
				return false;
		}
		return true;
	}

	/**
	 * A class that contains differences in a program. Essentially it is a set of {@link ClassDiffNode}s.
	 */
	public static class ProgramDiffNode implements Iterable<ClassDiffNode> {

		protected final Map<String, SootClass> classNameToClass = new HashMap<String, SootClass>();
		protected final Map<SootClass, List<SootMethod>> oldMethods;

		/**
		 * The set of ClassDiffNodes.
		 */
		protected Set<ClassDiffNode> diffClasses;

		/**
		 * Constructs an empty node.
		 * @param classNameToClass Mapping between class names and Soot classes.
		 * This object is copied, the ProgramDiffNode object does not keep a
		 * reference to the original.
		 * @param oldMethods Mapping between old classes and their respective
		 * methods. The object takes ownerships of this map which therefore
		 * must not be modified by the caller afterwards.
		 */
		public ProgramDiffNode(Map<String, SootClass> classNameToClass,
				Map<SootClass, List<SootMethod>> oldMethods) {
			diffClasses = new HashSet<ClassDiffNode>();
			this.classNameToClass.putAll(classNameToClass);
			this.oldMethods = oldMethods;
		}

		/**
		 * Adds the given class node to this program node.
		 * 
		 * @param n the node to add
		 */
		public void addDiffNode(ClassDiffNode n) {
			diffClasses.add(n);
		}

		public Iterator<ClassDiffNode> iterator() {
			return diffClasses.iterator();
		}

		@Override
		public String toString() {
			if (diffClasses.isEmpty())
				return "NO CHANGES";
			else {
				StringBuffer buff = new StringBuffer("CHANGES: \n");
				for (ClassDiffNode classDiff : diffClasses)
					buff.append(classDiff.toString());
				return buff.toString();
			}
		}

		/**
		 * Gets whether this diff node is empty, i.e. the program has not been
		 * changed.
		 * @return True if there are no changes in this diff node, otherwise
		 * false.
		 */
		public boolean isEmpty() {
			return diffClasses.isEmpty();
		}
		
		/**
		 * Gets the changes for the given Soot class.
		 * @param sc The class to check for changes. This can either be the new
		 * class or the old one.
		 * @return The changes made to the given Soot class or null if the class
		 * was not changed.
		 */
		public ClassDiffNode getClassChanges(SootClass sc) {
			for (ClassDiffNode cd : this.diffClasses)
				if (cd.getOldClass() == sc || cd.getNewClass() == sc)
					return cd;
			return null;
		}
		
		/**
		 * Gets the old class from before the update that corresponds to the given
		 * new class after the update.
		 * @param sc The new class for which to get the corresponding old one.
		 * @return The old class that corresponds to the given new one if it has
		 * been found, otherwise null.
		 */
		public SootClass getOldClassFor(SootClass sc) {
			return this.classNameToClass.get(sc.getName());
		}
		
		/**
		 * Gets the old method before the update that corresponds to the given method
		 * after the update.
		 * @param oldClass The old class in which to look for the old method
		 * @param newMethod The new method for which to get the corresponding old
		 * method
		 * @return The old method corresponding to the given new one if it has been
		 * found, otherwise null
		 */
		public SootMethod getOldMethodFor(SootClass oldClass, SootMethod newMethod) {
			assert oldClass != null && newMethod != null;
			Map<SootMethodEqualsWrapper, SootMethod> oldSootMethods =
					new HashMap<SootMethodEqualsWrapper, SootMethod>();
			if (!this.oldMethods.containsKey(oldClass))
				throw new RuntimeException("Old class " + oldClass.getName() + " not found");
			for (SootMethod oldMethod : this.oldMethods.get(oldClass)) {
				SootMethodEqualsWrapper wrapper = new SootMethodEqualsWrapper(oldMethod);
				oldSootMethods.put(wrapper, oldMethod);
			}
			SootMethodEqualsWrapper wrapper = new SootMethodEqualsWrapper(newMethod);
			return oldSootMethods.get(wrapper);
		}

		/**
		 * Gets the old method before the update that corresponds to the given method
		 * after the update.
		 * @param newMethod The new method for which to get the corresponding old
		 * method
		 * @return The old method corresponding to the given new one if it has been
		 * found, otherwise null
		 */
		public SootMethod getOldMethodFor(SootMethod newMethod) {
			SootClass oldClass = this.getOldClassFor(newMethod.getDeclaringClass());
			return this.getOldMethodFor(oldClass, newMethod);
		}
	}

	/**
	 * A class that contains differences between two classes. Essentially it is sets of {@link FieldDiffNode}s, {@link MethodDiffNode}s
	 * and two sets of {@link DependencyDiffNode}s for the super classes and implemented interfaces.
	 */
	public static class ClassDiffNode {

		/**
		 * The old class.
		 */
		protected SootClass oldClass;

		/**
		 * The new class.
		 */
		protected SootClass newClass;

		/**
		 * The type of difference between the two classes.
		 */
		protected DiffType diffType;

		/**
		 * The set of FieldDiffNodes.
		 */
		protected Set<FieldDiffNode> fieldDiffs;

		/**
		 * The set of MethodDiffNodes.
		 */
		protected Set<MethodDiffNode> methodDiffs;

		/**
		 * The set of DependencyDiffNodes for the super classes.
		 */
		protected Set<DependencyDiffNode> superClassDiffs;

		/**
		 * The set of DependencyDiffNodes for the implemented interfaces.
		 */
		protected Set<DependencyDiffNode> interfacesDiffs;

		/**
		 * Constructs a new node for the two given classes and the given type of difference between them.
		 * 
		 * @param oldClass the old class
		 * @param newClass the new class
		 * @param diffType the type of difference
		 */
		public ClassDiffNode(SootClass oldClass, SootClass newClass, DiffType diffType) {
			this.oldClass = oldClass;
			this.newClass = newClass;
			this.diffType = diffType;
			this.fieldDiffs = new HashSet<FieldDiffNode>();
			this.methodDiffs = new HashSet<MethodDiffNode>();
			this.superClassDiffs = new HashSet<DependencyDiffNode>();
			this.interfacesDiffs = new HashSet<DependencyDiffNode>();
		}

		/**
		 * Adds the given field node to this class node.
		 * 
		 * @param n the node to add
		 */
		public void addFieldDiff(FieldDiffNode n) {
			fieldDiffs.add(n);
		}

		/**
		 * Adds the given method node to this class node.
		 * 
		 * @param n the node to add
		 */
		public void addMethodDiff(MethodDiffNode n) {
			methodDiffs.add(n);
		}

		/**
		 * Adds the given dependency node for the super classes to this class node.
		 * 
		 * @param n the node to add
		 */
		public void addSuperClassDiff(DependencyDiffNode n) {
			superClassDiffs.add(n);
		}

		/**
		 * Adds the given dependency node for the implemented interfaces to this class node.
		 * 
		 * @param n the node to add
		 */
		public void addInterfacesDiff(DependencyDiffNode n) {
			interfacesDiffs.add(n);
		}

		/**
		 * Returns the old class.
		 * 
		 * @return the old class
		 */
		public SootClass getOldClass() {
			return oldClass;
		}

		/**
		 * Returns the new class.
		 * 
		 * @return the new class
		 */
		public SootClass getNewClass() {
			return newClass;
		}

		/**
		 * Returns the type of difference between the two classes.
		 * 
		 * @return the type of difference between the two classes
		 */
		public DiffType getDiffType() {
			return diffType;
		}

		/**
		 * Returns an unmodifiable set of the FieldDiffNodes.
		 * 
		 * @return an unmodifiable set of the FieldDiffNodes
		 */
		public Set<FieldDiffNode> getFieldDiffs() {
			return Collections.unmodifiableSet(fieldDiffs); // TODO why should this be unmodifiable? 
		}

		/**
		 * Returns the set of MethodDiffNodes.
		 * 
		 * @return the set of MethoddiffNodes
		 */
		public Set<MethodDiffNode> getMethodDiffs() {
			return methodDiffs;
		}
		
		/**
		 * Gets the method diff for one specific method.
		 * @param sm The Soot method for which to get the diff information. This
		 * can either be the old method or the new one.
		 * @return The changes made to the given method if such changes have been
		 * found, otherwise null.
		 */
		public MethodDiffNode getMethodDiff(SootMethod sm) {
			for (MethodDiffNode md : this.methodDiffs)
				if (md.getOldMethod() == sm || md.getNewMethod() == sm)
					return md;
			return null;
		}

		/**
		 * Returns the set of DependencyDiffNodes for the super classes.
		 * 
		 * @return the set of DependencyDiffNodes for the super classes
		 */
		public Set<DependencyDiffNode> getSuperClassDiffs() {
			return superClassDiffs;
		}

		/**
		 * Returns the set of DependencyDiffNodes for the implemented interfaces.
		 * 
		 * @return the set of DependencyDiffNodes for the implemented interfaces
		 */
		public Set<DependencyDiffNode> getInterfacesDiffs() {
			return interfacesDiffs;
		}

		@Override
		public String toString() {
			StringBuffer buff = new StringBuffer(diffType.toString());
			buff.append(" CLASS ");
			buff.append(oldClass != null ? oldClass.getName() : newClass.getName());
			buff.append(": \n");
			for (FieldDiffNode fieldDiff : fieldDiffs) {
				buff.append(fieldDiff.toString());
				buff.append("\n");
			}
			for (MethodDiffNode methodDiff : methodDiffs) {
				buff.append(methodDiff.toString());
				buff.append("\n");
			}
			for (DependencyDiffNode superDiff : superClassDiffs) {
				buff.append(superDiff.toString());
				buff.append("\n");
			}
			for (DependencyDiffNode sigDiff : interfacesDiffs) {
				buff.append(sigDiff.toString());
				buff.append("\n");
			}
			return buff.toString();
		}
	}

	/**
	 * A class that contains the differences between two fields.
	 */
	public static class FieldDiffNode {

		/**
		 * The old field.
		 */
		private final SootField oldField;

		/**
		 * The new field.
		 */
		private final SootField newField;

		/**
		 * The type of difference between the two fields.
		 */
		private final DiffType diffType;

		/**
		 * Constructs a new node for the two given fields and the given type of difference between them.
		 * 
		 * @param oldField the old field
		 * @param newField the new field
		 * @param diffType the type of difference
		 */
		public FieldDiffNode(SootField oldField, SootField newField, DiffType diffType) {
			this.oldField = oldField;
			this.newField = newField;
			this.diffType = diffType;
		}

		/**
		 * Returns the old field.
		 * 
		 * @return the old field
		 */
		public SootField getOldField() {
			return oldField;
		}

		/**
		 * Returns the new field.
		 * 
		 * @return the new field
		 */
		public SootField getNewField() {
			return newField;
		}

		/**
		 * Returns the type of difference between the two fields.
		 * 
		 * @return the type of difference between the two fields
		 */
		public DiffType getDiffType() {
			return diffType;
		}

		@Override
		public String toString() {
			StringBuffer buff = new StringBuffer("  " + diffType.toString());
			buff.append(" FIELD ");
			buff.append(oldField != null ? oldField.getName() : newField.getName());
			return buff.toString();
		}
	}

	/**
	 * A class that contains the difference between two methods.
	 */
	public static class MethodDiffNode {

		/**
		 * The old method.
		 */
		private final SootMethod oldMethod;

		/**
		 * The new method.
		 */
		private final SootMethod newMethod;

		/**
		 * The type of difference between the two methods.
		 */
		private final DiffType diffType;

		/**
		 * Constructs a new node for the two given methods and the given type of difference between them.
		 * 
		 * @param oldMethod the old method
		 * @param newMethod the new method
		 * @param diffType the type of difference
		 */
		public MethodDiffNode(SootMethod oldMethod, SootMethod newMethod, DiffType diffType) {
			this.oldMethod = oldMethod;
			this.newMethod = newMethod;
			this.diffType = diffType;
		}

		/**
		 * Returns the old method.
		 * 
		 * @return the old method
		 */
		public SootMethod getOldMethod() {
			return oldMethod;
		}

		/**
		 * Returns the new method.
		 * 
		 * @return the new method
		 */
		public SootMethod getNewMethod() {
			return newMethod;
		}

		/**
		 * Returns the type of difference between the two methods.
		 * 
		 * @return the type of difference between the two methods
		 */
		public DiffType getDiffType() {
			return diffType;
		}
		
		/**
		 * Gets the name of the method that has been changed
		 * @return The name of the changed method
		 */
		public String getMethodName() {
			return this.oldMethod == null ? this.newMethod.getName() : this.oldMethod.getName();
		}

		@Override
		public String toString() {
			StringBuffer buff = new StringBuffer("  " + diffType.toString());
			buff.append(" METHOD ");
			buff.append(oldMethod != null ? oldMethod.getSubSignature() : newMethod.getSubSignature());
			return buff.toString();
		}
	}

	/**
	 * A class that contains the difference between two dependencies, that is super classes or implemented interfaces.
	 */
	public static class DependencyDiffNode {

		/**
		 * The old dependency.
		 */
		private final SootClass oldDependency;

		/**
		 * The new dependency.
		 */
		private final SootClass newDependency;

		/**
		 * The type of difference between the two dependencies.
		 */
		private final DiffType diffType;

		/**
		 * Constructs a new node for the two given dependencies and the given type of difference between them.
		 * 
		 * @param oldDependency the old dependency
		 * @param newDependency the new dependency
		 * @param diffType the type of difference
		 */
		public DependencyDiffNode(SootClass oldDependency, SootClass newDependency, DiffType diffType) {
			this.oldDependency = oldDependency;
			this.newDependency = newDependency;
			this.diffType = diffType;
		}

		/**
		 * Returns the old dependency.
		 * 
		 * @return the old dependency
		 */
		public SootClass getOldDependency() {
			return oldDependency;
		}

		/**
		 * Returns the new dependency.
		 * 
		 * @return the new dependency
		 */
		public SootClass getNewDependency() {
			return newDependency;
		}

		/**
		 * Returns the type of difference between the two dependencies.
		 * 
		 * @return the type of difference between the two dependencies
		 */
		public DiffType getDiffType() {
			return diffType;
		}

		@Override
		public String toString() {
			StringBuffer buff = new StringBuffer("  " + diffType.toString());
			buff.append(" DEPENDENCY ");
			buff.append(oldDependency != null ? oldDependency.getName() : newDependency.getName());
			return buff.toString();
		}
	}

	/**
	 * Wrapper for a SootField that is equal to another wrapper when both associated fields have the same name.
	 * This is useful when indexing on "similar" fields, where fields are similar when they have the same name.
	 */
	public static class SootFieldEqualsWrapper {

		/**
		 * The associated field.
		 */
		protected SootField f;

		/**
		 * Constructs a new wrapper for the given field.
		 * 
		 * @param f the field
		 */
		public SootFieldEqualsWrapper(SootField f) {
			this.f = f;
		}

		/**
		 * Returns the associated field.
		 * 
		 * @return the associated field
		 */
		public SootField getField() {
			return f;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((f == null) ? 0 : f.getName().hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			SootFieldEqualsWrapper other = (SootFieldEqualsWrapper) obj;
			if (f == null) {
				if (other.f != null)
					return false;
			} else if (!f.getName().equals(other.f.getName()))
				return false;
			return true;
		}

	}

	/**
	 * Wrapper for a SootMethod that is equal to another wrapper when both associated methods have the same (sub-)signature.
	 * This is useful when indexing on "similar" methods, where fields are similar when they have the same signature.
	 */
	public static class SootMethodEqualsWrapper {

		/**
		 * The associated method.
		 */
		protected SootMethod m;

		/**
		 * Constructs a new wrapper for the given method.
		 * 
		 * @param m the method
		 */
		public SootMethodEqualsWrapper(SootMethod m) {
			this.m = m;
		}

		/**
		 * Returns the associated method.
		 * 
		 * @return the associated method
		 */
		public SootMethod getMethod() {
			return m;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((m == null) ? 0 : m.getSubSignature().hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj || super.equals(obj))
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			SootMethodEqualsWrapper other = (SootMethodEqualsWrapper) obj;
			if (m == null)
				return other.m == null;
			return m.getSubSignature().equals(other.m.getSubSignature());
		}

	}

	/**
	 * Wrapper for a SootClass that is equal to another wrapper when both associated classes have the same name.
	 * This is useful when indexing on "similar" classes, where classes are similar when they have the same name.
	 */
	public static class SootClassEqualsWrapper {

		/**
		 * The associated class.
		 */
		protected SootClass c;

		/**
		 * Constructs a new wrapper for the given class.
		 * 
		 * @param c the class
		 */
		public SootClassEqualsWrapper(SootClass c) {
			this.c = c;
		}

		/**
		 * Returns the associated class.
		 * 
		 * @return the associated class
		 */
		public SootClass getClazz() {
			return c;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((c == null) ? 0 : c.getName().hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			SootClassEqualsWrapper other = (SootClassEqualsWrapper) obj;
			if (c == null) {
				if (other.c != null)
					return false;
			} else if (!c.getName().equals(other.c.getName()))
				return false;
			return true;
		}

	}

	/**
	 * A enumeration of different types of changes.
	 */
	public enum DiffType {
		
		/**
		 * If something was added.
		 */
		ADDED, 
		
		/**
		 * If something was removed.
		 */
		REMOVED,
		
		/**
		 * If something has changed.
		 */
		CHANGED;
	}

}
