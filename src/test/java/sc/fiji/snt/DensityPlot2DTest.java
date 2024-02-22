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
package sc.fiji.snt;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import sc.fiji.snt.util.PointInImage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("unused")
public class DensityPlot2DTest {
    static public void densityPlot(String swcDirectory, String outDirectory, String resultPrefix) throws IOException {
        List<Tree> trees = Tree.listFromDir(swcDirectory);

        System.out.println("Processing: " + swcDirectory);

		int[] maxDims = new int[]{0, 0};

        new ij.ImageJ();

        int numTreesToEstimateOutDimensions = trees.size();
        //int numTreesToEstimateOutDimensions = 5;

        // ----- Guess the output dims

        long[] outputDims = new long[]{0, 0};

        double[] outputPadding = new double[]{3, 3};
        double[] outputMin = new double[]{Double.MAX_VALUE, Double.MAX_VALUE};
        double[] outputMax = new double[]{Double.MIN_VALUE, Double.MIN_VALUE};

        for( Tree tree : trees.subList(0, numTreesToEstimateOutDimensions )) {
            System.out.println("Processing tree: " + tree);
//            # retrieve an empty image capable of hosting the rasterized tree

            // Use soma to align
            //tree.getRoot();

            ImagePlus imp = tree.getSkeleton2D();

            PointInImage r = tree.getRoot();

            final SummaryStatistics xStats = new SummaryStatistics();
            final SummaryStatistics yStats = new SummaryStatistics();
            for (final PointInImage p : tree.getNodes()) {
                xStats.addValue(p.x);
                yStats.addValue(p.y);
            }
            final PointInImage mn = new PointInImage(xStats.getMin(), yStats.getMin(), 0);
            final PointInImage mx = new PointInImage(xStats.getMax(), yStats.getMax(), 0);

            outputMin[0] = Math.min(outputMin[0], (mn.x - r.x) - outputPadding[0]);
            outputMin[1] = Math.min(outputMin[1], (mn.y - r.y) - outputPadding[1]);

            outputMax[0] = Math.max(outputMax[0], (mx.x - r.x) + outputPadding[0] + 1);
            outputMax[1] = Math.max(outputMax[1], (mx.y - r.y) + outputPadding[1] + 1);

        }

        // Pad the answer
        for( int d = 0; d < 2; d++ ) {
            outputDims[d] = (long) (outputMax[d] - outputMin[d]);
        }

        // ----- Done guessing output dims

        System.out.println("maxDims: " + outputDims[0] + " " + outputDims[1]);

        // make an image of the largest size
        //ImagePlus outImp = IJ.createImage("outImg", "float32", (int) outputDims[0], (int) outputDims[1], 1);

        RandomAccessibleInterval<FloatType> sourceImg = ArrayImgs.floats(outputDims[0], outputDims[1]);

        RandomAccessibleInterval<FloatType> outImg =
                Views.translate(sourceImg, (long) outputMin[0], (long) outputMin[1]);

        System.out.println("outImg: " + outImg);

        List<PointInImage> origins = new ArrayList<>();

        for( int k = 0; k < trees.size(); k++ ) {
            Tree tree = trees.get(k);

            System.out.println("Processing tree: " + k + " " + tree);

            ImagePlus imp = tree.getSkeleton2D();

            PointInImage r = tree.getRoot();

            final SummaryStatistics xStats = new SummaryStatistics();
            final SummaryStatistics yStats = new SummaryStatistics();
            for (final PointInImage p : tree.getNodes()) {
                xStats.addValue(p.x);
                yStats.addValue(p.y);
            }
            final PointInImage mn = new PointInImage(xStats.getMin(), yStats.getMin(), 0);
            final PointInImage mx = new PointInImage(xStats.getMax(), yStats.getMax(), 0);

            double[] imMin = new double[2];
            double[] imMax = new double[2];

            imMin[0] = mn.x - r.x;
            imMin[1] = mn.y - r.y;

            imMax[0] = mx.x - r.x;
            imMax[1] = mx.y - r.y;

            Img<UnsignedByteType> im = ImageJFunctions.wrap(imp);

            System.out.println("Plotting " + tree );
            System.out.println("Root: " + r.x + " " + r.y);
            System.out.println("Img size: " + im.dimension(0) + " " + im.dimension(1));

            FinalInterval outInterval = new FinalInterval(
                    new long[]{(long) ((long) imMin[0] - outputPadding[0]), (long) ((long) imMin[1] - outputPadding[1])},
                    new long[]{(long) ((long) imMax[0] + outputPadding[0]), (long) ((long) imMax[1] + outputPadding[1])});

            System.out.println("Out interval: " + outInterval);

            IntervalView<FloatType> outView = Views.interval(outImg, outInterval);

            Cursor<UnsignedByteType> inCur = Views.flatIterable(im).cursor();
            Cursor<FloatType> outCur = Views.flatIterable(outView).cursor();
            while( inCur.hasNext() ) {
                inCur.fwd();
                outCur.fwd();
                outCur.get().set( outCur.get().get() + inCur.get().get() );
            }

            if( k < 5 ) {
                //ImagePlus frameImp = IJ.createImage("outImg", "float32", (int) outputDims[0], (int) outputDims[1], 1);
                sourceImg = ArrayImgs.floats(outputDims[0], outputDims[1]);
                RandomAccessibleInterval<FloatType> frameImg =
                        Views.translate(sourceImg, (long) (outputMin[0] + outputPadding[0]), (long) (outputMin[1] + outputPadding[1]));

                outInterval = new FinalInterval(
                    new long[]{(long) ((long) imMin[0]), (long) ((long) imMin[1])},
                    new long[]{(long) ((long) imMin[0] + im.dimension(0) - 1), (long) ((long) imMin[1] + im.dimension(1) - 1)});

                IntervalView<FloatType> frameView = Views.interval(frameImg, outInterval);
                //IntervalView<FloatType> frameView = Views.interval(frameImg, Views.translate(im, offset));

                System.out.println("outInterval: " + outInterval);
                System.out.println("frameView: " + frameView);
                System.out.println((Interval) im);

                inCur = Views.iterable(im).cursor();
                Cursor<FloatType> frameCur = Views.iterable(frameView).cursor();

                long[] pos = new long[2];

                while( inCur.hasNext() ) {
                    inCur.fwd();
                    frameCur.fwd();

//                    inCur.localize(pos);
//                    System.out.print("incur: " + Arrays.toString(pos) + " ");
//                    frameCur.localize(pos);
//                    System.out.println("framecur: " + Arrays.toString(pos));

                    frameCur.get().set( inCur.get().get() );
                }

                ImagePlus frameImp = ImageJFunctions.wrap(frameImg, "frame");
                frameImp.show();
                imp.show();

                ImageJFunctions.wrap(im, "wrappedim").show();
                ImageJFunctions.wrap(frameView, "wrappedframe").show();

                IJ.saveAsTiff(frameImp, resultPrefix + "density_plot2d_example_" + k + "_" + tree.getLabel() + ".tif");
            }

        }

//        ImagePlus outImp = ImageJFunctions.wrap(outImg, "densityPlot");
        IJ.saveAsTiff(ImageJFunctions.wrap(outImg, "densityPlot"), resultPrefix + "density_plot2d.tif");

    }

    static public void main(String[] args) throws IOException {
        //String parentDirectory = System.getProperty("user.home") + "/Dropbox/SNTmanuscript/Simulations/GRNFinalAnalysis";
        String parentDirectory = System.getProperty("user.home") + "/Data/SNT/GRN_RandomNeuriteDir";
        String resultDirectory = parentDirectory + "/output";

		ImageJ ij = new ImageJ();

//        Tree t = new Tree("/home/kharrington/Dropbox/SNTmanuscript/Simulations/GRN_RandomNeuriteDirection/snt_maxTime_6_filenameGRN_grn_1.grn_randomSeed_20326147.swc");
//        t.getSkeleton2D().show();

        //densityPlot(parentDirectory + "/grn0/", parentDirectory + "/grn0img/", resultDirectory + "/grn0_");
        densityPlot(parentDirectory + "/grn1/", parentDirectory + "/grn1img/", resultDirectory + "/grn1_");
//        densityPlot(parentDirectory + "/grn2/", parentDirectory + "/grn2img/", resultDirectory + "/grn2_");
//        densityPlot(parentDirectory + "/grn3/", parentDirectory + "/grn3img/", resultDirectory + "/grn3_");
//        densityPlot(parentDirectory + "/grn4/", parentDirectory + "/grn4img/", resultDirectory + "/grn4_");


    }

}
