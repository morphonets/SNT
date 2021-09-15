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

package sc.fiji.snt.analysis;

import ij.ImagePlus;
import ij.measure.Calibration;
import net.imagej.ImageJ;
import net.imglib2.*;
import net.imglib2.algorithm.region.CircleCursor;
import net.imglib2.algorithm.region.hypersphere.HyperSphere;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.util.LinAlgHelpers;
import net.imglib2.util.Util;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.util.*;
import smile.math.MathEx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Profile intensities within local neighborhoods around {@link Path} {@link sc.fiji.snt.util.PointInImage}s
 *
 * @author Cameron Arshadi
 */
public class ProfileProcessor< T extends RealType< T > > implements Callable< double[] >
{

    private final RandomAccessibleInterval< T > rai;
    private final Path path;
    private final double avgSep;
    private final long[] intervalMin;
    private final long[] intervalMax;
    private Metric metric = Metric.SUM;
    private Shape shape = Shape.HYPERSPHERE;
    private int radius = 0;
    private double[] values;

    public enum Metric {SUM, MIN, MAX, MEAN, MEDIAN, VARIANCE}

    public enum Shape {CENTERLINE, CIRCLE, DISK, HYPERSPHERE}

    public ProfileProcessor( final RandomAccessibleInterval< T > rai, final Path path )
    {
        this.path = path;
        this.rai = rai;
        this.intervalMin = Intervals.minAsLongArray( rai );
        this.intervalMax = Intervals.maxAsLongArray( rai );
        final Calibration cal = path.getCalibration();
        if ( rai.numDimensions() == 2 )
        {
            avgSep = ( cal.pixelWidth + cal.pixelHeight ) / 2;
        }
        else
        {
            avgSep = ( cal.pixelWidth + cal.pixelHeight + cal.pixelDepth ) / 3;
        }
    }

    /**
     * Sets the metric to be computed for each local neighborhood. This setting is ignored if using
     * {@link Shape#CENTERLINE}.
     *
     * @param metric
     */
    public void setMetric( final Metric metric )
    {
        this.metric = metric;
    }

    /**
     * Sets the shape to be iterated.
     *
     * @param shape
     */
    public void setShape( final Shape shape )
    {
        this.shape = shape;
    }


    /**
     * Specify a fixed radius for each {@link Shape} region around each {@link PointInImage}. Set to <= 0 to use the
     * actual {@link PointInImage} radii.
     *
     * @param radius
     */
    public void setRadius( final int radius )
    {
        this.radius = radius;
    }

    /**
     * The profile values, or null if they have not been processed yet.
     *
     * @return the values
     */
    public double[] getValues()
    {
        return values;
    }

    public void process()
    {
        call();
    }

    /**
     * Process and return the profile values.
     *
     * @return the values.
     */
    @Override
    public double[] call()
    {
        values = new double[ path.size() ];
        if ( path.size() == 1 )
        {
            return values;
        }
        if ( shape == Shape.CENTERLINE)
        {
            return profilePathNodes( rai, path, values );
        }
        for ( int i = 0; i < path.size(); ++i )
        {
            long r = (radius <= 0) ? Math.round( path.getNodeRadius( i ) / avgSep ) : radius;
            if ( r < 1 )
                r = 1;

            final Cursor< T > cursor = getSuitableCursor( rai, shape, r, path, i );
            if ( cursor == null )
                continue;

            final double value;
            switch ( metric )
            {
                case SUM:
                    value = sum( cursor );
                    break;
                case MIN:
                    value = min( cursor );
                    break;
                case MAX:
                    value = max( cursor );
                    break;
                case MEAN:
                    value = mean( cursor );
                    break;
                case MEDIAN:
                    value = median( cursor );
                    break;
                case VARIANCE:
                    value = variance( cursor );
                    break;
                default:
                    throw new IllegalArgumentException( "Unknown profiler method: " + metric );
            }
            values[ i ] = value;
        }
        return values;
    }

    private static < T extends RealType< T > > double[] profilePathNodes( final RandomAccessible< T > rai,
                                                                          final Path path, final double[] values )
    {
        // just return the node values
        final PathCursor< T > cursor = new PathCursor<>( rai, path );
        int i = 0;
        while ( cursor.hasNext() )
        {
            values[ i++ ] = cursor.next().getRealDouble();
        }
        return values;
    }

    private static double[] getPlaneNormal( final Path path, final int i )
    {
        double[] tangent = new double[ 3 ];
        path.getTangent( i, 1, tangent );
        if ( Arrays.stream( tangent ).allMatch( e -> e == 0 ) )
        {
            return null;
        }
        LinAlgHelpers.normalize( tangent );
        return tangent;
    }

    private static < T > Cursor< T > getSuitableCursor( final RandomAccessible< T > rai, final Shape shape,
                                                        final long radius, final Path path, final int i )
    {
        if ( rai.numDimensions() == 2 )
        {
            return getSuitableCursor2d( rai, shape, radius, path, i );
        }
        return getSuitableCursor3d( rai, shape, radius, path, i );
    }

    private static < T > Cursor< T > getSuitableCursor2d( final RandomAccessible< T > rai, final Shape shape,
                                                          final long radius, final Path path, final int i )
    {
        final Localizable centerPoint = new Point( path.getXUnscaled( i ), path.getYUnscaled( i ) );
        switch ( shape )
        {
            case CIRCLE:
                return new CircleCursor<>( rai, centerPoint, radius );
            case HYPERSPHERE:
            case DISK:
                return new HyperSphere<>( rai, centerPoint, radius ).cursor();
            case CENTERLINE:
            default:
                throw new IllegalArgumentException( "Unsupported shape: " + shape );

        }
    }

    private static < T > Cursor< T > getSuitableCursor3d( final RandomAccessible< T > rai, final Shape shape,
                                                          final long radius, final Path path, final int i )
    {
        final Localizable centerPoint = new Point( path.getXUnscaled( i ), path.getYUnscaled( i ),
                path.getZUnscaled( i ) );
        switch ( shape )
        {
            case CIRCLE:
            {
                double[] circleNormal = getPlaneNormal( path, i );
                if ( circleNormal == null )
                    return null;
                return new CircleCursor3d<>( rai, centerPoint, radius, circleNormal );
            }

            case HYPERSPHERE:
            {
                return new HyperSphere<>( rai, centerPoint, radius ).cursor();
            }

            case DISK:
            {
                double[] circleNormal = getPlaneNormal( path, i );
                if ( circleNormal == null )
                    return null;
                return new DiskCursor3d<>( rai, centerPoint, radius, circleNormal );
            }
            case CENTERLINE:
            default:
                throw new IllegalArgumentException( "Unsupported shape: " + shape );

        }
    }

    private static boolean outOfBounds( final long[] pos, final long[] min, final long[] max )
    {
        for ( int d = 0; d < pos.length; d++ )
            if ( pos[ d ] < min[ d ] || pos[ d ] > max[ d ] )
                return true;
        return false;
    }

    private double sum( final Cursor< T > cursor )
    {
        final long[] pos = new long[ cursor.numDimensions() ];
        double sum = 0;
        while ( cursor.hasNext() )
        {
            cursor.fwd();
            cursor.localize( pos );
            if ( outOfBounds( pos, intervalMin, intervalMax ) )
                continue;
            sum += cursor.get().getRealDouble();
        }
        return sum;
    }

    private double min( final Cursor< T > cursor )
    {
        final long[] pos = new long[ cursor.numDimensions() ];
        double min = Double.MAX_VALUE;
        while ( cursor.hasNext() )
        {
            cursor.fwd();
            cursor.localize( pos );
            if ( outOfBounds( pos, intervalMin, intervalMax ) )
                continue;
            min = Math.min( min, cursor.get().getRealDouble() );
        }
        return min;
    }

    private double max( final Cursor< T > cursor )
    {
        final long[] pos = new long[ cursor.numDimensions() ];
        double max = -Double.MAX_VALUE;
        while ( cursor.hasNext() )
        {
            cursor.fwd();
            cursor.localize( pos );
            if ( outOfBounds( pos, intervalMin, intervalMax ) )
                continue;
            max = Math.max( max, cursor.get().getRealDouble() );
        }
        return max;
    }

    private double mean( final Cursor< T > cursor )
    {
        final long[] pos = new long[ cursor.numDimensions() ];
        double sum = 0;
        long count = 0;
        while ( cursor.hasNext() )
        {
            cursor.fwd();
            cursor.localize( pos );
            if ( outOfBounds( pos, intervalMin, intervalMax ) )
                continue;
            sum += cursor.get().getRealDouble();
            count++;
        }
        return sum / (double) count;
    }

    private double median( final Cursor< T > cursor )
    {
        // FIXME
        final long[] pos = new long[ cursor.numDimensions() ];
        final List< Double > vals = new ArrayList<>();
        while ( cursor.hasNext() )
        {
            cursor.fwd();
            cursor.localize( pos );
            if ( outOfBounds( pos, intervalMin, intervalMax ) )
                continue;
            vals.add( cursor.get().getRealDouble() );
        }
        // This is nearly twice as fast as SMILE's median implementation
        return Util.median( vals.stream().mapToDouble( Double::doubleValue ).toArray() );
    }

    private double variance( final Cursor< T > cursor )
    {
        // FIXME
        final long[] pos = new long[ cursor.numDimensions() ];
        final List< Double > vals = new ArrayList<>();
        while ( cursor.hasNext() )
        {
            cursor.fwd();
            cursor.localize( pos );
            if ( outOfBounds( pos, intervalMin, intervalMax ) )
                continue;
            vals.add( cursor.get().getRealDouble() );
        }
        return MathEx.var( vals.stream().mapToDouble( Double::doubleValue ).toArray() );
    }

    public static void main( String[] args )
    {
        ImageJ ij = new ImageJ();
        ij.ui().showUI();
        SNTService snt = new SNTService();
        Tree t = snt.demoTree( "OP_1" );
        ImagePlus imp = snt.demoImage( "OP_1" );
        t.assignImage( imp );
        Img< UnsignedByteType > img = ImageJFunctions.wrapReal( imp );
        ProfileProcessor< UnsignedByteType > profiler = new ProfileProcessor<>( img, t.get( 0 ) );
        profiler.setShape( Shape.CIRCLE );
        profiler.setRadius( 5 );
        profiler.setMetric( Metric.VARIANCE );
        long t0 = System.currentTimeMillis();
        double[] values = profiler.call();
        System.out.println( System.currentTimeMillis() - t0 );
        ImgUtils.raiToImp( img, "Imp" ).show();
        System.out.println( Arrays.toString( values ) );
    }

}
