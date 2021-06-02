/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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

package sc.fiji.snt;

import ij.ImagePlus;
import ij.measure.Calibration;

import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;

import java.awt.*;
import java.util.ArrayList;

public abstract class AbstractSearch implements SearchInterface, Runnable {

    protected RandomAccessibleInterval<? extends RealType<?>> img;
    protected RandomAccess<? extends RealType<?>> imgAccess;
    protected double x_spacing;
    protected double y_spacing;
    protected double z_spacing;
    protected String spacing_units;
    protected int width;
    protected int height;
    protected int depth;

    protected Color openColor;
    protected Color closedColor;
    protected double drawingThreshold;

    protected int timeoutSeconds;
    protected long reportEveryMilliseconds;
    protected ArrayList<SearchProgressCallback> progressListeners;

    protected int exitReason;
    protected final boolean verbose = SNTUtils.isDebugMode();
    protected int minExpectedSize;

    protected AbstractSearch(final ImagePlus imagePlus, final int timeoutSeconds, final long reportEveryMilliseconds)
    {
        this(ImageJFunctions.wrapReal(imagePlus), imagePlus.getCalibration(), timeoutSeconds, reportEveryMilliseconds);
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
        this.width = (int) this.img.dimension(0);
        this.height = (int) this.img.dimension(1);
        this.depth = (int) this.img.dimension(2);
        this.x_spacing = calibration.pixelWidth;
        this.y_spacing = calibration.pixelHeight;
        this.z_spacing = calibration.pixelDepth;
        spacing_units = SNTUtils.getSanitizedUnit(calibration.getUnit());
        if ((x_spacing == 0.0) || (y_spacing == 0.0) || (z_spacing == 0.0)) {
            SNTUtils.error(
                    "SearchThread: One dimension of the calibration information was zero: (" +
                            x_spacing + "," + y_spacing + "," + z_spacing + ")");
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
        this.width = snt.width;
        this.height = snt.height;
        this.depth = snt.depth;
        this.x_spacing = snt.x_spacing;
        this.y_spacing = snt.y_spacing;
        this.z_spacing = snt.z_spacing;
        this.spacing_units = snt.spacing_units;
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

    protected abstract SearchNode anyNodeUnderThreshold(final int x, final int y, final int z, final double threshold);

    public void setDrawingColors(final Color openColor, final Color closedColor) {
        this.openColor = openColor;
        this.closedColor = closedColor;
    }

    public void setDrawingThreshold(final double threshold) {
        this.drawingThreshold = threshold;
    }

}
