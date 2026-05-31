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

package sc.fiji.snt.seed;

import ij.ImagePlus;
import ij.gui.Line;
import ij.gui.OvalRoi;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.FloatPolygon;
import ij.process.ImageStatistics;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Bidirectional {@link SeedPoint}-{@link Roi} converter.
 *
 * @author Tiago Ferreira
 */
public final class SeedRois {

    private SeedRois() {
    }

    /**
     * Converts the given ROIs to seeds.
     * <p>
     * Rules per ROI type:
     * <ul>
     *   <li><b>Area ROIs</b> (oval, polygon, rectangle, freehand, traced):
     *       centroid as position, radius derived from the area of the matched
     *       circle ({@code r = sqrt(area / pi)})
     *   <li><b>Line ROIs</b>: midpoint as position, radius = half the path
     *       length
     *   <li><b>Point ROIs</b>: each contained point becomes its own seed with
     *       {@code radius = 0}. All points inherit the ROI's hyperstack position.
     *   <li><b>Other (e.g. ANGLE, COMPOSITE)</b>: bounds center as position,
     *       {@code radius = 0}.
     * </ul>
     *
     * @param rois       the input ROIs. {@code null}/empty array returns an empty list.
     *                   {@code null} entries are skipped.
     * @param imp        provides calibration and default C/T/Z. {@code null}
     *                   yields uncalibrated (voxel-unit) coordinates and unset CT position.
     * @param confidence value assigned to every produced seed, clamped to {@code [0, 1]}.
     * @param type       value assigned to each seed's {@code type} field.
     *                   {@code null} -> {@code ""}.
     * @param source     value assigned to each seed's {@code source} field (e.g.
     *                   {@code "roi"}). {@code null} -> {@code ""}.
     * @return a list of seeds. Point ROIs may produce multiple seeds
     * (one per contained point); a ROI with no usable geometry is
     * skipped.
     */
    public static List<SeedPoint> toSeeds(final Roi[] rois, final ImagePlus imp,
                                          final double confidence,
                                          final String type, final String source) {
        if (rois == null || rois.length == 0) return new ArrayList<>(0);
        final double confSafe = Math.clamp(confidence, 0.0, 1.0);

        final double sx, sy, sz;
        final int defaultC, defaultT, defaultZ;
        if (imp != null) {
            final Calibration cal = imp.getCalibration();
            sx = cal.pixelWidth;
            sy = cal.pixelHeight;
            sz = cal.pixelDepth;
            defaultC = imp.getC();
            defaultT = imp.getT();
            defaultZ = imp.getZ();
        } else {
            sx = sy = sz = 1.0;
            defaultC = SeedPoint.CT_UNSET;
            defaultT = SeedPoint.CT_UNSET;
            defaultZ = 1;
        }
        final String typeSafe = (type == null) ? SeedPoint.TAG_UNSET : type;
        final String srcSafe = (source == null) ? SeedPoint.TAG_UNSET : source;

        final List<SeedPoint> seeds = new ArrayList<>(rois.length);
        for (final Roi roi : rois) {
            if (roi == null) continue;

            // Resolve channel/frame/Z from ROI metadata (fall back to imp's active position when not set on the ROI)
            int c = roi.getCPosition();
            if (c <= 0) c = defaultC;
            int t = roi.getTPosition();
            if (t <= 0) t = defaultT;
            int z = roi.getZPosition();
            if (z <= 0) z = roi.getPosition();
            if (z <= 0) z = defaultZ;
            final double zPhys = (z - 1) * sz; // 1-based slice -> 0-based physical depth

            if (roi instanceof PointRoi pr) {
                // Each contained point becomes its own seed (radius 0)
                final FloatPolygon poly = pr.getFloatPolygon();
                for (int i = 0; i < poly.npoints; i++) {
                    seeds.add(new SeedPoint(
                            poly.xpoints[i] * sx, poly.ypoints[i] * sy, zPhys,
                            confSafe, 0.0, c, t, typeSafe, srcSafe));
                }
                continue;
            }

            final double xVox, yVox, radius;
            if (roi.isArea()) {
                final ImageStatistics stats = roi.getStatistics();
                xVox = stats.xCentroid;
                yVox = stats.yCentroid;
                final double areaPhys = stats.area * sx * sy;
                radius = Math.sqrt(areaPhys / Math.PI);
            } else if (roi instanceof Line line) {
                // Straight line: exact midpoint + calibrated half-length
                xVox = (line.x1d + line.x2d) / 2.0;
                yVox = (line.y1d + line.y2d) / 2.0;
                final double dx = (line.x2d - line.x1d) * sx;
                final double dy = (line.y2d - line.y1d) * sy;
                radius = Math.sqrt(dx * dx + dy * dy) / 2.0;
            } else if (roi.isLine()) {
                // Polyline / freeline: vertex centroid + half the summed calibrated segment length.
                final FloatPolygon poly = roi.getFloatPolygon();
                double sumX = 0;
                double sumY = 0;
                double pathLen = 0;
                for (int i = 0; i < poly.npoints; i++) {
                    sumX += poly.xpoints[i];
                    sumY += poly.ypoints[i];
                    if (i > 0) {
                        final double dx = (poly.xpoints[i] - poly.xpoints[i - 1]) * sx;
                        final double dy = (poly.ypoints[i] - poly.ypoints[i - 1]) * sy;
                        pathLen += Math.sqrt(dx * dx + dy * dy);
                    }
                }
                xVox = sumX / poly.npoints;
                yVox = sumY / poly.npoints;
                radius = pathLen / 2.0;
            } else {
                // Unsupported geometry (ANGLE, COMPOSITE, etc.) - use bounds center, no radius
                final Rectangle b = roi.getBounds();
                xVox = b.getCenterX();
                yVox = b.getCenterY();
                radius = 0.0;
            }
            seeds.add(new SeedPoint(xVox * sx, yVox * sy, zPhys, confSafe, radius, c, t, typeSafe, srcSafe));
        }
        return seeds;
    }

    /**
     * Converts the given seeds to ROIs.
     *<p>
     * Seed radius is converted using the geometric mean of pixelWidth and pixelHeight
     * so a 2D seed round-trip preserves the physical area of the equivalent disk
     *
     * @param seeds the input seed points. {@code null}/empty array returns
     *              an empty list. {@code null} entries are skipped.
     * @param imp   provides calibration. {@code null}  yields uncalibrated
     *              (voxel-unit) coordinates
     * @return a list of ROIs (either PointROI (seeds without radius) or OvalRoi.
     */
    public static List<Roi> toRois(final Collection<SeedPoint> seeds, final ImagePlus imp) {
        if (seeds == null || seeds.isEmpty()) return new ArrayList<>(0);
        final Calibration cal = (imp == null) ? new Calibration() : imp.getCalibration();
        final List<Roi> rois = new ArrayList<>(seeds.size());
        for (final SeedPoint seed : seeds) {
            if (seed == null) continue;
            final double xVox = seed.x / cal.pixelWidth;
            final double yVox = seed.y / cal.pixelHeight;
            final double rVox = seed.radius / Math.sqrt(cal.pixelWidth * cal.pixelHeight);
            final int zSlice = (int) Math.round(cal.getRawZ(seed.z)) + 1; // 1-based index
            final int c = (seed.channel >= 1) ? seed.channel : 0;
            final int t = (seed.frame >= 1) ? seed.frame : 0;
            final Roi roi = (seed.radius > 0)
                    ? new OvalRoi(xVox - rVox, yVox - rVox, 2 * rVox, 2 * rVox)
                    : new PointRoi(xVox, yVox);
            roi.setPosition(c, zSlice, t);
            if (seed.type != null && !seed.type.isBlank())
                roi.setName(seed.type);
            rois.add(roi);
        }
        return rois;
    }

}
