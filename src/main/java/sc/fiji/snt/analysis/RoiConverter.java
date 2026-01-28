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
import java.util.*;

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
import sc.fiji.snt.util.BoundingBox;
import sc.fiji.snt.util.PointInCanvas;
import sc.fiji.snt.util.PointInImage;

/**
 * Converts SNT {@link Path}s into (IJ1) ROIs.
 *
 * @see ROIExporterCmd
 * @author Tiago Ferreira
 */
public class RoiConverter {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

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
	private final boolean twoD;
	private final TreeStatistics tStats;

	/**
	 * Instantiates a new Converter. Since an image has not been specified, C,Z,T
	 * positions may not be properly for converted nodes.
	 *
	 * @param tree the Tree to be converted
	 */
	public RoiConverter(final Tree tree) {
		tStats = new TreeStatistics(tree);
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
		tStats = new TreeStatistics(tree);
		this.imp = imp;
		hyperstack = imp.getNChannels() > 1 || imp.getNFrames() > 1;
		twoD = imp.getNSlices() == 1;
	}

	/**
	 * Converts paths into 2D polyline ROIs.
	 *
	 * @param overlay the target overlay to hold converted paths
	 * @return a reference to the overlay holding paths
	 */
	public Overlay convertPaths(Overlay overlay) {
		if (overlay == null) overlay = new Overlay();
		return convertPaths(overlay, tStats.tree.list(), null);
	}

	/**
	 * Converts an ad-hoc collection of paths into 2D polyline ROIs.
	 *
	 * @param overlay the target overlay to hold converted paths
	 * @param paths   the collection of paths to be converted (e.g., a list of branches produced by {@link StrahlerAnalyzer}
	 * @return a reference to the overlay holding paths
	 */
	public Overlay convertPaths(Overlay overlay, Collection<Path> paths) {
		if (overlay == null) overlay = new Overlay();
		return convertPaths(overlay, paths, null);
	}

	private Overlay convertPaths(final Overlay overlay, Collection<Path> paths, final String basename) {
		for (final Path p : paths) {
			if (p.size() > 1) {
				drawPathSegments(p, (basename==null) ? p.getName() : basename, overlay);
			}
			else { // Single Point Path
				final HashSet<PointInImage> pim = new HashSet<>();
				pim.add(p.getNode(0));
				convertPoints(pim, overlay, getColor(p), "SPP");
			}
		}
		return overlay;
	}

	/**
	 * Gets ROI representations of the specified path.
	 * <p>
	 * Converts the path to a list of PolygonRoi objects using the path's name
	 * as the ROI name. The conversion respects the current view setting.
	 * </p>
	 *
	 * @param path the path to convert
	 * @return a list of PolygonRoi objects representing the path
	 */
	public List<PolygonRoi> getROIs(final Path path) {
		return getROIs(path, path.getName());
	}

	private List<PolygonRoi> getROIs(final Path path, final String basename) {

		final List<PolygonRoi> polygons = new ArrayList<>();
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
				final PolygonRoi polyline = polygonToRoi(polygon, roi_pos, basename, roi_identifier++, color, stroke);
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
	 * @see TreeStatistics#getTips()
	 * @param overlay the target overlay to hold converted point
	 */
	public void convertTips(Overlay overlay) {
		if (overlay == null) overlay = new Overlay();
		convertPoints(tStats.getTips(), overlay, Color.PINK, "EndPoint");
	}

	/**
	 * Converts the starting nodes of primary paths in the parsed pool into
	 * {@link ij.gui.PointRoi}s
	 *
	 * @param overlay the target overlay to hold converted point
	 */
	public void convertRoots(Overlay overlay) {
		if (overlay == null) overlay = new Overlay();
		final Set<PointInImage> roots = new HashSet<>();
		for (final Path p : tStats.tree.list()) {
			if (p.isPrimary())
				roots.add(p.getNode(0));
		}
		convertPoints(roots, overlay, Color.CYAN, "Root");
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

    public void convertBranches(Overlay overlay) {
        if (overlay == null) overlay = new Overlay();
        int lastIdx = overlay.size();
        convertPaths(overlay, tStats.getBranches(), "Branch");
        appendNumericSuffixToROIs(overlay, lastIdx, overlay.size()-1);
    }

	public void convertInnerBranches(Overlay overlay) {
		if (overlay == null) overlay = new Overlay();
		int lastIdx = overlay.size();
		convertPaths(overlay, tStats.getInnerBranches(), "InnerBranch");
		appendNumericSuffixToROIs(overlay, lastIdx, overlay.size()-1);
	}

	public void convertPrimaryBranches(Overlay overlay) {
		if (overlay == null) overlay = new Overlay();
		int lastIdx = overlay.size();
		convertPaths(overlay, tStats.getPrimaryBranches(), "Prim.Branch");
		appendNumericSuffixToROIs(overlay, lastIdx, overlay.size()-1);
	}

	public void convertTerminalBranches(Overlay overlay) {
		if (overlay == null) overlay = new Overlay();
		int lastIdx = overlay.size();
		convertPaths(overlay, tStats.getTerminalBranches(), "Term.Branch");
		appendNumericSuffixToROIs(overlay, lastIdx, overlay.size()-1);
	}

	private void appendNumericSuffixToROIs(final Overlay overlay, final int from, final int to) {
		int suffix = 1;
		for (int i = from; i <= to; i++) {
			final Roi roi = overlay.get(i);
			roi.setName(String.format("%s-%04d", roi.getName(), suffix++));
		}
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
	 * Converts the starting nodes of primary paths in the parsed pool into
	 * {@link ij.gui.PointRoi}s
	 *
	 * @throws IllegalArgumentException if this RoiConverter instance is not aware
	 *                                  of any image
	 * @see #convertRoots(Overlay)
	 */
	public void convertRoots() throws IllegalArgumentException {
		convertRoots(getImpOverlay());
	}

	/**
	 * Extracts all the ROIs converted so far associated with the specified
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
	 * @see TreeStatistics#getBranchPoints()
	 * @param overlay the target overlay to hold converted point
	 */
	public void convertBranchPoints(Overlay overlay) {
		if (overlay == null) overlay = new Overlay();
		convertPoints(tStats.getBranchPoints(), overlay, Color.ORANGE, "BranchPoint");
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

	private void drawPathSegments(final Path path, final String basename, final Overlay overlay) {
		getROIs(path, basename).forEach( roi -> overlay.add(roi));
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
			final String name = basename.replaceAll("\\s", "");
			if (twoD)
				polyline.setName(String.format("%s-%04d", name, roi_id));
			else
				polyline.setName(String.format("%s-%s-%04d-%04d", name, sPlane, impPosition[1], roi_id));
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
				SNTUtils.log("Converting path failed. Skipping it..." + p);
				continue;
			}
			final PointInCanvas pp = p.getUnscaledPoint(exportPlane);
			final int[] pos = new int[] { path.getChannel(), (int) Math.round(
					pp.z + 1), path.getFrame() };
			roi.addPoint(pp.x, pp.y, pos);
		}
		roi.setStrokeColor(color);
		final String label = (roi.size() > 1) ? "s-MultiPoint" : "-SinglePoint";
		if (twoD)
			roi.setName(String.format("%s%s", id, label));
		else
			roi.setName(String.format("%s%s-%s", id, label, sPlane));
		overlay.add(roi);
	}

	private String getExportPlaneAsString() {
        return switch (exportPlane) {
            case XZ_PLANE -> "XZ";
            case ZY_PLANE -> "ZY";
            default -> "XY";
        };
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
				imp = tStats.tree.getImpContainer(exportPlane, 8);
			}
		}

		public void addPoint(final double ox, final double oy, final int[] position) {
			imp.setPositionWithoutUpdate(position[0], position[1], position[2]);
			super.addPoint(imp, ox, oy);
		}
	}

	/**
	 * Extracts ROIs associated with a specified Z position.
	 *
	 * @param overlay the overlay holding ROIs
	 * @param zSlice  the z-plane (1-based index)
	 * @return the sub-list of ROIs associated with the specified Z position. Note
	 *         that ROIs with a ZPosition of 0 are considered to be associated with
	 *         all Z-slices of a stack
	 */
	public static List<Roi> getZplaneROIs(final Overlay overlay, final int zSlice) {
		final List<Roi> rois = new ArrayList<>();
		final Iterator<Roi> it = overlay.iterator();
		while (it.hasNext()) {
			final Roi roi = it.next();
			// see #setPosition: In IJ1 ROIs w/ position 0 are associated with all slices of a stack
			if ((roi.hasHyperStackPosition() && roi.getZPosition() == zSlice) || roi.getPosition() == zSlice
					|| roi.getPosition() == 0) {
				rois.add(roi);
			}
		}
		return rois;
	}

	/**
	 * Extracts ROIs associated with a specified CZT position. Only ROIS with known
	 * hyperstackPosition are considered.
	 *
	 * @param overlay the overlay holding ROIs
	 * @param channel the channel (1-based index)
	 * @param zSlice the z-plane (1-based index)
	 * @param tFrame the t-frame (1-based index)
	 * @return the sub-list of ROIs associated with the specified CZT position
	 * @see Roi#hasHyperStackPosition()
	 */
	public static List<Roi> getROIs(final Overlay overlay, final int channel, final int zSlice, final int tFrame) {
		final List<Roi> rois = new ArrayList<>();
		final Iterator<Roi> it = overlay.iterator();
		while (it.hasNext()) {
			final Roi roi = it.next();
			if (roi.hasHyperStackPosition() && roi.getCPosition() == channel && roi.getZPosition() == zSlice
					&& roi.getTPosition() == tFrame) {
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

    public static Roi convert(final Collection<Path> paths, final ImagePlus imp) {
        final RoiConverter converter = new RoiConverter(paths, imp);
        final Overlay holdingOverlay = new Overlay();
        converter.convertPaths(holdingOverlay);
        return combine(Arrays.asList(holdingOverlay.toArray()));
    }

    public static Roi get2DBoundingBox(final Collection<Path> paths, final int exportPlane) {
        final BoundingBox box = new BoundingBox();
        paths.forEach(path -> box.append(path.getUnscaledNodes().iterator()) );
        final PointInImage ori = box.origin();
        final PointInImage oriOpp = box.originOpposite();
        switch (exportPlane) {
            case XY_PLANE -> {
                final Roi roi = new Roi(ori.x, ori.y, (oriOpp.x - ori.x), (oriOpp.y - ori.y));
                roi.setPosition((int) ((oriOpp.z - ori.z) / 2) + 1);
                return roi;
            }
            case XZ_PLANE -> {
                final Roi roi = new Roi(ori.x, ori.z, (oriOpp.x - ori.x), (oriOpp.z - ori.z));
                roi.setPosition((int) ((oriOpp.y - ori.y) / 2) + 1);
                return roi;
            }
            case ZY_PLANE -> {
                final Roi roi = new Roi(ori.z, ori.y, (oriOpp.z - ori.z), (oriOpp.y - ori.y));
                roi.setPosition((int) ((oriOpp.x - ori.x) / 2) + 1);
                return roi;
            }
            default -> throw new IllegalArgumentException("exportPlane is not valid");
        }
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
        final List<Integer> cPositions= new ArrayList<>();
        final List<Integer> zPositions= new ArrayList<>();
        final List<Integer> tPositions= new ArrayList<>();
        for (Roi roi : rois) {
            cPositions.add(roi.getCPosition());
            zPositions.add(roi.getZPosition());
            tPositions.add(roi.getTPosition());
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
				SNTUtils.log("Skipping " + roi + " an exception occurred " + ex.getMessage());
			}
		}
        if (s1 == null) return null;
        s1.setPosition((int) cPositions.stream().mapToInt(Integer::intValue).average().orElse(1d),
                (int) zPositions.stream().mapToInt(val -> val).average().orElse(1d),
                (int) tPositions.stream().mapToInt(val -> val).average().orElse(1d));
		return s1.trySimplify();
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
