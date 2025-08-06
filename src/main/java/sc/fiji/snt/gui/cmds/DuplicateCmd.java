/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.NumberWidget;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;

import java.util.*;

/**
 * GUI command for duplicating a Path.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Duplicate Path...", initializer = "init")
public class DuplicateCmd extends CommonDynamicCmd {

	private final String CHOICE_FULL_LENGTH = "Full length";
	private final String CHOICE_LENGTH = "Use % specified below";
	private final String CHOICE_FIRST_BP = "Duplicate until first branch point";
	private final String CHOICE_LAST_BP = "Duplicate until last branch point";
	private final String CHOICE_NO_CHILDREN = "Do not duplicate child paths";
	private final String CHOICE_IMMEDIATE_CHILDREN = "Duplicate immediate children only";
	private final String CHOICE_ALL_CHILDREN = "Duplicate all children";

	@Parameter(label = "Portion to be duplicated", callback = "portionChoiceChanged",
			choices = {CHOICE_FULL_LENGTH, CHOICE_LENGTH, CHOICE_FIRST_BP, CHOICE_LAST_BP })
	private String portionChoice;

	@Parameter(label = "<HTML>&nbsp;", min = "0", max = "100", 
			style = NumberWidget.SCROLL_BAR_STYLE, callback = "percentageChanged")
	private double percentage;

	@Parameter(label = "Children", callback = "childrenChoiceChanged",
			choices = { CHOICE_NO_CHILDREN, CHOICE_IMMEDIATE_CHILDREN, CHOICE_ALL_CHILDREN },
			description = "NB: If choice includes children, The entire group will be duplicated at full length.")
	private String childrenChoice;

	@Parameter(persist = false, label = "Assign to channel", min="1")
	private int channel;

	@Parameter(persist = false, label = "Assign to frame", min="1")
	private int frame;

	@Parameter(label = "Make primary (disconnect)",
			description="If selected, duplicate path will become primary (root path)")
	private boolean disconnect;

	@Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
	private String msg = "";

	@Parameter(required = true, persist = false)
	private Path path;

	private double length;
	private boolean dupChildren;
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
			resolveInput("childrenChoice");
			getInfo().getMutableInput("childrenChoice", String.class).setLabel("");
		}
		if (path.size() == 1) {
			percentage = 100;
			name = "single point path";
			resolveInput("percentage");
			resolveInput("msg");
			getInfo().getMutableInput("percentage", Double.class).setLabel("");
			getInfo().getMutableInput("msg", String.class).setLabel("");
		} else if (!name.contains("[L:")) {
			name += String.format(" [L:%.2f]", length);
		}
		msg = "Duplicating " + name;
		junctionIndices = path.findJunctionIndices();
	}

	private void portionChoiceChanged() {
		switch (portionChoice) {
		case CHOICE_FULL_LENGTH:
			percentage = 100d;
			break;
		case CHOICE_FIRST_BP:
		case CHOICE_LAST_BP:
			percentage = 0d;
			break;
		default:
			percentage = 50d;
		}
		updatePrompt();
	}

	@SuppressWarnings("unused")
	private void childrenChoiceChanged() {
		dupChildren = !CHOICE_NO_CHILDREN.equals(childrenChoice);
		if (dupChildren) {
			portionChoice = CHOICE_FULL_LENGTH;
			percentage = 100;
			portionChoiceChanged();
		}
	}

	@SuppressWarnings("unused")
	private void percentageChanged() {
		if (percentage == 0d)
			portionChoice = CHOICE_FIRST_BP;
		else if (percentage == 100d) 
			portionChoice = CHOICE_FULL_LENGTH;
		else 
			portionChoice = CHOICE_LENGTH;
		updatePrompt();
	}

	private void updatePrompt() {
		switch((int)percentage) {
		case 100:
			msg = "Duplicating full length";
			dupChildren = childrenChoice != null && !CHOICE_NO_CHILDREN.equals(childrenChoice);
			break;
		case 0:
			if (junctionIndices == null || junctionIndices.isEmpty())
				msg = "Invalid choice: Path has no branch points!";
			else
				msg = "...";
			dupChildren = false;
			break;
		default:
			msg = String.format("Aprox. length: %.2f", percentage / 100 * length);
			dupChildren = false;
			break;
		}
		if (!dupChildren)
			childrenChoice = CHOICE_NO_CHILDREN;		
	}


	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {

		updatePrompt(); // ensure options are up-to-date
		if (dupChildren && path.getChildren() != null && !path.getChildren().isEmpty()) {

			final boolean recursive = CHOICE_ALL_CHILDREN.equals(childrenChoice);
			final Tree dupTree = new Tree(getPathsToDuplicate(recursive)).clone();
			dupTree.list().forEach(dupPath -> dupPath.setCTposition(Math.max(1, channel), Math.max(1, frame)));
			final Path dupParentPath = dupTree.list().get(0);
			connectToAncestorAsNeeded(dupParentPath);
			snt.getPathAndFillManager().addTree(dupTree, "DUP");

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
			case CHOICE_FULL_LENGTH:
				dupIndex = path.size() - 1;
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
			dup.setName("Dup " + dup.getName());
			dup.setCTposition(Math.max(1, channel), Math.max(1, frame));
			snt.getPathAndFillManager().addPath(dup, false, true);

		}

		resetUI();
	}

	private void connectToAncestorAsNeeded(final Path dup) {
		// Disconnect duplicated path by default
		if (dup.getParentPath() != null) dup.detachFromParent();
		// Now connect it to the parent of the original path if requested. Do nothing if original path has no parent
		if (!path.isPrimary() && !disconnect) {
			if (path.getParentPath() != null) {
				dup.setBranchFrom(path.getParentPath(), path.getBranchPoint());
			}
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

	private List<Path> getPathsToDuplicate(final boolean allChildren) {
		final List<Path> pathsToClone = new ArrayList<>();
		pathsToClone.add(path);
		appendChildren(pathsToClone, path, allChildren);
		return pathsToClone;
	}

	private void appendChildren(final List<Path> set, final Path p, final boolean recursive) {
		if (p.getChildren() != null && !p.getChildren().isEmpty()) {
			p.getChildren().forEach(child -> {
				set.add(child);
				if (recursive)
					appendChildren(set, child, recursive);
			});
		}
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
