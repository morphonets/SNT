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

/**
 * Automatic neuron tracing algorithms that reconstruct complete neuronal
 * morphologies from images without user interaction.
 * <p>
 * This package provides implementations of whole-neuron auto-tracing algorithms,
 * as opposed to the interactive point-to-point A*-based tracers in the parent
 * {@code sc.fiji.snt.tracing} package.
 * </p>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link sc.fiji.snt.tracing.auto.AutoTracer} - Common interface for all
 *       auto-tracers, defining ROI strategy constants and the {@code traceTrees()}
 *       contract</li>
 *   <li>{@link sc.fiji.snt.tracing.auto.AbstractAutoTracer} - Base class for
 *       grayscale-based tracers with shared soma ROI handling and graph utilities</li>
 *   <li>{@link sc.fiji.snt.tracing.auto.GWDTTracer} - APP2-style tracer using
 *       Gray-Weighted Distance Transform and hierarchical pruning</li>
 *   <li>{@link sc.fiji.snt.tracing.auto.BinaryTracer} - Skeleton-based tracer
 *       using topological thinning and AnalyzeSkeleton</li>
 * </ul>
 *
 * <h2>Algorithm Categories</h2>
 * <dl>
 *   <dt>Grayscale-based (extends {@code AbstractAutoTracer})</dt>
 *   <dd>Operate directly on intensity images without binarization. Use geodesic
 *       distance transforms and intensity-weighted path finding.</dd>
 *
 *   <dt>Skeleton-based ({@code BinaryTracer})</dt>
 *   <dd>Require binarization and topological skeletonization. Convert skeleton
 *       graphs to neuronal trees. Suitable for high-contrast images with clear
 *       foreground/background separation.</dd>
 * </dl>
 *
 * <h2>Soma ROI Strategies</h2>
 * All auto-tracers support configurable soma handling via {@link AutoTracer}
 * constants:
 * <ul>
 *   <li>{@code ROI_UNSET} - Ignore soma ROI, root at algorithm-specific point</li>
 *   <li>{@code ROI_EDGE} - Split into separate trees per neurite exiting soma</li>
 *   <li>{@code ROI_CENTROID} - Collapse soma nodes to ROI geometric centroid</li>
 *   <li>{@code ROI_CENTROID_WEIGHTED} - Collapse to weighted centroid of soma nodes</li>
 *   <li>{@code ROI_CONTAINED} - Root on nodes inside ROI (skeleton-based only)</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Grayscale-based tracing
 * GWDTTracer<?> tracer = GWDTTracer.create(imagePlus);
 * tracer.setSeed(somaCenter);
 * tracer.setSomaRoi(somaRoi, AutoTracer.ROI_CENTROID);
 * List<Tree> trees = tracer.traceTrees();
 *
 * // Skeleton-based tracing
 * BinaryTracer tracer = new BinaryTracer(binaryImage);
 * tracer.setRootRoi(somaRoi, AutoTracer.ROI_EDGE);
 * List<Tree> trees = tracer.traceTrees();
 * }</pre>
 *
 * @see sc.fiji.snt.tracing
 * @see sc.fiji.snt.Tree
 * @author Tiago Ferreira
 */
package sc.fiji.snt.tracing.auto;
