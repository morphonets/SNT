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

import org.scijava.command.Command;
import org.scijava.command.DynamicCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.ColorRGB;
import org.scijava.widget.ChoiceWidget;
import org.scijava.widget.NumberWidget;
import sc.fiji.snt.viewer.Bvv;

/**
 * Command providing a GUI for configuring {@link Bvv.PathRenderingOptions}.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Path Rendering Options", initializer = "init")
public class BvvRenderingOptionsCmd extends DynamicCommand {

    private static final String STYLE_LINE = "Simple centerlines";
    private static final String STYLE_RADII = "Frusta (uses path radii)";

    @Parameter
    private Bvv bvv;

    @Parameter(label = "Rendering style", choices = {STYLE_LINE, STYLE_RADII},
            style = ChoiceWidget.RADIO_BUTTON_VERTICAL_STYLE,
            description = "<html>Centerline rendering is recommended for large datasets.<br>"
                    + "Frusta (tapered tubes) use per-node radii for a more accurate 3D appearance")
    private String renderingStyle;

    @Parameter(label = "Use path radii",
            description = "Sets whether to use path radius for thickness calculation." +
                    "Disable for paths with uniform thickness")
    private boolean usePathRadius;

    @Parameter(label = "Thickness multiplier", min = "0.1", max = "10.0", stepSize = "0.1",
            style = NumberWidget.SLIDER_STYLE,
            description = "Scale factor applied to all path radii. "
                    + "1.0× = natural size; increase if paths appear too thin.")
    private double thicknessMultiplier;

    @Parameter(label = "Min. thickness", min = "0.1", max = "20.0", stepSize = "0.1",
            style = NumberWidget.SLIDER_STYLE, description = "Minimum rendered thickness in physical units. "
            + "Controls line width in line-rendering mode and the floor radius in tube mode.")
    private double minThickness;

    @Parameter(label = "Max. thickness", min = "1", max = "100.0", stepSize = "0.5",
            style = NumberWidget.SLIDER_STYLE,
            description = "Maximum rendered thickness in physical units. "
                    + "Controls line width in line-rendering mode and the ceiling radius in tube mode.")
    private double maxThickness;

    @Parameter(label = "Transparency (%)", min = "0", max = "100", stepSize = "1",
            style = NumberWidget.SLIDER_STYLE, description = "Opacity of all path overlays.\n" +
            "0%: Fully opaque; 100%: fully transparent.")
    private double transparency;

    @Parameter(required = false, label = "Default path color")
    private ColorRGB defaultColor;

    protected void init() {
        final Bvv.PathRenderingOptions opts = bvv.getRenderingOptions();
        renderingStyle = opts.isDisplayRadii() ? STYLE_RADII : STYLE_LINE;
        thicknessMultiplier = opts.getThicknessMultiplier();
        minThickness = opts.getMinThickness();
        maxThickness = opts.getMaxThickness();
        usePathRadius = opts.isUsePathRadius();
        transparency = (1.0 - opts.getTransparency()) * 100; // invert and scale: 0%=opaque, 100%=transparent
        defaultColor = new ColorRGB(opts.fallbackColor.getRed(),
                opts.fallbackColor.getGreen(), opts.fallbackColor.getBlue());
    }

    @Override
    public void run() {
        if (bvv == null) return;
        final Bvv.PathRenderingOptions opts = bvv.getRenderingOptions();
        opts.setThicknessMultiplier((float) thicknessMultiplier);
        opts.setMinThickness((float) minThickness);
        opts.setMaxThickness((float) maxThickness);
        opts.setUsePathRadius(usePathRadius);
        opts.fallbackColor = new java.awt.Color(defaultColor.getRed(), defaultColor.getGreen(), defaultColor.getBlue());
        opts.setTransparency((float) (100 - transparency) / 100);
        // setDisplayRadii also invalidates the overlay cache
        bvv.setDisplayRadii(STYLE_RADII.equals(renderingStyle));
        bvv.syncOverlays();
    }
}
