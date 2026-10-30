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


import net.imagej.ImageJ;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import sc.fiji.snt.gui.GuiUtils;

import java.util.HashMap;

/**
 * Command for customizing a LUT legend in Reconstruction Viewer
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "Customize Legend...", initializer = "init")
public class CustomizeLegendCmd extends ContextCommand {

	@Parameter(label = "Min:")
	private double min;

	@Parameter(label = "Max:")
	private double max;

	@Parameter(label = "Title:", required = false, persist = false,
			description = "Vertical label beside the tick labels. Empty for none")
	private String label;

	@Parameter(label = "Font size:", min = "1")
	private double fSize;

	@Parameter(label = "Number of ticks:", min = "0", required = false, persist = false,
			description = "0 for automatic placement")
	private int ticks;

	@Parameter(label = "Decimal places:", min = "0", max = "10", required = false, persist = false)
	private int decimals;

	@Parameter(label = "Scientific notation", required = false, persist = false)
	private boolean scientific;

	// Current legend settings (min, max, fSize, label, ticks, decimals, scientific)
	@Parameter(required = false, persist = false)
	private HashMap<String, Object> defaults;

	@Parameter(required = false)
	private HashMap<String, Object> outMap;

	@SuppressWarnings("unused")
	private void init() {
		if (defaults == null)
			return;
		min = ((Number) defaults.getOrDefault("min", min)).doubleValue();
		max = ((Number) defaults.getOrDefault("max", max)).doubleValue();
		fSize = ((Number) defaults.getOrDefault("fSize", fSize)).doubleValue();
		ticks = ((Number) defaults.getOrDefault("ticks", ticks)).intValue();
		decimals = ((Number) defaults.getOrDefault("decimals", decimals)).intValue();
		scientific = (boolean) defaults.getOrDefault("scientific", scientific);
		label = (String) defaults.getOrDefault("label", label);
	}

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
		outMap.put("label", (label == null) ? "" : label.trim());
		outMap.put("ticks", ticks);
		outMap.put("decimals", decimals);
		outMap.put("scientific", scientific);
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.LAF.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(CustomizeLegendCmd.class, true);
	}

}
