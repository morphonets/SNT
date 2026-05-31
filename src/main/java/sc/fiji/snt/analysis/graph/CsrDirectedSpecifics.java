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

import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.jgrapht.GraphType;
import org.jgrapht.graph.specifics.Specifics;
import sc.fiji.snt.util.SWCPoint;

import java.util.*;
import java.util.function.Supplier;

/**
 * Compressed-sparse-row (CSR) implementation of jgrapht's
 * {@link Specifics} for directed {@link SWCPoint} / {@link SWCWeightedEdge}
 * graphs. Drop-in replacement for jgrapht's {@code DirectedSpecifics} and
 * {@code FastLookupDirectedSpecifics} that trades the per-vertex
 * incoming/outgoing edge {@code ArrayList}s for primitive {@code int[]}.
 * <p>
 * Memory accounting at scale (used by {@link SparseDirectedWeightedGraph} on
 * the disk-backed GWDT tracer's post-Fast-Marching forest):
 * <ul>
 *   <li>Per vertex: 16 bytes ({@code firstOutgoing} + {@code firstIncoming} +
 *       two cached degrees) + the {@link SWCPoint} object itself + one
 *       fastutil index-map entry.</li>
 *   <li>Per edge: ~28 bytes (source, target, next/prev outgoing, next/prev
 *       incoming) + one fastutil index-map entry + the
 *       {@link SWCWeightedEdge} object (handled by
 *       {@code WeightedIntrusiveEdgesSpecifics}).</li>
 * </ul>
 * versus ~400 B/vertex and ~250 B/edge for jgrapht's default
 * {@code FastLookupDirectedSpecifics}.
 * <p>
 * Trade-offs:
 * <ul>
 *   <li>{@link #getEdge(SWCPoint, SWCPoint)} and
 *       {@link #getAllEdges(SWCPoint, SWCPoint)} degrade from O(1) to
 *       O(out-degree of source): no per-pair index. Pruners enumerate edges
 *       by vertex only</li>
 *   <li>{@link #removeEdgeFromTouchingVertices(SWCPoint, SWCPoint, SWCWeightedEdge)}
 *       is O(1): the doubly-linked-list backbone lets us unlink without a
 *       scan</li>
 *   <li>Edge / vertex removal is <em>lazy</em>: slots are not reclaimed</li>
 *   <li>{@link Set#size()} on {@link #incomingEdgesOf}/{@link #outgoingEdgesOf}
 *       runs O(1) thanks to cached degrees; {@link #edgesOf} materialises a
 *       new {@link LinkedHashSet} of size {@code in+out} per call</li>
 * </ul>
 *
 * @author Tiago Ferreira
 * @see SparseDirectedWeightedGraph
 * @see CsrGraphSpecificsStrategy
 */
final class CsrDirectedSpecifics implements Specifics<SWCPoint, SWCWeightedEdge> {

    private static final long serialVersionUID = 1L;

    /**
     * Sentinel for "no edge / vertex" in the int arrays.
     */
    private static final int NONE = -1;

    /**
     * Whether this graph type allows multiple edges between the same vertex
     * pair. For SNT's {@link DirectedWeightedGraph} this is always {@code false},
     * but we honor it so the same specifics could host a pseudograph variant.
     */
    private final boolean allowsMultipleEdges;

    // ---------------- Vertex storage ----------------
    // vertexIndex's keySet() is the authoritative vertex set (insertion-ordered
    // and supports remove(), which AbstractBaseGraph drives when a vertex is
    // removed from the graph). Slot ids are stable for the life of the graph
    // (no compaction); removed vertices leave tombstones in `vertices`.
    // Object2IntLinkedOpenHashMap mirrors LinkedHashMap semantics while keeping
    // the int values unboxed: ~16 bytes/entry vs ~50 for HashMap<V, Integer>.
    private final Object2IntLinkedOpenHashMap<SWCPoint> vertexIndex = new Object2IntLinkedOpenHashMap<>();
    private final ArrayList<SWCPoint> vertices = new ArrayList<>();
    // per-slot adjacency heads + cached degrees
    private int[] firstOutgoing = new int[16];
    private int[] firstIncoming = new int[16];
    private int[] outDeg = new int[16];
    private int[] inDeg = new int[16];

    // Edge storage (adjacency only)
    // Edge metadata (source/target/weight) is owned by IntrusiveEdgesSpecifics;
    // here we duplicate source/target slot ids only for fast adjacency walks.
    // Non-linked Object2IntOpenHashMap (no insertion-order guarantee needed:
    // edges are never enumerated via this map; adjacency walks use the
    // linked-list arrays)
    private final Object2IntOpenHashMap<SWCWeightedEdge> edgeIndex = new Object2IntOpenHashMap<>();
    private final ArrayList<SWCWeightedEdge> edges = new ArrayList<>();
    private int[] edgeSource = new int[64];
    private int[] edgeTarget = new int[64];
    private int[] nextOutgoing = new int[64];
    private int[] prevOutgoing = new int[64];
    private int[] nextIncoming = new int[64];
    private int[] prevIncoming = new int[64];

    /**
     * Marks edge slots that have been logically removed.
     */
    private final BitSet deletedEdges = new BitSet();

    /**
     * @param type graph type: used only to read {@code allowsMultipleEdges()}.
     */
    CsrDirectedSpecifics(final GraphType type) {
        this.allowsMultipleEdges = type.isAllowingMultipleEdges();
        // Make slot-lookup misses return our NONE sentinel instead of fastutil's
        // default 0 (which would collide with a legitimate slot=0).
        vertexIndex.defaultReturnValue(NONE);
        edgeIndex.defaultReturnValue(NONE);
    }

    // Vertices

    @Override
    public boolean addVertex(final SWCPoint v) {
        if (vertexIndex.containsKey(v)) return false;
        final int slot = vertices.size();
        vertices.add(v);
        vertexIndex.put(v, slot);
        ensureVertexCapacity(slot + 1);
        firstOutgoing[slot] = NONE;
        firstIncoming[slot] = NONE;
        outDeg[slot] = 0;
        inDeg[slot] = 0;
        return true;
    }

    @Override
    public Set<SWCPoint> getVertexSet() {
        // AbstractBaseGraph calls `.remove(v)` on this set when removing a
        // vertex from the graph. fastutil's keySet view supports remove and
        // propagates the deletion to the underlying map, so our slot()
        // lookups for the removed vertex will subsequently return NONE.
        // The orphaned slot in `vertices` becomes a tombstone
        return vertexIndex.keySet();
    }

    // Edge lookup

    @Override
    public Set<SWCWeightedEdge> getAllEdges(final SWCPoint sourceVertex, final SWCPoint targetVertex) {
        if (sourceVertex == null || targetVertex == null) return null;
        final int s = slot(sourceVertex);
        final int t = slot(targetVertex);
        if (s == NONE || t == NONE) return null;
        // We honor the multi-edges flag even though SNT's DirectedWeightedGraph
        // forbids them; This keeps the specifics reusable for pseudographs.
        final LinkedHashSet<SWCWeightedEdge> out = new LinkedHashSet<>();
        for (int e = firstOutgoing[s]; e != NONE; e = nextOutgoing[e]) {
            if (edgeTarget[e] == t) {
                out.add(edges.get(e));
                if (!allowsMultipleEdges) break;
            }
        }
        return out;
    }

    @Override
    public SWCWeightedEdge getEdge(final SWCPoint sourceVertex, final SWCPoint targetVertex) {
        if (sourceVertex == null || targetVertex == null) return null;
        final int s = slot(sourceVertex);
        final int t = slot(targetVertex);
        if (s == NONE || t == NONE) return null;
        for (int e = firstOutgoing[s]; e != NONE; e = nextOutgoing[e]) {
            if (edgeTarget[e] == t) return edges.get(e);
        }
        return null;
    }

    // Edge mutation

    @Override
    public boolean addEdgeToTouchingVertices(final SWCPoint sourceVertex,
                                             final SWCPoint targetVertex,
                                             final SWCWeightedEdge e) {
        registerEdge(sourceVertex, targetVertex, e);
        return true;
    }

    @Override
    public boolean addEdgeToTouchingVerticesIfAbsent(final SWCPoint sourceVertex,
                                                     final SWCPoint targetVertex,
                                                     final SWCWeightedEdge e) {
        if (!allowsMultipleEdges && getEdge(sourceVertex, targetVertex) != null) return false;
        registerEdge(sourceVertex, targetVertex, e);
        return true;
    }

    @Override
    public SWCWeightedEdge createEdgeToTouchingVerticesIfAbsent(final SWCPoint sourceVertex,
                                                                final SWCPoint targetVertex,
                                                                final Supplier<SWCWeightedEdge> edgeSupplier) {
        if (!allowsMultipleEdges && getEdge(sourceVertex, targetVertex) != null) return null;
        if (edgeSupplier == null) return null;
        final SWCWeightedEdge e = edgeSupplier.get();
        registerEdge(sourceVertex, targetVertex, e);
        return e;
    }

    @Override
    public void removeEdgeFromTouchingVertices(final SWCPoint sourceVertex,
                                               final SWCPoint targetVertex,
                                               final SWCWeightedEdge e) {
        final int idx = edgeSlot(e);
        if (idx == NONE || deletedEdges.get(idx)) return;
        unlinkEdge(idx, e);
    }

    // Degree / neighborhood

    @Override
    public int degreeOf(final SWCPoint v) {
        final int s = slot(v);
        return (s == NONE) ? 0 : outDeg[s] + inDeg[s];
    }

    @Override
    public int inDegreeOf(final SWCPoint v) {
        final int s = slot(v);
        return (s == NONE) ? 0 : inDeg[s];
    }

    @Override
    public int outDegreeOf(final SWCPoint v) {
        final int s = slot(v);
        return (s == NONE) ? 0 : outDeg[s];
    }

    @Override
    public Set<SWCWeightedEdge> edgesOf(final SWCPoint v) {
        final int s = slot(v);
        if (s == NONE) return Collections.emptySet();
        // Union of incoming and outgoing.
        final LinkedHashSet<SWCWeightedEdge> result = new LinkedHashSet<>(outDeg[s] + inDeg[s]);
        for (int e = firstOutgoing[s]; e != NONE; e = nextOutgoing[e]) result.add(edges.get(e));
        for (int e = firstIncoming[s]; e != NONE; e = nextIncoming[e]) result.add(edges.get(e));
        return result;
    }

    @Override
    public Set<SWCWeightedEdge> incomingEdgesOf(final SWCPoint v) {
        final int s = slot(v);
        if (s == NONE) return Collections.emptySet();
        return new IncomingEdgeSet(s);
    }

    @Override
    public Set<SWCWeightedEdge> outgoingEdgesOf(final SWCPoint v) {
        final int s = slot(v);
        if (s == NONE) return Collections.emptySet();
        return new OutgoingEdgeSet(s);
    }

    // Internal helpers

    private int slot(final SWCPoint v) {
        return (v == null) ? NONE : vertexIndex.getInt(v);
    }

    private int edgeSlot(final SWCWeightedEdge e) {
        return (e == null) ? NONE : edgeIndex.getInt(e);
    }

    private void registerEdge(final SWCPoint sourceVertex,
                              final SWCPoint targetVertex,
                              final SWCWeightedEdge e) {
        final int s = slot(sourceVertex);
        final int t = slot(targetVertex);
        if (s == NONE || t == NONE) {
            // AbstractBaseGraph validates membership before calling us, so this is defensive only
            throw new IllegalArgumentException("source or target vertex is not in the graph");
        }
        final int idx = edges.size();
        edges.add(e);
        edgeIndex.put(e, idx);
        ensureEdgeCapacity(idx + 1);
        edgeSource[idx] = s;
        edgeTarget[idx] = t;
        // prepend to source's outgoing list
        final int oldOutHead = firstOutgoing[s];
        nextOutgoing[idx] = oldOutHead;
        prevOutgoing[idx] = NONE;
        if (oldOutHead != NONE) prevOutgoing[oldOutHead] = idx;
        firstOutgoing[s] = idx;
        outDeg[s]++;
        // prepend to target's incoming list
        final int oldInHead = firstIncoming[t];
        nextIncoming[idx] = oldInHead;
        prevIncoming[idx] = NONE;
        if (oldInHead != NONE) prevIncoming[oldInHead] = idx;
        firstIncoming[t] = idx;
        inDeg[t]++;
    }

    private void unlinkEdge(final int idx, final SWCWeightedEdge edgeObj) {
        final int s = edgeSource[idx];
        final int t = edgeTarget[idx];
        // unlink from source's outgoing list
        final int po = prevOutgoing[idx];
        final int no = nextOutgoing[idx];
        if (po == NONE) firstOutgoing[s] = no;
        else nextOutgoing[po] = no;
        if (no != NONE) prevOutgoing[no] = po;
        outDeg[s]--;
        // unlink from target's incoming list
        final int pi = prevIncoming[idx];
        final int ni = nextIncoming[idx];
        if (pi == NONE) firstIncoming[t] = ni;
        else nextIncoming[pi] = ni;
        if (ni != NONE) prevIncoming[ni] = pi;
        inDeg[t]--;
        // tombstone the slot: leaves the linked-list arrays alone but stops
        // future lookups and frees the SWCWeightedEdge reference
        deletedEdges.set(idx);
        edges.set(idx, null);
        edgeIndex.removeInt(edgeObj);
    }

    private void ensureVertexCapacity(final int needed) {
        if (needed <= firstOutgoing.length) return;
        final int newLen = Math.max(needed, firstOutgoing.length * 2);
        firstOutgoing = growIntArray(firstOutgoing, newLen, NONE);
        firstIncoming = growIntArray(firstIncoming, newLen, NONE);
        outDeg = growIntArray(outDeg, newLen, 0);
        inDeg = growIntArray(inDeg, newLen, 0);
    }

    private void ensureEdgeCapacity(final int needed) {
        if (needed <= edgeSource.length) return;
        final int newLen = Math.max(needed, edgeSource.length * 2);
        edgeSource = growIntArray(edgeSource, newLen, NONE);
        edgeTarget = growIntArray(edgeTarget, newLen, NONE);
        nextOutgoing = growIntArray(nextOutgoing, newLen, NONE);
        prevOutgoing = growIntArray(prevOutgoing, newLen, NONE);
        nextIncoming = growIntArray(nextIncoming, newLen, NONE);
        prevIncoming = growIntArray(prevIncoming, newLen, NONE);
    }

    private static int[] growIntArray(final int[] src, final int newLen, final int fill) {
        final int[] out = new int[newLen];
        System.arraycopy(src, 0, out, 0, src.length);
        if (fill != 0) {
            for (int i = src.length; i < newLen; i++) out[i] = fill;
        }
        return out;
    }

    // Per-vertex edge-list views (live, no allocation)

    /**
     * Live, non-allocating view over a vertex's outgoing edge linked-list.
     * Walks {@code firstOutgoing → nextOutgoing} on each iteration.
     */
    private final class OutgoingEdgeSet extends AbstractSet<SWCWeightedEdge> {
        private final int vertex;

        OutgoingEdgeSet(final int vertex) {
            this.vertex = vertex;
        }

        @Override
        public int size() {
            return outDeg[vertex];
        }

        @Override
        public boolean isEmpty() {
            return outDeg[vertex] == 0;
        }

        @Override
        public Iterator<SWCWeightedEdge> iterator() {
            return new EdgeListIterator(firstOutgoing[vertex], /*outgoing=*/true);
        }

        @Override
        public boolean contains(final Object o) {
            if (!(o instanceof SWCWeightedEdge)) return false;
            final int idx = edgeSlot((SWCWeightedEdge) o);
            return idx != NONE && !deletedEdges.get(idx) && edgeSource[idx] == vertex;
        }
    }

    /**
     * Live, non-allocating view over a vertex's incoming edge linked-list.
     */
    private final class IncomingEdgeSet extends AbstractSet<SWCWeightedEdge> {
        private final int vertex;

        IncomingEdgeSet(final int vertex) {
            this.vertex = vertex;
        }

        @Override
        public int size() {
            return inDeg[vertex];
        }

        @Override
        public boolean isEmpty() {
            return inDeg[vertex] == 0;
        }

        @Override
        public Iterator<SWCWeightedEdge> iterator() {
            return new EdgeListIterator(firstIncoming[vertex], /*outgoing=*/false);
        }

        @Override
        public boolean contains(final Object o) {
            if (!(o instanceof SWCWeightedEdge)) return false;
            final int idx = edgeSlot((SWCWeightedEdge) o);
            return idx != NONE && !deletedEdges.get(idx) && edgeTarget[idx] == vertex;
        }
    }

    /**
     * Iterator over a per-vertex linked-list of edges. Snapshots the
     * next-pointer before yielding the current edge, so the caller may
     * unlink the just-yielded edge mid-iteration without breaking traversal.
     * <p>
     * Also skips over any tombstoned slots reached via {@code next/prev}
     * pointers. Needed because the deleted edge's own {@code nextOutgoing}
     * / {@code nextIncoming} entries are NOT scrubbed by {@link #unlinkEdge},
     * so an iterator that captured a now-deleted slot as its {@code next}
     * pointer must walk forward until it finds a live edge or hits the end.
     */
    private final class EdgeListIterator implements Iterator<SWCWeightedEdge> {
        private int next;
        private final boolean outgoing;

        EdgeListIterator(final int head, final boolean outgoing) {
            this.next = head;
            this.outgoing = outgoing;
            advancePastDeleted();
        }

        @Override
        public boolean hasNext() {
            return next != NONE;
        }

        @Override
        public SWCWeightedEdge next() {
            if (next == NONE) throw new NoSuchElementException();
            final int curr = next;
            next = outgoing ? nextOutgoing[curr] : nextIncoming[curr];
            advancePastDeleted();
            return edges.get(curr);
        }

        private void advancePastDeleted() {
            while (next != NONE && deletedEdges.get(next)) {
                next = outgoing ? nextOutgoing[next] : nextIncoming[next];
            }
        }
    }
}
