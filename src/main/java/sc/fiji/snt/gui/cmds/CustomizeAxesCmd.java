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

import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.util.HashMap;

/**
 * Command for customizing axis-to-anatomy mappings in Reconstruction Viewer.
 * Allows the user to specify which anatomical dimension (Anterior-Posterior,
 * Dorsal-Ventral, Left-Right) corresponds to each world axis (X, Y, Z), and
 * the direction of the dorsal axis. The mapping determines the camera up
 * vector and the axis-swap behavior for constrained 2D views.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "Axes Mapping...")
public class CustomizeAxesCmd extends ContextCommand {

    /** Anatomical axis choices. */
    private static final String AP = "Anterior-Posterior";
    private static final String DV = "Dorsal-Ventral";
    private static final String LR = "Left-Right (ML)";
    private static final String NONE = "None";

    private static final String DIR_POS = "Positive (+)";
    private static final String DIR_NEG = "Negative (-)";

    @Parameter(label = "X axis:", choices = {AP, DV, LR, NONE})
    private String xAxisMapping;

    @Parameter(label = "Y axis:", choices = {AP, DV, LR, NONE})
    private String yAxisMapping;

    @Parameter(label = "Z axis:", choices = {AP, DV, LR, NONE})
    private String zAxisMapping;

    @Parameter(label = "Dorsal direction:", choices = {DIR_POS, DIR_NEG},
            description = "The direction along the Dorsal-Ventral axis in which dorsal values increase.\n"
                    + "Set to 'Negative' if dorsal is at the origin (e.g. Allen CCF).")
    private String dorsalDirection;

    @Parameter(type = ItemIO.OUTPUT, required = false, persist = false)
    private HashMap<String, String> outMap;

    /**
     * Returns the axis identifier ("X", "Y", or "Z") assigned to the
     * Dorsal-Ventral role, or {@code null} if none is assigned.
     */
    public static String getDVAxis(final HashMap<String, String> map) {
        if (DV.equals(map.get("xAxis"))) return "X";
        if (DV.equals(map.get("yAxis"))) return "Y";
        if (DV.equals(map.get("zAxis"))) return "Z";
        return null;
    }

    /**
     * Returns the sign of the dorsal direction: {@code -1} for negative,
     * {@code +1} for positive.
     */
    public static int getDorsalSign(final HashMap<String, String> map) {
        return DIR_NEG.equals(map.get("dorsalDir")) ? -1 : 1;
    }

    /**
     * Returns the axis labels derived from the mapping, suitable for
     * {@code Viewer3D.setAxesLabels()}.
     */
    public static String[] getLabels(final HashMap<String, String> map) {
        return new String[]{
                NONE.equals(map.get("xAxis")) ? "X" : map.get("xAxis"),
                NONE.equals(map.get("yAxis")) ? "Y" : map.get("yAxis"),
                NONE.equals(map.get("zAxis")) ? "Z" : map.get("zAxis")
        };
    }

    @Override
    public void run() {
        outMap = new HashMap<>();
        outMap.put("xAxis", xAxisMapping);
        outMap.put("yAxis", yAxisMapping);
        outMap.put("zAxis", zAxisMapping);
        outMap.put("dorsalDir", dorsalDirection);
    }

}
