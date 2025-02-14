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

package sc.fiji.snt.gui;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.scijava.command.CommandModule;
import org.scijava.command.CommandService;
import sc.fiji.snt.Path;
import sc.fiji.snt.PathManagerUI;
import sc.fiji.snt.analysis.PathStatistics;
import sc.fiji.snt.gui.cmds.FilterOrTagPathsByAngleCmd;
import sc.fiji.snt.gui.cmds.SWCTypeFilterCmd;
import sc.fiji.snt.util.SNTColor;

import javax.swing.*;
import java.awt.*;
import java.io.Serial;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Implements the customized SearchableBar used by {@link PathManagerUI},
 * including GUI commands for selection and morphological filtering of Paths.
 *
 * @author Tiago Ferreira
 */
public class PathManagerUISearchableBar extends SNTSearchableBar {

	@Serial
	private static final long serialVersionUID = 1L;
	private final PathManagerUI pmui;
	private final GuiUtils guiUtils;

	/**
	 * Creates PathManagerUI's SearchableBar
	 *
	 * @param pmui the PathManagerUI instance
	 */
	public PathManagerUISearchableBar(final PathManagerUI pmui) {
		super(pmui.getSearchable(), "Text filtering");
		this.pmui = pmui;
		guiUtils = new GuiUtils(pmui);
		setGuiUtils(guiUtils);
		setSearchableObjectDescription("Paths");
		setFindAndReplaceMenuItem(createFindAndReplaceMenuItem());
		_extraButtons = new ArrayList<>();
		_extraButtons.add(createSWCTypeFilteringButton());
		_extraButtons.add(createColorFilteringButton());
		_extraButtons.add(createMorphoFilteringButton());
		_extraButtons.add(createSubFilteringButton());
		setVisibleButtons(SHOW_STATUS | SHOW_HIGHLIGHTS);
		setStatusLabelPlaceholder(String.format("%d Path(s) listed", pmui
			.getPathAndFillManager().size()));
		_highlightsButton.setToolTipText("Highlight all: Auto-select paths matching filtered text");
	}

	@Override
	JToggleButton createSubFilteringButton() {
		final JToggleButton button = super.createSubFilteringButton();
		button.addActionListener( e -> {
			if (pmui.getSNT().getUI().getRecorder(false) != null)
				pmui.getSNT().getUI().getRecorder(false).recordCmd(
					String.format("snt.getUI().getPathManager().setCombinedSelectionFilters(%b)", button.isSelected()));
		});
		return button;
	}

	private void logSelectionCount(final int count) {
		_statusLabel.setText( (subFilteringEnabled) ? "⫧ " + count + " Match(es)" : count + " Match(es)");
	}

	private JMenuItem createFindAndReplaceMenuItem() {
		final JMenuItem mi = new JMenuItem("Find and Replace...");
		mi.addActionListener(e -> {
			final String[] labels = { "<HTML>Find", "<HTML>Replace&nbsp;" };
			if (getSearchable().isCaseSensitive())
				labels[0] += " <i>[Cc]</i> ";
			if (getSearchable().isWildcardEnabled())
				labels[0] += " <i>[.*]</i> ";
			labels[0] += "&nbsp;";
			final String[] defaults = { getSearchingText(), "" };
			final String[] findReplace = guiUtils.getStrings("Replace by Pattern...", labels, defaults);
			if (findReplace == null || findReplace[0] == null || findReplace[0].isEmpty())
				return; // user pressed cancel or chose no inputs
			setSearchingText(findReplace[0]);
			final boolean clickOnHighlightAllNeeded = !isHighlightAll();
			if (clickOnHighlightAllNeeded)
				_highlightsButton.doClick();
			final Collection<Path> selectedPath = pmui.getSelectedPaths(false);
			if (selectedPath.isEmpty()) {
				guiUtils.error("No Paths matching '" + findReplace[0] + "'.", "No Paths Selected");
				return;
			}
			if (findReplace[1] == null || findReplace[1].isEmpty())
				return; // nothing to replace
			if (getSearchable().isWildcardEnabled()) {
				findReplace[0] = findReplace[0].replaceAll("\\?", ".?");
				findReplace[0] = findReplace[0].replaceAll("\\*", ".*");
			}
			if (!getSearchable().isCaseSensitive()) {
				findReplace[0] = "(?i)" + findReplace[0];
			}
			try {
				final Pattern pattern = Pattern.compile(findReplace[0]);
				for (final Path p : selectedPath) {
					p.setName(pattern.matcher(p.getName()).replaceAll(findReplace[1]));
				}
				pmui.update();
			} catch (final IllegalArgumentException ex) { // PatternSyntaxException etc.
				guiUtils.error("Replacement pattern not valid: " + ex.getMessage());
			} finally {
				if (clickOnHighlightAllNeeded)
					_highlightsButton.doClick(); // restore status
			}
		});
		return mi;
	}

	private JMenu getImageFilterMenu() {
		final JMenu imgFilteringMenu = new JMenu("Select by Image Property");
		imgFilteringMenu.setIcon(IconFactory.menuIcon(
			IconFactory.GLYPH.IMAGE));
		JMenuItem mi1 = new JMenuItem("Traced channel...");
		mi1.addActionListener(e -> doImageFiltering("Traced channel"));
		mi1.setIcon(IconFactory.menuIcon('C', false));
		imgFilteringMenu.add(mi1);
		mi1 = new JMenuItem("Traced frame...");
		mi1.addActionListener(e -> doImageFiltering("Traced frame"));
		mi1.setIcon(IconFactory.menuIcon('T', false));
		imgFilteringMenu.add(mi1);
		mi1 = new JMenuItem("Z-slice of first node...");
		mi1.addActionListener(e -> doImageFiltering("Z-slice of first node"));
		mi1.setIcon(IconFactory.menuIcon('Z', false));
		imgFilteringMenu.add(mi1);
		mi1 = new JMenuItem("Z-slice of last node...");
		mi1.addActionListener(e -> doImageFiltering("Z-slice of last node"));
		mi1.setIcon(IconFactory.menuIcon('Z', true));
		imgFilteringMenu.add(mi1);
		return imgFilteringMenu;
	}

	private JMenu getMorphoFilterMenu() {
		final JMenu morphoFilteringMenu = new JMenu("Select by Morphological Trait");
		morphoFilteringMenu.setIcon(IconFactory.menuIcon( IconFactory.GLYPH.RULER));
		JMenuItem mi1 = new JMenuItem("Cell ID...");
		mi1.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.ID_ALT));
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
			final String[] choices = ids.toArray(new String[0]);
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
			logSelectionCount(paths.size());
		});
		morphoFilteringMenu.add(mi1);
		morphoFilteringMenu.add(angleFilterMenuItem());
		morphoFilteringMenu.add(morphoFilterMenuItem(PathStatistics.N_CHILDREN, ""));
		morphoFilteringMenu.add(morphoFilterMenuItem(PathStatistics.N_NODES, ""));
		morphoFilteringMenu.add(morphoFilterMenuItem(PathStatistics.N_SPINES, ""));
		morphoFilteringMenu.add(morphoFilterMenuItem(PathStatistics.PATH_CONTRACTION, ""));
		morphoFilteringMenu.add(morphoFilterMenuItem(PathStatistics.PATH_LENGTH,
				pmui.getPathAndFillManager().getBoundingBox(false).getUnit()));
		morphoFilteringMenu.add(morphoFilterMenuItem(PathStatistics.PATH_MEAN_RADIUS, ""));
		morphoFilteringMenu.add(morphoFilterMenuItem(PathStatistics.PATH_ORDER, ""));
		return morphoFilteringMenu;
	}

	private JMenuItem angleFilterMenuItem() {
		final JMenuItem mi = new JMenuItem("Extension Angle...");
		mi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.ANGLE_RIGHT));
		mi.addActionListener(e -> {
			final Collection<Path> filteredPaths = getPaths();
			if (filteredPaths.isEmpty()) {
				guiUtils.error("There are no traced paths.");
				return;
			}
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("tagOnly", false);
			inputs.put("paths", filteredPaths);
			pmui.getSNT().getUI().runCommand(FilterOrTagPathsByAngleCmd.class, inputs);
		});
		return mi;
	}

	private JMenuItem morphoFilterMenuItem(final String pathAnalyzerMetric, final String unit) {
		final JMenuItem mi = new JMenuItem(pathAnalyzerMetric + "...");
		mi.addActionListener(e -> doMorphoFiltering(pathAnalyzerMetric, unit));
		switch (pathAnalyzerMetric) {
			case PathStatistics.N_CHILDREN:
				mi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.CHILDREN));
				break;
			case PathStatistics.N_NODES:
				mi.setIcon(IconFactory.menuIcon('#', true));
				break;
			case PathStatistics.N_SPINES:
				mi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.MAP_PIN));
				break;
			case PathStatistics.PATH_CONTRACTION:
				mi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.STAIRS));
				break;
			case PathStatistics.PATH_LENGTH:
				mi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.RULER_VERTICAL));
				break;
			case PathStatistics.PATH_MEAN_RADIUS:
				mi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.CIRCLE));
				break;
			case PathStatistics.PATH_ORDER:
				mi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.BRANCH_CODE));
				break;
			default:
				break;
		}
		return mi;
	}

	private ColorMenu getColorFilterMenu() {
		final ColorMenu colorFilterMenu = new ColorMenu("Filter by color tag");
		colorFilterMenu.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.COLOR));
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
			logSelectionCount(filteredPaths.size());
			// refreshManager(true, true);
		});
		return colorFilterMenu;
	}

	private void doImageFiltering(final String property) {
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
				if (value.contains("-")) {
					final String[] limits = value.split("-\\s*");
					if (limits.length != 2) {
						guiUtils.error("Input contained an invalid range.");
						return;
					}
					IntStream.rangeClosed(Integer.parseInt(limits[0].trim()), Integer.parseInt(limits[1].trim())).forEach(set::add);
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
			int value = switch (property.toLowerCase()) {
                case "z-slice of first node" -> p.getZUnscaled(0) + 1;
                case "z-slice of last node" -> p.getZUnscaled(p.size() - 1) + 1;
                case "traced channel" -> p.getChannel();
                case "traced frame" -> p.getFrame();
                default -> throw new IllegalArgumentException("Unrecognized parameter");
            };
            if (!set.contains(value))
				iterator.remove();
		}
		if (filteredPaths.isEmpty()) {
			guiUtils.error("No Path matches the specified list or range.");
			return;
		}
		pmui.setSelectedPaths(filteredPaths, this);
		logSelectionCount(filteredPaths.size());
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
		if (!s.contains("-")) s = s + "-"+ s;

		double min = Double.MIN_VALUE;
		double max = Double.MAX_VALUE;
		if (s.contains("min") || s.contains("max")) {
			final PathStatistics stats = new PathStatistics(filteredPaths, null);
			final SummaryStatistics summary = stats.getSummaryStats(property);
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
		if (pmui.getSNT().getUI() != null && pmui.getSNT().getUI().getRecorder(false) != null) {
			pmui.getSNT().getUI().getRecorder(false)
					.recordCmd(String.format("snt.getUI().getPathManager().applySelectionFilter(\"%s\", %.2f, %.2f)",
							property, values[0], values[1]));
		}
	}

	public void doMorphoFiltering(final  Collection<Path> paths, final String property, final Number min, final Number max) {
		for (final Iterator<Path> iterator = paths.iterator(); iterator
			.hasNext();)
		{
			final Path p = iterator.next();
			double value = switch (property) {
                case PathStatistics.PATH_EXT_ANGLE_XY, PathStatistics.PATH_EXT_ANGLE_REL_XY ->
                        p.getExtensionAngleXY(PathStatistics.PATH_EXT_ANGLE_REL_XY.equals(property));
                case PathStatistics.PATH_EXT_ANGLE_XZ, PathStatistics.PATH_EXT_ANGLE_REL_XZ ->
                        p.getExtensionAngleXZ(PathStatistics.PATH_EXT_ANGLE_REL_XZ.equals(property));
                case PathStatistics.PATH_EXT_ANGLE_ZY, PathStatistics.PATH_EXT_ANGLE_REL_ZY ->
                        p.getExtensionAngleZY(PathStatistics.PATH_EXT_ANGLE_REL_ZY.equals(property));
                case PathStatistics.PATH_LENGTH, "Length" -> p.getLength();
                case PathStatistics.N_NODES -> p.size();
                case PathStatistics.PATH_MEAN_RADIUS -> p.getMeanRadius();
                case PathStatistics.PATH_ORDER -> p.getOrder();
                case PathStatistics.N_SPINES -> p.getSpineOrVaricosityCount();
                case PathStatistics.PATH_CONTRACTION -> p.getContraction();
                case PathStatistics.N_CHILDREN -> p.getChildren().size();
                default -> throw new IllegalArgumentException("Unrecognized parameter");
            };
            if (value < min.doubleValue() || value > max.doubleValue()) iterator.remove();
		}
		if (paths.isEmpty()) {
			guiUtils.error("No Path matches the specified range.");
			return;
		}
		pmui.setSelectedPaths(paths, this);
		logSelectionCount(paths.size());
		// refreshManager(true, true);
	}

	private JButton createMorphoFilteringButton() {
		final JButton button = new JButton();
		formatButton(button, IconFactory.GLYPH.RULER);
		button.setToolTipText("Filter by morphometry or image properties");
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
		button.addActionListener( e -> popupMenu.show(button, button.getWidth() / 2, button.getHeight() / 2));
		colorFilterMenu.addActionListener( e-> {
			final SNTColor color = colorFilterMenu.getSelectedSWCColor();
			if (color != null && pmui.getSNT().getUI().getRecorder(false) != null) {
				pmui.getSNT().getUI().getRecorder(false).recordCmd(
						String.format("snt.getUI().getPathManager().applySelectionFilter(\"%s\")", color));
			}
		});
		return button;
	}

	private JButton createSWCTypeFilteringButton() {
		final JButton button = new JButton();
		formatButton(button, IconFactory.GLYPH.ID);
		button.setToolTipText("Filter by SWC-type(s)");
		button.addActionListener(e -> {
			final Collection<Path> paths = getPaths();
			if (paths.isEmpty()) {
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
					if (pmui.getSNT().getUI().getRecorder(false) != null) {
						final String s = types.stream().map(Object::toString).collect( Collectors.joining(","));
						pmui.getSNT().getUI().getRecorder(false).recordCmd(
							String.format("snt.getUI().getPathManager().applySelectionFilter(\"[%s]\")", s));
					}
					paths.removeIf(path -> !types.contains(path.getSWCType()));
					if (paths.isEmpty()) {
						guiUtils.error("No Path matches the specified type(s).");
						return;
					}

					pmui.setSelectedPaths(paths, this);
					logSelectionCount(paths.size());
				}
			}
			(new GetFilteredTypes()).execute();
		});
		return button;
	}

	 public Collection<Path> getPaths() {
		return (subFilteringEnabled) ? pmui.getSelectedPaths(true) : pmui.getPathAndFillManager()
				.getPathsFiltered();
	}

	/* IDE Debug method */
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		PathManagerUI.main(args);
	}
}
