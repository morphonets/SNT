/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2019 Fiji developers.
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

import java.util.HashMap;

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import net.imagej.ImageJ;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.GraphUtils;
import sc.fiji.snt.gui.GuiUtils;

/**
 * Command for displaying the contents of Path Manager in the "SNT Graph Canvas"
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "Create Dendrogram", initializer = "init")
public class GraphGeneratorCmd extends CommonDynamicCmd {

//	@Parameter(label="Downsampling value", description="<HTML><div WIDTH=500>"
//			+ "The max. permitted distance between nodes. "
//			+ "This will reduce the number of nodes in the dendrogram to facilitate its navigation. "
//			+ "Set it to zero to skip downsampling.")
//	private double dFactor;

	@Parameter(required = false)
	private Tree tree;

	protected void init() {
		if (tree == null) {
			init(true);
			tree = sntService.getTree(false);
			if (tree.isEmpty()) cancel("There are no traced paths.");
		}

	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		try {
//			if (dFactor > 0) {
//				tree = tree.clone();
//				tree.downSample(dFactor);
//			}
			GraphUtils.show(tree.getGraph());
			resetUI();
			status("Righ-click on canvas for options", true);
		} catch (final IllegalArgumentException exc) { // multiple roots, etc..
			error("Could not generate graph: " + exc.getMessage());
		}
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setSystemLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		SNTService sntService = ij.context().getService(SNTService.class);
		final HashMap<String, Object> inputs = new HashMap<>();
		inputs.put("tree", sntService.demoTrees().get(0));
		ij.command().run(GraphGeneratorCmd.class, true, inputs);
	}

}