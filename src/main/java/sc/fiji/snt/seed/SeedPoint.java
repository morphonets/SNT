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

import sc.fiji.snt.annotation.BrainAnnotation;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTPoint;

/**
 * Immutable candidate seed point used to bootstrap auto-tracing or as a
 * visualization aid for the output of upstream point detectors (e.g.,
 * deep-learning probability maps + local-max suppression).
 * <p>
 * Coordinates are stored in <b>physical units</b> (same convention as
 * {@link sc.fiji.snt.util.PointInImage}). Importers that receive voxel-indexed
 * input are expected to convert at parse time using the active image's
 * spacing.
 * <p>
 * Implements {@link SNTPoint} so seeds work polymorphically with the rest of
 * SNT's spatial / analysis APIs. The core seed payload (x, y, z, confidence,
 * radius, channel, frame, type, source) is immutable; only the
 * {@link BrainAnnotation} and hemisphere fields are mutable, since they are
 * typically populated after construction by atlas-lookup code.
 * <p>
 * Instances are cheap to copy; collections of millions of seeds are
 * intended use cases.
 *
 * @author Tiago Ferreira
 * @see SeedOverlay
 */
public final class SeedPoint implements SNTPoint {

    /** Sentinel for missing/unspecified channel or frame. */
    public static final int CT_UNSET = -1;
    /** Empty-string sentinel for unset {@link #type} / {@link #source}. */
    public static final String TAG_UNSET = "";

    /** Physical X coordinate (calibrated). */
    public final double x;
    /** Physical Y coordinate (calibrated). */
    public final double y;
    /** Physical Z coordinate (calibrated). 0 for 2D images. */
    public final double z;
    /** Detector confidence in {@code [0, 1]}. */
    public final double confidence;
    /** Estimated radius in physical units. {@code 0} if unknown. */
    public final double radius;
    /** 1-based channel index, or {@link #CT_UNSET}. */
    public final int channel;
    /** 1-based frame index, or {@link #CT_UNSET}. */
    public final int frame;
    /**
     * Free-form semantic label (e.g. {@code "soma"}, {@code "tip"},
     * {@code "branchpoint"}, {@code "waypoint"}). Set at import time by the
     * upstream detector. Never {@code null}; {@link #TAG_UNSET} when absent.
     */
    public final String type;
    /**
     * Provenance label (e.g. {@code "dl-detected"}, {@code "user-added"},
     * {@code "path-derived"}). Records where the seed came from. Never
     * {@code null}; {@link #TAG_UNSET} when absent.
     */
    public final String source;

    /** Optional atlas / neuropil annotation. {@code null} until populated. */
    private BrainAnnotation annotation;
    /** Hemisphere flag ({@code 'l'}, {@code 'r'}, or {@code '?'} for unknown). */
    private char hemisphere = '?';

    /**
     * Creates a seed without channel/frame assignment and no type/source.
     *
     * @param x          physical X
     * @param y          physical Y
     * @param z          physical Z (0 for 2D)
     * @param confidence detector confidence in {@code [0, 1]}
     * @param radius     physical radius ({@code >= 0})
     */
    public SeedPoint(final double x, final double y, final double z,
                     final double confidence, final double radius) {
        this(x, y, z, confidence, radius, CT_UNSET, CT_UNSET, TAG_UNSET, TAG_UNSET);
    }

    /**
     * Creates a seed with channel/frame assignment but no type/source.
     *
     * @param x          physical X
     * @param y          physical Y
     * @param z          physical Z (0 for 2D)
     * @param confidence detector confidence in {@code [0, 1]}
     * @param radius     physical radius ({@code >= 0})
     * @param channel    1-based channel index, or {@link #CT_UNSET}
     * @param frame      1-based frame index, or {@link #CT_UNSET}
     */
    public SeedPoint(final double x, final double y, final double z,
                     final double confidence, final double radius,
                     final int channel, final int frame) {
        this(x, y, z, confidence, radius, channel, frame, TAG_UNSET, TAG_UNSET);
    }

    /**
     * Full constructor. {@code null} values for {@code type} or {@code source}
     * are normalised to {@link #TAG_UNSET}.
     *
     * @param x          physical X
     * @param y          physical Y
     * @param z          physical Z (0 for 2D)
     * @param confidence detector confidence in {@code [0, 1]}
     * @param radius     physical radius ({@code >= 0})
     * @param channel    1-based channel index, or {@link #CT_UNSET}
     * @param frame      1-based frame index, or {@link #CT_UNSET}
     * @param type       semantic label, or {@link #TAG_UNSET}
     * @param source     provenance label, or {@link #TAG_UNSET}
     */
    public SeedPoint(final double x, final double y, final double z,
                     final double confidence, final double radius,
                     final int channel, final int frame,
                     final String type, final String source) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.confidence = confidence;
        this.radius = radius;
        this.channel = channel;
        this.frame = frame;
        this.type = (type == null) ? TAG_UNSET : type;
        this.source = (source == null) ? TAG_UNSET : source;
    }

    /**
     * Squared 3D Euclidean distance to another seed (physical units).
     * Use the squared form for nearest-neighbor comparisons to avoid the
     * {@code sqrt}; pass the result to {@link Math#sqrt(double)} when an
     * actual distance is needed.
     */
    public double distanceSqTo(final SeedPoint other) {
        final double dx = x - other.x;
        final double dy = y - other.y;
        final double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Squared 3D Euclidean distance to a raw point (physical units). */
    public double distanceSqTo(final double px, final double py, final double pz) {
        final double dx = x - px;
        final double dy = y - py;
        final double dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Returns this seed as a {@code double[]{x, y, z}} array. */
    public double[] asArray() {
        return new double[]{x, y, z};
    }

    /**
     * Returns a {@link PointInImage} carrying this seed's coordinates and
     * radius, useful when handing the seed to APIs that expect the concrete
     * point class rather than the {@link SNTPoint} interface.
     */
    public PointInImage toPointInImage() {
        final PointInImage p = new PointInImage(x, y, z);
        p.radius = radius;
        p.v = confidence;
        if (annotation != null) p.setAnnotation(annotation);
        if (hemisphere != '?') p.setHemisphere(hemisphere);
        return p;
    }

    // SNTPoint contract
    @Override public double getX() { return x; }
    @Override public double getY() { return y; }
    @Override public double getZ() { return z; }

    @Override
    public double getCoordinateOnAxis(final int axis) {
        return switch (axis) {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            default -> throw new IllegalArgumentException("axis must be 0, 1, or 2; got " + axis);
        };
    }

    @Override public void setAnnotation(final BrainAnnotation annotation) { this.annotation = annotation; }
    @Override public BrainAnnotation getAnnotation() { return annotation; }
    @Override public void setHemisphere(final char lr) { this.hemisphere = lr; }
    @Override public char getHemisphere() { return hemisphere; }

    @Override
    public String toString() {
        return String.format(
                "SeedPoint[x=%.3f y=%.3f z=%.3f conf=%.3f r=%.3f c=%d t=%d type='%s' src='%s']",
                x, y, z, confidence, radius, channel, frame, type, source);
    }
}
