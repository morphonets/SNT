/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2026 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt.annotation;

import java.util.Comparator;

import org.scijava.util.ColorRGB;

import sc.fiji.snt.viewer.OBJMesh;

/**
 * Classes extending this interface implement a neuropil label/annotation aka
 * "compartment".
 *
 * @author Tiago Ferreira
 */
public interface BrainAnnotation {

	/** Character constant representing the left hemisphere */
	public char LEFT_HEMISPHERE = 'l';
	/** Character constant representing the right hemisphere */
	public char RIGHT_HEMISPHERE = 'r';
	/** Character constant representing any hemisphere (null character) */
	public char ANY_HEMISPHERE = '\u0000';

	/** Returns the compartment's unique id. */
	public int id();

	/** Returns the compartment's name. */
	public String name();

	/** Returns the compartment's acronym. */
	public String acronym();

	/** Returns the compartment's alias(es). */
	public String[] aliases();

	/** Returns the mesh associated with this compartment. */
	public OBJMesh getMesh();

	/** Returns whether this compartment is a sub-compartment of {@code annotation}. */
	public boolean isChildOf(BrainAnnotation annotation);

	/** Returns whether this compartment is a parent of {@code parentCompartment}. */
	public boolean isParentOf(final BrainAnnotation parentCompartment);

	/** Returns the ontology depth of this compartment. */
	public int getOntologyDepth();

	/** Returns whether a mesh is available for this compartment. */
	public boolean isMeshAvailable();

	/**
	 * Returns the ancestor of this compartment at the specified level.
	 *
	 * @param level the ancestor level as negative 1-based index. E.g., {@code -1}
	 *              retrieves the last ancestor (parent), {@code -2} retrieves the
	 *              second to last, etc
	 * @return the ancestor at the given level
	 */
	public BrainAnnotation getAncestor(final int level);

	/** Returns the parent of this compartment. */
	public BrainAnnotation getParent();

	/** Returns the display color of this compartment (if known). */
	public ColorRGB color();

	public static char getHemisphereFlag(final String hemisphere) {
		final char flag = hemisphere.trim().toLowerCase().charAt(0);
        return switch (flag) {
            case LEFT_HEMISPHERE, RIGHT_HEMISPHERE -> flag;
            default -> ANY_HEMISPHERE;
        };
	}

	public static Comparator<BrainAnnotation> comparator() {
		return Comparator.nullsLast(
				Comparator.comparing(BrainAnnotation::getOntologyDepth, Comparator.nullsLast(Comparator.reverseOrder()))
						.thenComparing(BrainAnnotation::acronym, Comparator.nullsLast(String::compareTo)));
//						.thenComparing(BrainAnnotation::acronym, Comparator.nullsLast(String::compareToIgnoreCase)));
	}

	public static String simplifiedString(final BrainAnnotation annotation) {
		if (annotation == null)
			return "Other (n/a)";
		if (annotation.getOntologyDepth() == 0)
			return "Other (root)";
		return annotation.acronym();
	}

}
