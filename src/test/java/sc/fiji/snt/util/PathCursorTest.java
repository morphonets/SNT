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

import ij.ImagePlus;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import org.junit.Before;
import org.junit.Test;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;

import static org.junit.Assert.assertEquals;

/**
 * @author Cameron Arshadi
 */
public class PathCursorTest
{

    private Path path;
    private RandomAccessibleInterval< UnsignedByteType > img;

    @Before
    public void setup()
    {
        SNTService snt = new SNTService();
        Tree tree = snt.demoTree( "OP" );
        path = tree.get( 0 );
        ImagePlus imp = snt.demoImage( "OP" );
        img = ImageJFunctions.wrap( imp );
    }

    @Test
    public void test()
    {
        Cursor< UnsignedByteType > cursor = new PathCursor<>( img, path );
        int i = 0;
        final int[] pos = new int[ 3 ];
        while ( cursor.hasNext() )
        {
            cursor.next();
            cursor.localize( pos );
            assertEquals( path.getXUnscaled( i ), pos[ 0 ] );
            assertEquals( path.getYUnscaled( i ), pos[ 1 ] );
            assertEquals( path.getZUnscaled( i ), pos[ 2 ] );
            i++;
        }
        assertEquals( path.size(), i );
    }

}
