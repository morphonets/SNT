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
import org.scijava.command.InteractiveCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.ColorRGB;
import org.scijava.util.Colors;
import org.scijava.widget.Button;
import org.scijava.widget.NumberWidget;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.viewer.Viewer3D;

import java.awt.*;

/**
 * Implements Reconstruction Viewer's 'Add Annotation Label...' commands.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "Add Annotation Label...", initializer = "init")
public class AddTextAnnotationCmd extends InteractiveCommand {

	@Parameter(label = "Text", required = false, description = "Font size")
	private String string;

	@Parameter(label = "Font style", required = false, description = "Font style", choices = { "Plain", "Italic",
			"Bold" })
	private String styleChoice;

	@Parameter(label = "Font size", required = false, min = "4", max = "100", stepSize = "1", style = NumberWidget.SCROLL_BAR_STYLE)
	private double size;

	@Parameter(label = "Angle", required = false, min = "0", max = "360", style = NumberWidget.SCROLL_BAR_STYLE,
			description = "Text angle")
	private double angle;

	@Parameter(label = "Color", required = false)
	private ColorRGB color;

	@Parameter(label = "Location (X)", required = false)
	private float locationX;

	@Parameter(label = "Location (Y)", required = false)
	private float locationY;

	@Parameter(label = "Reset", required = false, callback="reset")
	private Button reset;


	@Parameter(required = false)
	private Viewer3D recViewer;

	@SuppressWarnings("unused")
	private void init() {
		if (recViewer == null)
			cancel("SNT's Reconstruction Viewer is not available");
		if (color == null)
			color = Colors.RED;
	}

	@SuppressWarnings("unused")
	private void reset() {
		angle = 0;
		color = Colors.RED;
		locationX = 0;
		locationY = 0;
		size = 12;
		string = "";
		run();
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		final Font font = new Font(Font.SANS_SERIF, getStyle(styleChoice), (int) size);
		recViewer.setFont(font, (float) angle, color);
		recViewer.setLabelLocation(locationX, locationY);
		recViewer.addLabel(string);
	}

	private int getStyle(final String style) {
		switch (style.toLowerCase()) {
		case "italic":
			return Font.ITALIC;
		case "bold":
			return Font.BOLD;
		case "plain":
		default:
			return Font.PLAIN;
		}
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(AddTextAnnotationCmd.class, true);
	}

}
