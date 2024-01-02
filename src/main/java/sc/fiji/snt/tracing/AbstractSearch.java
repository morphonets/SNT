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

package sc.fiji.snt.tracing;

import ij.ImagePlus;
import ij.measure.Calibration;
import net.imagej.Dataset;
import net.imagej.axis.Axes;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.SearchProgressCallback;

import java.util.ArrayList;

/**
 * Abstract class for path-finding over {@link RandomAccessibleInterval}s
 *
 * @author Cameron Arshadi
 */
public abstract class AbstractSearch implements SearchInterface, Runnable {

    protected final RandomAccessibleInterval<? extends RealType<?>> img;
    protected final RandomAccess<? extends RealType<?>> imgAccess;
    public final int imgWidth;
    public final int imgHeight;
    public final int imgDepth;
    protected final long xMin;
    protected final long yMin;
    protected final long zMin;
    protected final long xMax;
    protected final long yMax;
    protected final long zMax;
    protected final double xSep;
    protected final double ySep;
    protected final double zSep;
    protected final String spacing_units;
    protected int timeoutSeconds;
    protected long reportEveryMilliseconds;
    protected ArrayList<SearchProgressCallback> progressListeners;

    protected final boolean verbose = SNTUtils.isDebugMode();


    protected AbstractSearch(final ImagePlus imagePlus, final int timeoutSeconds, final long reportEveryMilliseconds)
    {
        this(ImageJFunctions.wrapReal(imagePlus), imagePlus.getCalibration(), timeoutSeconds, reportEveryMilliseconds);
    }

    protected AbstractSearch(final Dataset image, final int timeoutSeconds, final long reportEveryMilliseconds)
    {
        // If the image is 2D, add a dummy dimension so that we may access the image the same way in both the
        //  2D and 3D cases, i.e., randomAccess.setPositionAndGet(x, y, z), where z = 0 in the 2D case.
        if (image.numDimensions() == 2) {
            this.img = Views.addDimension(image, 0, 0);
        } else {
            this.img = image;
        }
        this.imgAccess = this.img.randomAccess();
        this.imgWidth = (int) this.img.dimension(0);
        this.imgHeight = (int) this.img.dimension(1);
        this.imgDepth = (int) this.img.dimension(2);
        final long[] intervalMin = Intervals.minAsLongArray(img);
        this.xMin = intervalMin[0];
        this.yMin = intervalMin[1];
        this.zMin = intervalMin[2];
        final long[] intervalMax = Intervals.maxAsLongArray(img);
        this.xMax = intervalMax[0];
        this.yMax = intervalMax[1];
        this.zMax = intervalMax[2];
        this.xSep = image.averageScale(0);
        this.ySep = image.averageScale(1);
        this.zSep = image.dimensionIndex(Axes.Z) > -1 ? image.averageScale(image.dimensionIndex(Axes.Z)) : 1.0;
        spacing_units = SNTUtils.getSanitizedUnit(image.axis(0).unit());
        if ((xSep == 0.0) || (ySep == 0.0) || (zSep == 0.0)) {
            SNTUtils.error("SearchThread: One dimension of the calibration information was zero: (" + xSep + "," + ySep + "," + zSep + ")");
            return;
        }
        this.timeoutSeconds = timeoutSeconds;
        this.reportEveryMilliseconds = reportEveryMilliseconds;
    }

    protected AbstractSearch(final RandomAccessibleInterval<? extends RealType<?>> image,
                             final Calibration calibration, final int timeoutSeconds,
                             final long reportEveryMilliseconds)
    {
        // If the image is 2D, add a dummy dimension so that we may access the image the same way in both the
        //  2D and 3D cases, i.e., randomAccess.setPositionAndGet(x, y, z), where z = 0 in the 2D case.
        if (image.numDimensions() == 2) {
            this.img = Views.addDimension(image, 0, 0);
        } else {
            this.img = image;
        }
        this.imgAccess  = this.img.randomAccess();
        this.imgWidth = (int) this.img.dimension(0);
        this.imgHeight = (int) this.img.dimension(1);
        this.imgDepth = (int) this.img.dimension(2);
        final long[] intervalMin = Intervals.minAsLongArray(img);
        this.xMin = intervalMin[0];
        this.yMin = intervalMin[1];
        this.zMin = intervalMin[2];
        final long[] intervalMax = Intervals.maxAsLongArray(img);
        this.xMax = intervalMax[0];
        this.yMax = intervalMax[1];
        this.zMax = intervalMax[2];
        this.xSep = calibration.pixelWidth;
        this.ySep = calibration.pixelHeight;
        this.zSep = calibration.pixelDepth;
        spacing_units = SNTUtils.getSanitizedUnit(calibration.getUnit());
        if ((xSep == 0.0) || (ySep == 0.0) || (zSep == 0.0)) {
            SNTUtils.error(
                    "SearchThread: One dimension of the calibration information was zero: (" +
                            xSep + "," + ySep + "," + zSep + ")");
            return;
        }
        this.timeoutSeconds = timeoutSeconds;
        this.reportEveryMilliseconds = reportEveryMilliseconds;
    }

    protected AbstractSearch(final SNT snt, final ImagePlus imagePlus) {
        this(snt, ImageJFunctions.wrapReal(imagePlus));
    }

    protected AbstractSearch(final SNT snt, final RandomAccessibleInterval<? extends RealType<?>> image) {
        if (image.numDimensions() == 2) {
            this.img = Views.addDimension(image, 0, 0);
        } else {
            this.img = image;
        }
        this.imgAccess  = this.img.randomAccess();
        this.imgWidth = (int) img.dimension(0);
        this.imgHeight = (int) img.dimension(1);
        this.imgDepth = (int) img.dimension(2);
        final long[] intervalMin = Intervals.minAsLongArray(img);
        this.xMin = intervalMin[0];
        this.yMin = intervalMin[1];
        this.zMin = intervalMin[2];
        final long[] intervalMax = Intervals.maxAsLongArray(img);
        this.xMax = intervalMax[0];
        this.yMax = intervalMax[1];
        this.zMax = intervalMax[2];
        this.xSep = snt.getPixelWidth();
        this.ySep = snt.getPixelHeight();
        this.zSep = snt.getPixelDepth();
        this.spacing_units = snt.getSpacingUnits();
        this.timeoutSeconds = 0;
        this.reportEveryMilliseconds = 1000;
    }

    public abstract void addProgressListener(SearchProgressCallback callback);

    public abstract void printStatus();

    /*
     * Use this for doing special progress updates, beyond what
     * SearchProgressCallback provides.
     */
    protected abstract void reportPointsInSearch();

    public abstract long pointsConsideredInSearch();

}
