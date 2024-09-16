package sc.fiji.snt.gui.cmds;

import net.imagej.legacy.LegacyService;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.Types;
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

    @Override
    public void run() {
        if (Types.load(CLASS) != null) {
            legacyService.runLegacyCommand(CLASS, "");
            return;
        }
        final boolean openDoc = new GuiUtils().getConfirmation("Spot Spine does not seem to be available. Please " +
                        "install it by following the installation details on imagej.net. Open documentation page now?",
                "Spot Spine Not Installed", "Yes. Open In Browser", "No. Dismiss");
        if (openDoc)
            legacyService.runLegacyCommand("ij.plugin.BrowserLauncher", DOC_PAGE);
    }

}
