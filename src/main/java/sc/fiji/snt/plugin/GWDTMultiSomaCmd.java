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

package sc.fiji.snt.plugin;

import org.scijava.command.Command;
import org.scijava.command.Interactive;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;

import javax.swing.*;
import java.awt.*;

/**
 * Interactive command for multi-soma GWDT autotracing. Detects all cell bodies
 * in the image and traces each independently, using exclusion masks to prevent
 * territory overlap between cells.
 *
 * @author Tiago Ferreira
 * @see GWDTMultiSomaCommonCmd
 * @see GWDTMultiSomaFileCmd
 */
@Plugin(type = Command.class, initializer = "init")
public class GWDTMultiSomaCmd extends GWDTMultiSomaCommonCmd implements Interactive {

    private static final String PROMPT_TITLE = "Autotracing Multiple Cells (GWDT)... ";

    @Parameter(label = "   Run   ", callback = "runTrace", description = "<HTML>Run multi-soma autotracing")
    private Button run;

    @SuppressWarnings("unused")
    private void init() {
        getInfo().setLabel(PROMPT_TITLE);
        initForImage();
        initMultiSoma();
    }

    /**
     * Returns whether an instance of this dialog is currently visible.
     *
     * @return true if a dialog with this command's title is showing
     */
    public static boolean isOpen() {
        for (final Window w : JDialog.getWindows()) {
            if (w instanceof JDialog && w.isVisible() && PROMPT_TITLE.equals(((JDialog) w).getTitle())) {
                w.toFront();
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean isFileMode() {
        return false;
    }

    private JDialog getPrompt() {
        return getPromptWithCloseHandler(PROMPT_TITLE);
    }

    @Override
    public void run() {
        // Called on widget change; do nothing. Tracing triggered by runTrace().
        getPrompt(); // ensure close handler is attached early
    }

    @SuppressWarnings("unused")
    private void runTrace() {
        final JDialog prompt = getPrompt();
        if (prompt != null) prompt.dispose();
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                runMultiSoma();
                return null;
            }
        }.execute();
    }
}
