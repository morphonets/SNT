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

package sc.fiji.snt;

import ij.ImagePlus;
import org.junit.Before;
import org.junit.Test;
import sc.fiji.snt.tracing.BinaryTracer;
import sc.fiji.snt.analysis.TreeStatistics;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeNotNull;

/**
 * Tests for {@link BinaryTracer}
 *
 * @author Cameron Arshadi
 */
public class BinaryTracerTest {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

    private ImagePlus imp;
    private Tree demoTree;

    @Before
    public void setUp() throws Exception {
        final SNTService sntService = new SNTService();
        imp = sntService.demoImage("fractal");
        demoTree = sntService.demoTree("fractal");
        assumeNotNull(imp);
        assumeNotNull(demoTree);
    }

    @Test
    public void testConverter() {
        final BinaryTracer converter = new BinaryTracer(imp, false);
        final List<Tree> skelTrees = converter.getTrees();
        final Tree tree = skelTrees.get(0);
        final TreeStatistics skelAnalyzer = new TreeStatistics(tree);
        final TreeStatistics demoAnalyzer = new TreeStatistics(demoTree);
        assertEquals("# Trees", 1, skelTrees.size());
        assertEquals("# Paths", demoAnalyzer.getNPaths(), skelAnalyzer.getNPaths());
        assertEquals("# Branch points", demoAnalyzer.getBranchPoints().size(), skelAnalyzer.getBranchPoints().size());
        assertEquals("# Branches", demoAnalyzer.getNBranches(), skelAnalyzer.getNBranches());
        assertEquals("# Tips", demoAnalyzer.getTips().size(), skelAnalyzer.getTips().size());
        assertEquals("# I paths", demoAnalyzer.getPrimaryPaths().size(), skelAnalyzer.getPrimaryPaths().size());
        assertEquals("# Highest path order", demoAnalyzer.getHighestPathOrder(), skelAnalyzer.getHighestPathOrder());
        assertEquals("Sum length of all paths", demoAnalyzer.getCableLength(), skelAnalyzer.getCableLength(), 10.0);
        assertEquals("Average branch length", demoAnalyzer.getAvgBranchLength(), skelAnalyzer.getAvgBranchLength(), 0.5);
    }

}
