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

package sc.fiji.snt.util;

import net.imglib2.*;
import net.imglib2.util.LinAlgHelpers;

/**
 * Iterate the pixels of an oriented circle in 3-space using Bresenham's algorithm, where the
 * circle is constructed from the unit normal to the circle plane, the center point, and radius. Alternatively,
 * x and y basis vectors may be used in place of the circle normal.
 *
 * @author Cameron Arshadi
 * @see net.imglib2.algorithm.region.CircleCursor
 * @see <a href="https://en.wikipedia.org/wiki/Midpoint_circle_algorithm">Midpoint circle algorithm on Wikipedia.</a>
 */
public class CircleCursor3d< T > implements Cursor< T >
{

    protected final RandomAccessible< T > rai;
    protected final RandomAccess< T > ra;
    protected final Localizable centerPoint;
    protected final double[] center;
    protected final double[] xBasis;
    protected final double[] yBasis;
    // outermost radius
    protected final long radius;
    protected double[] xVec;
    protected double[] yVec;
    protected long[] longPos;
    protected double[] doublePos;
    // Dynamic parameters
    protected long x;
    protected long y;
    protected boolean hasNext;
    private long r;
    private long err;
    private Octant octant;

    private enum Octant
    {
        O1, O2, O3, O4, O5, O6, O7, O8
    }

    /**
     * Iterates over a Bresenham circle in the target {@link RandomAccessible}.
     * Each point of the circle is iterated exactly once, and there is no "hole"
     * in the circle.
     *
     * @param rai        the random accessible. It is the caller's responsibility to
     *                   ensure it can be accessed everywhere the circle will be
     *                   iterated.
     * @param center     the circle center. Must be at least of dimension 3. Dimensions
     *                   0, 1 and 2 are used to specify the circle center.
     * @param radius     the circle radius.
     * @param circleNorm the unit normal to the circle plane, must be 3-dimensional. The "new"
     *                   x and y basis vectors will be constructed from this vector.
     */
    public CircleCursor3d( final RandomAccessible< T > rai, final Localizable center, final long radius,
                           final double[] circleNorm )
    {
        this.rai = rai;
        this.ra = rai.randomAccess();
        this.centerPoint = center;
        this.center = center.positionAsDoubleArray();
        this.radius = radius;
        this.xBasis = new double[ 3 ];
        this.yBasis = new double[ 3 ];
        final double epsilon = 1e-6;
        // Took this hint from Mark's fitting code to handle the degenerate case
        if ( Math.abs( circleNorm[ 0 ] ) < epsilon && Math.abs( circleNorm[ 1 ] ) < epsilon )
        {
            xBasis[ 0 ] = circleNorm[ 2 ];
            xBasis[ 1 ] = 0;
            xBasis[ 2 ] = -circleNorm[ 0 ];
        }
        else
        {
            xBasis[ 0 ] = -circleNorm[ 1 ];
            xBasis[ 1 ] = circleNorm[ 0 ];
            xBasis[ 2 ] = 0;
        }
        LinAlgHelpers.cross( circleNorm, xBasis, yBasis );
        init();
    }

    /**
     * Iterates over a Bresenham circle in the target {@link RandomAccessible}.
     * Each point of the circle is iterated exactly once, and there is no "hole"
     * in the circle.
     *
     * @param rai    the random accessible. It is the caller responsibility to
     *               ensure it can be accessed everywhere the circle will be
     *               iterated.
     * @param center the circle center.
     * @param radius the circle radius.
     * @param xBasis the vector representing the "new" x-axis of the circle plane. Should be orthogonal to both the
     *               circle normal and the yBasis
     * @param yBasis the vector representing the "new" y-axis of the circle plane. Should be orthogonal to both the circle
     *               normal and the xBasis
     */
    public CircleCursor3d( final RandomAccessible< T > rai, final Localizable center, final long radius,
                           double[] xBasis, double[] yBasis )
    {
        this.rai = rai;
        this.ra = rai.randomAccess();
        this.centerPoint = center;
        this.center = center.positionAsDoubleArray();
        this.radius = radius;
        this.xBasis = xBasis;
        this.yBasis = yBasis;
        init();
    }

    private void init()
    {
        this.doublePos = new double[ 3 ];
        this.longPos = new long[ 3 ];
        this.yVec = new double[ 3 ];
        this.xVec = new double[ 3 ];
        reset();
    }

    @Override
    public Cursor< T > copyCursor()
    {
        return new CircleCursor3d<>( rai, centerPoint, radius, xBasis, yBasis );
    }

    @Override
    public T next()
    {
        fwd();
        return get();
    }

    @Override
    public void jumpFwd( long steps )
    {
        for ( int i = 0; i < steps; i++ )
            fwd();
    }

    @Override
    public void fwd()
    {
        switch ( octant )
        {
            default:
            case O1:
                setPos( -x, y );
                octant = Octant.O2;
                break;

            case O2:
                setPos( -y, -x );
                octant = Octant.O3;
                break;

            case O3:
                setPos( x, -y );
                octant = Octant.O4;
                break;

            case O4:
                setPos( y, x );
                r = err;
                if ( r > x )
                {
                    x++;
                    err += x * 2 + 1;
                }
                if ( r <= y )
                {
                    y++;
                    err += y * 2 + 1;
                }
                if ( x >= 0 )
                {
                    hasNext = false;
                }
                octant = Octant.O1;
                break;
        }
    }

    protected void setPos( final long xScale, final long yScale )
    {
        LinAlgHelpers.scale( xBasis, xScale, xVec );
        LinAlgHelpers.scale( yBasis, yScale, yVec );
        LinAlgHelpers.add( center, xVec, doublePos );
        LinAlgHelpers.add( doublePos, yVec, doublePos );
        roundPos( doublePos, longPos );
        ra.setPosition( longPos );
    }

    protected void roundPos( final double[] doublePos, final long[] longPos )
    {
        for ( int i = 0; i < doublePos.length; ++i )
        {
            longPos[ i ] = (long) Math.floor( doublePos[ i ] );
        }
    }

    @Override
    public void reset()
    {
        r = radius;
        x = -radius;
        y = 0;
        err = 2 - 2 * radius;
        octant = Octant.O1;
        ra.setPosition( centerPoint );
        hasNext = true;
    }

    @Override
    public boolean hasNext()
    {
        return hasNext;
    }

    @Override
    public void localize( final float[] position )
    {
        ra.localize( position );
    }

    @Override
    public void localize( final double[] position )
    {
        ra.localize( position );
    }

    @Override
    public void localize( final int[] position )
    {
        ra.localize( position );
    }

    @Override
    public void localize( final long[] position )
    {
        ra.localize( position );
    }

    @Override
    public int getIntPosition( final int d )
    {
        return ra.getIntPosition( d );
    }

    @Override
    public long getLongPosition( int d )
    {
        return ra.getLongPosition( d );
    }

    @Override
    public float getFloatPosition( final int d )
    {
        return ra.getFloatPosition( d );
    }

    @Override
    public double getDoublePosition( final int d )
    {
        return ra.getDoublePosition( d );
    }

    @Override
    public int numDimensions()
    {
        return ra.numDimensions();
    }

    @Override
    public T get()
    {
        return ra.get();
    }

    @Override
    public Sampler< T > copy()
    {
        return ra.copy();
    }

}
