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
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.Sampler;
import sc.fiji.snt.Path;

/**
 * Iterate over the pixels along the defined nodes of a {@link Path}
 *
 * @author Cameron Arshadi
 */
public class PathCursor< T > implements Cursor< T >
{

    private final RandomAccessible< T > rai;
    private final Path path;
    private final RandomAccess< T > ra;
    private int i;
    private boolean hasNext;

    public PathCursor( final RandomAccessible< T > accessible, final Path path )
    {
        this.rai = accessible;
        this.ra = accessible.randomAccess();
        if ( path.size() == 0 )
            throw new IllegalArgumentException( "Path cannot be empty" );
        this.path = path;
        reset();
    }

    @Override
    public Cursor< T > copyCursor()
    {
        return new PathCursor<>( rai, path );
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
        for ( int s = 0; s < steps; s++ )
            fwd();
    }

    @Override
    public void fwd()
    {
        final int x = path.getXUnscaled( i );
        final int y = path.getYUnscaled( i );
        ra.setPosition( x, 0 );
        ra.setPosition( y, 1 );
        if ( ra.numDimensions() == 3 )
        {
            int z = path.getZUnscaled( i );
            ra.setPosition( z, 2 );
        }
        if ( ++i >= path.size() )
            hasNext = false;
    }

    @Override
    public void reset()
    {
        i = 0;
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
