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
import ij.gui.ImageCanvas;
import ij.gui.StackWindow;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.SNTUtils;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.LayoutManager;
import java.awt.Window;
import java.awt.event.WindowEvent;

/**
 * Lifecycle shared by the preview wizards ({@link CostPalette}, {@link SigmaPalette}, {@link TipPalette}): they
 * prepare their window in a thread (with the busy cursor on), and are dismissed in the same sequence of steps
 *
 * @author Tiago Ferreira
 */
abstract class WizardPalette extends Thread {

    protected final SNT snt;
    protected Window parent;
    protected ImagePlus paletteImage;
    protected PaletteOptions paletteOptions;

    protected WizardPalette(final SNT snt) {
        this.snt = snt;
    }

    public void setParent(final Window parent) {
        this.parent = parent;
    }

    @Override
    public final void run() {
        WizardUtils.busy(snt, this::build);
    }

    /** Prepares and displays the wizard. Runs with the busy cursor on */
    protected abstract void build();

    /** Tells the listeners that the wizard is being dismissed */
    protected abstract void notifyDismissed();

    /** Removes the listeners */
    protected abstract void clearListeners();

    /** @return the window to be closed (null if it was not created). Subclasses may release their reference to it */
    protected abstract StackWindow windowToClose();

    /** Called first: stop background work */
    protected void beforeDismiss() {
    }

    /** Called after the window is closed: release data */
    protected void afterDismiss() {
    }

    /**
     * Whether SNT's UI is reset to {@link SNTUI#READY} when dismissed. Wizards launched from a command that stays
     * open (and manages the state) do not
     */
    protected boolean resetsUiState() {
        return true;
    }

    public void dismiss() {
        beforeDismiss();
        notifyDismissed();
        closeWindow(windowToClose());
        if (paletteImage != null) paletteImage.flush();
        afterDismiss();
        clearListeners();
        if (resetsUiState() && snt.getUI() != null) snt.getUI().changeState(SNTUI.READY); // Runs on EDT
    }

    private void closeWindow(final StackWindow w) {
        if (w == null) return;
        try {
            if (paletteImage != null) paletteImage.changes = false; // No "save changes" prompt
            w.close();
        } catch (final RuntimeException ex) {
            SNTUtils.error("Wizard window could not be closed", ex);
        }
        // Fallback: closing can be refused (or fail) silently
        if (w.isDisplayable()) {
            w.setVisible(false);
            w.dispose();
        }
    }

    /** Window of a wizard: closing it dismisses the wizard */
    abstract static class PaletteWindow extends StackWindow {
        private static final long serialVersionUID = 1L;
        private final transient Runnable onClosing;

        PaletteWindow(final ImagePlus imp, final ImageCanvas ic, final Runnable onClosing) {
            super(imp, ic);
            this.onClosing = onClosing;
        }

        @Override
        public void windowClosing(final WindowEvent e) {
            onClosing.run();
            super.windowClosing(e);
        }

        protected final JPanel newPanel(final LayoutManager layout) {
            return new JPanel(layout);
            // customizations could be put here like setting the same background as the awt root
        }

        /**
         * A Swing panel for the buttons of the window, aligned as the buttons of option dialogs are in the current
         * look and feel (right, if it does not say)
         */
        protected final JPanel newButtonRow() {
            final Object orientation = javax.swing.UIManager.get("OptionPane.buttonOrientation");
            final int align = switch ((orientation instanceof Integer i) ? i : javax.swing.SwingConstants.RIGHT) {
                case javax.swing.SwingConstants.LEFT -> java.awt.FlowLayout.LEFT;
                case javax.swing.SwingConstants.CENTER -> java.awt.FlowLayout.CENTER;
                default -> java.awt.FlowLayout.RIGHT;
            };
            return newPanel(new java.awt.FlowLayout(align));
        }

        /** Adds a pointer to the tour of controls to the tooltip of a control */
        protected final void tip(final javax.swing.JComponent comp, final String text) {
            comp.setToolTipText("<html>" + text + "<br><i>More &gt; Tour of Controls... for details</i></html>");
        }

        /**
         * Starts the tour of the controls, or moves to its next callout if it is already running
         *
         * @param registrar registers the callouts of the tour (receives the group they belong to)
         */
        protected final void runTour(final java.util.function.Consumer<String> registrar) {
            final String group = CalloutManager.groupFor(this);
            if (!CalloutManager.isActive(group)) {
                // (re)registered every time: the window may have been resized or rebuilt since last time
                CalloutManager.clearRegistrations(group);
                registrar.accept(group);
            }
            CalloutManager.showAllOrAdvance(group);
        }

        /**
         * Stop gap for occasional painting glitches (e.g., after collapsing panels quickly): revalidates and
         * repaints all the controls and repacks the window
         *
         * @param root the (AWT) container holding the controls
         */
        protected final void refreshLayout(final Container root) {
            if (root == null) return;
            repaintTree(root);
            root.invalidate();
            root.validate();
            pack();
            root.repaint();
        }

        private static void repaintTree(final Component comp) {
            if (comp instanceof javax.swing.JComponent jc) {
                jc.revalidate();
                jc.repaint();
            }
            if (comp instanceof Container ct) {
                for (final Component child : ct.getComponents()) repaintTree(child);
            }
        }

        /** Boxed section: titled border (as in MaterializeRegionDialog) or, if the title is null, an etched one */
        protected final JPanel section(final String title, final Component content) {
            final JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createCompoundBorder((title == null)
                            ? BorderFactory.createEtchedBorder()
                            : BorderFactory.createTitledBorder(title),
                    BorderFactory.createEmptyBorder(2, 2, 2, 2)));
            panel.add(content, BorderLayout.CENTER);
            return panel;
        }
    }
}
