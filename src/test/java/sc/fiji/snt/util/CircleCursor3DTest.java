/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2026 Fiji developers.
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
import net.imglib2.Point;
import net.imglib2.algorithm.region.CircleCursor;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * @author Cameron Arshadi
 */
public class CircleCursor3DTest
{

    @Test
    public void test() throws InterruptedException
    {
        Img< UnsignedByteType > rai = ArrayImgs.unsignedBytes( 128, 128, 1 );
        Point center = new Point( 64, 64, 0 );
        long radius = 40;
        Cursor< UnsignedByteType > testCursor = new CircleCursor3D<>(
                rai,
                center,
                radius,
                new double[]{ 1, 0, 0 },
                new double[]{ 0, 1, 0 } );
        int testCount = 0;
        while ( testCursor.hasNext() )
        {
            testCursor.fwd();
            UnsignedByteType t = testCursor.get();
            t.set( t.getInteger() + 25 );
            testCount++;
        }

        CircleCursor< UnsignedByteType > expectedCursor = new CircleCursor<>(
                rai,
                center,
                radius );
        int expectedCount = 0;
        while ( expectedCursor.hasNext() )
        {
            expectedCursor.fwd();
            UnsignedByteType t = expectedCursor.get();
            t.set( t.getInteger() + 25 );
            expectedCount++;
        }

        assertEquals( expectedCount, testCount );

        Cursor< UnsignedByteType > resultCursor = rai.cursor();
        int count = 0;
        while ( resultCursor.hasNext() )
        {
            int t = resultCursor.next().get();
            if ( t == 50 )
            {
                ++count;
            }
            else
            {
                assert t == 0;
            }
        }
        assertEquals( expectedCount, count );
//        ImageJFunctions.show( rai );
//        Thread.sleep( 10000 );

    }

}
