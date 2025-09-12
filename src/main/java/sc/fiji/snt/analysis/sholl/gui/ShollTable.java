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
package sc.fiji.snt.analysis.sholl.gui;

import java.io.File;
import java.util.Properties;

import org.scijava.Context;
import org.scijava.NullContextException;
import org.scijava.plugin.Parameter;
import org.scijava.prefs.PrefService;
import org.scijava.table.DefaultGenericTable;

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.sholl.Profile;
import sc.fiji.snt.analysis.sholl.math.LinearProfileStats;
import sc.fiji.snt.analysis.sholl.math.NormalizedProfileStats;
import sc.fiji.snt.analysis.sholl.math.PolarProfileStats;
import sc.fiji.snt.analysis.sholl.math.ShollStats;
import sc.fiji.snt.plugin.ShollAnalysisPrefsCmd;
import sc.fiji.snt.util.ShollPoint;


/**
 * Implementation of {@link SNTTable} for Sholl metrics and Profile lists.
 * 
 * @author Tiago Ferreira
 */
public class ShollTable extends SNTTable {

	private static final long serialVersionUID = 1L;
	private final Profile profile;
	private ShollStats[] stats;
	private boolean detailedSummary = false;
	private String title;

	@Parameter
	private PrefService prefService;


	/** Instantiates a new empty table. */
	public ShollTable() {
		super();
		this.profile = new Profile();
	}

	/**
	 * Instantiates a new table from a {@link Profile}
	 *
	 * @param profile the profile to be listed and/or summarized by this table
	 */
	public ShollTable(final Profile profile) {
		super();
		this.profile = profile;
	}

	/**
	 * Instantiates a new table capable of detailing metrics from a
	 * {@link LinearProfileStats}, a {@link NormalizedProfileStats} instance or
	 * both.
	 *
	 * @param stats the {@link ShollStats} instances from which metrics should be
	 *              retrieved. It is assumed that all instances analyze the same
	 *              {@link Profile}
	 */
	public ShollTable(final ShollStats... stats) {
		this(stats[0].getProfile());
		this.stats = stats;
	}

	/**
	 * Lists (details) the {@link Profile} entries. If this table is aware of
	 * {@link ShollStats} that successfully fitted a model to the profile, XY
	 * coordinates of the fitted curve are also be listed.
	 */
	public void listProfileEntries() {

		final boolean intensities = profile.isIntDensityProfile();
		addColumn("Radius", profile.radii());
        addColumn((intensities) ? "Norm. IntDen" : "Inters.", profile.counts());
        addColumn("Length", profile.lengths());

		if (stats == null)
			return;

		for (final ShollStats stat : stats) {

            if (stat == null || (!(stat instanceof PolarProfileStats) && !stat.validFit()))
				continue;

            String yFitHeader;
            if (intensities)
                yFitHeader = "Norm. IntDen";
            else if (stat.getDataMode() == ShollStats.DataMode.LENGTH)
                yFitHeader = "Length";
            else
                yFitHeader = "Inters.";

            switch (stat) {
                case LinearProfileStats lStats ->
                    //addCol("Radius (Polyn. fit)", lStats.getXValues());
                        addColumn(yFitHeader + " (Polyn. fit)", lStats.getFitYValues());
                case NormalizedProfileStats nStats -> {
                    if (nStats.getMethod() == NormalizedProfileStats.LOG_LOG)
                        addColumn("log(Radius)", nStats.getXValues());
                    final String yHeader = (intensities) ? "log(Norm. IntDen /" + nStats.getNormalizerDescription() + ")"
                            : "log(" + yFitHeader + "/" + nStats.getNormalizerDescription() + ")";
                    addColumn(yHeader, nStats.getFitYValues());
                }
                case PolarProfileStats pStats -> {
                    pStats.detailReport(this);
                }
                default -> {
                }
            }
		}
		fillEmptyCells(Double.NaN);
	}

	/**
	 * Sets whether extensive metrics should be listed when outputting summaries.
	 *
	 * @param detailed if true summaries will list verbose details, otherwise
	 *                 summaries will fall back to the 'default' repertoire of
	 *                 metrics
	 */
	public void setDetailedSummary(final boolean detailed) {
		detailedSummary = detailed;
	}

	/**
	 * Runs {@link #summarize(String)} and appends (copies) the summary row to the
	 * specified table
	 *
	 * @param table  the table to which the summary row should be copied
	 * @param header the header for the summary row. If empty or null, the profile
	 *               identifier is used
	 * @see #setDetailedSummary(boolean)
	 * @see #summarize(String)
	 */
	public void summarize(final ShollTable table, final String header) {
		summarize(header);
		table.appendRow(header);
		final int destinationRow = table.getRowCount() - 1;
		final int sourceRow = getRowCount() - 1;
		for (int col = 0; col < getColumnCount(); col++) {
			final String sourceHeader = getColumnHeader(col);
			final Object sourceObject = get(col, sourceRow);
			table.set(getCol(table, sourceHeader), destinationRow, sourceObject);
		}
	}

	/**
	 * Summarizes {@link Profile} and {@link ShollStats} metrics to a new row. Note
	 * that some of the reported metrics rely on the options set in {@link ShollAnalysisPrefsCmd}.
	 * To ensure that those are read, you should run {@link #setContext(Context)},
	 * so that a {@link PrefService} is set.
	 *
	 * @param header the header for the summary row. If empty or null, the profile
	 *               identifier is used
	 * @see #setDetailedSummary(boolean)
	 */
	public void summarize(final String header) {

		if (header != null && !header.trim().isEmpty()) {
			appendRow(header);
		} else {
			appendRow(profile.identifier());
		}

		final int row = getRowCount() - 1;
		if (detailedSummary)
			set(getCol("Unit"), row, profile.spatialCalibration().getUnit());
		set(getCol("Center"), row, profile.center());
		set(getCol("Start radius"), row, profile.startRadius());
		set(getCol("End radius"), row, profile.endRadius());
		set(getCol("Radius step"), row, profile.stepSize());

		final Properties props = profile.getProperties();

		// Image Properties
		if (Profile.SRC_IMG.equals(props.getProperty(Profile.KEY_SOURCE))) {

			final String thresh = props.getProperty(Profile.KEY_THRESHOLD_RANGE, "-1:-1");
			set(getCol("Threshold range"), row, thresh);

			if (detailedSummary) {
				final int c = Integer.parseInt(props.getProperty(Profile.KEY_CHANNEL_POS));
				final int z = Integer.parseInt(props.getProperty(Profile.KEY_SLICE_POS));
				final int t = Integer.parseInt(props.getProperty(Profile.KEY_FRAME_POS));
				set(getCol("CZT Position "), row, "" + c + ":" + z + ":" + t);
			}
			final int nSamples = Integer.parseInt(props.getProperty(Profile.KEY_NSAMPLES, "1"));
			set(getCol("Samples/radius"), row, nSamples);
			set(getCol("Samples/radius integration"), row, (nSamples == 1) ? "NA" : nSamples);

		}

		if (stats == null)
			return;

		for (final ShollStats stat : stats) {

            switch (stat) {
                case LinearProfileStats lStats -> {
                    if (detailedSummary && !profile.isIntDensityProfile()) {
                        final String pLabel = (lStats.isPrimaryBranchesInferred()) ? "(inferred)" : "(specified)";
                        set(getCol("I branches " + pLabel), row, lStats.getPrimaryBranches());
                    }
                    addLinearStats(row, lStats, false);
                    if (lStats.validFit())
                        addLinearStats(row, lStats, true);
                }
                case NormalizedProfileStats nStats -> {
                    set(getCol("Sholl decay"), row, nStats.getShollDecay());
                    set(getCol("Method"), row, nStats.getMethodDescription());
                    set(getCol("Normalizer"), row, nStats.getNormalizerDescription());
                    set(getCol("R^2"), row, nStats.getRSquaredOfFit());
                    set(getCol("r"), row, nStats.getR());
                    if (detailedSummary) {
                        set(getCol("Determination ratio"), row, nStats.getDeterminationRatio());
                        set(getCol("Regression intercept"), row, nStats.getIntercept());
                        set(getCol("Regression slope"), row, nStats.getSlope());
                    }
                }
                case PolarProfileStats pStats -> {
                    pStats.appendSummaryReport(this);
                }
                case null, default -> {
                }
            }

        }
	}

	private void addLinearStats(final int row, final LinearProfileStats lStats, final boolean fData) {

        final String dataLabel = (lStats.getDataMode() == ShollStats.DataMode.LENGTH) ? "length" : "inters.";
        final String key = (profile.isIntDensityProfile()) ? "IntDen" : dataLabel;

		set(getCol(getHeader("Max " + key, fData)), row, lStats.getMax(fData));
		set(getCol(getHeader("Max " + key + " radius", fData)), row, lStats.getCenteredMaximum(fData).x);
		set(getCol(getHeader("Sum " + key, fData)), row, lStats.getSum(fData));
		set(getCol(getHeader("Mean " + key , fData)), row, lStats.getMean(fData));

		if (detailedSummary) {
			set(getCol(getHeader("Median " + key, fData)), row, lStats.getMedian(fData));
			set(getCol(getHeader("Skeweness", fData)), row, lStats.getSkewness(fData));
			set(getCol(getHeader("Kurtosis", fData)), row, lStats.getKurtosis(fData));
			final ShollPoint centroid = lStats.getCentroid(fData);
			set(getCol(getHeader("Centroid value", fData)), row, centroid.y);
			set(getCol(getHeader("Centroid radius", fData)), row, centroid.x);
		}

		set(getCol(getHeader("Ramification index", fData)), row, lStats.getRamificationIndex(fData));
		set(getCol(getHeader("Branching index", fData)), row, lStats.getBranchingIndex(fData));

		try {
			if (prefService != null) {
				final int cutoff = prefService.getInt(ShollAnalysisPrefsCmd.class, "enclosingRadiusCutoff",
						ShollAnalysisPrefsCmd.DEF_ENCLOSING_RADIUS_CUTOFF);
				set(getCol(getHeader("Enclosing radius", fData)), row, lStats.getEnclosingRadius(fData, cutoff));
			}
		} catch (final NullContextException ignored) {
			// move on;
		}

		if (fData) {
			// things that only make sense for fitted data
			set(getCol("Polyn. degree"), row, lStats.getPolynomialDegree());
			set(getCol("Polyn. R^2"), row, lStats.getRSquaredOfFit(true));
			if (detailedSummary) {
				set(getCol("Polyn. R^2 (adj)"), row, lStats.getRSquaredOfFit(true));
				if (profile.size() > 2)
					set(getCol("K-S p-value"), row, lStats.getKStestOfFit());
			}
		} else {
			// things that only make sense for sampled data
			set(getCol("Intersecting radii"), row, lStats.getIntersectingRadii(fData));
		}

	}

	private String getHeader(final String metric, final boolean fittedData) {
		if ("Max inters. radius".equals(metric) && fittedData) {
			return "Critical radius";
		} else if ("Max inters.".equals(metric) && fittedData) {
			return "Critical value";
		}
		return (fittedData) ? (metric + " (fit)") : (metric + " (sampled)");
	}

	private int getCol(final String header) {
		return getCol(this, header);
	}

	private int getCol(final DefaultGenericTable table, final String header) {
		int idx = table.getColumnIndex(header);
		if (idx == -1) {
			table.appendColumn(header);
			idx = table.getColumnCount() - 1;
		}
		return idx;
	}

	/**
	 * Sets the services required by this ShollTable, namely {@link PrefService},
	 * used to read advanced options set by {@link ShollAnalysisPrefsCmd}.
	 *
	 * @param context the SciJava application context
	 * @throws IllegalStateException    If this ShollTable already has a context
	 * @throws IllegalArgumentException If {@code context} cannot provide the
	 *                                  services required by this ShollTable
	 */
	@Override
	public void setContext(final Context context) throws IllegalStateException, IllegalArgumentException {
		super.setContext(context);
		if (prefService != null) {
			final boolean detailedMetrics = prefService.getBoolean(ShollAnalysisPrefsCmd.class, "detailedMetrics", ShollAnalysisPrefsCmd.DEF_DETAILED_METRICS);
			setDetailedSummary(detailedMetrics);
		}
	}

	public boolean hasContext() {
		return prefService != null;
	}

	public boolean saveSilently(final File file) { // csv extension appended as needed
		if (file == null)
			return false;
		File savedFile;
		if (file.isDirectory()) {
			String fName = getTitle();
			if (fName == null || fName.trim().isEmpty())
				fName = "Sholl_Table-1.csv";
			savedFile = SNTUtils.getUniquelySuffixedFile(new File(file, fName));
		} else {
			savedFile = file;
		}
		return save(savedFile.getAbsolutePath());
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getTitle() {
		return title;
	}
}
