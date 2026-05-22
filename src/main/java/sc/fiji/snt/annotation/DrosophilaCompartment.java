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

import org.scijava.util.ColorRGB;
import sc.fiji.snt.viewer.OBJMesh;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a brain region from the Drosophila Anatomy Ontology (FBbt).
 *
 * @author Tiago Ferreira
 */
public class DrosophilaCompartment implements BrainAnnotation {

    /**
     * Package-private map populated by DrosophilaUtils.
     */
    static Map<Integer, DrosophilaCompartment> compartmentMap = new HashMap<>();

    int id;
    String name;
    String acronym;
    String[] synonyms;
    int parentId = -1;
    int depth;

    @Override
    public int id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String acronym() {
        return (acronym == null) ? "" : acronym;
    }

    @Override
    public String[] aliases() {
        return (synonyms == null) ? new String[0] : synonyms;
    }

    @Override
    public OBJMesh getMesh() {
        return null;
    }

    @Override
    public boolean isMeshAvailable() {
        return false;
    }

    @Override
    public boolean isChildOf(final BrainAnnotation annotation) {
        if (annotation == null) return false;
        final int targetId = annotation.id();
        DrosophilaCompartment current = this;
        while (current.parentId != -1) {
            if (current.parentId == targetId) return true;
            current = compartmentMap.get(current.parentId);
            if (current == null) break;
        }
        return false;
    }

    @Override
    public boolean isParentOf(final BrainAnnotation annotation) {
        return annotation != null && annotation.isChildOf(this);
    }

    @Override
    public int getOntologyDepth() {
        return depth;
    }

    @Override
    public BrainAnnotation getAncestor(final int level) {
        if (level >= 0) return null;
        DrosophilaCompartment current = this;
        for (int i = 0; i > level; i--) {
            if (current.parentId == -1) return null;
            current = compartmentMap.get(current.parentId);
            if (current == null) return null;
        }
        return current;
    }

    @Override
    public BrainAnnotation getParent() {
        if (parentId == -1) return null;
        return compartmentMap.get(parentId);
    }

    @Override
    public ColorRGB color() {
        return null;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        final String a = acronym();
        if (!a.isEmpty()) {
            sb.append(a);
            if (!a.equalsIgnoreCase(name)) sb.append(": ").append(name);
        } else {
            sb.append(name);
        }
        sb.append("  (").append(id).append(")");
        return sb.toString();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return id == ((DrosophilaCompartment) o).id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }

}
