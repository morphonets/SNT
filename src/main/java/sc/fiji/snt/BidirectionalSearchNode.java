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

package sc.fiji.snt;

import org.jheaps.AddressableHeap;

import static sc.fiji.snt.AbstractBidirectionalSearch.UNEXPLORED;

/**
 * A {@link SearchNode} which can maintain both a from-start and from-goal search state.
 *
 * @author Cameron Arshadi
 */
public class BidirectionalSearchNode implements SearchNode {

    public final int x;
    public final int y;
    public final int z;

    public double gFromStart;
    public double gFromGoal;

    public double hFromStart;
    public double hFromGoal;

    public double fFromStart;
    public double fFromGoal;

    public BidirectionalSearchNode predecessorFromStart;
    public BidirectionalSearchNode predecessorFromGoal;

    AddressableHeap.Handle<BidirectionalSearchNode, Void> heapHandleFromStart;
    AddressableHeap.Handle<BidirectionalSearchNode, Void> heapHandleFromGoal;

    public byte searchStatusFromStart;
    public byte searchStatusFromGoal;

    public byte stateFromStart = UNEXPLORED;
    public byte stateFromGoal = UNEXPLORED;

    public BidirectionalSearchNode(int x, int y,  int z,
                                   double hFromStart, double hFromGoal,
                                   double gFromStart, double gFromGoal,
                                   BidirectionalSearchNode predecessorFromStart,
                                   BidirectionalSearchNode predecessorFromGoal,
                                   byte searchStatusFromStart, byte searchStatusFromGoal)
    {
        this.x = x;
        this.y = y;
        this.z = z;
        this.hFromStart = hFromStart;
        this.hFromGoal = hFromGoal;
        this.gFromStart = gFromStart;
        this.gFromGoal = gFromGoal;
        this.fFromStart = gFromStart + hFromStart;
        this.fFromGoal = gFromGoal + hFromGoal;
        this.predecessorFromStart = predecessorFromStart;
        this.predecessorFromGoal = predecessorFromGoal;
        this.searchStatusFromStart = searchStatusFromStart;
        this.searchStatusFromGoal = searchStatusFromGoal;
    }

    public BidirectionalSearchNode(int x, int y, int z) {
        this(
                x, y, z,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                null, null,
                AbstractSearch.FREE, AbstractSearch.FREE
        );
    }


    @Override
    public byte getSearchStatus() {
        return searchStatusFromStart;
    }

    @Override
    public void setSearchStatus(byte searchStatus) {
        this.searchStatusFromStart = searchStatus;
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getZ() {
        return z;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        BidirectionalSearchNode that = (BidirectionalSearchNode) o;

        if (x != that.x) return false;
        if (y != that.y) return false;
        return z == that.z;
    }

    @Override
    public int hashCode() {
        int result = x;
        result = 31 * result + y;
        result = 31 * result + z;
        return result;
    }

}
