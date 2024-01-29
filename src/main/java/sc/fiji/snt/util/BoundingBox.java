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

package sc.fiji.snt.util;

import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.jzy3d.maths.BoundingBox3d;

import ij.measure.Calibration;
import sc.fiji.snt.gui.GuiUtils;

/**
 * A BoundingBox contains information (including spatial calibration) of a
 * tracing canvas bounding box, i.e., the minimum bounding cuboid containing all
 * nodes ({@link SNTPoint}s) of a reconstructed structure.
 *
 * @author Tiago Ferreira
 */
public class BoundingBox {

	private final static String DEF_SPACING_UNIT = "? units";

	/** The 'voxel width' of the bounding box */
	public double xSpacing = 1d;

	/** The 'voxel height' of the bounding box */
	public double ySpacing = 1d;

	/** The 'voxel depth' of the bounding box */
	public double zSpacing = 1d;

	/** Used to store information about this bounding box. Default is null */
	public String info = null;

	protected String spacingUnit = DEF_SPACING_UNIT;

	/** The bounding box origin (SE, lower corner) */
	protected PointInImage origin;

	/** The origin opposite (NW, upper corner) */
	protected PointInImage originOpposite;

	/**
	 * Constructs an 'empty' BoundingBox using default values, with the box origin
	 * defined as a point with {@link Double#NaN} coordinates and
	 * {@link Double#NaN} dimensions
	 */
	public BoundingBox() {
		reset();
	}

	protected void reset() {
		origin = new PointInImage(Double.NaN, Double.NaN, Double.NaN);
		originOpposite = new PointInImage(Double.NaN, Double.NaN, Double.NaN);
	}

	/**
	 * Sets the voxel spacing.
	 *
	 * @param xSpacing the 'voxel width' of the bounding box
	 * @param ySpacing the 'voxel height' of the bounding box
	 * @param zSpacing the 'voxel depth' of the bounding box
	 * @param spacingUnit the length unit
	 */
	public void setSpacing(final double xSpacing, final double ySpacing,
		final double zSpacing, final String spacingUnit)
	{
		this.xSpacing = xSpacing;
		this.ySpacing = ySpacing;
		this.zSpacing = zSpacing;
		this.setUnit(spacingUnit);
	}

	/**
	 * Computes a new positioning so that this box encloses the specified point
	 * cloud.
	 *
	 * @param iterator the iterator of the points Collection
	 */
	public void compute(final Iterator<? extends SNTPoint> iterator) {
		final SummaryStatistics xStats = new SummaryStatistics();
		final SummaryStatistics yStats = new SummaryStatistics();
		final SummaryStatistics zStats = new SummaryStatistics();
		iterator.forEachRemaining(p -> {
			xStats.addValue(p.getX());
			yStats.addValue(p.getY());
			zStats.addValue(p.getZ());
		});
		origin = new PointInImage(xStats.getMin(), yStats.getMin(), zStats.getMin());
		originOpposite = new PointInImage(xStats.getMax(), yStats.getMax(), zStats.getMax());
	}

	/**
	 * Computes the bounding box of the specified point cloud and appends it to this
	 * bounding box, resizing it as needed.
	 *
	 * @param iterator the iterator of the points Collection
	 */
	public void append(final Iterator<? extends SNTPoint> iterator) {
		if (hasDimensions()) {
			final BoundingBox b = new BoundingBox();
			b.compute(iterator);
			combine(b);
		} else {
			compute(iterator);
		}
	}

	public SNTPoint getCentroid() {
		return new PointInImage((origin.x + originOpposite.x) / 2,
				(origin.y + originOpposite.y) / 2,
				(origin.z + originOpposite.z) / 2);
	}

	/**
	 * Infers the voxel spacing of this box from the inter-node distances of a
	 * Collection of {@link SWCPoint}s.
	 *
	 * @param points the point collection
	 */
	public void inferSpacing(final Collection<SWCPoint> points) {
		final SummaryStatistics xyStats = new SummaryStatistics();
		final SummaryStatistics zStats = new SummaryStatistics();
		for (final SWCPoint p : points) {
			if (p.getPreviousPoint() == null) continue;
			xyStats.addValue(Math.abs(p.x - p.getPreviousPoint().x));
			xyStats.addValue(Math.abs(p.y - p.getPreviousPoint().y));
			zStats.addValue(Math.abs(p.z - p.getPreviousPoint().z));
		}
		final double xyMean = xyStats.getMean();
		xSpacing = xyMean;
		ySpacing = xyMean;
		zSpacing = 2 * zStats.getMean();
	}

	/**
	 * Checks whether this BoundingBox is spatially calibrated, i.e., if voxel
	 * spacing has been specified
	 *
	 * @return true, if voxel spacing has been specified
	 */
	public boolean isScaled() {
		return xSpacing != 1d || ySpacing != 1d || zSpacing != 1d ||
				!spacingUnit.equals(DEF_SPACING_UNIT);
	}

	/**
	 * Sets the default length unit for voxel spacing (typically um, for SWC
	 * reconstructions)
	 *
	 * @param unit the new unit
	 */
	public void setUnit(final String unit) {
		if (unit == null || unit.startsWith("pixel")) {
			spacingUnit = DEF_SPACING_UNIT;
			return;
		}
		final String sanitizedUnit = unit.trim().toLowerCase();
		if (sanitizedUnit.isEmpty()) {
			spacingUnit = DEF_SPACING_UNIT;
		}
		else if ("um".equals(sanitizedUnit) || "micron".equals(sanitizedUnit) ||
			"microns".equals(sanitizedUnit))
		{
			spacingUnit = GuiUtils.micrometer();
		}
		else {
			spacingUnit = unit;
		}
	}

	/**
	 * Gets the length unit of voxel spacing
	 *
	 * @return the unit
	 */
	public String getUnit() {
		return spacingUnit;
	}

	/**
	 * Creates a Calibration object using information from this BoundingBox
	 *
	 * @return the Calibration object
	 */
	public Calibration getCalibration() {
		final Calibration cal = new Calibration();
		cal.pixelWidth = xSpacing;
		cal.pixelHeight = ySpacing;
		cal.pixelDepth = zSpacing;
		cal.xOrigin = origin.x;
		cal.yOrigin = origin.y;
		cal.zOrigin = origin.z;
		cal.setUnit(spacingUnit);
		return cal;
	}

	/**
	 * Gets this BoundingBox dimensions in real world units.
	 *
	 * @return the BoundingBox dimensions {width, height, depth}.
	 */
	public double[] getDimensions() {
		return getDimensions(true);
	}

	/**
	 * Gets this BoundingBox dimensions.
	 *
	 * @param scaled If true, dimensions are retrieved in real world units,
	 *          otherwise in ("pixel") units
	 * @return the BoundingBox dimensions {width, height, depth}.
	 */
	public double[] getDimensions(final boolean scaled) {
		final double xScale = (scaled) ? 1d : xSpacing;
		final double yScale = (scaled) ? 1d : ySpacing;
		final double zScale = (scaled) ? 1d : zSpacing;
		final double width = Math.abs((originOpposite.x - origin.x) / xScale);
		final double height = Math.abs((originOpposite.y - origin.y) / yScale);
		final double depth = Math.abs((originOpposite.z - origin.z) / zScale);
		return new double[] { width, height, depth };
	}

	/**
	 * Gets the box diagonal
	 *
	 * @return the diagonal of BoundingBox
	 */
	public double getDiagonal() {
		return origin.distanceTo(originOpposite);
	}

	/**
	 * Checks whether the specified BoundingBox is enclosed by this one .
	 *
	 * @param boundingBox the bounding box to be tested
	 * @return true, if successful
	 */
	public boolean contains(final BoundingBox boundingBox) {
		return (boundingBox.origin.x >= origin.x && //
			boundingBox.origin.y >= origin.y && //
			boundingBox.origin.z >= origin.z && //
			boundingBox.originOpposite.x <= originOpposite.x && //
			boundingBox.originOpposite.y <= originOpposite.y && //
			boundingBox.originOpposite.z <= originOpposite.z);
	}

	public boolean contains(final SNTPoint point) {
		return (point.getX() >= origin.x && //
			point.getY() >= origin.y && //
			point.getZ() >= origin.z && //
			point.getX() <= originOpposite.x && //
			point.getY() <= originOpposite.y && //
			point.getZ() <= originOpposite.z);
	}

	/**
	 * Combines Combines this bounding box with another one. It is asumed both boxes
	 * share the same voxel spacing/Calibration.
	 *
	 * @param boundingBox the bounding box to be combined.
	 */
	public void combine(final BoundingBox boundingBox) {
		origin.x = Math.min(origin.x, boundingBox.origin.x);
		origin.y = Math.min(origin.y, boundingBox.origin.y);
		origin.z = Math.min(origin.z, boundingBox.origin.z);
		originOpposite.x = Math.max(originOpposite.x, boundingBox.originOpposite.x);
		originOpposite.y = Math.max(originOpposite.y, boundingBox.originOpposite.y);
		originOpposite.z = Math.max(originOpposite.z, boundingBox.originOpposite.z);
	}

	/**
	 * Retrieves the origin of this box.
	 *
	 * @return the origin
	 */
	public PointInImage origin() {
		return origin;
	}

	/**
	 * Retrieves the origin opposite of this box.
	 *
	 * @return the origin
	 */
	public PointInImage originOpposite() {
		return originOpposite;
	}


	/**
	 * Retrieves the origin of this box in unscaled ("pixel" units)
	 *
	 * @return the unscaled origin
	 */
	public PointInImage unscaledOrigin() {
		return new PointInImage(origin.x / xSpacing, origin.y / ySpacing, origin.z /
			zSpacing);
	}

	/**
	 * Retrieves the origin opposite of this box in unscaled ("pixel" units)
	 *
	 * @return the unscaled origin opposite
	 */
	public PointInImage unscaledOriginOpposite() {
		return new PointInImage(originOpposite.x / xSpacing, originOpposite.y / ySpacing, originOpposite.z /
			zSpacing);
	}

	public double width() {
		return Math.abs(originOpposite.x - origin.x);
	}

	public double height() {
		return Math.abs(originOpposite.y - origin.y);
	}

	public double depth() {
		return Math.abs(originOpposite.z - origin.z);
	}

	/**
	 * Sets the dimensions of this bounding box using uncalibrated (pixel)
	 * lengths.
	 *
	 * @param uncalibratedWidth the uncalibrated width
	 * @param uncalibratedHeight the uncalibrated height
	 * @param uncalibratedDepth the uncalibrated depth
	 * @throws IllegalArgumentException If origin has not been set or
	 *           {@link #compute(Iterator)} has not been called
	 */
	public void setDimensions(final long uncalibratedWidth,
		final long uncalibratedHeight, final long uncalibratedDepth)
		throws IllegalArgumentException
	{
		if (!origin.isReal()) {
			throw new IllegalArgumentException("Origin has not been set");
		}
		originOpposite.x = uncalibratedWidth * xSpacing + origin.x;
		originOpposite.y = uncalibratedHeight * ySpacing + origin.y;
		originOpposite.z = uncalibratedDepth * zSpacing + origin.z;
	}

	/**
	 * Sets the origin for this box, i.e., its (xMin, yMin, zMin) vertex.
	 *
	 * @param origin the new origin
	 */
	public void setOrigin(final PointInImage origin) {
		this.origin = origin;
	}

	/**
	 * Sets the origin opposite for this box, i.e., its (xMax, yMax, zMax) vertex.
	 *
	 * @param originOpposite the new origin opposite.
	 */
	public void setOriginOpposite(final PointInImage originOpposite) {
		this.originOpposite = originOpposite;
	}

	public boolean hasDimensions() {
		return origin.distanceSquaredTo(originOpposite) > 0;
	}

	public BoundingBox3d toBoundingBox3d() {
		final BoundingBox3d bounds = new BoundingBox3d();
		bounds.setXmin((float)origin().x);
		bounds.setXmax((float)originOpposite().x);
		bounds.setYmin((float)origin().y);
		bounds.setYmax((float)originOpposite().y);
		bounds.setZmin((float)origin().z);
		bounds.setZmax((float)originOpposite().z);
		return bounds;
	}

	@Override
	public int hashCode() {
		return Objects.hash(xSpacing, ySpacing, zSpacing, spacingUnit, origin, originOpposite);
	}

	@Override
	public boolean equals(final Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof BoundingBox)) {
			return false;
		}
		final BoundingBox other = (BoundingBox) obj;
		return Double.doubleToLongBits(xSpacing) == Double.doubleToLongBits(other.xSpacing)
				&& Double.doubleToLongBits(ySpacing) == Double.doubleToLongBits(other.ySpacing)
				&& Double.doubleToLongBits(zSpacing) == Double.doubleToLongBits(other.zSpacing)
				&& Objects.equals(spacingUnit, other.spacingUnit) && Objects.equals(origin, other.origin)
				&& Objects.equals(originOpposite, other.originOpposite);
	}

	@Override
	public BoundingBox clone() {
		final BoundingBox clone = new BoundingBox();
		clone.origin = this.origin;
		clone.originOpposite = this.originOpposite;
		clone.xSpacing = this.xSpacing;
		clone.ySpacing = this.ySpacing;
		clone.zSpacing = this.zSpacing;
		clone.info = this.info;
		clone.spacingUnit = this.spacingUnit;
		return clone;
	}

	@Override
	public String toString() {
		return "Origin: " + origin.toString() + " OriginOpposite: " + originOpposite.toString();
	}
}
