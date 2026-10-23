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

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.Interactive;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;

import javax.swing.*;
import java.awt.*;

/**
 * Interactive command providing a GUI for GWDT autotracing when an image is
 * already loaded in SNT. Implements {@link Interactive} so that the dialog
 * stays open and responsive; the actual tracing is triggered via a "Run"
 * button callback rather than the standard OK button.
 *
 * @author Tiago Ferreira
 * @see GWDTTracerCommonCmd
 * @see GWDTTracerFileCmd
 */
@Plugin(type = Command.class, initializer = "init")
public class GWDTTracerCmd extends GWDTTracerCommonCmd implements Interactive {

    private static final String PROMPT_TITLE = "Autotracing Grayscale Data (GWDT)... "; // MUST BE UNIQUE for getPrompt() to work

    @Parameter(label = APPLY_DEFAULTS_LABEL, callback = "applyDefaultsCallback", description = APPLY_DEFAULTS_DESCRIPTION)
    private Button applyDefaultsBtn;

    @Parameter(label = LEARN_LABEL, callback = "learnParametersCallback", description = LEARN_DESCRIPTION)
    private Button learnFromSelectedPathsBtn;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String learnFromExampleMsg = " ";

    @Parameter(label = "   Run   ", callback = "runTrace", description = "<HTML>Run autotracing")
    private Button run;

    @SuppressWarnings("unused")
    private void init() {
        getInfo().setLabel(PROMPT_TITLE);
        initForImage();
    }

    /**
     * Returns whether an instance of this dialog is currently visible.
     * Used by SNTUI to enforce singleton behavior.
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
        // Called every time a widget changes in the prompt: Do nothing by default.
        // The actual run is triggered by the runTrace() callback.
        getPrompt(); // ensure close handler is attached early
    }

    @SuppressWarnings("unused")
    private void runTrace() {
        disposeLearnDialog();
        final JDialog prompt = getPrompt();
        if (prompt != null) prompt.dispose();
        // Button callbacks in Interactive commands run on the EDT,
        // so we must offload the heavy tracing work to a background
        // thread; otherwise setStatus()/setCanvasLabelAllPanes() can never repaint.
        final SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                runCommand();
                return null;
            }

            @Override
            protected void done() {
                // Unregister regardless of outcome (normal completion, error, or cancellation) so
                // a stale reference is never cancelled by a later, unrelated abort/exit request
                if (ui != null) ui.registerActiveCmdWorker(null);
            }
        };
        // Registered so SNTUI#abortCurrentOperation()/exitRequested() can cooperatively cancel this
        // worker (Thread.interrupt()) even though the dialog above has already closed and
        // currentState has reverted - see SNTUI#registerActiveCmdWorker()
        if (ui != null) ui.registerActiveCmdWorker(worker);
        worker.execute();
    }

    @SuppressWarnings("unused")
    private void applyDefaultsCallback() {
        applyDefaults();
    }

    @SuppressWarnings("unused")
    private void learnParametersCallback() {
        learnFromSelectedPaths(msg -> learnFromExampleMsg = msg);
    }
}
