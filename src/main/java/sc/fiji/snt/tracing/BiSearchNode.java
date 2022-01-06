/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2022 Fiji developers.
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

    public enum State {OPEN, CLOSED, FREE}

    private int x;
    private int y;
    private int z;

    private double gFromStart;
    private double gFromGoal;

    private double fFromStart;
    private double fFromGoal;

    private BiSearchNode predecessorFromStart;
    private BiSearchNode predecessorFromGoal;

    private AddressableHeap.Handle<BiSearchNode, Void> heapHandleFromStart;
    private AddressableHeap.Handle<BiSearchNode, Void> heapHandleFromGoal;

    private State stateFromStart = State.FREE;
    private State stateFromGoal = State.FREE;

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
            heapHandleFromStart = heap.insert(this);
        } else {
            heapHandleFromGoal = heap.insert(this);
        }
    }

    public void heapDecreaseKey(final boolean fromStart) {
        if (fromStart) {
            heapHandleFromStart.decreaseKey(this);
        } else {
            heapHandleFromGoal.decreaseKey(this);
        }
    }

    public State getStateFromStart() {
        return stateFromStart;
    }

    public State getStateFromGoal() {
        return stateFromGoal;
    }

    public State getState(final boolean fromStart) {
        return fromStart ? stateFromStart : stateFromGoal;
    }

    public void setState(State state, final boolean fromStart) {
        if (fromStart) {
            setStateFromStart(state);
        } else {
            setStateFromGoal(state);
        }
    }

    public void setStateFromStart(State stateFromStart) {
        this.stateFromStart = stateFromStart;
    }

    public void setStateFromGoal(State stateFromGoal) {
        this.stateFromGoal = stateFromGoal;
    }

    public void setX(int x) {
        this.x = x;
    }

    public void setY(int y) {
        this.y = y;
    }

    public void setZ(int z) {
        this.z = z;
    }

    public void setGFromStart(final double gFromStart) {
        this.gFromStart = gFromStart;
    }

    public void setGFromGoal(final double gFromGoal) {
        this.gFromGoal = gFromGoal;
    }

    public void setFFromStart(final double fFromStart) {
        this.fFromStart = fFromStart;
    }

    public void setFFromGoal(final double fFromGoal) {
        this.fFromGoal = fFromGoal;
    }

    public void setPredecessorFromStart(final BiSearchNode predecessorFromStart) {
        this.predecessorFromStart = predecessorFromStart;
    }

    public void setPredecessorFromGoal(final BiSearchNode predecessorFromGoal) {
        this.predecessorFromGoal = predecessorFromGoal;
    }

    public void setHeapHandle(final AddressableHeap.Handle<BiSearchNode, Void> handle, final boolean fromStart) {
        if (fromStart) {
            setHeapHandleFromStart(handle);
        } else {
            setHeapHandleFromGoal(handle);
        }
    }

    public void setHeapHandleFromStart(final AddressableHeap.Handle<BiSearchNode, Void> heapHandleFromStart) {
        this.heapHandleFromStart = heapHandleFromStart;
    }

    public void setHeapHandleFromGoal(final AddressableHeap.Handle<BiSearchNode, Void> heapHandleFromGoal) {
        this.heapHandleFromGoal = heapHandleFromGoal;
    }

    public double getG(final boolean fromStart) {
        return fromStart ? getGFromStart() : getGFromGoal();
    }

    public double getGFromStart() {
        return gFromStart;
    }

    public double getGFromGoal() {
        return gFromGoal;
    }

    public double getF(final boolean fromStart) {
        return fromStart ? getFFromStart() : getFFromGoal();
    }

    public double getFFromStart() {
        return fFromStart;
    }

    public double getFFromGoal() {
        return fFromGoal;
    }

    public BiSearchNode getPredecessorFromStart() {
        return predecessorFromStart;
    }

    public BiSearchNode getPredecessorFromGoal() {
        return predecessorFromGoal;
    }

    public AddressableHeap.Handle<BiSearchNode, Void> getHeapHandle(final boolean fromStart) {
        return fromStart ? getHeapHandleFromStart() : getHeapHandleFromGoal();
    }

    public AddressableHeap.Handle<BiSearchNode, Void> getHeapHandleFromStart() {
        return heapHandleFromStart;
    }

    public AddressableHeap.Handle<BiSearchNode, Void> getHeapHandleFromGoal() {
        return heapHandleFromGoal;
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
