/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2020 Fiji developers.
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
import java.util.Iterator;
import java.util.TreeSet;

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

	private final String CHOICE_LENGTH = "Use % specified below";
	private final String CHOICE_FIRST_BP = "Duplicate until first branch point";
	private final String CHOICE_LAST_BP = "Duplicate until last branch point";

	@Parameter(label = "Portion to be duplicated", callback = "portionChoiceChanged",
			choices = {CHOICE_LENGTH, CHOICE_FIRST_BP, CHOICE_LAST_BP })
	private String portionChoice;

	@Parameter(label = "<HTML>&nbsp;", min = "0", max = "100", 
			style = NumberWidget.SCROLL_BAR_STYLE, callback = "percentageChanged")
	private double percentage;

	@Parameter(persist = false, label = "Assign to channel", min="1")
	private int channel;

	@Parameter(persist = false, label = "Assign to frame", min="1")
	private int frame;

	@Parameter(label = "Duplicate immediate children", callback = "dupChildrenChanged",
			description = "If selected, direct children will also be duplicated. The entire group will be duplicated at full length.")
	private boolean dupChildren;

	@Parameter(label = "Make primary (disconnect)",
			description="If selected, duplicate path will become primary (root path)")
	private boolean disconnect;

	@Parameter(label = "<HTML>&nbsp", persist = false, required = false, visibility = ItemVisibility.MESSAGE)
	private String SPACER;

	@Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
	private String msg = "";

	@Parameter(required = true, persist = false)
	private Path path;

	private double length;
	private TreeSet<Integer> junctionIndices;

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
		if (path.getChildren() == null || path.getChildren().isEmpty()) {
			resolveInput("dupChildren");
			getInfo().getMutableInput("dupChildren", Boolean.class).setLabel("");
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
		junctionIndices = path.findJunctionIndices();
	}

	private void portionChoiceChanged() {
		percentage = (CHOICE_LENGTH.equals(portionChoice)) ? 100 : 0;
		if (percentage != 100d) dupChildren = false;
		updateMsg();
	}

	@SuppressWarnings("unused")
	private void dupChildrenChanged() {
		if (dupChildren) {
			portionChoice = CHOICE_LENGTH;
			percentage = 100;
			portionChoiceChanged();
		}
	}

	@SuppressWarnings("unused")
	private void percentageChanged() {
		portionChoice = (percentage==0d) ? CHOICE_FIRST_BP : CHOICE_LENGTH;
		if (percentage != 100d) dupChildren = false;
		updateMsg();
	}

	private void updateMsg() {
		switch((int)percentage) {
		case 100:
			msg = String.format("Duplicating full length");
			break;
		case 0:
			if (junctionIndices == null || junctionIndices.isEmpty())
				msg = "Invalid choice: Path has no branch points!";
			else
				msg = "...";
			break;
		default:
			msg = String.format("Aprox. length: %.2f", percentage / 100 * length);
			break;
		}
	}

	private int getNthJunction(final int desiredIndex) {
		final Iterator<Integer> itr = junctionIndices.iterator();
		int currentIndex = 0;
		int currentElement = 0;
		while (itr.hasNext()) {
			currentElement = itr.next();
			if (currentIndex == desiredIndex) {
				return currentElement;
			}
			currentIndex++;
		}
		return 0;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {

		if (dupChildren && path.getChildren() != null && !path.getChildren().isEmpty()) {

			final Path dup = path.clone(true);
			connectToAncestorAsNeeded(dup);
			setPropertiesAndAdd(dup, path);
			int idx = 0;
			for (final Path child : path.getChildren()) {
				setPropertiesAndAdd(dup.getChildren().get(idx), child);
				idx++;
			}

		} else {

			// Define the last node index of the duplicate section
			int dupIndex = 0;
			switch (portionChoice) {
			case CHOICE_FIRST_BP:
				if (junctionIndices != null && !junctionIndices.isEmpty()) {
					dupIndex = junctionIndices.first();
					// if we just retrieved the starting node, try the next fork point
					if (dupIndex == 0)
						dupIndex = getNthJunction(1);
				}
				break;
			case CHOICE_LAST_BP:
				if (junctionIndices != null && !junctionIndices.isEmpty()) {
					dupIndex = junctionIndices.last();
				}
				break;
			default:
				dupIndex = (int) Math.round(percentage / 100 * path.size());
				break;
			}
			if (dupIndex == 0 && path.size() > 1) {
				// if we are still stuck on the starting node, assume the user does not
				// want to generate a single-node path: make a full duplication instead
				if (dupIndex == 0)
					dupIndex = path.size() - 1;
			} else {
				dupIndex = Math.min(dupIndex, path.size() - 1);
			}

			// Now make the duplication and add to manager
			final Path dup = path.getSection(0, dupIndex);
			connectToAncestorAsNeeded(dup);
			setPropertiesAndAdd(dup, path);

		}

		resetUI();
	}

	private void connectToAncestorAsNeeded(final Path dup) {

		// Disconnect duplicated path by default
		if (dup.getStartJoins() != null) dup.unsetStartJoin();
		if (dup.getEndJoins() != null) dup.unsetEndJoin();

		// Now connect it to the parent of the original path if requested. Do nothing if original path has no parent
		if (!path.isPrimary() && !disconnect) {
			if (path.getStartJoins() != null) {
				dup.setStartJoin(path.getStartJoins(), path.getStartJoinsPoint());
			}
			if (path.getEndJoins() != null) {
				dup.setEndJoin(path.getEndJoins(), path.getEndJoinsPoint());
			}
		}

	}

	private void setPropertiesAndAdd(final Path dup, final Path original) {
		dup.setName("Dup " + original.getName());
		dup.setCTposition(Math.max(1, channel), Math.max(1, frame));
		snt.getPathAndFillManager().addPath(dup, false, true);
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
