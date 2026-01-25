/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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

/**
 * Path-finding algorithms for interactive neuron tracing.
 * <p>
 * This package provides A*-based search algorithms for finding optimal paths
 * between user-specified points in neuronal images. These are the core tracing
 * engines used by SNT's interactive tracing mode.
 * </p>
 *
 * <h2>Package Organization</h2>
 * <ul>
 *   <li>{@link sc.fiji.snt.tracing} - Core search algorithms and interfaces (this package)</li>
 *   <li>{@link sc.fiji.snt.tracing.cost} - Cost functions for edge weights</li>
 *   <li>{@link sc.fiji.snt.tracing.heuristic} - Heuristic functions for A* search</li>
 *   <li>{@link sc.fiji.snt.tracing.image} - Search image data structures</li>
 *   <li>{@link sc.fiji.snt.tracing.artist} - Real-time visualization of search progress</li>
 *   <li>{@link sc.fiji.snt.tracing.auto} - Automatic whole-neuron tracing algorithms</li>
 * </ul>
 *
 * <h2>Core Classes</h2>
 * <dl>
 *   <dt>{@link sc.fiji.snt.tracing.TracerThread}</dt>
 *   <dd>The primary A* path finder. Computes optimal paths between two points
 *       using intensity-based cost functions and configurable heuristics.</dd>
 *
 *   <dt>{@link sc.fiji.snt.tracing.BiSearch}</dt>
 *   <dd>Bidirectional A* search that explores from both endpoints simultaneously,
 *       often faster for long paths.</dd>
 *
 *   <dt>{@link sc.fiji.snt.tracing.FillerThread}</dt>
 *   <dd>Region-growing algorithm for filling/segmenting neuronal volumes.
 *       Used for distance-based analysis and volume rendering.</dd>
 *
 *   <dt>{@link sc.fiji.snt.tracing.TubularGeodesicsTracer}</dt>
 *   <dd>Geodesic path finder optimized for tubular structures using
 *       orientation-enhanced cost functions.</dd>
 *
 *   <dt>{@link sc.fiji.snt.tracing.ManualTracerThread}</dt>
 *   <dd>Lightweight tracer for manual point-to-point connections without
 *       automated path optimization.</dd>
 * </dl>
 *
 * <h2>Search Infrastructure</h2>
 * <dl>
 *   <dt>{@link sc.fiji.snt.tracing.SearchInterface}</dt>
 *   <dd>Common interface for all search algorithms, defining lifecycle methods
 *       and result retrieval.</dd>
 *
 *   <dt>{@link sc.fiji.snt.tracing.AbstractSearch}</dt>
 *   <dd>Base implementation with shared functionality: priority queue management,
 *       neighbor iteration, path reconstruction.</dd>
 *
 *   <dt>{@link sc.fiji.snt.tracing.SearchNode} / {@link sc.fiji.snt.tracing.DefaultSearchNode}</dt>
 *   <dd>Graph nodes storing position, costs (g, h, f), and parent pointers for
 *       path reconstruction.</dd>
 *
 *   <dt>{@link sc.fiji.snt.tracing.PathResult}</dt>
 *   <dd>Encapsulates search results: the found path, search statistics, and
 *       success/failure status.</dd>
 * </dl>
 *
 * <h2>Algorithm Overview</h2>
 * <p>
 * The A* algorithm finds the optimal path by minimizing {@code f(n) = g(n) + h(n)}, where:
 * </p>
 * <ul>
 *   <li>{@code g(n)} - Actual cost from start to node n (computed via {@link sc.fiji.snt.tracing.cost.Cost})</li>
 *   <li>{@code h(n)} - Estimated cost from n to goal (computed via {@link sc.fiji.snt.tracing.heuristic.Heuristic})</li>
 * </ul>
 * <p>
 * Cost functions typically use image intensity (brighter = lower cost for neurites),
 * while heuristics use Euclidean distance or Dijkstra (h=0) for guaranteed optimality.
 * </p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create tracer with reciprocal cost (bright = low cost)
 * TracerThread tracer = new TracerThread(
 *     searchImage,
 *     startX, startY, startZ,
 *     endX, endY, endZ,
 *     new Reciprocal(minCost, maxCost),
 *     new Euclidean()
 * );
 *
 * // Run search (blocks until complete)
 * tracer.run();
 *
 * // Get result
 * Path path = tracer.getResult();
 * }</pre>
 *
 * @see sc.fiji.snt.tracing.auto
 * @see sc.fiji.snt.Path
 * @see sc.fiji.snt.PathAndFillManager
 * @author Mark Longair
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
package sc.fiji.snt.tracing;
