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

package sc.fiji.snt.util;

import sc.fiji.snt.Path;

import java.util.Collection;
import java.util.List;

/**
 * Detects regions where two paths run <em>parallel</em> to each other for a sustained distance, complementary
 * to {@link CrossoverFinder} (which catches brief perpendicular near-crossings). Common use case: identifying
 * axons bundled along the same nerve, or a path that was inadvertently hijacked by signal from a neighboring
 * neurite running side-by-side.
 * <p>
 * This is a thin wrapper over {@link CrossoverFinder}: it reuses the entire grid/proximity/run-extraction
 * pipeline and only inverts the angle filter. Where {@code CrossoverFinder} (with {@code thetaMinDeg > 0})
 * keeps only <em>high-angle</em> approaches (X-shaped crossings), {@code BundleDetector} uses
 * {@code thetaMaxDeg > 0} to keep only <em>low-angle</em> approaches (||-shaped sustained proximity). Defaults
 * are also tuned for sustained  runs: higher {@code minRunNodes}, similar {@code proximity}.
 * <p>
 * The output type is {@link CrossoverFinder.CrossoverEvent}: Callers should interpret the events as "bundled run"
 * regions rather than crossings; the {@code medianAngleDeg} field will be small (parallel), and the index-window
 * span will be wide (sustained).
 *
 * @author Tiago Ferreira
 * @see CrossoverFinder
 */
public class BundleDetector {

    private BundleDetector() {
    } // static utility

    /**
     * Entry point: detect bundle events for a collection of paths using the given config. Internally constructs
     * a {@link CrossoverFinder.Config} ith the inverted angle filter ({@link CrossoverFinder.Config#thetaMaxDeg}
     * set, {@link CrossoverFinder.Config#thetaMinDeg} disabled) and delegates to {@link CrossoverFinder#find}.
     *
     * @param paths the collection of paths
     * @param cfg   the bundle-detection config settings
     * @return list of detected bundle events (typed as {@link CrossoverFinder.CrossoverEvent})
     */
    public static List<CrossoverFinder.CrossoverEvent> find(final Collection<Path> paths,
                                                            final Config cfg) {
        final CrossoverFinder.Config xfCfg = new CrossoverFinder.Config()
                .proximity(cfg.proximity)
                .thetaMaxDeg(cfg.maxParallelAngleDeg)
                .minRunNodes(cfg.minRunNodes)
                .includeSelfCrossovers(false)
                .includeDirectChildren(false)
                .sameCTOnly(cfg.sameCTOnly)
                .nodeWitnessRadius(cfg.nodeWitnessRadius);
        return CrossoverFinder.find(paths, xfCfg);
    }

    /**
     * Configuration for {@link BundleDetector}. Mirrors the relevant subset of {@link CrossoverFinder.Config}
     * with bundle-specific defaults.
     */
    public static final class Config {

        /**
         * Neighborhood radius for candidate mining (real-world units).
         * Same semantics as {@link CrossoverFinder.Config#proximity}.
         * <p>Default: {@code 3.0}.</p>
         */
        double proximity = 3.0;

        /**
         * Maximum angle (degrees, 0-90) between local tangents for two paths
         * to be considered "parallel". Pairs with angle above this value are
         * rejected. Lower = stricter (truly parallel only); higher = looser.
         * <p>Default: {@code 20.0}.</p>
         */
        double maxParallelAngleDeg = 20.0;

        /**
         * Minimum number of consecutive near node-pairs required to accept
         * a bundle run. Set high to require sustained proximity (the whole
         * point of bundle detection). Same semantics as
         * {@link CrossoverFinder.Config#minRunNodes}.
         * <p>Default: {@code 10}.</p>
         */
        int minRunNodes = 10;

        /**
         * If {@code true}, only compare paths sharing the same channel/time.
         * <p>Default: {@code true}.</p>
         */
        boolean sameCTOnly = true;

        /**
         * Witness radius for the post-hoc filter. {@code <= 0} falls back to
         * {@link #proximity}. Same semantics as
         * {@link CrossoverFinder.Config#nodeWitnessRadius}.
         * <p>Default: {@code -1.0}.</p>
         */
        double nodeWitnessRadius = -1.0;

        /**
         * Setter for {@link #proximity}.
         */
        public Config proximity(final double v) {
            this.proximity = Math.max(0, v);
            return this;
        }

        /**
         * Setter for {@link #maxParallelAngleDeg}.
         */
        public Config maxParallelAngleDeg(final double v) {
            this.maxParallelAngleDeg = Math.max(0, v);
            return this;
        }

        /**
         * Setter for {@link #minRunNodes}.
         */
        public Config minRunNodes(final int n) {
            this.minRunNodes = Math.max(1, n);
            return this;
        }

        /**
         * Setter for {@link #sameCTOnly}.
         */
        public Config sameCTOnly(final boolean b) {
            this.sameCTOnly = b;
            return this;
        }

        /**
         * Setter for {@link #nodeWitnessRadius}.
         */
        public Config nodeWitnessRadius(final double v) {
            this.nodeWitnessRadius = v;
            return this;
        }

        @Override
        public String toString() {
            return "BundleDetector.Config{proximity=" + proximity
                    + ", maxParallelAngleDeg=" + maxParallelAngleDeg
                    + ", minRunNodes=" + minRunNodes
                    + ", sameCTOnly=" + sameCTOnly
                    + ", nodeWitnessRadius=" + nodeWitnessRadius + "}";
        }
    }
}
