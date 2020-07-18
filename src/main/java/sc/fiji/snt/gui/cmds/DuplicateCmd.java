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

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.NumberWidget;

import net.imagej.ImageJ;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;

/**
 * GUI command for duplicating a Path.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "Duplicate Path...", initializer = "init")
public class DuplicateCmd extends CommonDynamicCmd {

	@Parameter(label = "Portion to be duplicated (%)", min = "1", max = "100", 
			style = NumberWidget.SCROLL_BAR_STYLE, callback = "updateMsg")
	private double percentage;

	@Parameter(persist = false, label = "Assign to channel")
	private int channel;

	@Parameter(persist = false, label = "Assign to frame")
	private int frame;

	@Parameter(label = "Make primary (disconnect)")
	private boolean disconnect;

	@Parameter(label = "<HTML>&nbsp", persist = false, required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER;

	@Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
	private String msg = "";

	@Parameter(required = true, persist = false)
	private Path path;

	private double length;

	@SuppressWarnings("unused")
	private void init() {
		super.init(true);
		length = path.getLength();
		channel = path.getChannel();
		frame = path.getFrame();
		String name = path.getName();
		if (path.isPrimary()) {
			resolveInput("disconnect");
			getInfo().getMutableInput("disconnect", Boolean.class).setLabel("");
		}
		if (path.size() == 1) {
			percentage = 100;
			name = "single point path";
			resolveInput("percentage");
			resolveInput("msg");
			getInfo().getMutableInput("percentage", Double.class).setLabel("");
			getInfo().getMutableInput("msg", String.class).setLabel("");
		} else if (name.indexOf("[L:") == -1) {
			name += String.format(" [L:%.2f]", length);
		}
		msg = "Duplicating " + name;

	}

	@SuppressWarnings("unused")
	private void updateMsg() {
		if (percentage == 100d)
			msg = String.format("Duplicating full length");
		else
			msg = String.format("aprox. %.2f", percentage / 100 * length);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		int lastIndex = (int) Math.round(percentage / 100 * path.size());
		final Path dup = path.getSection(0, Math.min(lastIndex, path.size() - 1));
		dup.setName("Dup " + path.getName());
		dup.setCTposition(channel, frame);
		if (!disconnect) {
			if (path.getStartJoins() != null)
				dup.setStartJoin(path.getStartJoins(), path.getStartJoinsPoint());
			if (path.getEndJoins() != null)
				dup.setEndJoin(path.getEndJoins(), path.getEndJoinsPoint());
		}
		snt.getPathAndFillManager().addPath(dup, false, true);
		resetUI();
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final SNTService sntService = ij.context().getService(SNTService.class);
		final HashMap<String, Object> inputs = new HashMap<>();
		inputs.put("path", sntService.demoTrees().get(0).get(0));
		ij.command().run(DuplicateCmd.class, true, inputs);
	}

}
