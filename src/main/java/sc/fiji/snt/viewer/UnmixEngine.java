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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt.viewer;

/**
 * Viewer-specific back end of {@link ChannelUnmixingCard}: how a mixed
 * (signal - w * background) source is produced, displayed and discarded. The
 * card handles the UI and calls the engine, which reports back through the
 * {@link Host} it is attached to.
 */
interface UnmixEngine {

    /**
     * The mixing formula shared by all engines: {@code signal - w * background}, with both values normalised by the
     * display range of their channel (the background is rescaled so that its range matches the signal's), and the
     * result clamped to the 16-bit range.
     *
     * @param a          the signal value
     * @param b          the background value
     * @param w          the subtraction weight
     * @param sigMin     lower bound of the signal's display range
     * @param subMin     lower bound of the background's display range
     * @param rangeScale ratio between the display range widths of signal and background
     * @return the mixed value
     */
    static double mix(final double a, final double b, final double w, final double sigMin, final double subMin,
                      final double rangeScale) {
        final double val = (a - sigMin) - w * ((b - subMin) * rangeScale) + sigMin;
        return Math.clamp(val, 0, BvvUtils.MAX_UINT16);
    }

    /** Callbacks into the UI hosting the engine */
    interface Host {

        /** @return the index of the selected signal channel */
        int signalIndex();

        /** @return the index of the selected background channel */
        int backgroundIndex();

        /** @return the current subtraction weight (0-1) */
        double weight();

        /** @return the display name of the i-th channel */
        String channelName(int i);

        /** @return whether unmixing is enabled and the weight is positive */
        boolean isActive();

        /** Sets the (short) status message */
        void setStatus(String msg);

        /** Flags a lengthy operation (wait cursor, weight slider disabled) */
        void setBusy(boolean busy);

        /** Asks the UI to re-validate its state */
        void requestCheck();

        /** Toggles unmixing off */
        void deselectEnable();
    }

    /** Binds the engine to the UI hosting it. Called once, before any other method */
    void attach(Host host);

    /**
     * @return {@code true} if weight changes are applied while the slider is dragged, {@code false} if
     * {@link #apply} is only called when the slider is released
     */
    boolean isLive();

    /** @return a sentence appended to the weight slider's tooltip */
    default String sliderHint() {
        return "";
    }

    /** @return a sentence appended to the Enable button's tooltip */
    default String enableHint() {
        return "";
    }

    /** @return whether a lengthy computation is running (UI checks are skipped meanwhile) */
    boolean isBusy();

    /** @return whether a mixed source is currently displayed */
    boolean hasResult();

    /**
     * Checks whether the selection can be unmixed in the current state of the viewer, discarding or scheduling
     * the refresh of stale results as needed.
     *
     * @param active whether unmixing is enabled and the weight is positive
     * @return a message describing why unmixing is not possible, or {@code null} if it is
     */
    String validate(int sigIdx, int subIdx, boolean active);

    /** @return the status message for the (valid and enabled) current state */
    String status(int sigIdx, int subIdx, double w);

    /** Computes/updates and displays the mix. Never called with {@code w <= 0} or identical channels */
    void apply(int sigIdx, int subIdx, double w);

    /** Cancels pending work, removes the mixed source, and restores the original channels */
    void clear();
}
