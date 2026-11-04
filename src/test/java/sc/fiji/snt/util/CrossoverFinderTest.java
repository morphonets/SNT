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

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import sc.fiji.snt.Path;

import static org.junit.Assert.*;

/**
 * JUnit tests for {@link CrossoverFinder}.
 */
public class CrossoverFinderTest {

    static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

    /** Straight path with unit node spacing along x (alongX=true) or y, at the given offsets */
    private static Path line(final boolean alongX, final double fixedA, final double z, final int nNodes) {
        final Path p = new Path(1.0, 1.0, 1.0, "um");
        for (int i = 0; i < nNodes; i++) {
            if (alongX) p.addNode(new PointInImage(i, fixedA, z));
            else p.addNode(new PointInImage(fixedA, i, z));
        }
        return p;
    }

    @Test
    public void testSingleCrossing() {
        final List<Path> paths = new ArrayList<>();
        paths.add(line(true, 10, 0, 21));   // along x at y=10, z=0
        paths.add(line(false, 10, 1, 21));  // along y at x=10, z=1: perpendicular, 1 um apart
        final List<CrossoverFinder.CrossoverEvent> events = CrossoverFinder.find(paths, new CrossoverFinder.Config());
        assertEquals(1, events.size());
        final CrossoverFinder.CrossoverEvent ev = events.getFirst();
        assertEquals(90, ev.medianAngleDeg(), 1e-6);
        assertEquals(10, ev.x(), 1.5);
        assertEquals(10, ev.y(), 1.5);
        assertTrue(ev.medianMinDist() >= 1 - 1e-9 && ev.medianMinDist() <= 2);
        assertEquals(2, ev.participants().size());
    }

    @Test
    public void testResultsAreReproducible() {
        // A small bundle of closely spaced perpendicular fibers, in which events are close enough to be merged.
        // Merging is order-dependent: results must not depend on the identity of the Path objects, which
        // changes for every instantiation (and between JVM sessions)
        List<String> reference = null;
        for (int run = 0; run < 25; run++) {
            final List<Path> paths = new ArrayList<>();
            for (final double y : new double[] { 5, 6.5, 8 }) paths.add(line(true, y, 0, 15));
            for (final double x : new double[] { 5, 6.5, 8 }) paths.add(line(false, x, 1, 15));
            final List<String> result = new ArrayList<>();
            for (final CrossoverFinder.CrossoverEvent ev : CrossoverFinder.find(paths, new CrossoverFinder.Config())) {
                result.add(String.format("%.6f,%.6f,%.6f,%.6f,%.6f,%d", ev.x(), ev.y(), ev.z(), ev.medianAngleDeg(),
                        ev.medianMinDist(), ev.participants().size()));
            }
            assertFalse("No events detected", result.isEmpty());
            if (reference == null) reference = result;
            else assertEquals("Run " + run + " differs from the first run", reference, result);
        }
    }

    /** Appends nodes (spacing {@code step}) from the last node of the path to (x, y, z), or from (x, y, z) if empty */
    private static void addLeg(final Path p, final double x0, final double y0, final double z0, final double x1,
                               final double y1, final double z1, final double step) {
        final double len = Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0));
        final int n = (int) Math.round(len / step);
        for (int i = (p.size() == 0 ? 0 : 1); i <= n; i++) {
            final double f = (double) i / n;
            p.addNode(new PointInImage(x0 + f * (x1 - x0), y0 + f * (y1 - y0), z0 + f * (z1 - z0)));
        }
    }

    @Test
    public void testSelfCrossoversIgnoreNeighboringStretchesOfCurvedPath() {
        // Gently curved path (arc of radius 20, ~21 um long) with a node spacing (0.5 um) much smaller than the
        // proximity (3 um): neighboring stretches are near each other, but are not self crossovers
        final Path arc = new Path(1.0, 1.0, 1.0, "um");
        final double radius = 20;
        for (int i = 0; i <= 42; i++) {
            final double theta = (i * 0.5) / radius;
            arc.addNode(new PointInImage(radius * Math.cos(theta), radius * Math.sin(theta), 0));
        }
        final CrossoverFinder.Config cfg = new CrossoverFinder.Config().proximity(3).includeSelfCrossovers(true);
        assertEquals(0, CrossoverFinder.find(List.of(arc), cfg).size());
    }

    @Test
    public void testSelfCrossoverOfLoopedPath() {
        // Path that crosses itself (at z-distance of 1 um) after a long detour
        final Path loop = new Path(1.0, 1.0, 1.0, "um");
        addLeg(loop, 0, 0, 0, 20, 0, 0, 0.5);    // east
        addLeg(loop, 20, 0, 0, 20, 10, 0, 0.5);  // north
        addLeg(loop, 20, 10, 0, 10, 10, 0, 0.5); // west
        addLeg(loop, 10, 10, 1, 10, -10, 1, 0.5); // south, crossing the first leg at (10, 0)
        final CrossoverFinder.Config off = new CrossoverFinder.Config().proximity(2);
        assertEquals(0, CrossoverFinder.find(List.of(loop), off).size());
        final CrossoverFinder.Config on = new CrossoverFinder.Config().proximity(2).includeSelfCrossovers(true);
        final List<CrossoverFinder.CrossoverEvent> events = CrossoverFinder.find(List.of(loop), on);
        assertEquals(1, events.size());
        assertEquals(1, events.getFirst().participants().size());
        assertEquals(10, events.getFirst().x(), 1.5);
        assertEquals(0, events.getFirst().y(), 1.5);
    }
}
