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


import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.ColorRGB;
import org.scijava.util.ColorRGBA;
import org.scijava.widget.NumberWidget;

import net.imagej.ImageJ;
import sc.fiji.snt.gui.GuiUtils;

/**
 * Command for customizing an OBJ mesh in Reconstruction Viewer
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "Customize Meshe(s)...")
public class CustomizeObjCmd extends ContextCommand {

	@Parameter(label = "<HTML><b>Surface:", persist = false, visibility = ItemVisibility.MESSAGE)
	private String msg1 = "";

	@Parameter(label = "Color", required = false)
	private ColorRGB mColor;

	@Parameter(label = "Transparency (%)", min = "0.5", max = "100", style = NumberWidget.SCROLL_BAR_STYLE)
	private double mTransparency;

	@Parameter(label = "Skip mesh customization", description="If checked, only bounding-box settings are applied")
	private boolean skipM;

	@Parameter(label = "<HTML>&nbsp;", required = false, persist = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER;

	@Parameter(label = "<HTML><b>Bounding Box:", persist = false, visibility = ItemVisibility.MESSAGE)
	private String msg2 = "";

	@Parameter(label = "Visible")
	private boolean displayBox;

	@Parameter(label = "Color", required = false)
	private ColorRGB bbColor;

	@Parameter(label = "Transparency (%)", min = "0.5", max = "100", style = NumberWidget.SCROLL_BAR_STYLE)
	private double bbTransparency;

	// this should really be @Parameter(type = ItemIO.OUTPUT), but this
	// supresses the annoying "ignoring unsupported output message
	@Parameter(required = false)
	private ColorRGBA[] colors;
	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		if (mTransparency >= 100) {
			cancel("Surface cannot be fully transparent.");
		}
		colors = new ColorRGBA[2];
		colors[0] = (mColor == null || skipM) ? null : new ColorRGBA(mColor.getRed(), mColor.getGreen(), mColor.getBlue(), getAlpha(mTransparency));
		colors[1] = (!displayBox || bbColor == null || bbTransparency == 100) ? null
				: new ColorRGBA(bbColor.getRed(), bbColor.getGreen(), bbColor.getBlue(), getAlpha(bbTransparency));
	}

	private int getAlpha( final double transparency) {
		int value = 255 - (int) Math.round(transparency * 255 / 100);
		if (value < 0) value = 0;
		if (value > 255) value = 255;
		return value;
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(CustomizeObjCmd.class, true);
	}

}
