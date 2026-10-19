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

package sc.fiji.snt.gui.cmds;

import org.scijava.command.DynamicCommand;
import org.scijava.plugin.Parameter;
import org.scijava.widget.Button;
import org.scijava.widget.ChoiceWidget;
import org.scijava.widget.NumberWidget;
import sc.fiji.snt.viewer.AbstractBigViewer;

/**
 * Abstract base command for configuring {@link AbstractBigViewer.PathRenderingOptions}.
 * Subclasses supply the specific viewer instance via {@link #getViewer()}.
 *
 * @author Tiago Ferreira
 * @see BdvRenderingOptionsCmd
 * @see BvvRenderingOptionsCmd
 */
public abstract class BigViewerRenderingOptionsCmd extends DynamicCommand {

    protected static final String STYLE_LINE_SIMPLE = "Centerlines (uniform width)";
    protected static final String STYLE_LINE_RADII = "Centerlines (width from path radii)";
    protected static final String STYLE_RADII = "Frusta (tapered, from path radii)";

    @Parameter(label = "Path rendering style", choices = {STYLE_LINE_SIMPLE, STYLE_LINE_RADII, STYLE_RADII},
            style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE, persist = false,
            description = "<html>Centerline rendering is recommended for large datasets.<br>"
                    + "Frusta (tapered tubes) use per-node radii for a more accurate 3D appearance")
    private String renderingStyle;

    @Parameter(label = "Thickness multiplier", min = "0.1", max = "10.0", stepSize = "0.1",
            style = "slider,format:0.00", persist = false,
            description = "Scale factor applied to all path radii.\n"
                    + "1.0x = natural size; increase if paths appear too thin.")
    private double thicknessMultiplier;

    @Parameter(label = "Min. thickness", min = "0.1", max = "20.0", stepSize = "0.1",
            style = "slider,format:0.0", persist = false,
            description = "Minimum rendered thickness in physical units.\n"
                    + "Controls line width in centerline mode and the floor radius in tube mode.")
    private double minThickness;

    @Parameter(label = "Max. thickness", min = "1", max = "100.0", stepSize = "0.5",
            style = "slider,format:0.0", persist = false,
            description = "Maximum rendered thickness in physical units.\n"
                    + "Controls line width in centerline mode and the ceiling radius in tube mode.")
    private double maxThickness;

    @Parameter(label = "Transparency (%)", min = "0", max = "100", stepSize = "1",
            style = NumberWidget.SLIDER_STYLE, persist = false,
            description = "Opacity of all path overlays.\n0% = fully opaque; 100% = fully transparent.")
    private double transparency;

    @Parameter(required = false, label = "Reset to Defaults", persist = false, callback = "reset")
    private Button reset;

    /**
     * Returns the viewer instance to configure. Implementations return their
     * specific viewer (Bvv or Bdv) injected via a @Parameter field.
     */
    protected abstract AbstractBigViewer getViewer();

    protected void init() {
        final AbstractBigViewer.PathRenderingOptions opts = getViewer().getRenderingOptions();
        renderingStyle = toLabel(opts.getStyle());
        thicknessMultiplier = opts.getThicknessMultiplier();
        minThickness = opts.getMinThickness();
        maxThickness = opts.getMaxThickness();
        transparency = (1.0 - opts.getTransparency()) * 100; // invert and scale: 0% = opaque, 100% = transparent
    }

    private void reset() {
        renderingStyle = toLabel(getViewer().getDefaultRenderingStyle());
        thicknessMultiplier = 1.0f;
        minThickness = 1.0f;
        maxThickness = 100.0f;
        transparency = 0; // inverted: 0% = opaque
        getViewer().getRenderingOptions().clearSaved(getViewer().getClass());
        getViewer().getRenderingOptions().setStyle(toStyle(renderingStyle));
        updateOverlay(getViewer());
    }

    private static String toLabel(final AbstractBigViewer.PathRenderingOptions.Style style) {
        return switch (style) {
            case LINE_SIMPLE -> STYLE_LINE_SIMPLE;
            case LINE_RADII -> STYLE_LINE_RADII;
            case FRUSTA -> STYLE_RADII;
        };
    }

    private static AbstractBigViewer.PathRenderingOptions.Style toStyle(final String label) {
        if (STYLE_RADII.equals(label)) return AbstractBigViewer.PathRenderingOptions.Style.FRUSTA;
        if (STYLE_LINE_RADII.equals(label)) return AbstractBigViewer.PathRenderingOptions.Style.LINE_RADII;
        return AbstractBigViewer.PathRenderingOptions.Style.LINE_SIMPLE;
    }

    @Override
    public void run() {
        final AbstractBigViewer viewer = getViewer();
        if (viewer == null) return;
        final AbstractBigViewer.PathRenderingOptions opts = viewer.getRenderingOptions();
        opts.setThicknessMultiplier((float) thicknessMultiplier);
        opts.setMinThickness((float) minThickness);
        opts.setMaxThickness((float) maxThickness);
        opts.setStyle(toStyle(renderingStyle));
        opts.setTransparency((float) (100 - transparency) / 100);
        opts.save(viewer.getClass());
        updateOverlay(viewer);
    }

    private void updateOverlay(final AbstractBigViewer viewer) {
        if (viewer != null) {
            viewer.setDisplayRadii(viewer.getRenderingOptions().isDisplayRadii()); // invalidates the overlay cache before syncing
            viewer.syncOverlays();
        }
    }

}
