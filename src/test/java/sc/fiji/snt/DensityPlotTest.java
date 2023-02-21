/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import ij.IJ;
import ij.ImagePlus;
import net.imglib2.Cursor;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import sc.fiji.snt.util.PointInImage;

public class DensityPlotTest {
    static public void densityPlot(String swcDirectory, String outDirectory, String resultPrefix) throws IOException {
        List<Tree> trees = Tree.listFromDir(swcDirectory);

        System.out.println("Processing: " + swcDirectory);

        int[] maxDims = new int[]{0, 0, 0};

        new ij.ImageJ();


        int numTreesToEstimateOutDimensions = trees.size();

        // ----- Guess the output dims

        long[] outputDims = new long[]{0, 0, 0};

        for( Tree tree : trees.subList(0, numTreesToEstimateOutDimensions )) {
            System.out.println("Processing tree: " + tree);
//            # retrieve an empty image capable of hosting the rasterized tree

            // Use soma to align
            //tree.getRoot();

            List<Object> skelResult = tree.getSkeleton2();
            ImagePlus imp = (ImagePlus) skelResult.get(0);

            PointInImage r = tree.getRoot();
            PointInImage mn = (PointInImage) skelResult.get(1);

            PointInImage o = new PointInImage(r.x - mn.x, r.y - mn.y, r.z - mn.z);

            // max should be 2x the radius as determined by distance from origin to border
            outputDims[0] = (int) Math.max(outputDims[0], (imp.getWidth() - Math.abs(o.x)) * 2);
            outputDims[1] = (int) Math.max(outputDims[1], (imp.getHeight() - Math.abs(o.y)) * 2);
            outputDims[2] = (int) Math.max(outputDims[2], (imp.getImageStackSize() - Math.abs(o.z)) * 2);

        }

        // Pad the answer
        for( int d = 0; d < 3; d++ ) {
            outputDims[d] *= 1.5;
        }

        // ----- Done guessing output dims

        System.out.println("maxDims: " + outputDims[0] + " " + outputDims[1] + " " + outputDims[2]);

        // make an image of the largest size
        ImagePlus outImp = IJ.createImage("outImg", "16bit", (int) outputDims[0], (int) outputDims[1], (int) outputDims[2]);

        Img<UnsignedShortType> outImg = ImageJFunctions.wrap(outImp);

        List<PointInImage> origins = new ArrayList<>();

        for( int k = 0; k < trees.size(); k++ ) {
            Tree tree = trees.get(k);

            System.out.println("Processing tree: " + tree);

            List<Object> skelResult = tree.getSkeleton2();
            ImagePlus imp = (ImagePlus) skelResult.get(0);

            PointInImage r = tree.getRoot();
            PointInImage mn = (PointInImage) skelResult.get(1);

            PointInImage o = new PointInImage(r.x - mn.x, r.y - mn.y, r.z - mn.z);

            origins.add(o);
            //origins.add((PointInImage) skelResult.get(1));

            // max should be 2x the radius as determined by distance from origin to border
            maxDims[0] = (int) Math.max(maxDims[0], ( imp.getWidth() - Math.abs(o.x) ) * 2);
            maxDims[1] = (int) Math.max(maxDims[1], ( imp.getHeight() - Math.abs(o.y) ) * 2 );
            maxDims[2] = (int) Math.max(maxDims[2], ( imp.getImageStackSize() - Math.abs(o.z) ) * 2);

            Img<UnsignedByteType> im = ImageJFunctions.wrap(imp);

            long[] offset = new long[3];
            for( int d = 0; d < im.numDimensions(); d++ ){
                //offset[d] = ( outImg.dimension(d) - im.dimension(d) ) / 2;
                if ( d == 0 ) offset[d] = (long) origins.get(k).x;
                if ( d == 1 ) offset[d] = (long) origins.get(k).y;
                if ( d == 2 ) offset[d] = (long) origins.get(k).z;
            }

            System.out.println("Plotting " + tree );
            System.out.println("Offset: " + offset[0] + " " + offset[1] + " " + offset[2]);
            System.out.println("Img size: " + im.dimension(0) + " " + im.dimension(1) + " " + im.dimension(2));

            IntervalView<UnsignedShortType> outView = Views.interval(outImg, Views.translate(im, offset));

            Cursor<UnsignedByteType> inCur = Views.flatIterable(im).cursor();
            Cursor<UnsignedShortType> outCur = Views.flatIterable(outView).cursor();
            while( inCur.hasNext() ) {
                inCur.fwd();
                outCur.fwd();
                outCur.get().set( outCur.get().get() + inCur.get().get() );
            }

        }

//        ImagePlus outImp = ImageJFunctions.wrap(outImg, "densityPlot");
        ij.IJ.saveAsTiff(outImp, resultPrefix + "density_plot.tif");

    }

    static public void main(String[] args) throws IOException {
        String parentDirectory = System.getProperty("user.home") + "/Dropbox/SNTmanuscript/Simulations/GRNFinalAnalysis";
        String resultDirectory = parentDirectory + "/output";

        densityPlot(parentDirectory + "/grn0/", parentDirectory + "/grn0img/", resultDirectory + "/grn0_");
        densityPlot(parentDirectory + "/grn1/", parentDirectory + "/grn1img/", resultDirectory + "/grn1_");
        densityPlot(parentDirectory + "/grn2/", parentDirectory + "/grn2img/", resultDirectory + "/grn2_");
        densityPlot(parentDirectory + "/grn3/", parentDirectory + "/grn3img/", resultDirectory + "/grn3_");
        densityPlot(parentDirectory + "/grn4/", parentDirectory + "/grn4img/", resultDirectory + "/grn4_");
    }

}
