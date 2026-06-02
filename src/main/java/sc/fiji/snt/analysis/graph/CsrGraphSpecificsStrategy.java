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

package sc.fiji.snt.analysis.graph;

import org.jgrapht.Graph;
import org.jgrapht.GraphType;
import org.jgrapht.graph.GraphSpecificsStrategy;
import org.jgrapht.graph.IntrusiveEdgesSpecifics;
import org.jgrapht.graph.WeightedIntrusiveEdgesSpecifics;
import org.jgrapht.graph.specifics.Specifics;
import sc.fiji.snt.util.SWCPoint;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Specifics strategy that produces a {@link CsrDirectedSpecifics} (per-vertex
 * CSR adjacency) backed by jgrapht's stock {@link WeightedIntrusiveEdgesSpecifics}
 * for edge metadata.
 * <p>
 * Used by {@link SparseDirectedWeightedGraph} when the disk-backed GWDT tracer
 * builds its post-Fast-Marching forest. Memory drops from ~400 bytes/vertex
 * (jgrapht's default FastLookup specifics) to ~30 bytes/vertex;
 *
 * @author Tiago Ferreira
 */
final class CsrGraphSpecificsStrategy implements GraphSpecificsStrategy<SWCPoint, SWCWeightedEdge> {

    private static final long serialVersionUID = 1L;

    @Override
    public BiFunction<Graph<SWCPoint, SWCWeightedEdge>, GraphType, Specifics<SWCPoint, SWCWeightedEdge>> getSpecificsFactory() {
        // AbstractBaseGraph passes (this, graphType) into this BiFunction to
        // build the Specifics. Our specifics is graph-agnostic, so we ignore
        // the Graph argument and just use the GraphType.
        return (graph, type) -> new CsrDirectedSpecifics(type);
    }

    @Override
    public Function<GraphType, IntrusiveEdgesSpecifics<SWCPoint, SWCWeightedEdge>> getIntrusiveEdgesSpecificsFactory() {
        return type -> new WeightedIntrusiveEdgesSpecifics<>(new java.util.LinkedHashMap<>());
    }
}
