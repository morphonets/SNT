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

import java.awt.Color;
import java.awt.geom.Arc2D;
import java.io.IOException;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.*;

import net.imagej.lut.DefaultLUTService;
import net.imagej.lut.LUTService;
import net.imglib2.display.ColorTable;
import sc.fiji.snt.analysis.sholl.Profile;
import sc.fiji.snt.analysis.sholl.ProfileEntry;
import sc.fiji.snt.analysis.sholl.ProfileProperties;
import sc.fiji.snt.analysis.sholl.ShollUtils;
import sc.fiji.snt.util.ShollPoint;

import org.apache.commons.math3.stat.StatUtils;

import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.measure.Calibration;

/**
 * @author Tiago Ferreira
 *
 */
public class ShollOverlay implements ProfileProperties {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private final Profile profile;
	private final Properties properties;
	private final Overlay overlay;
	private final ImagePlus imp;
	private final ShollPoint center;
	private final int channel;
	private final int frame;
	private final boolean hyperStack;
	private final double centerRawX;
	private final double centerRawY;
	private final double centerRawZ;
	private final Calibration cal;

	private Color baseColor;
	private ArrayList<Roi> shells;
	private ArrayList<PointRoi> points;
	private final LUTService ls;
	private int shellsAlpha = 100;
	private int pointsAlpha = 255;
	private boolean shellsAdded;
	private boolean pointsAdded;
	private int pointRoiSize;

	private static final String CENTER = "center";
	private static final String TYPE = "sholl-type";
	public static final String COUNT = "sholl-count";
	public static final String RADIUS = "sholl-radius";
	public static final String PROP = "sholl-prop";
	public static final String SHELL = "shell";
	public static final String POINTS = "points";
	private static final String BOTH = "s";

	public ShollOverlay(final Profile profile) {
		this(profile, null, false);
	}

	public ShollOverlay(final Profile profile, final ImagePlus imp) {
		this(profile, imp, true);
	}

	public ShollOverlay(final Profile profile, final ImagePlus imp, final boolean clearExistingROIs) {
		center = profile.center();
		this.profile = profile;
		this.imp = imp;
		ls = new DefaultLUTService();
		properties = profile.getProperties();
		channel = Integer.parseInt(properties.getProperty(KEY_CHANNEL_POS, "1"));
		frame = Integer.parseInt(properties.getProperty(KEY_FRAME_POS, "1"));
		hyperStack = (channel != 1 && frame != 1);
		cal = profile.spatialCalibration();
		centerRawX = center.rawX(cal);
		centerRawY = center.rawY(cal);
		centerRawZ = center.rawZ(cal);
		baseColor = Roi.getColor();
		overlay = initializedOverlay();
		if (clearExistingROIs)
			removeShollROIs(overlay);
	}

	private Overlay initializedOverlay() {
		if (imp == null)
			return new Overlay();
		Overlay overlay = imp.getOverlay();
		if (overlay == null) {
			overlay = new Overlay();
			imp.setOverlay(overlay);
		}
		return overlay;
	}

	public void updateDisplay() {
		if (imp != null) {
			imp.setHideOverlay(false);
			imp.setOverlay(overlay);
		}
	}

	public void assignProperty(String property) {
		for (int i = 0; i < overlay.size(); i++)
			overlay.get(i).setProperty(PROP, property);
	}

	public synchronized static void removeShells(final Overlay overlay) {
		removeShollROIs(overlay, TYPE, SHELL);
	}

	public synchronized static void removeShollROIs(final Overlay overlay) {
		removeShollROIs(overlay, TYPE, BOTH);
	}

	public synchronized static void remove(final Overlay overlay, String property) {
		removeShollROIs(overlay, PROP, property);
	}

	private synchronized static void removeShollROIs(final Overlay overlay, final String key, final String property) {
		if (overlay == null || overlay.size() == 0)
			return;
		for (int i = overlay.size() - 1; i >= 0; i--) {
			final String prpty = overlay.get(i).getProperty(key);
			if (prpty != null && prpty.contains(property))
				overlay.remove(i);
		}
	}

	private void validateShells() {
		if (shellsAdded && shells != null)
			return;
		if (shells == null)
			assembleShells();
		for (final Roi shell : shells)
			overlay.add(shell);
		shellsAdded = true;
	}

	private void validatePoints() {
		if (pointsAdded && points != null)
			return;
		if (points == null)
			assembleIntersPoints();
		for (final Roi point : points)
			overlay.add(point);
		pointsAdded = true;
	}

	public Overlay getOverlay() {
		validatePoints();
		return overlay;
	}

	public void removeShells() {
		if (!shellsAdded)
			return;
		for (final Roi shell : shells)
			overlay.remove(shell);
		shellsAdded = false;
	}

	public void removePoints() {
		if (!pointsAdded)
			return;
		for (final Roi point : points)
			overlay.remove(point);
		pointsAdded = false;
	}

	// If set coordinates of points are scaled to pixel coordinates using the
	// profile calibration, otherwise the image calibration is used
	private void assembleIntersPoints() {
		points = new ArrayList<>();
		final Color baseColor = alphaColor(this.baseColor, pointsAlpha);
		final DecimalFormat formatter = new DecimalFormat("#000.##");
		final boolean twoD = profile.is2D();
		for (final ProfileEntry entry : profile.entries()) {
			final Set<ShollPoint> ePoints = entry.points;
			if (ePoints == null || ePoints.isEmpty())
				continue;
			final Map<Integer, List<ShollPoint>> pointsByZ = new HashMap<>();
			for (final ShollPoint point : ePoints) {
				final int z = (int) point.rawZ(cal);
				pointsByZ.computeIfAbsent(z, k -> new ArrayList<>()).add(point);
			}
			pointsByZ.forEach((z, shollPoints) -> {
				final PointRoi multipointRoi = new PointRoi();
				multipointRoi.setProperty(TYPE, POINTS);
				multipointRoi.setProperty(COUNT, (twoD) ? String.valueOf(entry.count) : shollPoints.size() + "/" + entry.count);
				multipointRoi.setProperty(RADIUS, String.valueOf(entry.radius));
				multipointRoi.setPointType(2);
				multipointRoi.setStrokeColor(baseColor);
				multipointRoi.setSize(pointRoiSize);
				multipointRoi.setName(String.format("ShollPoints r=%s z=%s", formatter.format(entry.radius), formatter.format(z + 1))); //1-based index
				setROIPosition(multipointRoi, channel, z, frame);
				shollPoints.forEach(point -> multipointRoi.addPoint(point.rawX(cal), point.rawY(cal)));
				points.add(multipointRoi);
			});
		}
	}

	private void assembleShells() {
		if (center == null)
			throw new IllegalArgumentException("Shell ROIs cannot be generated with undefined center");
		shells = new ArrayList<>();
		final Color baseColor = alphaColor(this.baseColor, shellsAlpha);
		final int shellThickness = Integer.parseInt(properties.getProperty(KEY_NSAMPLES, "1"));

		// 2D analysis: circular shells
		final String sProperty = properties.getProperty(KEY_HEMISHELLS, HEMI_NONE);
		final boolean arcs = !HEMI_NONE.equals(sProperty);
		final boolean north = arcs && sProperty.contains(HEMI_NORTH);
		final boolean south = arcs && sProperty.contains(HEMI_SOUTH);
		final boolean west = arcs && sProperty.contains(HEMI_WEST);
		final boolean east = arcs && sProperty.contains(HEMI_EAST);

		for (final ProfileEntry entry : profile.entries()) {
			final double radiusX = entry.radius / cal.pixelWidth;
			final double radiusY = entry.radius / cal.pixelHeight;
			Roi shell;
			if (arcs) {
				final Arc2D.Double arc = new Arc2D.Double();
				final double radius = Math.sqrt(radiusX * radiusY);
				if (north) {
					arc.setArcByCenter(centerRawX, centerRawY, radius, 0, 180, Arc2D.OPEN);
				} else if (south) {
					arc.setArcByCenter(centerRawX, centerRawY, radius, -180, 180, Arc2D.OPEN);
				} else if (west) {
					arc.setArcByCenter(centerRawX, centerRawY, radius, 90, -180, Arc2D.OPEN);
				} else if (east) {
					arc.setArcByCenter(centerRawX, centerRawY, radius, -90, -180, Arc2D.OPEN);
				}
				shell = new ShapeRoi(arc);
			} else {
				shell = new OvalRoi(centerRawX - radiusX, centerRawY - radiusY, 2 * radiusX, 2 * radiusY);
			}
			shell.setStrokeWidth(shellThickness);
			shell.setStrokeColor(baseColor);
			shell.setProperty(TYPE, SHELL);
			shell.setProperty(COUNT, String.valueOf(entry.count));
			shell.setProperty(RADIUS, String.valueOf(entry.radius));
			shell.setName("Shell r=" + ShollUtils.d2s(entry.radius));
			shells.add(shell);
		}

	}

	public void setBaseColor(final Color baseColor) {
		this.baseColor = baseColor;
	}

	public void addCenter() {
		final PointRoi cRoi = new PointRoi(centerRawX, centerRawY);
		cRoi.setPointType(1);
		cRoi.setStrokeColor(baseColor);
		cRoi.setProperty(TYPE, CENTER);
		setROIposition(cRoi, channel, centerRawZ, frame, hyperStack);
		overlay.add(cRoi, CENTER);
	}

	private void setROIposition(final Roi roi, final int c, final double z, final int t, final boolean hyperStack) {
		if (hyperStack) // NB: ROI position uses 1-based indices
			roi.setPosition(c, (int) z + 1, t);
		else
			roi.setPosition((int) z + 1);
	}

	public void setShellsColor(final Color color) {
		if (color == null) {
			removeShells();
			return;
		}
		validateShells();
		final Color aColor = alphaColor(color, shellsAlpha);
		for (final Roi shell : shells)
			shell.setStrokeColor(aColor);
	}

	public void setPointsColor(final Color color) {
		if (color == null) {
			removePoints();
			return;
		}
		validatePoints();
		final Color aColor = alphaColor(color, pointsAlpha);
		for (final Roi point : points)
			point.setStrokeColor(aColor);
	}

	public Set<String> getLUTs() {
		return ls.findLUTs().keySet();
	}

	public void setShellsLUT(final String lutName) {
		try {
			setShellsLUT(lutName, COUNT);
		} catch (final IllegalArgumentException | IOException ignored) {
			// do nothing
		}
	}

	public void setShellsLUT(final String lutName, final String property) throws IOException {
		validateShells();
		setLUT(shells, property, lutName, shellsAlpha);
	}

	public void setPointsLUT(final String lutName, final String property) throws IOException {
		validatePoints();
		setLUT(points, property, lutName, pointsAlpha);
	}

	public void setPointsLUT(final String lutName) {
		try {
			setPointsLUT(lutName, COUNT);
		} catch (final IllegalArgumentException | IOException ignored) {
			// do nothing
		}
	}

	private void setLUT(final ArrayList<? extends Roi> rois, final String property, final String lutName, final int alpha)
			throws IllegalArgumentException, IOException {
		setLUT(rois, property, getColorTable(lutName), alpha);
	}

	public void setPointsLUT(final ColorTable colorTable, final String property) {
		try {
			validatePoints();
			setLUT(points, property, colorTable, pointsAlpha);
		} catch (final IllegalArgumentException ignored) {
			// do nothing
		}
	}

	public void setShellsLUT(final ColorTable colorTable, final String property) {
		try {
			validateShells();
			setLUT(shells, property, colorTable, shellsAlpha);
		} catch (final IllegalArgumentException ignored) {
			// do nothing
		}
	}

	private void setLUT(final ArrayList<? extends Roi> rois, final String property, final ColorTable ct, final int alpha)
			throws IllegalArgumentException {
		String fProperty = COUNT;
		if (property != null && property.toLowerCase().contains("radi")) // radi[i|us]
			fProperty = RADIUS;
		final double[] mappingValues = (RADIUS.equals(fProperty)) ? profile.radiiAsArray() : profile.countsAsArray();
		final double min = StatUtils.min(mappingValues);
		final double max = StatUtils.max(mappingValues);
		for (final Roi roi : rois) {
			final String[] valueString = roi.getProperty(fProperty).split("/"); // multipoint ROIs sharing the same Z coordinate
			final double value = Double.parseDouble(valueString[valueString.length - 1]);
			final int idx = (int) Math.round((ct.getLength() - 1) * (value - min) / (max - min));
			final Color color = new Color(ct.get(ColorTable.RED, idx), ct.get(ColorTable.GREEN, idx),
					ct.get(ColorTable.BLUE, idx), alpha);
			roi.setStrokeColor(color);
		}
	}

	private ColorTable getColorTable(final String lutName) throws IllegalArgumentException, IOException {
		final String lName = (lutName.endsWith(".lut")) ? lutName : lutName + ".lut";
		final Map<String, URL> map = ls.findLUTs();
		if (!map.containsKey(lName)) {
			throw new IllegalArgumentException(
					"Specified LUT could not be found: " + lutName + ". Use getLUTs() for available options");
		}
		return ls.loadLUT(map.get(lutName));
	}

	public void setShellsThickness(final int strokeWidth) {
		if (strokeWidth < 1) {
			removeShells();
			return;
		}
		validateShells();
		for (final Roi shell : shells)
			shell.setStrokeWidth(strokeWidth);
	}

	public void setShellsOpacity(final double percent) {
		shellsAlpha = (int) Math.round(percent * 255 / 100);
		validateShells();
		setROIsAlpha(shells, shellsAlpha);
	}

	public void setPointsOpacity(final double percent) {
		pointsAlpha = (int) Math.round(percent * 255 / 100);
		validatePoints();
		setROIsAlpha(points, pointsAlpha);
	}

	private void setROIsAlpha(final ArrayList<? extends Roi> rois, final int alpha) {
		for (final Roi roi : rois) {
			final Color c = roi.getStrokeColor();
			roi.setStrokeColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
		}
	}

	public void setGradientShells(final Color color1, final Color color2) {
		double i = 0d;
		validateShells();
		final double n = shells.size();
		for (final Iterator<Roi> it = shells.iterator(); it.hasNext(); i++) {
			it.next().setStrokeColor(gradientColor(color1, color2, i / n));
		}
	}

	public void setAlternateShells(final Color color1, final Color color2) {
		validateShells();
		int i = 0;
		for (final Iterator<Roi> it = shells.iterator(); it.hasNext(); i++) {
			final Roi shell = it.next();
			shell.setStrokeColor((i % 2 == 0) ? color1 : color2);
		}
	}

	public void setPointsSize(final String size) {
		switch(size.toLowerCase().trim()) {
			case "tiny":
				this.pointRoiSize = 0;
				break;
			case "small":
				this.pointRoiSize = 1;
				break;
			case "medium":
				this.pointRoiSize = 2;
				break;
			case "large":
				this.pointRoiSize = 3;
				break;
			case "extra large":
				this.pointRoiSize = 4;
				break;
			case "xxl":
				this.pointRoiSize = 5;
				break;
			case "xxxl":
				this.pointRoiSize = 6;
				break;
			default:
				this.pointRoiSize = PointRoi.getDefaultSize();
				break;
		}
		if (points != null) {
			for (final PointRoi point : points)
				point.setSize(pointRoiSize);
		}
	}

	public void setAlternateShells() {
		final Color[] colors = triadColors(baseColor);
		setAlternateShells(colors[0], colors[1]);
	}

	private Color gradientColor(final Color c1, final Color c2, final double ratio) {
		final double inverseRatio = 1.0 - ratio;
		final int r = (int) (c2.getRed() * ratio + c1.getRed() * inverseRatio);
		final int g = (int) (c2.getGreen() * ratio + c1.getGreen() * inverseRatio);
		final int b = (int) (c2.getBlue() * ratio + c1.getBlue() * inverseRatio);
		final int a = (int) (c2.getAlpha() * ratio + c1.getAlpha() * inverseRatio);
		return new Color(r, g, b, a);
	}

	private Color rotatedHue(final Color c, final float angle) {
		float[] hsbValues = new float[3];
		hsbValues = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), hsbValues);
		final float hue = (hsbValues[0] * 360f + angle) % 360f / 360f;
		final Color rc = Color.getHSBColor(hue, hsbValues[1], hsbValues[2]);
		return new Color(rc.getRed(), rc.getGreen(), rc.getBlue(), c.getAlpha());
	}

	private Color[] triadColors(final Color c) {
		final Color triad1 = rotatedHue(c, 120);
		final Color triad2 = rotatedHue(c, 240);
		return new Color[] { triad1, triad2 };
	}

	private Color alphaColor(final Color color, final int alpha) {
		final int r = color.getRed();
		final int g = color.getGreen();
		final int b = color.getBlue();
		return new Color(r, g, b, alpha);
	}

}
