/*
 * #%L
 * hIPNAT plugins for Fiji distribution of ImageJ
 * %%
 * Copyright (C) 2017 Tiago Ferreira
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
package sc.fiji.snt.plugin.ij1;

import java.util.stream.IntStream;

import org.apache.commons.math3.stat.StatUtils;

import ij.IJ;
import ij.ImagePlus;
import ij.measure.ResultsTable;
import ij.plugin.filter.PlugInFilter;
import ij.process.ImageProcessor;
import sc.fiji.analyzeSkeleton.AnalyzeSkeleton_;
import sc.fiji.analyzeSkeleton.SkeletonResult;

/**
 * This class implements the ImageJ {@code Summarize Skeleton} plugin. For more
 * information, visit the hIPNAT repository
 * {@literal https://github.com/tferr/hIPNAT}
 *
 * @author Tiago Ferreira
 */
public class SummarizeSkeleton implements PlugInFilter {

	private ImagePlus imp;
	private final String TABLE_TITLE = "Skeleton Stats";

	@Override
	public int setup(final String arg, final ImagePlus imp) {

		this.imp = imp;
		if (!IJ.isJava18()) {
			IJ.error("\"Summarize Skeleton\" requires Java 1.8 or later.");
			return DONE;
		}
		return DOES_8G | NO_CHANGES;

	}

	@Override
	public void run(final ImageProcessor ignored) {

		// Analyze skeleton
		final AnalyzeSkeleton_ as = new AnalyzeSkeleton_();
		as.setup("", imp);
		final SkeletonResult sr = as.run();

		// Get key skeleton properties
		final int nTrees = sr.getNumOfTrees();
		final int[] branches = sr.getBranches();
		final int nBranches = IntStream.of(branches).sum();

		if (branches == null || (nBranches == 0 && nTrees <= 1)) {
			Strahler.error("Summarize Skeleton", "Image does not seem to be a branched skeleton.", imp);
			return;
		}

		final ResultsTable rt = Strahler.getTable(TABLE_TITLE);
		try {

			// Integrate values from all trees
			double sumLength = 0d;
			final double[] avgLengths = sr.getAverageBranchLength();
			for (int i = 0; i < nTrees; i++)
				sumLength += avgLengths[i] * branches[i];

			// Log stats
			rt.incrementCounter();
			rt.addValue("Image", imp.getTitle());
			rt.addValue("Unit", imp.getCalibration().getUnits());
			rt.addValue("Total length", sumLength);
			rt.addValue("Max branch length", StatUtils.max(sr.getMaximumBranchLength()));
			rt.addValue("Mean branch length", StatUtils.mean(avgLengths));
			rt.addValue("# Trees", nTrees);
			rt.addValue("# Branches", nBranches);
			rt.addValue("# Junctions", IntStream.of(sr.getJunctions()).sum());
			rt.addValue("# End-points", IntStream.of(sr.getEndPoints()).sum());
			rt.addValue("# Triple Points", IntStream.of(sr.getTriples()).sum());
			rt.addValue("# Quadruple Points", IntStream.of(sr.getQuadruples()).sum());
			rt.addValue("Sum of voxels", IntStream.of(sr.calculateNumberOfVoxels()).sum());

		} catch (final Exception ignored1) {

			Strahler.error("Summarize Skeleton", "Some statistics could not be calculated", imp);

		} finally {

			rt.show(TABLE_TITLE);

		}

	}
}