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
import ij.gui.GUI;
import ij.gui.ImageRoi;
import ij.plugin.LutLoader;
import sc.fiji.snt.util.ImpUtils;

import java.awt.Button;
import java.awt.CheckboxMenuItem;
import java.awt.Component;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.image.IndexColorModel;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.util.function.Consumer;

/**
 * Per-palette delegate that builds the "More »" pop-up menu and supplies supporting operations (LUT, MIP overlay,
 * snapshot, etc.) shared by SNT's preview-wizard windows ({@link SigmaPalette}, and {@link CostPalette}).
 *
 * @author Tiago Ferreira
 * @see SigmaPalette
 * @see CostPalette
 */
public final class PaletteOptions {

    /** Name under which the MIP overlay is registered on the palette image. */
    private static final String MIP_OVERLAY_NAME = "mip";

    private final ImagePlus paletteImage;
    private final ImagePlus sourceImage;
    private final String snapshotTitle;
    private final Runnable onDismiss;

    /**
     * @param paletteImage  the wizard's preview image (the one whose LUT,
     *                      MIP, snapshot, etc. the menu operates on)
     * @param sourceImage   the original image whose LUT is restored by the
     *                      "Default" LUT entry; may be {@code null}
     * @param snapshotTitle title given to the duplicated image produced by
     *                      the "Take Snapshot" entry
     * @param onDismiss     called when the user picks the "Dismiss" item
     */
    public PaletteOptions(final ImagePlus paletteImage,
                          final ImagePlus sourceImage,
                          final String snapshotTitle,
                          final Runnable onDismiss) {
        this.paletteImage = paletteImage;
        this.sourceImage = sourceImage;
        this.snapshotTitle = snapshotTitle;
        this.onDismiss = onDismiss;
    }

    /**
     * Builds the standard "More »" button populated with help, LUT submenu,
     * MIP toggle, optional wizard-specific items, snapshot, and dismiss.
     *
     * @param parent   the AWT container the {@link PopupMenu} attaches to  (typically the wizard window). PopupMenu
     *                 instances must  be added to a Component before {@code show()} can display them.
     * @param helpHtml HTML message displayed by the "Help" entry; pass  {@code null} to omit the entry
     * @param extras   callback that appends wizard-specific menu items between the MIP toggle and the snapshot entry;
     *                 pass {@code null} for none. The callback receives the rin-progress popup menu and may add items /
     *                 separators.
     * @return the AWT {@link Button} that pops the menu on click
     */
    public Button buildMoreButton(final Component parent, final String helpHtml, final Consumer<PopupMenu> extras) {
        final PopupMenu pm = buildMoreMenu(parent, helpHtml, extras);
        final Button btn = new Button("More »"); // matches IJ1 convention
        btn.addActionListener(e -> pm.show(btn, btn.getWidth() / 2, btn.getHeight() / 2));
        return btn;
    }

    /**
     * Same as {@link #buildMoreButton(Component, String, Consumer)} but Swing-based (a {@link JPopupMenu} with
     * headings and icons, consistent with the rest of SNT's menus), for palettes whose controls are Swing
     *
     * @param extras   callback that appends palette-specific items (and headings) between the display items and the
     *                 image ones. Null for none
     * @return the Swing button that pops the menu on click
     */
    public JButton buildMoreSwingButton(final Consumer<JPopupMenu> extras) {
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
        pm.add(showMip);
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

    private void addLutEntry(final JMenu menu, final String label, final String lutName) {
        final JMenuItem mi = new JMenuItem(label);
        mi.addActionListener(e -> applyLut(lutName));
        menu.add(mi);
    }

    private PopupMenu buildMoreMenu(final Component parent, final String helpHtml, final Consumer<PopupMenu> extras) {
        final PopupMenu pm = new PopupMenu();
        GUI.scalePopupMenu(pm);
        parent.add(pm);

        if (helpHtml != null) {
            addHelpEntry(pm, helpHtml, parent);
            pm.addSeparator();
        }
        addLutSubmenu(pm);
        addMipToggle(pm);
        if (extras != null) {
            pm.addSeparator();
            extras.accept(pm);
        }
        pm.addSeparator();
        addSnapshotEntry(pm);
        pm.addSeparator();
        addDismissEntry(pm);

        return pm;
    }

    public void addHelpEntry(final PopupMenu pm, final String htmlMessage, final Component dialogParent) {
        final MenuItem mi = new MenuItem("Help...");
        mi.addActionListener(e -> showHelp(htmlMessage, dialogParent));
        pm.add(mi);
    }

    /**
     * Adds the standard LUT submenu (Default + 7 SNT favourites) to the supplied popup menu. The "Default" entry
     * restores the source image's primary LUT if one is available.
     */
    public void addLutSubmenu(final PopupMenu pm) {
        final Menu lutMenu = new Menu("Lookup Table");
        pm.add(lutMenu);
        addLutEntry(lutMenu, "Default", "reset");
        lutMenu.addSeparator();
        addLutEntry(lutMenu, "Edges", "edges");
        addLutEntry(lutMenu, "Fire", "fire");
        addLutEntry(lutMenu, "Grayscale", "grays");
        addLutEntry(lutMenu, "Inferno", "mpl-inferno");
        addLutEntry(lutMenu, "Magma", "mpl-magma");
        addLutEntry(lutMenu, "Plasma", "mpl-plasma");
        addLutEntry(lutMenu, "Viridis", "mpl-viridis");
    }

    /**
     * Adds an "Overlay MIP" {@link CheckboxMenuItem}. The toggle is disabled automatically when the palette image has
     * fewer than 2 slices.
     */
    private void addMipToggle(final PopupMenu pm) {
        final CheckboxMenuItem showMip = new CheckboxMenuItem("Overlay MIP");
        showMip.addItemListener(e -> {
            if (showMip.getState()) createMip();
            else disposeMip(true);
        });
        showMip.setEnabled(paletteImage.getNSlices() > 1);
        pm.add(showMip);
    }

    private void addSnapshotEntry(final PopupMenu pm) {
        final MenuItem mi = new MenuItem("Take Snapshot");
        mi.addActionListener(e -> takeSnapshot());
        pm.add(mi);
    }

    private void addDismissEntry(final PopupMenu pm) {
        final MenuItem mi = new MenuItem("Dismiss");
        mi.addActionListener(e -> {
            if (onDismiss != null) onDismiss.run();
        });
        pm.add(mi);
    }

    private void addLutEntry(final Menu menu, final String label, final String lutName) {
        final MenuItem mi = new MenuItem(label);
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

    /** Creates / replaces a MIP overlay using the palette image's current LUT. */
    private void createMip() {
        final ImagePlus mip = ImpUtils.getMIP(paletteImage); // inherits the LUT
        mip.setDisplayRange(paletteImage.getDisplayRangeMin(), paletteImage.getDisplayRangeMax());
        mip.updateAndDraw();
        final ImageRoi roi = new ImageRoi(0, 0, mip.getProcessor());
        roi.setName(MIP_OVERLAY_NAME);
        roi.setOpacity(0.2);
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

    /** Pops a duplicate of the palette image into its own window. */
    private void takeSnapshot() {
        final ImagePlus snap = paletteImage.duplicate();
        snap.setTitle(snapshotTitle);
        snap.show();
    }

    /** Shows the help HTML in a non-modal dialog parented to {@code parent} (or unparented when null). */
    void showHelp(final String htmlMessage, final Component parent) {
        new GuiUtils(parent).showHTMLDialog(htmlMessage, "Help", false);
    }
}
