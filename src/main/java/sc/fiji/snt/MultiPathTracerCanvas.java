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

package sc.fiji.snt;

import ij.ImagePlus;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link TracerCanvas} variant that renders a caller-supplied collection of {@link Path}s: each in its own color,
 * with one entry optionally "highlighted".
 * <p>
 * Intended for read-only wizard windows (e.g. the cost-function wizard). Supplied paths should already have their
 * {@code canvasOffset} configured if the canvas image is a cropped sub-volume of the source (so node coordinates
 * line up with the displayed cropped image).
 *
 * @author Tiago Ferreira
 * @see TracerCanvas
 * @see Path#drawPathAsPoints
 */
public class MultiPathTracerCanvas extends TracerCanvas {

    private static final long serialVersionUID = 1L;

    /**
     * One painted path: the path itself, its base color, and whether to highlight it
     */
    public record Entry(Path path, Color color, boolean highlighted) {
    }

    private final List<Entry> entries = new ArrayList<>();

    /**
     * Per-entry transparencies (percent).
     */
    private int highlightedAlpha = 100;
    private int dimmedAlpha = 60;
    /**
     * Out-of-band transparencies (percent).
     */
    private int highlightedOOBAlpha = 60;
    private int dimmedOOBAlpha = 30;

    /**
     * Builds the canvas. The {@code pafm} argument is required by the
     * {@code TracerCanvas} contract but is never iterated by this subclass —
     * pass the SNT instance's PathAndFillManager.
     */
    public MultiPathTracerCanvas(final ImagePlus imp, final SNT snt,
                                 final PathAndFillManager pafm) {
        super(imp, snt, MultiDThreePanes.XY_PLANE, pafm);
        just_near_slices = true;
        eitherSide = 1; // Z-slice band rendered around the current slice (in slices either side).
        setDefaultTransparency(dimmedAlpha);
        setOutOfBoundsTransparency(dimmedOOBAlpha);
    }

    /**
     * Replaces the current set of painted entries with the supplied list.
     */
    public void setEntries(final List<Entry> list) {
        entries.clear();
        if (list != null) entries.addAll(list);
        repaint();
    }

    /**
     * Clears all entries
     */
    public void clearEntries() {
        entries.clear();
        repaint();
    }

    /**
     * Appends a single entry without repainting
     */
    public void addEntry(final Path path, final Color color, final boolean highlighted) {
        entries.add(new Entry(path, color, highlighted));
    }

    /**
     * Sets the z-slice band (either side of the current slice) over which
     * paths still render (with reduced alpha). Pass 0 for strict per-slice
     * rendering.
     */
    public void setEitherSide(final int either_side) {
        this.eitherSide = either_side;
    }

    /**
     * Sets the in-band / out-of-band transparency for highlighted entries (percent).
     */
    public void setHighlightedAlpha(final int inBand, final int outOfBand) {
        this.highlightedAlpha = inBand;
        this.highlightedOOBAlpha = outOfBand;
    }

    /**
     * Sets the in-band / out-of-band transparency for non-highlighted entries (percent).
     */
    public void setDimmedAlpha(final int inBand, final int outOfBand) {
        this.dimmedAlpha = inBand;
        this.dimmedOOBAlpha = outOfBand;
    }

    /**
     * Replaces the parent's PAFM-iterating draw loop with our entry list.
     * SeedOverlay, SearchArtist, and crosshairs are intentionally skipped —
     * this canvas is a passive preview, not an interactive tracing surface.
     */
    @Override
    protected void drawOverlay(final Graphics2D g) {
        if (entries.isEmpty()) return;
        final int current_z = imp.getZ() - 1;
        final Stroke originalStroke = g.getStroke();
        // Two passes so the highlighted entry is always painted LAST (i.e. on top of all dimmed entries),
        // regardless of insertion order. pass==0 -> dimmed entries; pass==1 -> highlighted entries
        for (int pass = 0; pass < 2; pass++) {
            final boolean drawHighlighted = (pass == 1);
            for (final Entry e : entries) {
                if (e == null || e.path == null) continue;
                if (e.highlighted != drawHighlighted) continue;
                // Per-entry transparency: highlighted entry is more opaque to pop against the others
                // Stroke / node size is the canvas-default.
                if (e.highlighted) {
                    setDefaultTransparency(highlightedAlpha);
                    setOutOfBoundsTransparency(highlightedOOBAlpha);
                } else {
                    setDefaultTransparency(dimmedAlpha);
                    setOutOfBoundsTransparency(dimmedOOBAlpha);
                }
                e.path.drawPathAsPoints(
                        MultiPathTracerCanvas.this, // TracerCanvas
                        g,                          // Graphics2D
                        e.color,                    // java.awt.Color
                        e.highlighted,              // highContrast
                        false,                      // drawDiameter
                        current_z,                  // current 0-based slice
                        eitherSide                  // depth-band radius
                );
                g.setStroke(originalStroke);
            }
        }
    }
}
