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
import ij.ImageStack;
import ij.measure.Calibration;

import java.awt.*;
import java.util.ArrayList;

public abstract class AbstractSearch implements SearchInterface {

    protected ImagePlus imagePlus;
    protected byte[][] slices_data_b;
    protected short[][] slices_data_s;
    protected float[][] slices_data_f;
    int imageType;
    float stackMin;
    float stackMax;
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

    protected AbstractSearch(final ImagePlus imagePlus, final float stackMin,
                             final float stackMax, final int timeoutSeconds,
                             final long reportEveryMilliseconds)
    {
        this.imagePlus = imagePlus;

        this.stackMin = stackMin;
        this.stackMax = stackMax;

        this.imageType = imagePlus.getType();

        width = imagePlus.getWidth();
        height = imagePlus.getHeight();
        depth = imagePlus.getNSlices();

        {
            final ImageStack s = imagePlus.getStack();
            switch (imageType) {
                case ImagePlus.GRAY8:
                case ImagePlus.COLOR_256:
                    slices_data_b = new byte[depth][];
                    for (int z = 0; z < depth; ++z)
                        slices_data_b[z] = (byte[]) s.getPixels(z + 1);
                    break;
                case ImagePlus.GRAY16:
                    slices_data_s = new short[depth][];
                    for (int z = 0; z < depth; ++z)
                        slices_data_s[z] = (short[]) s.getPixels(z + 1);
                    break;
                case ImagePlus.GRAY32:
                    slices_data_f = new float[depth][];
                    for (int z = 0; z < depth; ++z)
                        slices_data_f[z] = (float[]) s.getPixels(z + 1);
                    break;
            }
        }

        final Calibration calibration = imagePlus.getCalibration();

        x_spacing = calibration.pixelWidth;
        y_spacing = calibration.pixelHeight;
        z_spacing = calibration.pixelDepth;
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

    protected AbstractSearch(final SNT snt)
    {
        imagePlus = snt.getImagePlus();
        imageType = imagePlus.getType();
        width = snt.width;
        height = snt.height;
        depth = snt.depth;
        stackMin = snt.stackMin;
        stackMax = snt.stackMax;
        if (snt.doSearchOnSecondaryData && snt.isSecondaryImageLoaded()) {
            imageType = ImagePlus.GRAY32;
            slices_data_f = snt.secondaryData;
            stackMin = snt.stackMinSecondary;
            stackMax = snt.stackMaxSecondary;
        } else if (snt.slices_data_b != null) {
            imageType = ImagePlus.GRAY8;
            slices_data_b = snt.slices_data_b;
        } else if (snt.slices_data_s != null) {
            imageType = ImagePlus.GRAY16;
            slices_data_s = snt.slices_data_s;
        } else if (snt.slices_data_f != null) {
            imageType = ImagePlus.GRAY32;
            slices_data_f = snt.slices_data_f;
        }
        x_spacing = snt.x_spacing;
        y_spacing = snt.y_spacing;
        z_spacing = snt.z_spacing;
        spacing_units = snt.spacing_units;
        timeoutSeconds = 0;
        reportEveryMilliseconds = 1000;
    }

    /*
     * This calculates the cost of moving to a new point in the image. This does not
     * take into account the distance to this new point, only the value at it. This
     * will be post-multiplied by the distance from the last point. So, if you want
     * to take into account the curvature of the image at that point then you should
     * do so in this method.
     */

    // The default implementation does a simple reciprocal of the
    // image value scaled to 0 to 255 if it is not already an 8
    // bit value:

    protected double costMovingTo(final int new_x, final int new_y,
                                  final int new_z)
    {

        double value_at_new_point = -1;
        switch (imageType) {
            case ImagePlus.GRAY8:
            case ImagePlus.COLOR_256:
                value_at_new_point = slices_data_b[new_z][new_y * width + new_x] & 0xFF;
                break;
            case ImagePlus.GRAY16:
                value_at_new_point = slices_data_s[new_z][new_y * width + new_x];
                value_at_new_point = 255.0 * (value_at_new_point - stackMin) /
                        (stackMax - stackMin);
                break;
            case ImagePlus.GRAY32:
                value_at_new_point = slices_data_f[new_z][new_y * width + new_x];
                value_at_new_point = 255.0 * (value_at_new_point - stackMin) /
                        (stackMax - stackMin);
                break;
        }

        if (value_at_new_point == 0) return 2.0;
        else return 1.0 / value_at_new_point;

    }

    public abstract void printStatus();

    /*
     * Use this for doing special progress updates, beyond what
     * SearchProgressCallback provides.
     */
    protected abstract void reportPointsInSearch();

    protected abstract SearchNode anyNodeUnderThreshold(final int x, final int y, final int z,
                                                        final double threshold);


    public void setDrawingColors(final Color openColor, final Color closedColor) {
        this.openColor = openColor;
        this.closedColor = closedColor;
    }

    public void setDrawingThreshold(final double threshold) {
        this.drawingThreshold = threshold;
    }

}
