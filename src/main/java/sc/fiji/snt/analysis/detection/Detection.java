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

package sc.fiji.snt.analysis.detection;

import sc.fiji.snt.Path;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTPoint;

/**
 * One detected feature along or around a traced path (varicosity, spine,
 * bouton, punctum, etc.).
 *
 * @author Tiago Ferreira
 * @see PeripathDetector
 * @see AlongPathDetector
 */
public class Detection {

    /** X-coordinate of the detection in real-world units */
    public final double x;
    /** Y-coordinate of the detection in real-world units */
    public final double y;
    /** Z-coordinate of the detection in real-world units */
    public final double z;
    /** Intensity at the detection site */
    public final double intensity;
    /** The path this detection is associated with */
    public final Path path;
    /** Index of the nearest node on the associated path */
    public final int nodeIndex;
    /** Distance from the path skeleton at the detection point (physical units) */
    public final double distanceFromSkeleton;
    /**
     * Label value from a segmentation/label image associated with this
     * detection, or {@code -1} if not applicable. Set by
     * {@link LabelProximityDetector} to identify which label surface
     * produced the contact.
     */
    public final int labelValue;

    /**
     * Creates a new detection.
     *
     * @param x                    X-coordinate in real-world units
     * @param y                    Y-coordinate in real-world units
     * @param z                    Z-coordinate in real-world units
     * @param intensity            intensity at the detection site
     * @param path                 associated path
     * @param nodeIndex            index of the nearest node on the path
     * @param distanceFromSkeleton distance from the skeleton (physical units)
     */
    public Detection(final double x, final double y, final double z,
                     final double intensity, final Path path, final int nodeIndex,
                     final double distanceFromSkeleton) {
        this(x, y, z, intensity, path, nodeIndex, distanceFromSkeleton, -1);
    }

    /**
     * Creates a new detection with an associated label value.
     *
     * @param x                    X-coordinate in real-world units
     * @param y                    Y-coordinate in real-world units
     * @param z                    Z-coordinate in real-world units
     * @param intensity            intensity at the detection site
     * @param path                 associated path
     * @param nodeIndex            index of the nearest node on the path
     * @param distanceFromSkeleton distance from the skeleton (physical units)
     * @param labelValue           label-image value associated with this
     *                             detection, or {@code -1} if not applicable
     */
    public Detection(final double x, final double y, final double z,
                     final double intensity, final Path path, final int nodeIndex,
                     final double distanceFromSkeleton, final int labelValue) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.intensity = intensity;
        this.path = path;
        this.nodeIndex = nodeIndex;
        this.distanceFromSkeleton = distanceFromSkeleton;
        this.labelValue = labelValue;
    }

    /**
     * Returns this detection as an {@link SNTPoint}.
     *
     * @return a new {@link PointInImage} at this detection's coordinates
     */
    public PointInImage toSNTPoint() {
        final PointInImage pim = new PointInImage(x, y, z);
        pim.onPath = path;
        return pim;
    }

    /**
     * Returns the XYZCT coordinates for bookmark/ROI integration.
     * X, Y, Z are in pixel (uncalibrated) coordinates; C and T are
     * 1-based indices from the associated path.
     *
     * @return {@code double[5]}: {xPixel, yPixel, zPixel, channel, frame}
     */
    public double[] xyzct() {
        final ij.measure.Calibration cal = path.getCalibration();
        return new double[]{
                cal.getRawX(x),
                cal.getRawY(y),
                cal.getRawZ(z),
                path.getChannel(),
                path.getFrame()
        };
    }

    @Override
    public String toString() {
        final String base = String.format(
                "Detection[x=%.3f,y=%.3f,z=%.3f; I=%.1f; dist=%.3f; path=%s; node=%d",
                x, y, z, intensity, distanceFromSkeleton,
                path != null ? path.getName() : "null", nodeIndex);
        return (labelValue >= 0)
                ? base + "; label=" + labelValue + "]"
                : base + "]";
    }
}
