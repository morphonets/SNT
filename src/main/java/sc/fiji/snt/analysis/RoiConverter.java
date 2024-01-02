/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.measure.Measurements;
import ij.plugin.RoiEnlarger;
import ij.process.FloatPolygon;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import sc.fiji.snt.Path;
import sc.fiji.snt.plugin.ROIExporterCmd;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;
import sc.fiji.snt.util.PointInCanvas;
import sc.fiji.snt.util.PointInImage;

/**
 * Converts SNT {@link Path}s into (IJ1) ROIs.
 *
 * @see ROIExporterCmd
 * @author Tiago Ferreira
 */
public class RoiConverter extends TreeAnalyzer {

	/** SNT's XY view (the default export plane) */
	public static final int XY_PLANE = MultiDThreePanes.XY_PLANE;
	/** SNT's ZY view */
	public static final int ZY_PLANE = MultiDThreePanes.ZY_PLANE;
	/** SNT's XZ view */
	public static final int XZ_PLANE = MultiDThreePanes.XZ_PLANE;

	private float width = -1f; // -1: use mean path diameter
	private int exportPlane = XY_PLANE;
	private boolean useSWCcolors;
	private final ImagePlus imp;
	private final boolean hyperstack;
	private boolean twoD;

	/**
	 * Instantiates a new Converter. Since an image has not been specified, C,Z,T
	 * positions may not be properly for converted nodes.
	 *
	 * @param tree the Tree to be converted
	 */
	public RoiConverter(final Tree tree) {
		super(tree);
		imp = null;
		hyperstack = false;
		twoD = !tree.is3D();
	}

	/**
	 * Instantiates a new Converter.
	 *
	 * @param path the Path to be converted
	 * @param imp the image associated with the Tree, used to properly assign C,T
	 *          positions of converted nodes
	 */
	public RoiConverter(final Path path, ImagePlus imp) {
		this(new Tree(Collections.singleton(path)), imp);
	}

	/**
	 * Instantiates a new Converter.
	 *
	 * @param paths the collection of Paths to be converted
	 * @param imp   the image associated with the collection, used to properly
	 *              assign C,T positions of converted nodes
	 */
	public RoiConverter(final Collection<Path> paths, final ImagePlus imp) {
		this(new Tree(paths), imp);
	}

	/**
	 * Instantiates a new Converter.
	 *
	 * @param tree the Tree to be converted
	 * @param imp the image associated with the Tree, used to properly assign C,T
	 *          positions of converted nodes
	 */
	public RoiConverter(final Tree tree, final ImagePlus imp) {
		super(tree);
		this.imp = imp;
		hyperstack = imp.isHyperStack();
		twoD = imp.getNSlices() == 1;
	}

	/**
	 * Converts paths into 2D polyline ROIs (segment paths).
	 *
	 * @param overlay the target overlay to hold converted paths
	 * @return a reference to the overlay holding paths
	 */
	public Overlay convertPaths(Overlay overlay) {
		if (overlay == null) overlay = new Overlay();
		for (final Path p : tree.list()) {
			if (p.size() > 1) {
				drawPathSegments(p, overlay);
			}
			else { // Single Point Path
				final HashSet<PointInImage> pim = new HashSet<>();
				pim.add(p.getNode(0));
				convertPoints(pim, overlay, getColor(p), "SPP");
			}
		}
		return overlay;
	}

	public List<PolygonRoi> getROIs(final Path path) {

		final List<PolygonRoi> polygons = new ArrayList<>();
		final String basename = path.getName();
		final Color color = getColor(path);
		final float stroke = (width < 0f) ? (float) path.getMeanRadius() * 2 : width;
		//if (stroke == 0f) stroke = 1f;
		FloatPolygon polygon = new FloatPolygon();
		int current_roi_slice = Integer.MIN_VALUE;
		int roi_identifier = 1;
		final int[] roi_pos = new int[] { path.getChannel(), 1, path.getFrame() };
		for (int i = 0; i < path.size(); ++i) {

			double x = Integer.MIN_VALUE;
			double y = Integer.MIN_VALUE;
			int slice_of_point = Integer.MIN_VALUE;

			switch (exportPlane) {
				case XY_PLANE:
					x = path.getXUnscaledDouble(i);
					y = path.getYUnscaledDouble(i);
					slice_of_point = path.getZUnscaled(i) + 1;
					break;
				case XZ_PLANE:
					x = path.getXUnscaledDouble(i);
					y = path.getZUnscaledDouble(i);
					slice_of_point = path.getYUnscaled(i) + 1;
					break;
				case ZY_PLANE:
					x = path.getZUnscaledDouble(i);
					y = path.getYUnscaledDouble(i);
					slice_of_point = path.getXUnscaled(i) + 1;
					break;
				default:
					throw new IllegalArgumentException("exportPlane is not valid");
			}

			if (current_roi_slice == slice_of_point || i == 0) {
				polygon.addPoint(x, y);
			}
			else {
				roi_pos[1] = current_roi_slice;
				final PolygonRoi polyline = polygonToRoi(polygon, roi_pos, basename, roi_identifier, color, stroke);
				if (polyline != null) polygons.add(polyline);
				polygon = new FloatPolygon(); // reset ROI
				polygon.addPoint(x, y);
			}
			current_roi_slice = slice_of_point;
			roi_pos[1] = current_roi_slice;

		}

		// Create ROI from any remaining points
		final PolygonRoi polyline = polygonToRoi(polygon, roi_pos, basename, roi_identifier, color, stroke);
		if (polyline != null) polygons.add(polyline);

		return polygons;
	}

	/**
	 * Converts all the tips associated with the parsed paths into
	 * {@link ij.gui.PointRoi}s
	 *
	 * @see TreeAnalyzer#getTips()
	 * @param overlay the target overlay to hold converted point
	 */
	public void convertTips(Overlay overlay) {
		if (overlay == null) overlay = new Overlay();
		convertPoints(getTips(), overlay, Color.PINK, "end point");
	}

	/**
	 * Converts paths into 2D polyline ROIs (segment paths), adding them to the
	 * overlay of the image specified in the constructor.
	 * 
	 * @throws IllegalArgumentException if this RoiConverter instance is not aware
	 *                                  of any image
	 * @see #convertPaths(Overlay)
	 */
	public void convertPaths() throws IllegalArgumentException {
		convertPaths(getImpOverlay());
	}

	/**
	 * Converts all the tips associated with the parsed paths into
	 * {@link ij.gui.PointRoi}s, adding them to the overlay of the image specified
	 * in the constructor.
	 * 
	 * @throws IllegalArgumentException if this RoiConverter instance is not aware
	 *                                  of any image
	 * @see #convertTips(Overlay)
	 */
	public void convertTips() throws IllegalArgumentException {
		convertTips(getImpOverlay());
	}

	/**
	 * Converts all the branch points associated with the parsed paths into
	 * {@link ij.gui.PointRoi}s, adding them to the overlay of the image specified
	 * in the constructor.
	 * 
	 * @throws IllegalArgumentException if this RoiConverter instance is not aware
	 *                                  of any image
	 * @see #convertBranchPoints(Overlay)
	 */
	public void convertBranchPoints() throws IllegalArgumentException {
		convertBranchPoints(getImpOverlay());
	}

	/**
	 * Extracts all of the ROIs converted so far associated with the specified
	 * Z-plane. It is assumed that ROIs are stored in the overlay of the image
	 * specified in the constructor.
	 * 
	 * @throws IllegalArgumentException if this RoiConverter instance is not aware
	 *                                  of any image
	 */
	public List<Roi> getZplaneROIs(final int zSlice) {
		return getZplaneROIs(getImpOverlay(), zSlice);
	}

	private Overlay getImpOverlay() throws IllegalArgumentException {
		if (imp == null) throw new IllegalArgumentException("Roiconverter initialized without image.");
		Overlay overlay = imp.getOverlay();
		if (overlay == null) {
			overlay = new Overlay();
			imp.setOverlay(overlay);
		}
		return overlay;
	}

	/**
	 * Converts all the branch points associated with the parsed paths into
	 * {@link ij.gui.PointRoi}s
	 *
	 * @see TreeAnalyzer#getBranchPoints()
	 * @param overlay the target overlay to hold converted point
	 */
	public void convertBranchPoints(Overlay overlay) {
		if (overlay == null) overlay = new Overlay();
		convertPoints(getBranchPoints(), overlay, Color.ORANGE, "fork point");
	}

	/**
	 * Sets the exporting view for segment paths (XY by default).
	 *
	 * @param view either {@link #XY_PLANE}, {@link #XZ_PLANE} or {@link #ZY_PLANE}.
	 */
	public void setView(final int view) {
		if (view != XY_PLANE && view != ZY_PLANE && view != XZ_PLANE)
			throw new IllegalArgumentException("view is not a valid flag");
		this.exportPlane = view;
	}

	/**
	 * Specifies coloring of ROIs by SWC type.
	 *
	 * @param useSWCcolors if true converted ROIs are colored according to their
	 *          SWC type integer flag
	 */
	public void useSWCcolors(final boolean useSWCcolors) {
		this.useSWCcolors = useSWCcolors;
	}

	/**
	 * Sets the line width of converted segment paths. Set it to -1 to have ROIs
	 * plotted using the average diameter of the path
	 *
	 * @param width the new stroke width
	 * @see Path#getMeanRadius
	 * @see ij.gui.Roi#getStrokeWidth
	 */
	public void setStrokeWidth(final int width) {
		this.width = width;
	}

	private Color getColor(final Path p) {
		return (useSWCcolors) ? Path.getSWCcolor(p.getSWCType()) : p.getColor();
	}

	private void drawPathSegments(final Path path, final Overlay overlay) {
		getROIs(path).forEach( roi -> overlay.add(roi));
	}

	private PolygonRoi polygonToRoi(final FloatPolygon p, final int[] impPosition,
			final String basename, final int roi_id, final Color color, final float strokeWidth)
	{
		final String sPlane = getExportPlaneAsString();
		if (p.npoints > 0) {
			if (p.npoints == 1) {
				// create 1-pixel length lines for single points
				p.xpoints[0] -= 0.5f;
				p.ypoints[0] -= 0.5f;
				p.addPoint(p.xpoints[0] + 0.5f, p.ypoints[0] + 0.5f);
			}
			final PolygonRoi polyline = new PolygonRoi(p, Roi.FREELINE);
			polyline.enableSubPixelResolution();
			// polyline.fitSplineForStraightening();
			polyline.setStrokeColor(color);
			polyline.setStrokeWidth(strokeWidth);
			if (twoD)
				polyline.setName(String.format("%s-%04d", basename, roi_id));
			else
				polyline.setName(String.format("%s-%s-%04d", basename, sPlane, roi_id));
			setPosition(polyline, impPosition);
			return polyline;
		}
		return null;
	}

	private void setPosition(final Roi roi, final int[] positions) {
		if (hyperstack) {
			roi.setPosition(positions[0], positions[1], positions[2]);
		} else if (!twoD) {
			roi.setPosition(positions[1]);
		} else {
			roi.setPosition(0);
		}
	}

	/* this will aggregate all points into a single multipoint ROI */
	private void convertPoints(final Set<PointInImage> points,
		final Overlay overlay, final Color color, final String id)
	{
		if (points.isEmpty()) return;
		final SNTPointRoi roi = new SNTPointRoi();
		final String sPlane = getExportPlaneAsString();
		for (final PointInImage p : points) {
			final Path path = p.onPath;
			if (path == null) {
				SNTUtils.log("Converting " + path + " failed. Skipping it...");
				continue;
			}
			final PointInCanvas pp = p.getUnscaledPoint(exportPlane);
			final int[] pos = new int[] { path.getChannel(), (int) Math.round(
					pp.z + 1), path.getFrame() };
			roi.addPoint(pp.x, pp.y, pos);
		}
		roi.setStrokeColor(color);
		roi.setName(String.format("%s-roi-%s", id, sPlane));
		overlay.add(roi);
	}

	private String getExportPlaneAsString() {
		switch (exportPlane) {
			case XZ_PLANE:
				return "XZ";
			case ZY_PLANE:
				return "ZY";
			default:
				return "XY";
		}
	}

	/**
	 * With current IJ1.51u API the only way to set the z-position of a point is
	 * to activate the corresponding image z-slice. This class makes it easier to
	 * do so.
	 */
	private class SNTPointRoi extends PointRoi {

		private static final long serialVersionUID = 1L;

		public SNTPointRoi() {
			super(0, 0);
			deletePoint(0);
			imp = RoiConverter.this.imp;
			if (imp == null) {
				// HACK: this image is just required to assign positions
				// to points. It is an overhead and not required for 2D images
				// We should change the IJ1 API so that position can be assigned
				// without an image
				imp = tree.getImpContainer(exportPlane, 8);
			}
		}

		public void addPoint(final double ox, final double oy, final int[] position) {
			imp.setPositionWithoutUpdate(position[0], position[1], position[2]);
			super.addPoint(imp, ox, oy);
		}
	}

	public static List<Roi> getZplaneROIs(final Overlay overlay, final int zSlice) {
		final List<Roi> rois = new ArrayList<>();
		final Iterator<Roi> it = overlay.iterator();
		while (it.hasNext()) {
			final Roi roi = it.next();
			// see #setPosition
			if ((roi.hasHyperStackPosition() && roi.getZPosition() == zSlice) || roi.getPosition() == zSlice
					|| roi.getPosition() == 0) {
				rois.add(roi);
			}
		}
		return rois;
	}

	public static Roi enlarge(Roi roi, final int pixels) {
		if (roi == null || roi instanceof PointRoi)
			return roi;
		if (roi.isLine())
			roi = Roi.convertLineToArea(roi);
		return (Math.abs(pixels) < 256) ? RoiEnlarger.enlarge255(roi, pixels) : RoiEnlarger.enlarge(roi, pixels);
	}

	public static Roi combine(final List<Roi> rois) {
		// from RoiManager#combine
		if (rois.size() == 1) {
			return rois.get(0);
		}
		final long nPoints = rois.stream().filter(roi -> roi.getType() == Roi.POINT).count();
		if (nPoints == rois.size()) {
			return combinePoints(rois);
		}
		return combineNonPoints(rois);
	}

	/**
	 * Retrieves the radius of a circle with the same area of the specified area ROI.
	 * 
	 * @param imp     The image associated with the ROI
	 * @param areaRoi The input area ROI
	 * @return the radius (in calibrated units) of a circle with the same area of
	 *         {@code areaRoi}
	 */
	public static double getFittedRadius(final ImagePlus imp, final Roi areaRoi) {
		if (!areaRoi.isArea())
			throw new IllegalArgumentException("Only area ROIs supported");
		final Roi existingRoi = imp.getRoi();
		final ImageProcessor ip = imp.getProcessor();
		ip.setRoi(areaRoi);
		final ImageStatistics stats = ImageStatistics.getStatistics(ip, Measurements.AREA, null);
		imp.setRoi(existingRoi);
		final double scaling = (imp.getCalibration().pixelWidth + imp.getCalibration().pixelHeight) / 2;
		final double r = Math.sqrt(stats.pixelCount / Math.PI);
		return r * scaling;
	}

	private static Roi combineNonPoints(final List<Roi> rois) {
		ShapeRoi s1 = null;
		ShapeRoi s2 = null;
		for (Roi roi : rois) {
			try {
				if (!roi.isArea() && roi.getType() != Roi.POINT)
					roi = Roi.convertLineToArea(roi);
				if (s1 == null) {
					if (roi instanceof ShapeRoi)
						s1 = (ShapeRoi) roi.clone();
					else
						s1 = new ShapeRoi(roi);
				} else {
					if (roi instanceof ShapeRoi)
						s2 = (ShapeRoi) roi;
					else
						s2 = new ShapeRoi(roi);
					s1.or(s2);
				}
			} catch (final NullPointerException ex) {
				SNTUtils.log("Skipping " + roi + " an exception occured " + ex.getMessage());
			}
		}
		return (s1 == null) ? null : s1.trySimplify();
	}

	private static Roi combinePoints(final List<Roi> rois) {
		final FloatPolygon fp = new FloatPolygon();
		for (final Roi roi : rois) {
			final FloatPolygon fpi = roi.getFloatPolygon();
			for (int i = 0; i < fpi.npoints; i++)
				fp.addPoint(fpi.xpoints[i], fpi.ypoints[i]);
		}
		return new PointRoi(fp);
	}

}
