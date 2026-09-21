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

package sc.fiji.snt.analysis;

import java.awt.Color;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

import net.imagej.lut.LUTService;
import net.imglib2.display.ColorTable;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.util.ColorMaps;

import org.scijava.plugin.Parameter;
import org.scijava.util.ColorRGB;
import sc.fiji.snt.Tree;

/**
 * Parent class for ColorMappers.
 *
 * @author Tiago Ferreira
 */
public class ColorMapper {

    @Parameter
	protected LUTService lutService;
    
	protected Map<String, URL> luts;
	protected ColorTable colorTable;
	protected boolean integerScale;
	protected double min = Double.MAX_VALUE;
	protected double max = Double.NEGATIVE_INFINITY;
	private Color nanColor;

	/**
	 * Sets up the color mapping for the specified measurement using the given color table.
	 * <p>
	 * This method configures the ColorMapper to use the specified measurement and
	 * color table for mapping values to colors. The actual mapping implementation
	 * is left to extending classes.
	 * </p>
	 *
	 * @param measurement the measurement to be mapped
	 * @param colorTable the color table to use for mapping
	 * @throws IllegalArgumentException if colorTable or measurement is null
	 */
	public void map(final String measurement, final ColorTable colorTable) {
		if (colorTable == null) throw new IllegalArgumentException(
			"colorTable cannot be null");
		if (measurement == null) throw new IllegalArgumentException(
			"measurement cannot be null");
		this.colorTable = colorTable;
		// Implementation left to extending classes
	}

	/**
	 * Gets the color used for NaN (Not a Number) values.
	 * <p>
	 * Returns the color that will be used when mapping NaN values, which
	 * cannot be mapped to the regular color scale.
	 * </p>
	 *
	 * @return the color for NaN values
	 */
	public Color getNaNColor() {
		return nanColor;
	}

	/**
	 * Sets the color to use for NaN (Not a Number) values.
	 * <p>
	 * Specifies the color that should be used when mapping NaN values,
	 * which cannot be mapped to the regular color scale.
	 * </p>
	 *
	 * @param nanColor the color to use for NaN values
	 */
	public void setNaNColor(final Color nanColor) {
		this.nanColor = nanColor;
	}

	/**
	 * Gets the color corresponding to the specified mapped value.
	 * <p>
	 * Maps the given value to a color using the current color table and
	 * mapping bounds. Returns the NaN color if the value is NaN.
	 * </p>
	 *
	 * @param mappedValue the value to map to a color
	 * @return the corresponding color
	 */
	public Color getColor(final double mappedValue) {
		if (Double.isNaN(mappedValue))
			return getNaNColor();
		final int idx = getColorTableIdx(mappedValue);
		return new Color(colorTable.get(ColorTable.RED, idx), colorTable.get(
			ColorTable.GREEN, idx), colorTable.get(ColorTable.BLUE, idx));
	}

	/**
	 * Gets the color corresponding to the specified mapped value as ColorRGB.
	 *
	 * @param mappedValue the value to map to a color
	 * @return the corresponding ColorRGB
	 * @see #getColor(double)
	 */
	public ColorRGB getColorRGB(final double mappedValue) {
		final Color color = getColor(mappedValue);
		return new ColorRGB(color.getRed(), color.getGreen(), color.getBlue());
	}

	private int getColorTableIdx(final double mappedValue) {
		final int idx;
		if (mappedValue <= min) idx = 0;
		else if (mappedValue > max) idx = colorTable.getLength() - 1;
		else idx = (int) Math.round((colorTable.getLength() - 1) * (mappedValue -
			min) / (max - min));
		return idx;
	}

	/**
	 * Sets the LUT mapping bounds.
	 *
	 * @param min the mapping lower bound (i.e., the highest measurement value for
	 *          the LUT scale). It is automatically calculated (the default) when
	 *          set to Double.NaN
	 * @param max the mapping upper bound (i.e., the highest measurement value for
	 *          the LUT scale).It is automatically calculated (the default) when
	 *          set to Double.NaN.
	 */
	public void setMinMax(final double min, final double max) {
		if (!Double.isNaN(min) && !Double.isNaN(max) && min > max)
			throw new IllegalArgumentException("min > max");
		this.min = (Double.isNaN(min)) ? Double.MAX_VALUE : min;
		this.max = (Double.isNaN(max)) ? Double.NEGATIVE_INFINITY : max;
	}

	/**
	 * Sets the LUT mapping bounds to a percentile-clipped range of {@code values}, curbing the influence of
	 * long-tailed outliers on the color scale. Plain min-max bounds let a single extreme value (e.g. one path
	 * length far outside the rest of the arbor) stretch the whole range, crushing every other mapped value
	 * into a narrow, near-indistinguishable band. This clips both ends to a percentile window instead: values
	 * beyond it still render as the coldest/hottest color (clamped, not discarded -- see {@link #getColor(double)})
	 * but no longer decide where that window sits. Same convention as
	 * {@code sc.fiji.snt.seed.SeedConfidence#percentileClipNormalize(double[], double)}, used throughout SNT's
	 * seed detectors for the identical long-tail problem.
	 *
	 * @param values      the raw values driving this mapping (e.g., a metric read across every mapped node/path).
	 *                    {@code NaN} entries are ignored; {@code null} or all-NaN leaves the current bounds
	 *                    unchanged.
	 * @param clipPercent percentile clip width, clamped to {@code [0, 45]} ({@code 0} recovers plain min-max)
	 */
	public void setMinMaxPercentileClipped(final double[] values, final double clipPercent) {
		final double[] valid = nonNaN(values);
		if (valid == null) return;
		final double clip = Math.clamp(clipPercent, 0.0, 45.0);
		final Percentile percentile = new Percentile();
		setMinMax(percentile.evaluate(valid, clip), percentile.evaluate(valid, 100.0 - clip));
	}

	/**
	 * One-sided companion to {@link #setMinMaxPercentileClipped(double[], double)}: sets only the lower bound,
	 * to the {@code clipPercent}th percentile of {@code values}, leaving the upper bound untouched. Useful when
	 * only one end of the scale should adapt to the data -- e.g. pairing this with a fixed, caller-chosen upper
	 * bound (or {@link #setMaxPercentileClipped} for the other combination). Unlike {@link #setMinMax(double,
	 * double)}, this does not validate against the current upper bound, since that bound may not have been set
	 * yet -- callers combining both ends should finish with a plain {@link #setMinMax(double, double)} (or
	 * {@link #getMinMax()}-checked call) once both are known.
	 *
	 * @param values      as {@link #setMinMaxPercentileClipped(double[], double)}
	 * @param clipPercent percentile clip width, clamped to {@code [0, 45]} ({@code 0} = the plain minimum)
	 */
	public void setMinPercentileClipped(final double[] values, final double clipPercent) {
		final double[] valid = nonNaN(values);
		if (valid == null) return;
		this.min = new Percentile().evaluate(valid, Math.clamp(clipPercent, 0.0, 45.0));
	}

	/**
	 * One-sided companion to {@link #setMinMaxPercentileClipped(double[], double)}: sets only the upper bound,
	 * to the {@code (100-clipPercent)}th percentile of {@code values}, leaving the lower bound untouched. See
	 * {@link #setMinPercentileClipped} for the other combination and the caveat about validating the final
	 * range once both bounds are known.
	 *
	 * @param values      as {@link #setMinMaxPercentileClipped(double[], double)}
	 * @param clipPercent percentile clip width, clamped to {@code [0, 45]} ({@code 0} = the plain maximum)
	 */
	public void setMaxPercentileClipped(final double[] values, final double clipPercent) {
		final double[] valid = nonNaN(values);
		if (valid == null) return;
		this.max = new Percentile().evaluate(valid, 100.0 - Math.clamp(clipPercent, 0.0, 45.0));
	}

	/** @return {@code values} with NaN entries dropped, or {@code null} if nothing valid remains */
	private static double[] nonNaN(final double[] values) {
		if (values == null || values.length == 0) return null;
		final double[] valid = Arrays.stream(values).filter(v -> !Double.isNaN(v)).toArray();
		return (valid.length == 0) ? null : valid;
	}

	/**
	 * Checks if the color mapping uses an integer scale.
	 * <p>
	 * Returns true if the mapping is configured to use discrete integer
	 * values rather than continuous floating-point values.
	 * </p>
	 *
	 * @return true if using integer scale, false for continuous scale
	 */
	public boolean isIntegerScale() {
		return integerScale;
	}

	/**
	 * Returns the mapping bounds
	 *
	 * @return a two-element array with current {minimum, maximum} mapping bounds
	 */
	public double[] getMinMax() {
		return new double[] { min, max };
	}

	/**
	 * Gets the current color table used for mapping.
	 * <p>
	 * Returns the ColorTable instance that defines the color mapping
	 * from values to colors.
	 * </p>
	 *
	 * @return the current color table
	 */
	public ColorTable getColorTable() {
		return colorTable;
	}

	protected void initLuts() {
		if (luts == null) {
			if (lutService == null)
				SNTUtils.getContext().inject(this);
			luts = lutService.findLUTs();
		}
	}

	/**
	 * Gets the available LUTs, as recognized by {@link #getColorTable(String)}: every LUT bundled with
	 * Fiji (via {@link LUTService}), in addition to the handful of "core" names {@link ColorMaps#get(String)}
	 * resolves directly (e.g. "viridis", "fire", "ice") without needing the LUTService lookup.
	 *
	 * @return the set of keys, corresponding to the set of LUTs available
	 */
	public Set<String> getAvailableLuts() {
		initLuts();
		return luts.keySet();
	}

	private static final Set<String> HEATMAP_LUT_HINTS = Set.of(
			"cividis", "cool", "fire", "glow", "green fire blue", "ice", "inferno",
			"magma", "magenta hot", "orange hot", "physics", "plasma", "red hot",
			"royal", "smart", "spectrum", "thermal", "viridis");


	/**
	 * Gets the subset of {@link #getAvailableLuts()} recognized as heatmap
	 * LUTs, matched case-insensitively and loosely against a curated list of
	 * known heatmap names. Absent LUTs are simply not returned, since
	 * availability depends on the running Fiji install
	 *
	 * @return the heatmap subset of {@link #getAvailableLuts()}
	 */
	public Set<String> getAvailableHeatmapLuts() {
		initLuts();
		return luts.keySet().stream()
				.filter(ColorMapper::looksLikeHeatmapLut)
				.collect(Collectors.toCollection(TreeSet::new));
	}

	private static boolean looksLikeHeatmapLut(final String lutKey) {
		final String base = lutKey.replace('\\', '/');
		final String name = base.substring(base.lastIndexOf('/') + 1)
				.replaceFirst("(?i)\\.lut$", "")
				.toLowerCase();
		return HEATMAP_LUT_HINTS.stream().anyMatch(name::contains);
	}

	/**
	 * Resolves a LUT (color table) by name. Tries the small set of "core" names known to
	 * {@link ColorMaps#get(String)} first (e.g. "viridis", "fire", "ice" -- no LUTService round-trip needed),
	 * then falls back to a substring match against every LUT bundled with Fiji, as reported by
	 * {@link #getAvailableLuts()}.
	 *
	 * @param lut the LUT name, or a substring of one of {@link #getAvailableLuts()}'s entries
	 *            (e.g. "mpl-viridis.lut")
	 * @return the matching color table, or null if no match was found
	 */
	public ColorTable getColorTable(final String lut) {
		final ColorTable cMap = ColorMaps.get(lut);
		if (cMap != null) return cMap;
		initLuts();
		for (final Map.Entry<String, URL> entry : luts.entrySet()) {
			if (entry.getKey().contains(lut)) {
				try {
					return lutService.loadLUT(entry.getValue());
				} catch (final IOException e) {
					SNTUtils.log("Could not load LUT '" + lut + "': " + e.getMessage());
				}
			}
		}
		return null;
	}

	/**
	 * Removes color mapping from all paths in the specified tree.
	 * <p>
	 * Resets the color of all paths in the tree to their default state,
	 * effectively removing any color mapping that was previously applied.
	 * </p>
	 *
	 * @param tree the tree whose paths should have color mapping removed
	 */
	public static void unMap(final Tree tree) {
		unMap(tree.list());
	}

	/**
	 * Removes color mapping from the specified collection of paths.
	 * <p>
	 * Resets the color of all paths in the collection to their default state,
	 * effectively removing any color mapping that was previously applied.
	 * This includes both path-level and node-level color assignments.
	 * </p>
	 *
	 * @param paths the collection of paths to have color mapping removed
	 */
	public static void unMap(final Collection<Path> paths) {
		paths.forEach(p -> {
			p.setColor((java.awt.Color)null);
			p.setNodeColors(null);
		});
	}
}
