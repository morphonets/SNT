/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2022 Fiji developers.
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

package sc.fiji.snt.gui;

import java.awt.Color;
import java.awt.Component;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import javax.swing.JButton;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JToggleButton;
import javax.swing.SwingWorker;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.scijava.command.CommandModule;
import org.scijava.command.CommandService;

import sc.fiji.snt.Path;
import sc.fiji.snt.PathManagerUI;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.gui.cmds.SWCTypeFilterCmd;

/**
 * Implements the customized SearchableBar used by {@link PathManagerUI},
 * including GUI commands for selection and morphological filtering of Paths.
 *
 * @author Tiago Ferreira
 */
public class PathManagerUISearchableBar extends SNTSearchableBar {

	private static final long serialVersionUID = 1L;
	private final PathManagerUI pmui;
	private final GuiUtils guiUtils;
	private boolean subFilteringEnabled;

	/**
	 * Creates PathManagerUI's SearchableBar
	 *
	 * @param pmui the PathManagerUI instance
	 */
	public PathManagerUISearchableBar(final PathManagerUI pmui) {
		super(pmui.getSearchable(), "Text filtering:");
		this.pmui = pmui;
		guiUtils = new GuiUtils(pmui);
		setGuiUtils(guiUtils);
		setSearcheableObjectDescription("Paths");
		setFindAndReplaceMenuItem(createFindAndReplaceMenuItem());
		_extraButtons = new ArrayList<>();
		_extraButtons.add(createColorFilteringButton());
		_extraButtons.add(createMorphoFilteringButton());
		_extraButtons.add(createSubFilteringButton());
		setVisibleButtons(SHOW_STATUS | SHOW_SEARCH_OPTIONS | SHOW_HIGHLIGHTS);
		setStatusLabelPlaceholder(String.format("%d Path(s) listed", pmui
			.getPathAndFillManager().size()));
		_highlightsButton.setToolTipText("Highlight all: Auto-select paths matching filtered text");
	}

	private JMenuItem createFindAndReplaceMenuItem() {
		JMenuItem mi = new JMenuItem("Replace...");
		mi.addActionListener(e -> {
			String findText = getSearchingText();
			if (findText == null || findText.isEmpty()) {
				guiUtils.error("No filtering string exists.", "No Filtering String");
				return;
			}
			final boolean clickOnHighlightAllNeeded = !isHighlightAll();
			if (clickOnHighlightAllNeeded) _highlightsButton.doClick();
			final Collection<Path> selectedPath = pmui.getSelectedPaths(false);
			if (selectedPath.isEmpty()) {
				guiUtils.error("No Paths matching '" + findText + "'.",
					"No Paths Selected");
				return;
			}
			final String replaceText = guiUtils.getString(
				"Please specify the text to replace all ocurrences of\n" + "\"" +
					findText + "\" in the " + selectedPath.size() +
					" Path(s) currently selected:", "Replace Filtering Pattern", null);
			if (replaceText == null) {
				if (clickOnHighlightAllNeeded) _highlightsButton.doClick(); // restore status
				return; // user pressed cancel
			}
			if (getSearchable().isWildcardEnabled()) {
				findText = findText.replaceAll("\\?", ".?");
				findText = findText.replaceAll("\\*", ".*");
			}
			if (!getSearchable().isCaseSensitive()) {
				findText = "(?i)" + findText;
			}
			try {
				final Pattern pattern = Pattern.compile(findText);
				for (final Path p : selectedPath) {
					p.setName(pattern.matcher(p.getName()).replaceAll(replaceText));
				}
				pmui.update();
			}
			catch (final IllegalArgumentException ex) { // PatternSyntaxException  etc.
				guiUtils.error("Replacement pattern not valid: " + ex.getMessage());
			} finally {
				if (clickOnHighlightAllNeeded) _highlightsButton.doClick(); // restore status
			}
		});
		return mi;
	}

	private JMenu getImageFilterMenu() {
		final JMenu imgFilteringMenu = new JMenu("Select by Image Property");
		imgFilteringMenu.setIcon(IconFactory.getMenuIcon(
			IconFactory.GLYPH.IMAGE));
		JMenuItem mi1 = new JMenuItem("Traced channel...");
		mi1.addActionListener(e -> doImageFiltering("Traced channel"));
		imgFilteringMenu.add(mi1);
		mi1 = new JMenuItem("Traced frame...");
		mi1.addActionListener(e -> doImageFiltering("Traced frame"));
		imgFilteringMenu.add(mi1);
		mi1 = new JMenuItem("Z-slice of first node...");
		mi1.addActionListener(e -> doImageFiltering("Z-slice of first node"));
		imgFilteringMenu.add(mi1);
		return imgFilteringMenu;
	}

	private JMenu getMorphoFilterMenu() {
		final JMenu morphoFilteringMenu = new JMenu("Select by Morphological Trait");
		morphoFilteringMenu.setIcon(IconFactory.getMenuIcon(
			IconFactory.GLYPH.RULER));
		JMenuItem mi1 = new JMenuItem("Cell ID...");
		mi1.addActionListener(e -> {
			final Collection<Path> paths = getPaths();
			if (paths.isEmpty()) {
				guiUtils.error("There are no traced paths.");
				return;
			}
			final HashSet<String> ids = new HashSet<>();
			paths.forEach(p->{
				final String treeID = p.getTreeLabel();
				ids.add(treeID);
			});
			if (ids.isEmpty()) {
				guiUtils.error("No Cell IDs have been specified.");
				return;
			}
			final String[] choices = ids.toArray(new String[ids.size()]);
			final String defChoice = pmui.getSNT().getPrefs().getTemp("cellidfilter", choices[0]);
			final String chosenID = guiUtils.getChoice("Select Paths from which cell?", "Cell ID Filtering", choices,
					defChoice);
			if (chosenID == null)
				return;
			pmui.getSNT().getPrefs().setTemp("cellidfilter", chosenID);
			paths.removeIf(path -> !chosenID.equals(path.getTreeLabel()));
			if (paths.isEmpty()) {
				guiUtils.error("No Path matches the specified ID.");
				return;
			}
			pmui.setSelectedPaths(paths, this);
			guiUtils.tempMsg(paths.size() + " Path(s) selected");
		});
		morphoFilteringMenu.add(mi1);
		mi1 = new JMenuItem(TreeStatistics.PATH_CONTRACTION + "...");
		mi1.addActionListener(e -> doMorphoFiltering(TreeStatistics.PATH_CONTRACTION, ""));
		morphoFilteringMenu.add(mi1);
		mi1 = new JMenuItem(TreeStatistics.PATH_LENGTH);
		mi1.addActionListener(e -> {
			final String unit = pmui.getPathAndFillManager().getBoundingBox(false)
				.getUnit();
			doMorphoFiltering(TreeStatistics.PATH_LENGTH, unit);
		});
		morphoFilteringMenu.add(mi1);
		mi1 = new JMenuItem(TreeStatistics.N_NODES + "...");
		mi1.addActionListener(e -> doMorphoFiltering(TreeStatistics.N_NODES, ""));
		morphoFilteringMenu.add(mi1);
		mi1 = new JMenuItem(TreeStatistics.N_SPINES + "...");
		mi1.addActionListener(e -> doMorphoFiltering(TreeStatistics.N_SPINES, ""));
		morphoFilteringMenu.add(mi1);
		mi1 = new JMenuItem(TreeStatistics.PATH_MEAN_RADIUS + "...");
		mi1.addActionListener(e -> doMorphoFiltering(TreeStatistics.PATH_MEAN_RADIUS, ""));
		morphoFilteringMenu.add(mi1);
		mi1 = new JMenuItem(TreeStatistics.PATH_ORDER + "...");
		mi1.addActionListener(e -> doMorphoFiltering(TreeStatistics.PATH_ORDER, ""));
		morphoFilteringMenu.add(mi1);
		mi1 = new JMenuItem("SWC type...");
		mi1.addActionListener(e -> {
			final Collection<Path> paths = getPaths();
			if (paths.size() == 0) {
				guiUtils.error("There are no traced paths.");
				return;
			}
			class GetFilteredTypes extends SwingWorker<Object, Object> {

				CommandModule cmdModule;

				@Override
				public Object doInBackground() {
					final CommandService cmdService = pmui.getSNT()
						.getContext().getService(CommandService.class);
					try {
						cmdModule = cmdService.run(SWCTypeFilterCmd.class, true).get();
					}
					catch (InterruptedException | ExecutionException ignored) {
						return null;
					}
					return null;
				}

				@Override
				protected void done() {
					final Set<Integer> types = SWCTypeFilterCmd.getChosenTypes(pmui
						.getSNT().getContext());
					if ((cmdModule != null && cmdModule.isCanceled()) || types == null ||
						types.isEmpty())
					{
						return; // user pressed cancel or chose nothing
					}
					paths.removeIf(path -> !types.contains(path.getSWCType()));
					if (paths.isEmpty()) {
						guiUtils.error("No Path matches the specified type(s).");
						return;
					}
					pmui.setSelectedPaths(paths, this);
					guiUtils.tempMsg(paths.size() + " Path(s) selected");
				}
			}
			(new GetFilteredTypes()).execute();
		});
		morphoFilteringMenu.add(mi1);
		return morphoFilteringMenu;
	}

	private ColorMenu getColorFilterMenu() {
		final ColorMenu colorFilterMenu = new ColorMenu("Filter by color tags");
		colorFilterMenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.COLOR));
		colorFilterMenu.addActionListener(e -> {
			final Collection<Path> filteredPaths = getPaths();
			if (filteredPaths.isEmpty()) {
				guiUtils.error("There are no traced paths.");
				return;
			}
			final Color filteredColor = colorFilterMenu.getSelectedSWCColor().color();
			for (final Iterator<Path> iterator = filteredPaths.iterator(); iterator
				.hasNext();)
			{
				final Color color = iterator.next().getColor();
				if ((filteredColor != null && color != null && !filteredColor.equals(
					color)) || (filteredColor == null && color != null) ||
					(filteredColor != null && color == null))
				{
					iterator.remove();
				}
			}
			if (filteredPaths.isEmpty()) {
				guiUtils.error("No Path matches the specified color tag.");
				return;
			}
			pmui.setSelectedPaths(filteredPaths, this);
			guiUtils.tempMsg(filteredPaths.size() + " Path(s) selected");
			// refreshManager(true, true);
		});
		return colorFilterMenu;
	}

	private void doImageFiltering(final String property) {
//		final boolean invalidImage = !pmui.getSNT().accessToValidImageData();
//		if (invalidImage) {
//			guiUtils.error("There is currently no valid image data to process.");
//			return;
//		}
		final Collection<Path> filteredPaths = getPaths();
		if (filteredPaths.isEmpty()) {
			guiUtils.error("There are no traced paths.");
			return;
		}
		final String msg = "Please specify a list or range for '" + property + "' (e.g. 2, 3-5, 6, 7-9):";
		final String s = guiUtils.getString(msg, property + " Filtering", "");
		if (s == null)
			return; // user pressed cancel
		final Set<Integer> set = new HashSet<>();
		try {
			for (final String value : s.split(",\\s*")) {
				if (value.indexOf("-") != -1) {
					final String[] limits = value.split("-\\s*");
					if (limits.length != 2) {
						guiUtils.error("Input contained an invalid range.");
						return;
					}
					IntStream.rangeClosed(Integer.valueOf(limits[0].trim()), Integer.valueOf(limits[1].trim())).forEach(v -> {
						set.add(v);
					});
				} else {
					set.add(Integer.valueOf(value.trim()));
				}
			}
		} catch (final NumberFormatException ignored) {
			guiUtils.error("Invalid list or range. Example of a valid input list: 2, 3-5, 6, 7-9");
			return;
		}
		for (final Iterator<Path> iterator = filteredPaths.iterator(); iterator.hasNext();) {
			final Path p = iterator.next();
			int value;
			switch (property.toLowerCase()) {
			case "z-slice of first node":
				value = p.getZUnscaled(0) + 1;
			case "traced channel":
				value = p.getChannel();
				break;
			case "traced frame":
				value = p.getFrame();
				break;
			default:
				throw new IllegalArgumentException("Unrecognized parameter");
			}
			if (!set.contains(value))
				iterator.remove();
		}
		if (filteredPaths.isEmpty()) {
			guiUtils.error("No Path matches the specified list or range.");
			return;
		}
		pmui.setSelectedPaths(filteredPaths, this);
		guiUtils.tempMsg(filteredPaths.size() + " Path(s) selected");
	}

	private void doMorphoFiltering(final String property, final String unit) {
		final Collection<Path> filteredPaths = getPaths();
		if (filteredPaths.isEmpty()) {
			guiUtils.error("There are no traced paths.");
			return;
		}
		String msg = "Please specify the " + property.toLowerCase() + " range";
		if (!unit.isEmpty()) msg += " (in " + unit + ")";
		msg += "\n(e.g., 10-50, min-10, 10-max, max-max):";
		String s = guiUtils.getString(msg, property + " Filtering", "10-100");
		if (s == null) return; // user pressed cancel
		s = s.toLowerCase();
		if (s.indexOf("-") == -1) s = s + "-"+ s;

		double min = Double.MIN_VALUE;
		double max = Double.MAX_VALUE;
		if (s.contains("min") || s.contains("max")) {
			final TreeStatistics treeStats = new TreeStatistics(new Tree(filteredPaths));
			final SummaryStatistics summary = treeStats.getSummaryStats(property);
			min = summary.getMin();
			max = summary.getMax();
		}
		final double[] values = new double[] { min, max };
		try {
			final String[] stringValues = s.toLowerCase().split("-");
			for (int i = 0; i < values.length; i++) {
				if (stringValues[i].contains("min")) values[i] = min;
				else if (stringValues[i].contains("max")) values[i] = max;
				else values[i] = Double.parseDouble(stringValues[i]);
			}
			if (values[1] < values[0]) {
				guiUtils.error("Invalid range: Upper limit smaller than lower limit.");
				return;
			}
		}
		catch (final Exception ignored) {
			guiUtils.error(
				"Invalid range. Example of valid inputs: 10-100, min-10, 100-max, max-max.");
			return;
		}
		doMorphoFiltering(filteredPaths, property, values[0], values[1]);
	}

	public void doMorphoFiltering(final  Collection<Path> paths, final String property, final Number min, final Number max) {
		for (final Iterator<Path> iterator = paths.iterator(); iterator
			.hasNext();)
		{
			final Path p = iterator.next();
			double value;
			switch (property) {
				case TreeStatistics.PATH_LENGTH:
				case "Length":
					value = p.getLength();
					break;
				case TreeStatistics.N_NODES:
					value = p.size();
					break;
				case TreeStatistics.PATH_MEAN_RADIUS:
					value = p.getMeanRadius();
					break;
				case TreeStatistics.PATH_ORDER:
					value = p.getOrder();
					break;
				case TreeStatistics.N_SPINES:
					value = p.getSpineOrVaricosityCount();
					break;
				case TreeStatistics.PATH_CONTRACTION:
					value = p.getContraction();
					break;
				default:
					throw new IllegalArgumentException("Unrecognized parameter");
			}
			if (value < min.doubleValue() || value > max.doubleValue()) iterator.remove();
		}
		if (paths.isEmpty()) {
			guiUtils.error("No Path matches the specified range.");
			return;
		}
		pmui.setSelectedPaths(paths, this);
		guiUtils.tempMsg(paths.size() + " Path(s) selected");
		// refreshManager(true, true);
	}

	private JButton createMorphoFilteringButton() {
		final JButton button = new JButton();
		formatButton(button, IconFactory.GLYPH.RULER);
		button.setToolTipText("Filter by morphometric traits or image properties");
		final JPopupMenu popup = new JPopupMenu();
		GuiUtils.addSeparator(popup, "Morphometric Traits:");
		for (final Component component : getMorphoFilterMenu().getMenuComponents()) {
			popup.add(component);
		}
		GuiUtils.addSeparator(popup, "Image Properties:");
		for (final Component component : getImageFilterMenu().getMenuComponents()) {
			popup.add(component);
		}
		button.addActionListener(e -> popup.show(button, button.getWidth() / 2,
		button.getHeight() / 2));
		return button;
	}

	private JButton createColorFilteringButton() {
		final JButton button = new JButton();
		formatButton(button, IconFactory.GLYPH.COLOR);
		final ColorMenu colorFilterMenu = getColorFilterMenu();
		button.setToolTipText(colorFilterMenu.getText());
		final JPopupMenu popupMenu = colorFilterMenu.getPopupMenu();
		popupMenu.setInvoker(colorFilterMenu);
		button.addActionListener(e -> {
			popupMenu.show(button, button.getWidth() / 2, button.getHeight() / 2);
		});
		return button;
	}

	private JToggleButton createSubFilteringButton() {
		final JToggleButton button = new JToggleButton();
		formatButton(button, IconFactory.GLYPH.FILTER);
		button.setToolTipText("Restrict filtering to selected Paths. This allows\n"
				+ "combining multiple criteria to further restrict matches");
		button.setRequestFocusEnabled(false);
		button.setFocusable(false);
		button.addActionListener(e -> {
			if (pmui.getJTree().isSelectionEmpty()) {
				guiUtils.error("There are no selected Paths.");
				button.setSelected(false);
			} else {
				subFilteringEnabled = button.isSelected();
			}
		});
		// There is a logic for when the "Highlights All" button is enabled. Apply it here too:
		_highlightsButton.addPropertyChangeListener("enabled", new PropertyChangeListener() {
			@Override
			public void propertyChange(final PropertyChangeEvent evt) {
				button.setEnabled(_highlightsButton.isEnabled());
			}
		});
		return button;
	}

	private Collection<Path> getPaths() {
		return (subFilteringEnabled) ? pmui.getSelectedPaths(true) : pmui.getPathAndFillManager()
				.getPathsFiltered();
	}


	/* IDE Debug method */
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		PathManagerUI.main(args);
	}
}
