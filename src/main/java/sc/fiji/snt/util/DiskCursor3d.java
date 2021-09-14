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

import net.imglib2.Cursor;
import net.imglib2.Localizable;
import net.imglib2.Point;
import net.imglib2.RandomAccessible;
import net.imglib2.util.LinAlgHelpers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Iterate the pixels of an oriented solid disk in 3-space, where the disk is constructed from the unit normal
 * to the circle plane, the center point, and radius.
 *
 * @author Cameron Arshadi
 */
public class DiskCursor3d< T > extends CircleCursor3d< T >
{

    private List< Localizable > values;
    private Iterator< Localizable > iterator;

    /**
     * Iterates over a parameterized disk in the target {@link RandomAccessible}.
     * Each point of the disk is iterated exactly once.
     *
     * @param rai        the random accessible. It is the caller's responsibility to
     *                   ensure it can be accessed everywhere the disk will be
     *                   iterated.
     * @param center     the disk center. Must be at least of dimension 3. Dimensions
     *                   0, 1 and 2 are used to specify the disk center.
     * @param radius     the circle radius.
     * @param circleNorm the unit normal to the disk plane, must be 3-dimensional. The "new"
     *                   x and y basis vectors will be constructed from this vector.
     */
    public DiskCursor3d( final RandomAccessible< T > rai, final Localizable center, final long radius,
                         final double[] circleNorm )
    {
        super( rai, center, radius, circleNorm );
        init();
    }

    /**
     * Iterates over a parameterized disk in the target {@link RandomAccessible}.
     * Each point of the disk is iterated exactly once.
     *
     * @param rai    the random accessible. It is the caller responsibility to
     *               ensure it can be accessed everywhere the disk will be
     *               iterated.
     * @param center the disk center.
     * @param radius the disk radius.
     * @param xBasis the vector representing the "new" x-axis of the disk plane. Should be orthogonal to both the
     *               circle normal and the yBasis
     * @param yBasis the vector representing the "new" y-axis of the disk plane. Should be orthogonal to both the circle
     *               normal and the xBasis
     */
    public DiskCursor3d( final RandomAccessible< T > rai, final Localizable center, final long radius,
                         double[] xBasis, double[] yBasis )
    {
        super( rai, center, radius, xBasis, yBasis );
        init();
    }

    private void init()
    {
        if ( values == null )
        {
            this.values = new ArrayList<>();
            final double rr = radius * radius;
            final double[] tmpY = new double[ 3 ];
            final double[] tmpDiff = new double[ 3 ];
            for ( long y = -radius; y <= radius; ++y )
            {
                LinAlgHelpers.scale( yBasis, y, yVec );
                LinAlgHelpers.add( center, yVec, tmpY );
                for ( long x = -radius; x <= radius; ++x )
                {
                    LinAlgHelpers.scale( xBasis, x, xVec );
                    LinAlgHelpers.add( tmpY, xVec, doublePos );
                    LinAlgHelpers.subtract( doublePos, center, tmpDiff );
                    if ( LinAlgHelpers.squareLength( tmpDiff ) > rr )
                        continue;
                    roundPos( doublePos, longPos );
                    values.add( new Point( longPos ) );
                }
            }
        }
        this.iterator = values.iterator();
    }

    @Override
    public Cursor< T > copyCursor()
    {
        return new DiskCursor3d<>( rai, centerPoint, radius, xBasis, yBasis );
    }

    @Override
    public void fwd()
    {
        ra.setPosition( iterator.next() );
    }

    @Override
    public boolean hasNext()
    {
        return iterator.hasNext();
    }

    @Override
    public void reset()
    {
        if ( values != null )
            iterator = values.iterator();

    }

}
