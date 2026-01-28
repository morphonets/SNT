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

package sc.fiji.snt;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import java.util.HashSet;
import java.util.List;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import ij.ImagePlus;
import sc.fiji.analyzeSkeleton.AnalyzeSkeleton_;
import sc.fiji.analyzeSkeleton.SkeletonResult;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.util.BoundingBox;

/**
 * Skeletonization and BoundingBox Tests for {@link Tree}s
 *
 * @author Tiago Ferreira
 */
public class TreeSkeletonTest {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private List<Tree> trees;
	private static final int XY_PADDING = 3; // as per Tree#getSkeleton()
	private static final int Z_PADDING = 1; // as per Tree#getSkeleton()

	@Before
	public void setUp() throws Exception {
		trees = new SNTService().demoTrees();
		assumeNotNull(trees);
	}

	@Ignore
	@Test
	public void testSkeletonizer() {
		final HashSet<BoundingBox> boxes = new HashSet<>();
		trees.forEach( tree -> {
			boxes.add(tree.getBoundingBox());

			// Bounding box tests
			final ImagePlus imp = tree.getSkeleton();
			//tree.getSkeleton2D().show();
			final BoundingBox box = tree.getBoundingBox();
			final int width = (int) Math.round(box.width()) + 2 * XY_PADDING;
			final int height = (int) Math.round(box.height()) + 2 * XY_PADDING;
			int depth = (int) Math.round(box.depth());
			if (box.depth() > 0) depth += 2 * Z_PADDING;
			assertTrue("Matched image width", width == imp.getWidth());
			assertTrue("Matched image height", height == imp.getHeight());
			assertTrue("Matched image depth", depth == imp.getNSlices());

			// Topology tests
			final AnalyzeSkeleton_ skAnalyzer = new AnalyzeSkeleton_();
			skAnalyzer.setup("", imp);
			final SkeletonResult skResult = skAnalyzer.run();
			final TreeStatistics analyzer = new TreeStatistics(tree);
			assertTrue("Match # Trees", 1 == skResult.getNumOfTrees());
			if (!"AA0002".equals(tree.getLabel())) {
				// TODO: Assess failure for AA0002.swc
				assertTrue("Matched # End-points", skResult.getListOfEndPoints().size() == analyzer.getTips().size());
			}
		});
		assertTrue("Bounding Boxes are all unique", boxes.size() == trees.size());
	}
}
