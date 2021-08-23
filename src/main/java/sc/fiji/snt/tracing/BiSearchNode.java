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

package sc.fiji.snt.tracing;

import org.jheaps.AddressableHeap;


/**
 * A {@link SearchNode} which can maintain both a from-start and from-goal search state.
 *
 * @author Cameron Arshadi
 */
public class BiSearchNode implements SearchNode {

    public int x;
    public int y;
    public int z;

    public double gFromStart;
    public double gFromGoal;

    public double fFromStart;
    public double fFromGoal;

    public BiSearchNode predecessorFromStart;
    public BiSearchNode predecessorFromGoal;

    AddressableHeap.Handle<BiSearchNode, Void> heapHandleFromStart;
    AddressableHeap.Handle<BiSearchNode, Void> heapHandleFromGoal;

    public BiSearch.NodeState stateFromStart = BiSearch.NodeState.FREE;
    public BiSearch.NodeState stateFromGoal = BiSearch.NodeState.FREE;

    public BiSearchNode() { }

    public BiSearchNode(int x, int y, int z) {
        this(x, y, z, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY, null, null);
    }

    public BiSearchNode(int x, int y, int z,
                        double fFromStart, double fFromGoal,
                        double gFromStart, double gFromGoal,
                        BiSearchNode predecessorFromStart,
                        BiSearchNode predecessorFromGoal)
    {
        this.x = x;
        this.y = y;
        this.z = z;
        this.gFromStart = gFromStart;
        this.gFromGoal = gFromGoal;
        this.fFromStart = fFromStart;
        this.fFromGoal = fFromGoal;
        this.predecessorFromStart = predecessorFromStart;
        this.predecessorFromGoal = predecessorFromGoal;
    }

    public void setPosition(final int x, final int y, final int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void setFrom(final double g, final double f, final BiSearchNode p, final boolean fromStart) {
        if (fromStart) {
            setFromStart(g, f, p);
        } else {
            setFromGoal(g, f, p);
        }
    }

    public void setFromStart(double gFromStart, double fFromStart, BiSearchNode predecessorFromStart) {
        this.gFromStart = gFromStart;
        this.fFromStart = fFromStart;
        this.predecessorFromStart = predecessorFromStart;
    }

    public void setFromGoal(double gFromGoal, double fFromGoal, BiSearchNode predecessorFromGoal) {
        this.gFromGoal = gFromGoal;
        this.fFromGoal = fFromGoal;
        this.predecessorFromGoal = predecessorFromGoal;
    }

    public void heapInsert(final AddressableHeap<BiSearchNode, Void> heap, final boolean fromStart) {
        if (fromStart) {
            this.stateFromStart = BiSearch.NodeState.OPEN_FROM_START;
            heapHandleFromStart = heap.insert(this);
        } else {
            this.stateFromGoal = BiSearch.NodeState.OPEN_FROM_GOAL;
            heapHandleFromGoal = heap.insert(this);
        }
    }

    public void heapInsertOrDecrease(final AddressableHeap<BiSearchNode, Void> heap, final boolean fromStart) {
        if (fromStart) {
            if (heapHandleFromStart == null) {
                assert stateFromStart != BiSearch.NodeState.OPEN_FROM_START;
                stateFromStart = BiSearch.NodeState.OPEN_FROM_START;
                heapHandleFromStart = heap.insert(this);
            } else {
                assert stateFromStart == BiSearch.NodeState.OPEN_FROM_START;
                heapHandleFromStart.decreaseKey(this);
            }
        } else {
            if (heapHandleFromGoal == null) {
                assert stateFromGoal != BiSearch.NodeState.OPEN_FROM_GOAL;
                stateFromGoal = BiSearch.NodeState.OPEN_FROM_GOAL;
                heapHandleFromGoal = heap.insert(this);
            } else {
                assert stateFromGoal == BiSearch.NodeState.OPEN_FROM_GOAL;
                heapHandleFromGoal.decreaseKey(this);
            }
        }
    }

    public BiSearch.NodeState getStateFromStart() {
        return stateFromStart;
    }

    public BiSearch.NodeState getStateFromGoal() {
        return stateFromGoal;
    }

    @Override
    public byte getSearchStatus() {
        return 0;
    }

    @Override
    public void setSearchStatus(byte searchStatus) { }

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

        BiSearchNode that = (BiSearchNode) o;

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
