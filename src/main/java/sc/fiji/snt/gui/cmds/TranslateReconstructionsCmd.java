/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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

import java.util.List;

import net.imagej.ImageJ;

import org.scijava.command.Command;
import org.scijava.command.Interactive;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;

import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.viewer.Viewer3D;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.GuiUtils;

/**
 * Implements Reconstruction Viewer's 'Translate...' command.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false,
	label = "Translate Reconstruction(s)", initializer = "init")
public class TranslateReconstructionsCmd extends CommonDynamicCmd implements
	Interactive
{

	@Parameter(label = "X", callback = "preview",
		description = "X offset in physical units")
	private double x;

	@Parameter(label = "Y", callback = "preview",
		description = "Y offset in physical units")
	private double y;

	@Parameter(label = "Z", callback = "preview",
		description = "Z offset in physical units")
	private double z;

	@Parameter(persist = false, label = "Preview/Apply", callback = "preview")
	private boolean preview;

	@Parameter(label = "Reset", callback = "reset",
		description = "Re-places reconstructions at their loaded position")
	private Button reset;

	@Parameter(required = false)
	private Viewer3D recViewer;

	@Parameter(required = false)
	private List<String> treeLabels;

	private double prevX;
	private double prevY;
	private double prevZ;

	protected void init() {
		if (recViewer == null) {
			error("No Reconstruction Viewer specified.");
			return;
		}
		if (recViewer.isSNTInstance()) {
			error("To avoid overwriting data from a tracing session, " +
				"this command is only available in the standalone viewer.");
			return;
		}
		if (treeLabels == null) {
			error("No reconstructions were specified.");
			return;
		}
	}

	@SuppressWarnings("unused")
	private void reset() {
		recViewer.translate(treeLabels, null);
		x = 0;
		y = 0;
		z = 0;
		prevX = 0;
		prevY = 0;
		prevZ = 0;
		SNTUtils.log("Reconstruction(s) re-placed at loaded location(s)");
	}

	@Override
	public void preview() {
		if (preview) run();
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		recViewer.translate(treeLabels, new PointInImage(x - prevX, y - prevY, z -
			prevZ));
		prevX = x;
		prevY = y;
		prevZ = z;
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setSystemLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final Tree tree = new Tree("/home/tferr/code/test-files/AA0100.swc");
		SNTUtils.setDebugMode(true);
		final Viewer3D jzy3D = new Viewer3D(ij.context());
		jzy3D.addTree(tree);
		jzy3D.loadRefBrain("Allen CCF");
		jzy3D.show();
	}

}
