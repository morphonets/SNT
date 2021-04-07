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

import sc.fiji.snt.SearchNode;

public class SearchNodeArray implements SearchImage<SearchNode> {

    final int width;
    final int height;
    final SearchNode[] arr;

    public SearchNodeArray(final int width, final int height) {
        this.width = width;
        this.height = height;
        this.arr = new SearchNode[width * height];
    }

    @Override
    public SearchNode getValue(final int x, final int y) {
        return arr[y * width + x];
    }

    @Override
    public void setValue(final int x, final int y, SearchNode node) {
        arr[y * width + x] = node;
    }


}
