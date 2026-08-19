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

import net.imagej.legacy.LegacyService;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.Types;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.gui.GuiUtils;

/**
 * Implements the 'Open Spot Spine...' command.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false)
public class SpotSpineLoaderCmd implements Command {

    private static final String DOC_PAGE = "https://imagej.net/plugins/spot-spine#installation";
    private static final String CLASS = "spine_sources.Spine3D_All";

    @Parameter
    LegacyService legacyService;

    @Parameter
    SNTService sntService;

    @Override
    public void run() {
        final boolean streamMode = sntService != null && sntService.isStreamMode();
        final boolean isMaterializedCrop = streamMode && sntService.getInstance().isMaterializedCrop();
        if (Types.load(CLASS) != null) {
            if (!streamMode || isMaterializedCrop) {
                legacyService.runLegacyCommand(CLASS, "");
            } else {
                GuiUtils.errorPrompt("This option requires the entire image to be loaded into memory (RAM) or a materialized region.");
            }
            return;
        }
        final String nb = (streamMode)
                ? "<br><br>NB: <i>Spot Spine requires the entire image to be loaded into memory (RAM) or a materialized region."
                : "";
        final boolean openDoc = new GuiUtils().getConfirmation("Spot Spine does not seem to be available. Please " +
                        "install it by following the installation details on imagej.net. Open documentation page now?" + nb,
                "Spot Spine Not Installed", "Yes. Open In Browser", "No. Dismiss");
        if (openDoc)
            legacyService.runLegacyCommand("ij.plugin.BrowserLauncher", DOC_PAGE);
    }

}
