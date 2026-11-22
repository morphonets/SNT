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

package sc.fiji.snt.gui;

import ij.ImagePlus;
import ij.gui.ImageRoi;
import ij.plugin.LutLoader;
import sc.fiji.snt.util.ImpUtils;

import java.awt.Component;
import java.awt.image.IndexColorModel;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.util.function.Consumer;

/**
 * Per-palette delegate that builds the "More »" pop-up menu and supplies supporting operations (LUT, MIP overlay,
 * snapshot, etc.) shared by SNT's preview-wizard windows ({@link SigmaPalette}, {@link CostPalette} and {@link TipPalette}).
 *
 * @author Tiago Ferreira
 * @see SigmaPalette
 * @see CostPalette
 * @see TipPalette
 */
public final class PaletteOptions {

    /** Name under which the MIP overlay is registered on the palette image. */
    static final String MIP_OVERLAY_NAME = "mip";

    private double mipOpacity = 0.2;
    private boolean mipMenuItem = true;
    private final ImagePlus paletteImage;
    private final ImagePlus sourceImage;
    private final String snapshotTitle;

    /**
     * @param paletteImage  the wizard's preview image (the one whose LUT,
     *                      MIP, snapshot, etc. the menu operates on)
     * @param sourceImage   the original image whose LUT is restored by the
     *                      "Default" LUT entry; may be {@code null}
     * @param snapshotTitle title given to the duplicated image produced by
     *                      the "Take Snapshot" entry
     */
    public PaletteOptions(final ImagePlus paletteImage,
                          final ImagePlus sourceImage,
                          final String snapshotTitle) {
        this.paletteImage = paletteImage;
        this.sourceImage = sourceImage;
        this.snapshotTitle = snapshotTitle;
    }


    /**
     * Options button with display, LUT choices, MIP overlay action, and other utilities
     *
     * @param extras   callback that appends palette-specific items (and headings) between the display items and the
     *                 image ones. Null for none
     * @return the Swing button that pops the menu on click
     */
    public JButton getOptionsButton(final Consumer<JPopupMenu> extras) {
        final JPopupMenu pm = new JPopupMenu();
        GuiUtils.MenuItems.addSeparator(pm, "Display:");
        final JMenu lutMenu = new JMenu("Lookup Table");
        lutMenu.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.COLOR2));
        addLutEntry(lutMenu, "Default", "reset");
        lutMenu.addSeparator();
        addLutEntry(lutMenu, "Edges", "edges");
        addLutEntry(lutMenu, "Fire", "fire");
        addLutEntry(lutMenu, "Grayscale", "grays");
        addLutEntry(lutMenu, "Inferno", "mpl-inferno");
        addLutEntry(lutMenu, "Magma", "mpl-magma");
        addLutEntry(lutMenu, "Plasma", "mpl-plasma");
        addLutEntry(lutMenu, "Viridis", "mpl-viridis");
        pm.add(lutMenu);
        final JCheckBoxMenuItem showMip = new JCheckBoxMenuItem("Overlay MIP", IconFactory.menuIcon(IconFactory.GLYPH.Z));
        showMip.addActionListener(e -> {
            if (showMip.isSelected()) createMip();
            else disposeMip(true);
        });
        showMip.setEnabled(paletteImage.getNSlices() > 1);
        if (mipMenuItem) pm.add(showMip);
        final JMenuItem bc = new JMenuItem("Brightness & Contrast...", IconFactory.menuIcon(IconFactory.GLYPH.ADJUST));
        bc.addActionListener(e -> ij.IJ.doCommand("Brightness/Contrast..."));
        pm.add(bc);
        GuiUtils.MenuItems.addSeparator(pm, "Utilities:");
        final JMenuItem snapshot = new JMenuItem("Take Snapshot", IconFactory.menuIcon(IconFactory.GLYPH.CAMERA));
        snapshot.addActionListener(e -> takeSnapshot());
        pm.add(snapshot);
        if (extras != null) extras.accept(pm);
        return GuiUtils.Buttons.OptionsButton("Actions/Options", pm, true);
    }

    /**
     * Adds a "Help" heading with a Help entry to a Swing popup menu
     *
     * @param pm         the menu
     * @param helpHtml   the HTML message
     * @param dialogParent the parent of the help dialog
     */
    public void addHelpItem(final JPopupMenu pm, final String helpHtml, final Component dialogParent) {
        GuiUtils.MenuItems.addSeparator(pm, "Help:");
        final JMenuItem help = new JMenuItem("Help...", IconFactory.menuIcon(IconFactory.GLYPH.QUESTION_CIRCLE));
        help.addActionListener(e -> showHelp(helpHtml, dialogParent));
        pm.add(help);
    }

    /**
     * Adds a "Rebuild Dialog" entry (a stop gap for painting glitches) to a Swing popup menu
     *
     * @param pm     the menu
     * @param action what the entry does
     */
    public void addRebuildItem(final JPopupMenu pm, final Runnable action) {
        final JMenuItem rebuild = new JMenuItem("Rebuild Dialog", IconFactory.menuIcon(IconFactory.GLYPH.RECYCLE));
        rebuild.setToolTipText("Troubleshooting option: Embedding a image into this dialog may cause\n" +
                "flickering, layout, or resizing problems. Run it if you run into such issues.");
        rebuild.addActionListener(e -> action.run());
        pm.add(rebuild);
    }

    /**
     * Adds a "Tour of Controls..." entry to a Swing popup menu
     *
     * @param pm     the menu
     * @param action starts the tour
     */
    public void addTourItem(final JPopupMenu pm, final Runnable action) {
        final JMenuItem tour = new JMenuItem("Tour of Controls...",
                IconFactory.menuIcon(IconFactory.GLYPH.PERSON_CHALKBOARD));
        tour.addActionListener(e -> action.run());
        pm.add(tour);
    }

    private void addLutEntry(final JMenu menu, final String label, final String lutName) {
        final JMenuItem mi = new JMenuItem(label);
        mi.addActionListener(e -> applyLut(lutName));
        menu.add(mi);
    }

    /**
     * Applies a named ImageJ LUT to the palette image. {@code "reset"} restores
     * the source image's first LUT (preserving the palette image's current
     * min/max). Other names are forwarded to {@link LutLoader#getLut(String)}.
     */
    public void applyLut(final String lutName) {
        if ("reset".equals(lutName) && sourceImage != null && sourceImage.getLuts().length > 0) {
            final double min = paletteImage.getDisplayRangeMin();
            final double max = paletteImage.getDisplayRangeMax();
            paletteImage.setLut(sourceImage.getLuts()[0]);
            paletteImage.setDisplayRange(min, max);
            updateMip();
            return;
        }
        final IndexColorModel lut = LutLoader.getLut(lutName);
        if (lut == null) {
            new GuiUtils().error(
                    "LUT could not be retrieved. Some file(s) may be missing from your installation.");
            return;
        }
        paletteImage.getProcessor().setColorModel(lut);
        if (paletteImage.getStackSize() > 1) paletteImage.getStack().setColorModel(lut);
        paletteImage.updateAndDraw();
        updateMip();
    }

    @SuppressWarnings("unused")
    private void toggleMip() {
        if (paletteImage.getOverlay() != null
                && paletteImage.getOverlay().get(MIP_OVERLAY_NAME) != null) {
            disposeMip(true);
        } else {
            createMip();
        }
    }

    /**
     * Omits the "Overlay MIP" entry from the options menu, for wizards that manage the MIP overlay themselves
     *
     * @return this
     */
    public PaletteOptions withoutMipItem() {
        mipMenuItem = false;
        return this;
    }

    /**
     * Shows or removes the MIP overlay
     *
     * @param show    whether the overlay is shown
     * @param opacity the opacity of the overlay (0-1). Remembered, e.g., when the LUT changes
     */
    public void showMip(final boolean show, final double opacity) {
        mipOpacity = opacity;
        disposeMip(false);
        if (show) createMip();
        else if (paletteImage.getCanvas() != null) paletteImage.getCanvas().repaint();
    }

    /** Creates / replaces a MIP overlay using the palette image's current LUT. */
    private void createMip() {
        final ImagePlus mip = ImpUtils.getMIP(paletteImage); // inherits the LUT
        mip.setDisplayRange(paletteImage.getDisplayRangeMin(), paletteImage.getDisplayRangeMax());
        mip.updateAndDraw();
        final ImageRoi roi = new ImageRoi(0, 0, mip.getProcessor());
        roi.setName(MIP_OVERLAY_NAME);
        roi.setOpacity(mipOpacity);
        if (paletteImage.getOverlay() == null) paletteImage.setOverlay(new ij.gui.Overlay());
        paletteImage.getOverlay().add(roi);
        paletteImage.getCanvas().repaint();
    }

    /** Removes the MIP overlay. */
    private void disposeMip(final boolean repaint) {
        if (paletteImage.getOverlay() != null) {
            paletteImage.getOverlay().remove(MIP_OVERLAY_NAME);
            if (repaint && paletteImage.getCanvas() != null) {
                paletteImage.getCanvas().repaint();
            }
        }
    }

    /** Refreshes the MIP overlay (if currently shown) so it picks up LUT/min/max changes. */
    public void updateMip() {
        if (paletteImage.getOverlay() != null
                && paletteImage.getOverlay().get(MIP_OVERLAY_NAME) != null) {
            disposeMip(false);
            createMip();
        }
    }

    /**
     * Pops what is displayed (canvas with paths, detections, labels, etc.) into
     * its own window. Falls back to a copy of the image if rendering fails.
     */
    private void takeSnapshot() {
        ImagePlus snap = ImpUtils.snapshot(paletteImage);
        if (snap == null)
            snap = paletteImage.duplicate();
        snap.setTitle(snapshotTitle);
        snap.show();
    }

    /** Shows the help HTML in a non-modal dialog parented to {@code parent} (or unparented when null). */
    void showHelp(final String htmlMessage, final Component parent) {
        new GuiUtils(parent).showHTMLDialog(htmlMessage, "Help", false);
    }
}
