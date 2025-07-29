/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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
import org.scijava.widget.NumberWidget;
import sc.fiji.snt.Path;
import sc.fiji.snt.PathManagerUI;
import sc.fiji.snt.analysis.PathStatistics;
import sc.fiji.snt.gui.GuiUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;

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

    @Parameter(label = "Type of angle:", choices = { "Relative to parent path", "Absolute (3D)", "Absolute (XY plane)",
            "Absolute (XZ plane)", "Absolute (ZY plane)"})
    private String angleChoice;

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
        final String metric = getMetric();
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
            final double value = switch (metric) {
                case PathStatistics.PATH_EXT_ANGLE_REL -> p.getExtensionAngle3D(true);
                case PathStatistics.PATH_EXT_ANGLE_XY -> p.getExtensionAngleXY();
                case PathStatistics.PATH_EXT_ANGLE_ZY -> p.getExtensionAngleZY();
                case PathStatistics.PATH_EXT_ANGLE_XZ -> p.getExtensionAngleXZ();
                default -> p.getExtensionAngle3D(false);
            };
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

    private String getMetric() {
        return switch (angleChoice) {
            case String s when s.contains("Relative") ->  PathStatistics.PATH_EXT_ANGLE_REL;
            case String s when s.contains("XY") ->  PathStatistics.PATH_EXT_ANGLE_XY;
            case String s when s.contains("XZ") ->  PathStatistics.PATH_EXT_ANGLE_XZ;
            case String s when s.contains("ZY") ->  PathStatistics.PATH_EXT_ANGLE_ZY;
            default -> PathStatistics.PATH_EXT_ANGLE;
        };
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
