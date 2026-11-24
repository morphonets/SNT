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
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Lifecycle shared by the preview wizards ({@link CostPalette}, {@link SigmaPalette}, {@link TipPalette}): they
 * prepare their window in a thread (with the busy cursor on), and are dismissed in the same sequence of steps
 *
 * @author Tiago Ferreira
 */
abstract class WizardPalette implements Runnable {

    protected final SNT snt;
    protected Window parent;
    protected ImagePlus paletteImage;
    protected PaletteOptions paletteOptions;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean dismissed = new AtomicBoolean();

    protected WizardPalette(final SNT snt) {
        this.snt = snt;
    }

    public void setParent(final Window parent) {
        this.parent = parent;
    }

    /**
     * Prepares the wizard in a background (daemon) thread. May only be called once
     *
     * @throws IllegalStateException if the wizard was already started
     */
    protected final void start() {
        if (!started.compareAndSet(false, true))
            throw new IllegalStateException(getClass().getSimpleName() + " was already started");
        final Thread t = new Thread(this, "SNT-" + getClass().getSimpleName());
        t.setDaemon(true); // a wizard that hangs must not keep the application from exiting
        t.start();
    }

    /** @return true if {@link #dismiss()} was called. Long-running steps of {@link #build()} may poll it */
    protected final boolean isDismissed() {
        return dismissed.get();
    }

    @Override
    public final void run() {
        try {
            if (!isDismissed()) WizardUtils.busy(snt, this::build);
        } catch (final Throwable t) {
            // Otherwise the thread would die silently, leaving SNT's UI in the state of the wizard
            SNTUtils.error(getClass().getSimpleName() + " could not be built", t);
            if (snt.getUI() != null) snt.getUI().error("The wizard could not be created: " + t.getMessage());
            dismiss();
        } finally {
            // Dismissed while being built: the window (if it was created in the meantime) must not be left behind
            if (isDismissed()) SwingUtilities.invokeLater(() -> closeWindow(windowToClose()));
        }
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

    /** Dismisses the wizard. Only the first call has an effect */
    public void dismiss() {
        if (!dismissed.compareAndSet(false, true)) return;
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
        // Largest height of the canvas, as a fraction of the screen height, when widening it to fit the controls
        private static final double MAX_CANVAS_SCREEN_FRACTION = 0.5;
        private final transient Runnable onClosing;
        final transient Color infoColor;

        PaletteWindow(final ImagePlus imp, final ImageCanvas ic, final Runnable onClosing) {
            super(imp, ic);
            this.onClosing = onClosing;
            // Swing controls sit on this window: use the look and feel colors (not the AWT/IJ ones). Done here
            // so that panels copying getBackground() in subclasses pick it up
            final Color bg = UIManager.getColor("Panel.background");
            if (bg != null) setBackground(bg);
            infoColor = UIManager.getColor("Label.foreground");
        }

        @Override
        public void drawInfo(final Graphics g) {
            if (g != null && infoColor != null) g.setColor(infoColor);
            super.drawInfo(g);
        }

        /**
         * Guarantees that the displayed image is not narrower than the panel below it, by zooming in (to any
         * magnification, not just IJ zoom levels) if needed. Never zooms out. The canvas height is kept below half
         * of the screen height, so that tall images may remain narrower than the panel. Call once the controls
         * are assembled, before {@code pack()}
         *
         * @param panel the (assembled) panel under the canvas
         */
        protected final void fitCanvasToPanel(final Component panel) {
            final ImageCanvas ic = getCanvas();
            final ImagePlus imp = getImagePlus();
            if (ic == null || imp == null || panel == null) return;
            final int minWidth = panel.getPreferredSize().width;
            final double current = ic.getMagnification();
            if (imp.getWidth() * current >= minWidth) return;
            final Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            final double ceiling = MAX_CANVAS_SCREEN_FRACTION * screen.height / imp.getHeight();
            final double mag = Math.min((double) minWidth / imp.getWidth(), Math.max(ceiling, current));
            if (mag <= current) return;
            ic.setMagnification(mag);
            ic.setSize((int) Math.ceil(imp.getWidth() * mag), (int) Math.ceil(imp.getHeight() * mag));
        }

        @Override
        public void windowClosing(final WindowEvent e) {
            onClosing.run();
            super.windowClosing(e);
        }

        protected final JPanel newPanel(final LayoutManager layout) {
            return new JPanel(layout);
        }

        /**
         * A Swing panel for the buttons of the window, aligned as the buttons of option dialogs are in the current
         * look and feel (right, if it does not say)
         */
        protected final JPanel newButtonRow() {
            final Object orientation = UIManager.get("OptionPane.buttonOrientation");
            final int align = switch ((orientation instanceof Integer i) ? i : SwingConstants.RIGHT) {
                case SwingConstants.LEFT -> FlowLayout.LEFT;
                case SwingConstants.CENTER -> FlowLayout.CENTER;
                default -> FlowLayout.RIGHT;
            };
            return newPanel(new FlowLayout(align));
        }

        /** Adds a pointer to the tour of controls to the tooltip of a control */
        protected final void tip(final JComponent comp, final String text) {
            comp.setToolTipText("<html>" + text + "<br><i>More &gt; Tour of Controls... for details</i></html>");
        }

        /**
         * Starts the tour of the controls, or moves to its next callout if it is already running
         *
         * @param registrar registers the callouts of the tour (receives the group they belong to)
         */
        protected final void runTour(final Consumer<String> registrar) {
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
            if (comp instanceof JComponent jc) {
                jc.revalidate();
                jc.repaint();
            }
            if (comp instanceof Container ct) {
                for (final Component child : ct.getComponents()) repaintTree(child);
            }
        }
    }
}
