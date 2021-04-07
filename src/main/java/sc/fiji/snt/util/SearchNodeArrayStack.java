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

public class SearchNodeArrayStack implements SearchImageStack<SearchNode> {

    final int width;
    final int height;
    final int depth;
    SearchNodeArray[] stack;

    public SearchNodeArrayStack(int width, int height, int depth) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        stack = new SearchNodeArray[depth];
    }

    @Override
    public SearchImage<SearchNode> getSlice(int z) {
        return stack[z];
    }

    @Override
    public SearchImage<SearchNode> newSlice(int z) {
        SearchNodeArray arr = new SearchNodeArray(width, height);
        stack[z] = arr;
        return arr;
    }
}
