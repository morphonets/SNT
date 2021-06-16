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

package sc.fiji.snt.filter;

import ij.ImagePlus;
import ij.measure.Calibration;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import sc.fiji.snt.SNTPrefs;

/**
 * TODO
 *
 * @author Cameron Arshadi
 */
public abstract class AbstractFilter {

    protected final RandomAccessibleInterval<? extends RealType<?>> img;
    protected final long[] imgDim;
    protected final double pixelWidth;
    protected final double pixelHeight;
    protected final double pixelDepth;

    protected RandomAccessibleInterval<FloatType> result;

    protected int nThreads;
    protected long[] blockDimensions;
    protected boolean isAutoBlockDimensions = true;

    protected AbstractFilter(final RandomAccessibleInterval<? extends RealType<?>> img, final Calibration calibration)
    {
        if (img == null) {
            throw new IllegalArgumentException("BUG: img cannot be null");
        }
        this.img = img;
        this.imgDim = Intervals.dimensionsAsLongArray(img);
        if (calibration != null) {
            this.pixelWidth = calibration.pixelWidth;
            this.pixelHeight = calibration.pixelHeight;
            this.pixelDepth = calibration.pixelDepth;
        } else {
            this.pixelWidth = 1.0;
            this.pixelHeight = 1.0;
            this.pixelDepth = 1.0;
        }
        this.nThreads = SNTPrefs.getThreads();
    }

    protected AbstractFilter(final ImagePlus imp) {
        this(ImageJFunctions.wrapReal(imp), imp.getCalibration());
    }

    public abstract void process();

    public RandomAccessibleInterval<FloatType> getResult() {
        return this.result;
    }

    public void setNumThreads(final int nThreads) {
        this.nThreads = nThreads;
    }

    public boolean isAutoBlockDimensions() {
        return this.isAutoBlockDimensions;
    }

    public void setAutoBlockDimensions(final boolean useAutoBlockSize) {
        this.isAutoBlockDimensions = useAutoBlockSize;
    }

    public long[] getBlockDimensions() {
        return this.blockDimensions;
    }

    public void setBlockDimensions(final long[] dimensions) {
        this.blockDimensions = dimensions;
    }
}
