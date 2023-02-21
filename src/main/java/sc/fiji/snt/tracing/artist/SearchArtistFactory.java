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

package sc.fiji.snt.tracing.artist;

import sc.fiji.snt.tracing.BiSearch;
import sc.fiji.snt.tracing.FillerThread;
import sc.fiji.snt.tracing.SearchInterface;
import sc.fiji.snt.tracing.TracerThread;

import java.awt.*;

/**
 * Factory for creation of {@link SearchArtist}s
 *
 * @author Cameron Arshadi
 *
 */
public class SearchArtistFactory {

    public SearchArtistFactory() {}

    public SearchArtist create(SearchInterface search) {
        if (search.getClass().equals(FillerThread.class)) {
            return create((FillerThread) search);
        } else if (search.getClass().equals(TracerThread.class)) {
            return create((TracerThread) search);
        } else if (search.getClass().equals(BiSearch.class)) {
            return create((BiSearch) search);
        } else {
            throw new UnsupportedOperationException("Cannot create SearchArtist with type: " + search.getClass());
        }
    }

    public FillerThreadArtist create(FillerThread search) {
        Color fillColor = new Color(0, 128, 0);
        return new FillerThreadArtist(search, Color.CYAN, fillColor);
    }

    public SearchThreadArtist create(TracerThread search) {
        return new SearchThreadArtist(search, Color.CYAN, null);
    }

    public BiSearchArtist create(BiSearch search) {
        return new BiSearchArtist(search, Color.CYAN, null);
    }


}
