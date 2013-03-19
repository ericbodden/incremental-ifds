package soot.jimple.interproc.ifds;

/**
 * Interface that extends joinable half-lattices to full latices supporting
 * both meet and join operations.
 *
 * @author Steven Arzt
 */
public interface Lattice<V> extends JoinLattice<V> {
	
	V meet(V left, V right);

}
