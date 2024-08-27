/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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

import net.imagej.ImageJ;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.ChoiceWidget;
import org.scijava.widget.NumberWidget;
import sc.fiji.snt.Path;
import sc.fiji.snt.PathManagerUI;
import sc.fiji.snt.analysis.PathAnalyzer;
import sc.fiji.snt.gui.GuiUtils;

import java.util.*;

/**
 * Implements {@link PathManagerUI}'s "Extension Angle(s)" prompt.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "Angle Options", initializer = "init")
public class FilterOrTagPathsByAngleCmd extends CommonDynamicCmd {

    @Parameter(label = "Smallest angle:", min = "0", max = "360", stepSize = "30", style = NumberWidget.SLIDER_STYLE)
    private double lowestAngle;

    @Parameter(label = "Largest angle:", min = "0", max = "360", stepSize = "30",style = NumberWidget.SLIDER_STYLE)
    private double largestAngle;

    @Parameter(label = "Plane:", choices = {"XY", "XZ", "ZY"}, style = ChoiceWidget.RADIO_BUTTON_HORIZONTAL_STYLE)
    private String planeChoice;

    @Parameter(label = "Type:", choices = {"Absolute", "Relative to parent path"}, style = ChoiceWidget.RADIO_BUTTON_HORIZONTAL_STYLE)
    private String type;

    @Parameter(required = false)
    private boolean tagOnly;

    @Parameter(required = true)
    Collection<Path> paths;

    @SuppressWarnings("unused")
    private void init() {
        super.init(true);
        if (tagOnly) {
            resolveInput("lowestAngle");
            resolveInput("largestAngle");
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {
        final boolean relative = type != null && type.toLowerCase().contains("relative");
        final String metric = getMetric(relative);
        if (tagOnly) {
            ui.getPathManager().applyDefaultTags(metric);
            if (ui != null && ui.getRecorder(false) != null)
                ui.getRecorder(false).
                        recordCmd(String.format("snt.getUI().getPathManager().applyDefaultTags(\"%s\")", metric));
            resetUI();
            return;
        }
        for (final Iterator<Path> iterator = paths.iterator(); iterator.hasNext();) {
            final Path p = iterator.next();
            double value;
            switch (planeChoice) {
                case "XZ":
                    value = p.getExtensionAngleXZ(relative);
                    break;
                case "ZY":
                    value = p.getExtensionAngleZY(relative);
                    break;
                default:
                    value = p.getExtensionAngleXY(relative);
                    break;
            }
            if (value < lowestAngle || value > largestAngle) {
                iterator.remove();
            }
        }
        ui.getPathManager().applySelectionFilter(metric, lowestAngle, largestAngle);
        if (ui != null && ui.getRecorder(false) != null) {
            ui.getRecorder(false).recordCmd(
                    String.format("snt.getUI().getPathManager().applySelectionFilter(\"%s\", %.2f, %.2f)",
                        metric, lowestAngle, largestAngle));
        }
    }

    private String getMetric(final boolean relative) {
        switch (planeChoice) {
            case "XZ":
                return (relative) ? PathAnalyzer.PATH_EXT_ANGLE_REL_XZ : PathAnalyzer.PATH_EXT_ANGLE_XZ;
            case "ZY":
                return (relative) ? PathAnalyzer.PATH_EXT_ANGLE_REL_ZY : PathAnalyzer.PATH_EXT_ANGLE_ZY;
            default:
                return (relative) ? PathAnalyzer.PATH_EXT_ANGLE_REL_XY : PathAnalyzer.PATH_EXT_ANGLE_XY;
        }
    }

    /* IDE debug method **/
    public static void main(final String[] args) {
        GuiUtils.setLookAndFeel();
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();
		final HashMap<String, Object> inputs = new HashMap<>();
		inputs.put("paths", null);
        ij.command().run(FilterOrTagPathsByAngleCmd.class, true, inputs);
    }

}
