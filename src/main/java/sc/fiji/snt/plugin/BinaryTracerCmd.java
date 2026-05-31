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
import org.scijava.plugin.Plugin;
import sc.fiji.snt.Tree;
import sc.fiji.snt.tracing.auto.BinaryTracer;

import java.util.List;

/**
 * Interactive command providing a GUI for {@link BinaryTracer}-based
 * autotracing when an image is already loaded in SNT. Uses choice widgets
 * to select from open images.
 *
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 * @see BinaryTracerCommonCmd
 * @see BinaryTracerFileCmd
 */
@Plugin(type = Command.class, label = "Automated Tracing: Tree(s) from Segmented Image...", initializer = "init")
public class BinaryTracerCmd extends BinaryTracerCommonCmd {

    @SuppressWarnings("unused")
    private void init() {
        initForImage();
    }

    @Override
    protected boolean isFileMode() {
        return false;
    }

    @Override
    public void run() {
        if (abortRun || isCanceled()) return;
        runCommand();
    }

    @Override
    protected void handleTracedTrees(final List<Tree> trees) {
        // If no display canvas exists or no image is being traced, adopt the
        // chosen image as tracing canvas before the base method adds paths
        if (snt.getImagePlus() == null) {
            // Suppress the 'auto-tracing' prompt for this image. This
            // will be reset once SNT initializes with the new data
            snt.getPrefs().setTemp("autotracing-prompt-armed", false);
            snt.initialize(chosenMaskImp);
        }
        super.handleTracedTrees(trees);
    }
}
