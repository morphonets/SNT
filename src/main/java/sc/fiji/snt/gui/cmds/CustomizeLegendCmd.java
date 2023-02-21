/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
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
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import net.imagej.ImageJ;
import sc.fiji.snt.gui.GuiUtils;

/**
 * Command for customizing a LUT legend in Reconstruction Viewer
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "Customize Legend...")
public class CustomizeLegendCmd extends ContextCommand {

	@Parameter(label = "Min:")
	private double min;

	@Parameter(label = "Max:")
	private double max;

	@Parameter(label = "Font size:")
	private double fSize;

	@Parameter(required = false)
	private HashMap<String, Double> outMap;

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		outMap = new HashMap<>();
		outMap.put("min", min);
		outMap.put("max", max);
		outMap.put("fSize", fSize);
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(CustomizeLegendCmd.class, true);
	}

}
