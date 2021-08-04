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

package sc.fiji.snt;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import org.scijava.command.CommandModule;
import org.scijava.command.CommandService;
import org.scijava.display.DisplayService;
import org.scijava.prefs.PrefService;
import org.scijava.table.TableDisplay;
import org.scijava.util.ColorRGB;
import org.scijava.util.Colors;

import com.jidesoft.swing.Searchable;
import com.jidesoft.swing.TreeSearchable;

import ij.ImagePlus;
import net.imagej.ImageJ;
import net.imagej.lut.LUTService;
import sc.fiji.snt.analysis.PathProfiler;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.TreeColorMapper;
import sc.fiji.snt.gui.ColorMenu;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.gui.PathManagerUISearchableBar;
import sc.fiji.snt.gui.SwingSafeResult;
import sc.fiji.snt.gui.cmds.DistributionBPCmd;
import sc.fiji.snt.gui.cmds.DistributionCPCmd;
import sc.fiji.snt.gui.cmds.DuplicateCmd;
import sc.fiji.snt.gui.cmds.PathFitterCmd;
import sc.fiji.snt.gui.cmds.SWCTypeOptionsCmd;
import sc.fiji.snt.plugin.AnalyzerCmd;
import sc.fiji.snt.plugin.MultiTreeMapperCmd;
import sc.fiji.snt.plugin.PathAnalyzerCmd;
import sc.fiji.snt.plugin.PathMatcherCmd;
import sc.fiji.snt.plugin.PathSpineAnalysisCmd;
import sc.fiji.snt.plugin.PathTimeAnalysisCmd;
import sc.fiji.snt.plugin.ROIExporterCmd;
import sc.fiji.snt.plugin.SkeletonizerCmd;
import sc.fiji.snt.plugin.SpineExtractorCmd;
import sc.fiji.snt.plugin.TreeMapperCmd;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.util.SNTPoint;
import sc.fiji.snt.util.SWCPoint;
import sc.fiji.snt.gui.GuiUtils;

/**
 * Implements the <i>Path Manager</i> Dialog.
 *
 * @author Tiago Ferreira
 */
public class PathManagerUI extends JDialog implements PathAndFillListener,
	TreeSelectionListener
{

	private static final long serialVersionUID = 1L;
	private static final String FIT_URI = "https://imagej.net/SNT:_Manual#Refine.2FFit";
	private final HelpfulJTree tree;
	private final SNT plugin;
	private final PathAndFillManager pathAndFillManager;
	private SNTTable table;

	protected static final String TABLE_TITLE = "SNT Measurements";
	protected final GuiUtils guiUtils;
	private final JScrollPane scrollPane;
	private final JMenuBar menuBar;
	private final JPopupMenu popup;
	private final JMenu swcTypeMenu;
	private final JMenu tagsMenu;
	private ButtonGroup swcTypeButtonGroup;
	private final ColorMenu colorMenu;
	private final JMenuItem fitVolumeMenuItem;
	private FitHelper fittingHelper;
	private PathManagerUISearchableBar searchableBar;

	/**
	 * Instantiates a new Path Manager Dialog.
	 *
	 * @param plugin the the {@link SNT} instance to be associated
	 *               with this Path Manager. It is assumed that its {@link SNTUI} is
	 *               available.
	 */
	public PathManagerUI(final SNT plugin) {

		super(plugin.getUI(), "Path Manager");
		this.plugin = plugin;
		guiUtils = new GuiUtils(this);
		pathAndFillManager = plugin.getPathAndFillManager();
		pathAndFillManager.addPathAndFillListener(this);

		tree = new HelpfulJTree();
		tree.setRootVisible(false);
		tree.setVisibleRowCount(25);
		tree.setDoubleBuffered(true);
		tree.addTreeSelectionListener(this);
		scrollPane = new JScrollPane();
		scrollPane.getViewport().add(tree);
		add(scrollPane, BorderLayout.CENTER);

		// Create all the menu items:
		final NoPathActionListener noPathListener = new NoPathActionListener();
		final SinglePathActionListener singlePathListener =
			new SinglePathActionListener();
		final MultiPathActionListener multiPathListener =
			new MultiPathActionListener();

		GuiUtils.removeIcon(this);
		menuBar = new JMenuBar();
		setJMenuBar(menuBar);

		final JMenu editMenu = new JMenu("Edit");
		menuBar.add(editMenu);
		editMenu.add(getDeleteMenuItem(multiPathListener));
		editMenu.add(getDuplicateMenuItem(singlePathListener));
		editMenu.add(getRenameMenuItem(singlePathListener));
		editMenu.addSeparator();

		final JMenuItem primaryMitem = new JMenuItem(
			SinglePathActionListener.MAKE_PRIMARY_CMD);
		primaryMitem.setToolTipText("Makes a single selected path primary");
		primaryMitem.addActionListener(singlePathListener);
		editMenu.add(primaryMitem);
		JMenuItem jmi = new JMenuItem(MultiPathActionListener.MERGE_PRIMARY_PATHS_CMD);
		jmi.setToolTipText("Merges selected primary path(s) into a common root");
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.LINK));
		jmi.addActionListener(multiPathListener);
		editMenu.add(jmi);
		editMenu.addSeparator();

		jmi = new JMenuItem(MultiPathActionListener.COMBINE_CMD);
		jmi.setToolTipText("Concatenates 2 or more disconnected paths into a single one");
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.TAPE));
		jmi.addActionListener(multiPathListener);
		editMenu.add(jmi);
		jmi = new JMenuItem(MultiPathActionListener.CONCATENATE_CMD);
		jmi.setToolTipText("Concatenates 2 or more end-connected Paths into a single one. " +
				"All paths must be oriented in the same direction");
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.TAPE));
		jmi.addActionListener(multiPathListener);
		editMenu.add(jmi);
		jmi = new JMenuItem(MultiPathActionListener.DISCONNECT_CMD);
		jmi.setToolTipText("Disconnects paths from all of their connections");
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.UNLINK));
		jmi.addActionListener(multiPathListener);
		editMenu.add(jmi);
		editMenu.addSeparator();

		jmi = new JMenuItem(MultiPathActionListener.SPECIFY_RADIUS_CMD);
		jmi.setToolTipText("Assigns a fixed radius to selected path(s)");
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.CIRCLE));
		jmi.addActionListener(multiPathListener);
		editMenu.add(jmi);
		jmi = new JMenuItem(MultiPathActionListener.SPECIFY_COUNTS_CMD);
		jmi.setToolTipText("Assigns a no. of varicosities/spines to selected path(s)");
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.MAP_PIN));
		jmi.addActionListener(multiPathListener);
		editMenu.add(jmi);
		editMenu.addSeparator();

		jmi = new JMenuItem(MultiPathActionListener.DOWNSAMPLE_CMD);
		jmi.setToolTipText("Reduces the no. of nodes in selected paths (lossy simplification)");
		jmi.addActionListener(multiPathListener);
		editMenu.add(jmi);
		editMenu.addSeparator();

		jmi = new JMenuItem(MultiPathActionListener.REBUILD_CMD);
		jmi.setToolTipText("Re-computes all hierarchical connections in the list");
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.RECYCLE));
		jmi.addActionListener(multiPathListener);
		editMenu.add(jmi);

		tagsMenu = new JMenu("Tag ");
		menuBar.add(tagsMenu);
		swcTypeMenu = new JMenu("Type");
		swcTypeMenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.ID));
		tagsMenu.add(swcTypeMenu);
		assembleSWCtypeMenu(false);
		colorMenu = new ColorMenu(MultiPathActionListener.COLORS_MENU);
		colorMenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.COLOR));
		colorMenu.addActionListener(multiPathListener);
		tagsMenu.add(colorMenu);

		final JMenu imageTagsMenu = new JMenu("Image Metadata");
		imageTagsMenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.IMAGE));
		imageTagsMenu.add(new TagMenuItem(MultiPathActionListener.CHANNEL_TAG_CMD));
		imageTagsMenu.add(new TagMenuItem(MultiPathActionListener.FRAME_TAG_CMD));
		jmi = new JMenuItem(MultiPathActionListener.SLICE_LABEL_TAG_CMD);
		jmi.addActionListener(multiPathListener);
		imageTagsMenu.add(jmi);
		tagsMenu.add(imageTagsMenu);

		final JMenu morphoTagsMenu = new JMenu("Morphometry");
		morphoTagsMenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.RULER));
		morphoTagsMenu.add(new TagMenuItem(MultiPathActionListener.TREE_TAG_CMD));
		morphoTagsMenu.add(new TagMenuItem(MultiPathActionListener.LENGTH_TAG_CMD));
		morphoTagsMenu.add(new TagMenuItem(MultiPathActionListener.MEAN_RADIUS_TAG_CMD));
		morphoTagsMenu.add(new TagMenuItem(MultiPathActionListener.COUNT_TAG_CMD));
		morphoTagsMenu.add(new TagMenuItem(MultiPathActionListener.ORDER_TAG_CMD));
		tagsMenu.add(morphoTagsMenu);
		tagsMenu.addSeparator();

		jmi = new JMenuItem(MultiPathActionListener.CUSTOM_TAG_CMD);
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.PEN));
		jmi.addActionListener(multiPathListener);
		tagsMenu.add(jmi);
		tagsMenu.addSeparator();
		jmi = new JMenuItem(MultiPathActionListener.REMOVE_ALL_TAGS_CMD);
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.TRASH));
		jmi.addActionListener(multiPathListener);
		tagsMenu.add(jmi);

		final JMenu fitMenu = new JMenu("Refine");
		menuBar.add(fitMenu);
		fitVolumeMenuItem = new JMenuItem("Fit Path(s)...");
		fitVolumeMenuItem.setIcon(IconFactory.getMenuIcon(
			IconFactory.GLYPH.CROSSHAIR));
		fitVolumeMenuItem.addActionListener(multiPathListener);
		fitMenu.add(fitVolumeMenuItem);
		jmi = new JMenuItem(SinglePathActionListener.EXPLORE_FIT_CMD);
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.EXPLORE));
		jmi.addActionListener(singlePathListener);
		fitMenu.add(jmi);
		jmi = new JMenuItem(MultiPathActionListener.RESET_FITS, IconFactory.getMenuIcon(
			IconFactory.GLYPH.BROOM));
		jmi.addActionListener(multiPathListener);
		fitMenu.add(jmi);
		fitMenu.addSeparator();
		jmi = new JMenuItem("Parameters...", IconFactory.getMenuIcon(IconFactory.GLYPH.SLIDERS));
		jmi.addActionListener(e -> {
			if (fittingHelper == null) fittingHelper = new FitHelper();
			fittingHelper.showPrompt();
		});
		fitMenu.add(jmi);
		jmi = GuiUtils.menuItemTriggeringURL("<HTML>Help on <i>Fitting", FIT_URI);
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.QUESTION));
		fitMenu.add(jmi);

		final JMenu fillMenu = new JMenu("Fill");
		menuBar.add(fillMenu);
		jmi = new JMenuItem(MultiPathActionListener.FILL_OUT_CMD);
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.FILL));
		jmi.addActionListener(multiPathListener);
		fillMenu.add(jmi);
		fillMenu.addSeparator();
		jmi = GuiUtils.menuItemTriggeringURL("<HTML>Help on <i>Filling", FillManagerUI.FILLING_URI);
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.QUESTION));
		fillMenu.add(jmi);

		final JMenu advanced = new JMenu("Analyze");
		menuBar.add(advanced);

		final JMenu colorMapMenu = new JMenu("Color Coding");
		colorMapMenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.COLOR2));
		advanced.add(colorMapMenu);
		jmi = new JMenuItem(MultiPathActionListener.COLORIZE_PATHS_CMD);
		jmi.setToolTipText("Color codes selected path(s) using connectivity-independent metrics");
		jmi.addActionListener(multiPathListener);
		colorMapMenu.add(jmi);
		jmi = new JMenuItem(MultiPathActionListener.COLORIZE_TREES_CMD);
		jmi.setToolTipText("Color codes selected path(s) assuming valid connectivity between paths");
		jmi.addActionListener(multiPathListener);
		colorMapMenu.add(jmi);

		final JMenu distributionMenu = new JMenu("Frequency Analysis");
		distributionMenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.CHART));
		advanced.add(distributionMenu);
		jmi = new JMenuItem(MultiPathActionListener.HISTOGRAM_PATHS_CMD);
		jmi.addActionListener(multiPathListener);
		distributionMenu.add(jmi);
		jmi = new JMenuItem(MultiPathActionListener.HISTOGRAM_TREES_CMD);
		jmi.addActionListener(multiPathListener);
		distributionMenu.add(jmi);
	
		final JMenu measureMenu = new JMenu("Measurements");
		measureMenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.TABLE));
		advanced.add(measureMenu);
		jmi = new JMenuItem(MultiPathActionListener.MEASURE_PATHS_CMD);
		jmi.setToolTipText("Measures selected path(s), indepently of their connectivity");
		jmi.addActionListener(multiPathListener);
		measureMenu.add(jmi);
		jmi = new JMenuItem(MultiPathActionListener.MEASURE_TREES_CMD);
		jmi.setToolTipText("Measures complete structures assuming valid connectivity between paths");
		jmi.addActionListener(multiPathListener);
		measureMenu.add(jmi);

		advanced.addSeparator();
		jmi = new JMenuItem(MultiPathActionListener.CONVERT_TO_ROI_CMD);
		jmi.addActionListener(multiPathListener);
		advanced.add(jmi);

		jmi = new JMenuItem(MultiPathActionListener.PLOT_PROFILE_CMD);
		jmi.addActionListener(multiPathListener);
		advanced.add(jmi);
		jmi = new JMenuItem(MultiPathActionListener.CONVERT_TO_SKEL_CMD);
		jmi.addActionListener(multiPathListener);
		advanced.add(jmi);
		advanced.addSeparator();

		advanced.add(getSpineUtilsMenu(multiPathListener));
		advanced.add(getTimeSequenceMenu(multiPathListener));

		advanced.addSeparator();
		jmi = new JMenuItem(MultiPathActionListener.CONVERT_TO_SWC_CMD);
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.EXPORT));
		jmi.addActionListener(multiPathListener);
		advanced.add(jmi);

		// Search Bar TreeSearchable
		searchableBar = new PathManagerUISearchableBar(this);
		popup = new JPopupMenu();
		popup.add(getDeleteMenuItem(multiPathListener));
		popup.add(getDuplicateMenuItem(singlePathListener));
		popup.add(getRenameMenuItem(singlePathListener));
		popup.addSeparator();
		JMenuItem pjmi = popup.add(NoPathActionListener.COLLAPSE_ALL_CMD);
		pjmi.addActionListener(noPathListener);
		pjmi = popup.add(NoPathActionListener.EXPAND_ALL_CMD);
		pjmi.addActionListener(noPathListener);
		pjmi = popup.add(NoPathActionListener.SELECT_NONE_CMD);
		pjmi.addActionListener(noPathListener);
		pjmi = popup.add(MultiPathActionListener.APPEND_DIRECT_CHILDREN_CMD);
		pjmi.addActionListener(multiPathListener);
		pjmi = popup.add(MultiPathActionListener.APPEND_ALL_CHILDREN_CMD);
		pjmi.addActionListener(multiPathListener);
		popup.addSeparator();
		final JMenu selectByColorMenu = searchableBar.getColorFilterMenu();
		selectByColorMenu.setText("Select by Color Tag");
		popup.add(selectByColorMenu);
		final JMenu selectByMorphoMenu = searchableBar.getMorphoFilterMenu();
		selectByMorphoMenu.setText("Select by Morphometric Trait");
		popup.add(selectByMorphoMenu);
		tree.setComponentPopupMenu(popup);
		tree.addMouseListener(new MouseAdapter() {

			@Override
			public void mouseReleased(final MouseEvent me) { // Required for Windows
				handleMouseEvent(me);
			}

			@Override
			public void mousePressed(final MouseEvent me) {
				handleMouseEvent(me);
			}

			private void handleMouseEvent(final MouseEvent e) {
				if (!e.isConsumed() && tree.getRowForLocation(e.getX(), e.getY()) == -1) {
					tree.clearSelection(); // Deselect when clicking on 'empty space'
					e.consume();
				}
			}
		});

		add(searchableBar, BorderLayout.PAGE_END);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(final WindowEvent ignored) {
				setVisible(false);
			}
		});
		pack();
	}

	private JMenu getSpineUtilsMenu(final MultiPathActionListener multiPathListener) {
		final JMenu menu = new JMenu("Spine/Varicosity Utilities");
		menu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.MAP_PIN));
		JMenuItem jmi = new JMenuItem(MultiPathActionListener.SPINE_COLOR_CODING_CMD);
		jmi.addActionListener(multiPathListener);
		menu.add(jmi);
		jmi = new JMenuItem(MultiPathActionListener.SPINE_PROFILE_CMD);
		jmi.addActionListener(multiPathListener);
		menu.add(jmi);
		jmi = new JMenuItem(MultiPathActionListener.SPINE_EXTRACT_CMD);
		jmi.addActionListener(multiPathListener);
		menu.add(jmi);
		return menu;
	}

	private JMenu getTimeSequenceMenu(final MultiPathActionListener multiPathListener) {
		final JMenu menu = new JMenu("Time-lapse Utilities");
		menu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.VIDEO));
		JMenuItem jmi = new JMenuItem(MultiPathActionListener.TIME_COLOR_CODING_CMD);
		jmi.addActionListener(multiPathListener);
		menu.add(jmi);
		jmi = new JMenuItem(MultiPathActionListener.MATCH_PATHS_ACROSS_TIME_CMD);
		jmi.addActionListener(multiPathListener);
		menu.add(jmi);
		jmi = new JMenuItem(MultiPathActionListener.TIME_PROFILE_CMD);
		jmi.addActionListener(multiPathListener);
		menu.add(jmi);
		return menu;
	}

	private JMenuItem getRenameMenuItem(final SinglePathActionListener singlePathListener) {
		final JMenuItem renameMitem = new JMenuItem(
			SinglePathActionListener.RENAME_CMD);
		renameMitem.addActionListener(singlePathListener);
		renameMitem.setToolTipText("Renames a single path");
		return renameMitem;
	}

	private JMenuItem getDuplicateMenuItem(final SinglePathActionListener singlePathListener) {
		final JMenuItem duplicateMitem = new JMenuItem(
			SinglePathActionListener.DUPLICATE_CMD);
		duplicateMitem.addActionListener(singlePathListener);
		duplicateMitem.setToolTipText("Duplicates a single path");
		return duplicateMitem;
	}

	private JMenuItem getDeleteMenuItem(final MultiPathActionListener multiPathListener) {
		final JMenuItem deleteMitem = new JMenuItem(
			MultiPathActionListener.DELETE_CMD);
		deleteMitem.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.TRASH));
		deleteMitem.addActionListener(multiPathListener);
		return deleteMitem;
	}

	private void assembleSWCtypeMenu(final boolean applyPromptOptions) {
		swcTypeMenu.removeAll();
		swcTypeButtonGroup = new ButtonGroup();
		final int iconSize = GuiUtils.getMenuItemHeight();
		final SWCTypeOptionsCmd optionsCmd = new SWCTypeOptionsCmd();
		optionsCmd.setContext(plugin.getContext());
		final TreeMap<Integer, Color> map = optionsCmd.getColorMap();
		final boolean assignColors = optionsCmd.isColorPairingEnabled();
		map.forEach((key, value) -> {

			final Color color = (assignColors) ? value : null;
			final ImageIcon icon = GuiUtils.createIcon(color, iconSize, iconSize);
			final JRadioButtonMenuItem rbmi = new JRadioButtonMenuItem(Path
				.getSWCtypeName(key, true), icon);
			rbmi.setName(String.valueOf(key)); // store SWC type flag as name
			swcTypeButtonGroup.add(rbmi);
			rbmi.addActionListener(e -> {
				final Collection<Path> selectedPaths = getSelectedPaths(true);
				if (selectedPaths.size() == 0) {
					guiUtils.error("There are no traced paths.");
					selectSWCTypeMenuEntry(-1);
					return;
				}
				if (tree.getSelectionCount() == 0 && !guiUtils.getConfirmation(
					"Currently no paths are selected. Change type of all paths?",
					"Apply to All?"))
				{
					selectSWCTypeMenuEntry(-1);
					return;
				}
				setSWCType(selectedPaths, key, color);
				refreshManager(true, assignColors, selectedPaths);
			});
			swcTypeMenu.add(rbmi);
		});
		final JMenuItem jmi = new JMenuItem("Options...");
		jmi.addActionListener(e -> {

			class GetOptions extends SwingWorker<Boolean, Object> {

				@Override
				public Boolean doInBackground() {
					try {
						final CommandService cmdService = plugin.getContext().getService(
							CommandService.class);
						final CommandModule cm = cmdService.run(SWCTypeOptionsCmd.class,
							true).get();
						return !cm.isCanceled();
					}
					catch (final InterruptedException | ExecutionException e1) {
						e1.printStackTrace();
					}
					return false;
				}

				@Override
				protected void done() {
					try {
						assembleSWCtypeMenu(get());
					}
					catch (final InterruptedException | ExecutionException exc) {
						exc.printStackTrace();
					}
				}
			}
			(new GetOptions()).execute();

		});
		swcTypeMenu.addSeparator();
		swcTypeMenu.add(jmi);
		if (applyPromptOptions && pathAndFillManager.size() > 0 && guiUtils
			.getConfirmation("Apply new color options to all paths?", "Apply Colors"))
		{
			final List<Path> allPaths = pathAndFillManager.getPathsFiltered();
			if (assignColors) {
				allPaths.forEach(p -> p.setColor(map.get(p.getSWCType())));
			}
			else {
				allPaths.forEach(p -> p.setColor((Color)null));
			}
			refreshManager(false, true, allPaths);
		}
	}

	private void setSWCType(final Collection<Path> paths, final int swcType,
		final Color color)
	{
		for (final Path p : paths) {
			p.setSWCType(swcType);
			p.setColor(color);
		}
	}

	private void resetPathsColor(final Collection<Path> paths) {
		for (final Path p : paths) {
			p.setColor((Color)null);
		}
		refreshManager(true, true, paths);
	}

	private void deletePaths(final Collection<Path> pathsToBeDeleted) {
		final boolean resetIDs = pathsToBeDeleted.size() == pathAndFillManager.size();
		boolean rebuild = false;
		for (final Path p : pathsToBeDeleted) {
			if (plugin !=null && p.isBeingEdited()) plugin.enableEditMode(false);
			if (!p.somehowJoins.isEmpty()) {
				rebuild = true;
			}
			p.disconnectFromAll();
			pathAndFillManager.deletePath(p);
		}
		if (resetIDs) {
			pathAndFillManager.resetIDs();
			plugin.unsavedPaths = false;
		} else if (rebuild) {
			pathAndFillManager.rebuildRelationships();
		}
		refreshManager(false, true, pathAndFillManager.getPathsFiltered());
	}

	/**
	 * Gets the paths currently selected in the Manager's {@link JTree} list as {@link Tree}s.
	 *
	 * @param ifNoneSelectedGetAll if true and no paths are currently selected, all
	 *                             Paths in the list are considered
	 * @return the map of selected paths ({@link Tree#getLabel() as keys}
	 * @see #getSelectedPaths(boolean)
	 */
	public Map<String, Tree> getSelectedPathsOrganizedByTrees(final boolean ifNoneSelectedGetAll) {
		final HashMap<String, Tree> trees = new HashMap<>();
		final Collection<Path> paths = getSelectedPaths(ifNoneSelectedGetAll);
		if (paths.isEmpty()) return trees;
		for (final Path p : paths) {
			final String tLabel = p.getTreeLabel();
			if (trees.get(tLabel) == null) {
				final Tree tree = new Tree();
				tree.setLabel(tLabel);
				tree.add(p);
				trees.put(tLabel, tree);
			} else {
				trees.get(tLabel).add(p);
			}
		}
		return trees;
	}

	/**
	 * Gets the paths currently selected in the Manager's {@link JTree} list.
	 *
	 * @param ifNoneSelectedGetAll if true and no paths are currently selected,
	 *          all Paths in the list will be returned
	 * @return the selected paths. Note that children of a Path are not returned
	 *         if unselected.
	 */
	public List<Path> getSelectedPaths(final boolean ifNoneSelectedGetAll) {
		return SwingSafeResult.getResult(() -> {
			if (ifNoneSelectedGetAll && tree.getSelectionCount() == 0)
				return pathAndFillManager.getPathsFiltered();
			final List<Path> result = new ArrayList<>();
			final TreePath[] selectedPaths = tree.getSelectionPaths();
			if (selectedPaths == null || selectedPaths.length == 0) {
				return result;
			}
			for (final TreePath tp : selectedPaths) {
				final DefaultMutableTreeNode node = (DefaultMutableTreeNode) (tp
					.getLastPathComponent());
				if (!node.isRoot()) {
					final Path p = (Path) node.getUserObject();
					result.add(p);
				}
			}
			return result;
		});
	}

	protected boolean selectionExists() {
		return tree.getSelectionCount() > 0;
	}

	synchronized protected void cancelFit(final boolean updateUIState) {
		if (fittingHelper != null) fittingHelper.cancelFit(updateUIState);
	}

	private void exportSelectedPaths(final Collection<Path> selectedPaths) {

		List<SWCPoint> swcPoints = null;
		try {
			swcPoints = pathAndFillManager.getSWCFor(selectedPaths);
		}
		catch (final SWCExportException see) {
			guiUtils.error("" + see.getMessage());
			return;
		}

		final File saveFile = plugin.getUI().saveFile("Save Paths as SWC...", null, ".swc");
		if (saveFile == null) {
			return; // user pressed cancel
		}

		plugin.statusService.showStatus("Exporting SWC data to " + saveFile
			.getAbsolutePath());

		try {
			final PrintWriter pw = new PrintWriter(new OutputStreamWriter(
				new FileOutputStream(saveFile), StandardCharsets.UTF_8));
			pathAndFillManager.flushSWCPoints(swcPoints, pw);
			pw.close();
		}
		catch (final IOException ioe) {
			guiUtils.error("Saving to " + saveFile.getAbsolutePath() + " failed");
			return;
		}
	}

	private void updateCmdsOneSelected(final Path p) {
		assert SwingUtilities.isEventDispatchThread();
		if (p.getUseFitted()) {
			fitVolumeMenuItem.setText("Un-fit Path");
		}
		else {
			final boolean fitExists = p.getFitted() != null;
			fitVolumeMenuItem.setText((fitExists) ? "Apply Existing Fit"
				: "Fit Path...");
			fitVolumeMenuItem.setToolTipText((fitExists)
				? "<html>Path has never been fitted:<br>Fit will be computed for the first time"
				: "<html>Path has already been fitted:\nCached properties will be aplied");
		}
		colorMenu.selectSWCColor(new SNTColor(p.getColor(), p.getSWCType()));
		selectSWCTypeMenuEntry(p.getSWCType());
	}

	private void updateCmdsManyOrNoneSelected(
		final Collection<Path> selectedPaths)
	{
		assert SwingUtilities.isEventDispatchThread();

		if (allUsingFittedVersion(selectedPaths)) {
			fitVolumeMenuItem.setText("Un-fit Paths");
			fitVolumeMenuItem.setToolTipText(null);
		}
		else {
			fitVolumeMenuItem.setText("Fit Paths...");
			fitVolumeMenuItem.setToolTipText(
				"<html>If fitting has run for a selected path, cached properties<br>" +
					" will be applied, otherwise a new computation will be performed");
		}

		// Update Type & Tags Menu entries only if a real selection exists
		if (tree.getSelectionCount() == 0) {
			colorMenu.selectNone();
			selectSWCTypeMenuEntry(-1);
			return;
		}

		final Path firstPath = selectedPaths.iterator().next();
		final Color firstColor = firstPath.getColor();
		if (!allWithColor(selectedPaths, firstColor)) {
			colorMenu.selectNone();
			return;
		}

		final int type = firstPath.getSWCType();
		if (allWithSWCType(selectedPaths, type)) {
			colorMenu.selectSWCColor(new SNTColor(firstColor, type));
			selectSWCTypeMenuEntry(type);
		}
		else {
			colorMenu.selectColor(firstColor);
			selectSWCTypeMenuEntry(-1);
		}
	}

	private void selectSWCTypeMenuEntry(final int index) {
		if (index < 0) {
			swcTypeButtonGroup.clearSelection();
			return;
		}
		for (final Component component : swcTypeMenu.getMenuComponents()) {
			if (!(component instanceof JRadioButtonMenuItem)) continue;
			final JRadioButtonMenuItem mi = (JRadioButtonMenuItem) component;
			if (Integer.parseInt(mi.getName()) == index) {
				mi.setSelected(true);
				break;
			}
		}
	}

	private boolean allWithSWCType(final Collection<Path> paths, final int type) {
		if (paths == null || paths.isEmpty()) return false;
		for (final Path p : paths) {
			if (p.getSWCType() != type) return false;
		}
		return true;
	}

	private boolean allWithColor(final Collection<Path> paths,
		final Color color)
	{
		if (paths == null || paths.isEmpty()) return false;
		for (final Path p : paths) {
			if (p.getColor() != color) return false;
		}
		return true;
	}

	private boolean allUsingFittedVersion(final Collection<Path> paths) {
		for (final Path p : paths)
			if (!p.getUseFitted()) {
				return false;
			}
		return true;
	}

	/* (non-Javadoc)
	 * @see javax.swing.event.TreeSelectionListener#valueChanged(javax.swing.event.TreeSelectionEvent)
	 */
	@Override
	public void valueChanged(final TreeSelectionEvent e) {
		assert SwingUtilities.isEventDispatchThread();
		if (!pathAndFillManager.enableUIupdates) return;
		final Collection<Path> selectedPaths = getSelectedPaths(true);
		final int selectionCount = tree.getSelectionCount();
		if (selectionCount == 1) {
			final Path p = selectedPaths.iterator().next();
			updateHyperstackPosition(p);
			updateCmdsOneSelected(p);
		}
		else {
			updateCmdsManyOrNoneSelected(selectedPaths);
		}
		pathAndFillManager.setSelected((selectionCount == 0) ? null : selectedPaths,
			this);
	}

	private void updateHyperstackPosition(final Path p) {
		final ImagePlus imp = plugin.getImagePlus();
		if (imp != null) imp.setPosition(p.getChannel(), imp.getZ(), p.getFrame());
	}

	private void displayTmpMsg(final String msg) {
		assert SwingUtilities.isEventDispatchThread();
		guiUtils.tempMsg(msg);
	}

	/* (non-Javadoc)
	 * @see PathAndFillListener#setSelectedPaths(java.util.HashSet, java.lang.Object)
	 */
	@Override
	public void setSelectedPaths(final Collection<Path> selectedPaths,
		final Object source)
	{
		SwingUtilities.invokeLater(new Runnable() {

			@Override
			public void run() {
				if (source == this || !pathAndFillManager.enableUIupdates) return;
				final TreePath[] noTreePaths = {};
				tree.setSelectionPaths(noTreePaths);
				tree.setSelectedPaths(tree.getRoot(), selectedPaths);
			}
		});
	}

	/* (non-Javadoc)
	 * @see PathAndFillListener#setPathList(java.lang.String[], Path, boolean)
	 */
	@Override
	public void setPathList(final List<Path> pathList, final Path justAdded,
		final boolean expandAll)
	{

		SwingUtilities.invokeLater(() -> {

			// Save the selection and expanded states:
			final Set<Path> selectedPathsBefore = tree.getSelectedPaths();
			final Set<Path> expandedPathsBefore = tree.getExpandedPaths();

			/*
			 * Ignore the arguments and get the real path list from the PathAndFillManager:
			 */
			final DefaultMutableTreeNode newRoot = new DefaultMutableTreeNode(HelpfulJTree.ROOT_LABEL);
			final DefaultTreeModel model = new DefaultTreeModel(newRoot);
			final Path[] primaryPaths = pathAndFillManager.getPathsStructured();
			for (final Path primaryPath : primaryPaths) {
				// Add the primary path if it's not just a fitted version of another:
				if (!primaryPath.isFittedVersionOfAnotherPath()) addNode(newRoot, primaryPath, model);
			}
			tree.setModel(model);
			tree.reload();
			// Set back the expanded state:
			if (expandAll)
				GuiUtils.expandAllTreeNodes(tree);
			else {
				expandedPathsBefore.add(justAdded);
				tree.setExpandedPaths(expandedPathsBefore);
			}

			// Set back the selection state
			tree.setSelectedPaths(newRoot, selectedPathsBefore);
		});
	}

	private void addNode(final MutableTreeNode parent, final Path childPath,
		final DefaultTreeModel model)
	{
		assert SwingUtilities.isEventDispatchThread();
		final MutableTreeNode newNode = new DefaultMutableTreeNode(childPath);
		model.insertNodeInto(newNode, parent, parent.getChildCount());
		for (final Path p : childPath.children)
			addNode(newNode, p, model);
	}

	protected Tree getSingleTree() {
		return getSingleTreePrompt((this.hasFocus()) ? guiUtils : new GuiUtils(plugin.getActiveWindow()));
	}

	protected Collection<Tree> getMultipleTrees() {
		return getMultipleTreesPrompt((this.hasFocus()) ? guiUtils : new GuiUtils(plugin.getActiveWindow()), true);
	}

	protected Tree getMultipleTreesInASingleContainer() {
		final Collection<Tree> trees = getMultipleTrees();
		if (trees == null) return null;
		if (trees.size() == 1) trees.iterator().next();
		final Tree holdingTree = new Tree();
		holdingTree.setLabel("Mixed Paths");
		trees.forEach(tree -> tree.list().forEach(path -> holdingTree.add(path)));
		return holdingTree;
	}

	private Collection<Tree> getTreesMimickingPrompt(final String description) {
		final Collection<Tree> trees = pathAndFillManager.getTrees();
		if (trees.size() == 1 || description.contains("All")) return trees;
		for (final Tree t : trees) {
			if (t.getLabel().equals(description)) return Collections.singleton(t);
		}
		return null;
	}

	private Tree getSingleTreeMimickingPrompt(final String description) {
		return getTreesMimickingPrompt(description).iterator().next();
	}

	private Tree getSingleTreePrompt(final GuiUtils guiUtils) {
		final Collection<Tree> trees = pathAndFillManager.getTrees();
		if (trees.size() == 1) return trees.iterator().next();
		final ArrayList<String> treeLabels = new ArrayList<>(trees.size());
		trees.forEach(t -> treeLabels.add(t.getLabel()));
		final String defChoice = plugin.getPrefs().getTemp("singletree", treeLabels.get(0));
		final String choice = guiUtils.getChoice("Multiple rooted structures exist. Which one should be considered?",
				"Which Structure?", treeLabels.toArray(new String[trees.size()]), defChoice);
		if (choice != null) {
			plugin.getPrefs().setTemp("singletree", choice);
			for (final Tree t : trees) {
				if (t.getLabel().equals(choice)) return t;
			}
		}
		return null; // user pressed canceled prompt
	}

	private Collection<Tree> getMultipleTreesPrompt(final GuiUtils guiUtils, final boolean includeAll) {
		final Collection<Tree> trees = pathAndFillManager.getTrees();
		if (trees.size() == 1) return trees;
		final ArrayList<String> treeLabels = new ArrayList<>(trees.size() + 1);
		trees.forEach(t -> treeLabels.add(t.getLabel()));
		Collections.sort(treeLabels);
		if (includeAll)
			treeLabels.add(0, "   -- All --  ");
		final List<String> choices = guiUtils.getMultipleChoices("Multiple rooted structures exist. Which ones should be considered?",
				"Which Structure?", treeLabels.toArray(new String[trees.size()]));
		if (includeAll && choices.contains("   -- All --  "))
			return trees;
		List<Tree> toReturn = new ArrayList<>();
		for (final Tree t : trees) {
			if (choices.contains(t.getLabel())) {
				toReturn.add(t);
			}
		}
		if (!toReturn.isEmpty()) {
			return toReturn;
		}
		
		return null; // user pressed canceled prompt
	}

	protected void quickMeasurementsCmdError(final GuiUtils guiUtils) {
		guiUtils.error("Selected paths do not fullfill requirements for retrieval of choiceless measurements. "
				+ "This can happen if e.g., paths are disconnected, or have been tagged with different type "
				+ "flags combined in an unexpected way. Please use the options in the \"Measure\" prompt to "
				+ "retrieve measurements.");
		updateTable();
	}

	/* (non-Javadoc)
	 * @see PathAndFillListener#setFillList(java.lang.String[])
	 */
	@Override
	public void setFillList(final List<Fill> fillList) {}  // ignored

	private class FitHelper {

		private boolean promptHasBeenDisplayed = false;
		private int fitType = PathFitter.RADII;
		private int maxRadius = PathFitter.DEFAULT_MAX_RADIUS;
		private boolean fitInPlace = false;
		private SwingWorker<Object, Object> fitWorker;
		private List<PathFitter> pathsToFit;

		public void showPrompt() {
			new FittingOptionsWorker(this, false).execute();
		}

		private boolean displayPromptRequired() {
			return !promptHasBeenDisplayed && plugin.getUI() != null && plugin.getUI().askUserConfirmation
					&& guiUtils.getConfirmation("You have not yet adjusted the fitting parameters. "
							+ "It is recommended that you do so at least once. Adjust them now?",
							"Adjust Parameters?", "Yes. Adjust Parameters...", "No. Use Defaults.");
		}

		synchronized protected void cancelFit(final boolean updateUIState) {
			if (fitWorker != null) {
				synchronized (fitWorker) {
					fitWorker.cancel(true);
					if (updateUIState) plugin.getUI().resetState();
					fitWorker = null;
				}
			}
		}

		public void fitUsingPrompAsNeeded() {
			if (pathsToFit == null || pathsToFit.isEmpty()) return; // nothing to fit
			if (displayPromptRequired()) {
				new FittingOptionsWorker(this, true).execute();
			} else {
				fit();
			}
		}

		public void fit() {
			assert SwingUtilities.isEventDispatchThread();
			final SNTUI ui = plugin.getUI();
			final int preFittingState = ui.getState();
			ui.changeState(SNTUI.FITTING_PATHS);
			final int numberOfPathsToFit = pathsToFit.size();
			final int processors = Math.min(numberOfPathsToFit, SNTPrefs.getThreads());
			final String statusMsg = (processors == 1) ? "Fitting 1 path..."
				: "Fitting " + numberOfPathsToFit + " paths (" + processors +
					" threads)...";
			ui.showStatus(statusMsg, false);
			setEnabledCommands(false);
			final JDialog msg = guiUtils.floatingMsg(statusMsg, false);

			fitWorker = new SwingWorker<Object, Object>() {

				@Override
				protected Object doInBackground() {

					final ExecutorService es = Executors.newFixedThreadPool(processors);
					final FittingProgress progress = new FittingProgress(plugin.getUI(),
						plugin.statusService, numberOfPathsToFit);
					try {
						for (int i = 0; i < numberOfPathsToFit; ++i) {
							final PathFitter pf = pathsToFit.get(i);
							pf.setScope(fitType);
							pf.setMaxRadius(maxRadius);
							pf.setReplaceNodes(fitInPlace);
							pf.setProgressCallback(i, progress);
						}
						for (final Future<Path> future : es.invokeAll(pathsToFit)) {
							final Path path = future.get();
							if (!fitInPlace) pathAndFillManager.addPath(path);
						}
					}
					catch (InterruptedException | ExecutionException | RuntimeException e) {
						msg.dispose();
						guiUtils.error(
							"Unfortunately an Exception occured. See Console for details");
						e.printStackTrace();
					}
					finally {
						progress.done();
					}
					return null;
				}

				@Override
				protected void done() {
					if (pathsToFit != null) {
						// since paths were fitted asynchronously, we need to rebuild connections
						pathsToFit.forEach(p-> p.getPath().rebuildConnectionsOfFittedVersion());
					}
					refreshManager(true, false, getSelectedPaths(true));
					msg.dispose();
					plugin.changeUIState(preFittingState);
					setEnabledCommands(true);
					ui.showStatus(null, false);
					pathsToFit = null;
				}
			};
			fitWorker.execute();
		}

	}

	private class FittingOptionsWorker extends SwingWorker<String, Object> {

		private final FitHelper fitHelper;
		private final boolean fit;

		public FittingOptionsWorker(final FitHelper fitHelper, final boolean fit) {
			this.fitHelper = fitHelper;
			this.fit = fit;
		}
	
		@Override
		public String doInBackground() {
			final PrefService prefService = plugin.getContext().getService(PrefService.class);
			final CommandService cmdService = plugin.getContext().getService(CommandService.class);
			try {
				final CommandModule cm = cmdService.run(PathFitterCmd.class, true).get();
				if (cm.isCanceled())
					return null;
				final String fitTypeString = prefService.get(PathFitterCmd.class, PathFitterCmd.FITCHOICE_KEY);
				fitHelper.fitType = Integer.parseInt(fitTypeString);
				final String maxRadiustring = prefService.get(PathFitterCmd.class, PathFitterCmd.MAXRADIUS_KEY);
				fitHelper.maxRadius = Integer.parseInt(maxRadiustring);
				fitHelper.fitInPlace = prefService.getBoolean(PathFitterCmd.class, PathFitterCmd.FITINPLACE_KEY, false);
			} catch (final InterruptedException | ExecutionException ignored) {
				return null;
			} catch (final NumberFormatException ex) {
				SNTUtils.error("Could not parse settings. Adopting Defaults...", ex);
			}
			return "";
		}

		@Override
		protected void done() {
			try {
				fitHelper.promptHasBeenDisplayed = get() != null;
			} catch (final InterruptedException | ExecutionException ex) {
				SNTUtils.error(ex.getMessage(), ex);
				fitHelper.promptHasBeenDisplayed = false;
			}
			if (fit) fitHelper.fit();
		}
	}

	/** This class defines the JTree hosting traced paths */
	private class HelpfulJTree extends JTree {

		private static final long serialVersionUID = 1L;
		private static final String ROOT_LABEL = "All Paths";
		private final TreeSearchable searchable;

		public HelpfulJTree() {
			super(new DefaultMutableTreeNode(HelpfulJTree.ROOT_LABEL));
			setLargeModel(true);
			setCellRenderer(new NodeRender());
			getSelectionModel().setSelectionMode(
				TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
			setExpandsSelectedPaths(true);
			setScrollsOnExpand(true);
			setRowHeight(getPreferredRowSize());
			searchable = new TreeSearchable(this);
		}

		private DefaultMutableTreeNode getRoot() {
			return ((DefaultMutableTreeNode) getModel().getRoot());
		}


		public void setSelected(final Object[] path) {
			assert SwingUtilities.isEventDispatchThread();
			final TreePath tp = new TreePath(path);
			addSelectionPath(tp);
		}

		public Set<Path> getSelectedPaths() {
			final TreePath[] selectionTreePath = getSelectionPaths();
			final Set<Path> selectedPaths = new HashSet<>();
			if (selectionTreePath == null)
				return selectedPaths;
			for (final TreePath tp : selectionTreePath) {
				final DefaultMutableTreeNode node = (DefaultMutableTreeNode) tp.getLastPathComponent();
				if (!node.isRoot()) { // 'invisible root' is not a SNT Path
					selectedPaths.add((Path) node.getUserObject());
				}
			}
			return selectedPaths;
		}

		public Set<Path> getExpandedPaths() {
			final Set<Path> set = new HashSet<>();
			final Enumeration<?> children = getRoot().depthFirstEnumeration();
			while (children.hasMoreElements()) {
				final DefaultMutableTreeNode node = (DefaultMutableTreeNode) children.nextElement();
				if (isExpanded(new TreePath(node.getPath()))) {
					final Object o = node.getUserObject();
					if (o instanceof Path) { // 'invisible root' is not a SNT Path
						set.add( (Path) o);
					}
				}
			}
			return set;
		}

		public void setSelectedPaths(final MutableTreeNode node, final Collection<Path> set) {
			assert SwingUtilities.isEventDispatchThread();
			final int count = getModel().getChildCount(node);
			final boolean updateCTposition = set.size() == 1;
			for (int i = 0; i < count; i++) {
				final DefaultMutableTreeNode child = (DefaultMutableTreeNode) getModel().getChild(node, i);
				final Path p = (Path) child.getUserObject();
				if (set.contains(p)) {
					setSelected(child.getPath());
					if (updateCTposition && plugin != null) {
						updateHyperstackPosition(p);
					}
				}
				if (!getModel().isLeaf(child))
					setSelectedPaths(child, set);
			}
		}

		public void setExpandedPaths(final Collection<Path> set) {
			assert SwingUtilities.isEventDispatchThread();
			final Enumeration<?> children = getRoot().depthFirstEnumeration();
			while (children.hasMoreElements()) {
				final DefaultMutableTreeNode node = (DefaultMutableTreeNode) children.nextElement();
				final Object o = node.getUserObject();
				if (o instanceof Path && set.contains((Path) o)) // 'invisible root' is not a SNT Path
					expandPath(new TreePath(node.getPath()));
			}
		}

		public void repaintAllNodes() {
			final DefaultTreeModel model = (DefaultTreeModel) tree.getModel();
			final Enumeration<?> e = getRoot().preorderEnumeration();
			while (e.hasMoreElements()) {
				final DefaultMutableTreeNode node = (DefaultMutableTreeNode) e.nextElement();
				model.nodeChanged(node); // will also update the 'invisible'/unrendered root
			}
		}

		public void repaintSelectedNodes() {
			final TreePath[] selectedPaths = getSelectionPaths();
			if (selectedPaths != null) {
				final DefaultTreeModel model = (DefaultTreeModel) tree.getModel();
				for (final TreePath tp : selectedPaths) {
					final DefaultMutableTreeNode node = (DefaultMutableTreeNode) (tp.getLastPathComponent());
					if (!node.isRoot())
						model.nodeChanged(node);
				}
			}
		}

		public void reload() {
			((DefaultTreeModel) getModel()).reload();
			if (searchableBar != null) {
				searchableBar.setStatusLabelPlaceholder(String.format("%d Path(s) listed", getPathAndFillManager().size()));
			}
		}

	}

	private class NodeRender extends DefaultTreeCellRenderer {

		private static final long serialVersionUID = 1L;

		public NodeRender() {
			super();
			setClosedIcon(new NodeIcon(NodeIcon.PLUS));
			setOpenIcon(new NodeIcon(NodeIcon.MINUS));
			setLeafIcon(new NodeIcon(NodeIcon.EMPTY));
		}

		@Override
		public Component getTreeCellRendererComponent(final JTree tree,
			final Object value, final boolean selected, final boolean expanded,
			final boolean isLeaf, final int row, final boolean focused)
		{
			final Component c = super.getTreeCellRendererComponent(tree, value,
				selected, expanded, isLeaf, row, focused);
//			((JLabel)c).setOpaque(true);
//			((JLabel)c).setBackground(Color.RED);

			final TreePath tp = tree.getPathForRow(row);
			if (tp == null) {
				return c;
			}
			final DefaultMutableTreeNode node = (DefaultMutableTreeNode) (tp
				.getLastPathComponent());
			if (node == null || node.isRoot()) return c;
			final Path p = (Path) node.getUserObject();
			final Color color = p.getColor();
			if (color == null) {
				return c;
			}
			if (isLeaf)
				setIcon(new NodeIcon(NodeIcon.EMPTY, color));
			else if (!expanded)
				setIcon(new NodeIcon(NodeIcon.PLUS, color));
			else
				setIcon(new NodeIcon(NodeIcon.MINUS, color));
			return c;
		}

	}

	/**
	 * This class generates the JTree node icons. Heavily inspired by
	 * http://stackoverflow.com/a/7984734
	 */
	private static class NodeIcon implements Icon {

		private final static int SIZE = getPreferredIconSize();
		private static final char PLUS = '+';
		private static final char MINUS = '-';
		private static final char EMPTY = ' ';
		private final char type;
		private final Color color;

		private NodeIcon(final char type) {
			this.type = type;
			this.color = UIManager.getColor("Tree.background");
		}

		private NodeIcon(final char type, final Color color) {
			this.type = type;
			this.color = color;
		}

		/* see https://stackoverflow.com/a/9780689 */
		private boolean closerToBlack(final Color c) {
			final double y = 0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c
				.getBlue();
			return y < 100;
		}

		@Override
		public void paintIcon(final Component c, final Graphics g, final int x,
			final int y)
		{
			g.setColor(color);
			g.fillRect(x, y, SIZE - 1, SIZE - 1);
			g.setColor(Color.BLACK);
			g.drawRect(x, y, SIZE - 1, SIZE - 1);
			if (type == EMPTY) return;
			g.setColor(closerToBlack(color) ? Color.WHITE : Color.BLACK);
			g.drawLine(x + 2, y + SIZE / 2, x + SIZE - 3, y + SIZE / 2);
			if (type == PLUS) {
				g.drawLine(x + SIZE / 2, y + 2, x + SIZE / 2, y + SIZE - 3);
			}
		}

		@Override
		public int getIconWidth() {
			return SIZE;
		}

		@Override
		public int getIconHeight() {
			return SIZE;
		}

	}

	@SuppressWarnings("unused")
	private static class TreeTransferHandler extends TransferHandler {

		private static final long serialVersionUID = 1L;
		DataFlavor nodesFlavor;
		DataFlavor[] flavors = new DataFlavor[1];
		DefaultMutableTreeNode[] nodesToRemove;

		public TreeTransferHandler() {
			try {
				final String mimeType = DataFlavor.javaJVMLocalObjectMimeType +
					";class=\"" + javax.swing.tree.DefaultMutableTreeNode[].class
						.getName() + "\"";
				nodesFlavor = new DataFlavor(mimeType);
				flavors[0] = nodesFlavor;
			}
			catch (final ClassNotFoundException e) {
				System.out.println("ClassNotFound: " + e.getMessage());
			}
		}

		@Override
		public boolean canImport(final TransferHandler.TransferSupport support) {
			if (!support.isDrop()) {
				return false;
			}
			support.setShowDropLocation(true);
			if (!support.isDataFlavorSupported(nodesFlavor)) {
				return false;
			}
			// Do not allow a drop on the drag source selections.
			final JTree.DropLocation dl = (JTree.DropLocation) support
				.getDropLocation();
			final JTree tree = (JTree) support.getComponent();
			final int dropRow = tree.getRowForPath(dl.getPath());
			final int[] selRows = tree.getSelectionRows();
			for (int selRow : selRows) {
				if (selRow == dropRow) {
					return false;
				}
			}
			// Do not allow MOVE-action drops if a non-leaf node is
			// selected unless all of its children are also selected.
			final int action = support.getDropAction();
			if (action == MOVE) {
				return haveCompleteNode(tree);
			}
			// Do not allow a non-leaf node to be copied to a level
			// which is less than its source level.
			final TreePath dest = dl.getPath();
			final DefaultMutableTreeNode target = (DefaultMutableTreeNode) dest
				.getLastPathComponent();
			final TreePath path = tree.getPathForRow(selRows[0]);
			final DefaultMutableTreeNode firstNode = (DefaultMutableTreeNode) path
				.getLastPathComponent();
			return firstNode.getChildCount() <= 0 || target.getLevel() >= firstNode
					.getLevel();
		}

		private boolean haveCompleteNode(final JTree tree) {
			final int[] selRows = tree.getSelectionRows();
			TreePath path = tree.getPathForRow(selRows[0]);
			final DefaultMutableTreeNode first = (DefaultMutableTreeNode) path
				.getLastPathComponent();
			final int childCount = first.getChildCount();
			// first has children and no children are selected.
			if (childCount > 0 && selRows.length == 1) return false;
			// first may have children.
			for (int i = 1; i < selRows.length; i++) {
				path = tree.getPathForRow(selRows[i]);
				final DefaultMutableTreeNode next = (DefaultMutableTreeNode) path
					.getLastPathComponent();
				if (first.isNodeChild(next)) {
					// Found a child of first.
					if (childCount > selRows.length - 1) {
						// Not all children of first are selected.
						return false;
					}
				}
			}
			return true;
		}

		@Override
		protected Transferable createTransferable(final JComponent c) {
			final JTree tree = (JTree) c;
			final TreePath[] paths = tree.getSelectionPaths();
			if (paths != null) {
				// Make up a node array of copies for transfer and
				// another for/of the nodes that will be removed in
				// exportDone after a successful drop.
				final List<DefaultMutableTreeNode> copies = new ArrayList<>();
				final List<DefaultMutableTreeNode> toRemove = new ArrayList<>();
				final DefaultMutableTreeNode node = (DefaultMutableTreeNode) paths[0]
					.getLastPathComponent();
				final DefaultMutableTreeNode copy = copy(node);
				copies.add(copy);
				toRemove.add(node);
				for (int i = 1; i < paths.length; i++) {
					final DefaultMutableTreeNode next = (DefaultMutableTreeNode) paths[i]
						.getLastPathComponent();
					// Do not allow higher level nodes to be added to list.
					if (next.getLevel() < node.getLevel()) {
						break;
					}
					else if (next.getLevel() > node.getLevel()) { // child node
						copy.add(copy(next));
						// node already contains child
					}
					else { // sibling
						copies.add(copy(next));
						toRemove.add(next);
					}
				}
				final DefaultMutableTreeNode[] nodes = copies.toArray(
						new DefaultMutableTreeNode[0]);
				nodesToRemove = toRemove.toArray(new DefaultMutableTreeNode[0]);
				return new NodesTransferable(nodes);
			}
			return null;
		}

		/** Defensive copy used in createTransferable. */
		private DefaultMutableTreeNode copy(final TreeNode node) {
			final DefaultMutableTreeNode n = (DefaultMutableTreeNode) node;
			return (DefaultMutableTreeNode) n.clone();
		}

		@Override
		protected void exportDone(final JComponent source, final Transferable data,
			final int action)
		{
			if ((action & MOVE) == MOVE) {
				final JTree tree = (JTree) source;
				final DefaultTreeModel model = (DefaultTreeModel) tree.getModel();
				// Remove nodes saved in nodesToRemove in createTransferable.
				for (DefaultMutableTreeNode defaultMutableTreeNode : nodesToRemove) {
					model.removeNodeFromParent(defaultMutableTreeNode);
				}
			}
		}

		@Override
		public int getSourceActions(final JComponent c) {
			return COPY_OR_MOVE;
		}

		@Override
		public boolean importData(final TransferHandler.TransferSupport support) {
			if (!canImport(support)) {
				return false;
			}
			// Extract transfer data.
			DefaultMutableTreeNode[] nodes = null;
			try {
				final Transferable t = support.getTransferable();
				nodes = (DefaultMutableTreeNode[]) t.getTransferData(nodesFlavor);
			}
			catch (final UnsupportedFlavorException ufe) {
				System.out.println("UnsupportedFlavor: " + ufe.getMessage());
			}
			catch (final java.io.IOException ioe) {
				System.out.println("I/O error: " + ioe.getMessage());
			}
			// Get drop location info.
			final JTree.DropLocation dl = (JTree.DropLocation) support
				.getDropLocation();
			final int childIndex = dl.getChildIndex();
			final TreePath dest = dl.getPath();
			final DefaultMutableTreeNode parent = (DefaultMutableTreeNode) dest
				.getLastPathComponent();
			final JTree tree = (JTree) support.getComponent();
			final DefaultTreeModel model = (DefaultTreeModel) tree.getModel();
			// Configure for drop mode.
			int index = childIndex; // DropMode.INSERT
			if (childIndex == -1) { // DropMode.ON
				index = parent.getChildCount();
			}
			// Add data to model.
			for (DefaultMutableTreeNode node : nodes) {
				model.insertNodeInto(node, parent, index++);
			}
			return true;
		}

		@Override
		public String toString() {
			return getClass().getName();
		}

		public class NodesTransferable implements Transferable {

			DefaultMutableTreeNode[] nodes;

			public NodesTransferable(final DefaultMutableTreeNode[] nodes) {
				this.nodes = nodes;
			}

			@Override
			public Object getTransferData(final DataFlavor flavor)
				throws UnsupportedFlavorException
			{
				if (!isDataFlavorSupported(flavor))
					throw new UnsupportedFlavorException(flavor);
				return nodes;
			}

			@Override
			public DataFlavor[] getTransferDataFlavors() {
				return flavors;
			}

			@Override
			public boolean isDataFlavorSupported(final DataFlavor flavor) {
				return nodesFlavor.equals(flavor);
			}
		}
	}

	private static int getPreferredRowSize() {
		final JTree tree = new JTree();
		return tree.getFontMetrics(tree.getFont()).getHeight();
	}

	private static int getPreferredIconSize() {
		final JTree tree = new JTree();
		final int size = tree.getFontMetrics(tree.getFont()).getAscent();
		return (size % 2 == 0) ? size - 1 : size;
	}

	private void setEnabledCommands(final boolean enabled) {
		assert SwingUtilities.isEventDispatchThread();
		tree.setEnabled(enabled);
		menuBar.setEnabled(enabled);
	}

	private void exploreFit(final Path p) {
		assert SwingUtilities.isEventDispatchThread();

		// Announce computation
		final SNTUI ui = plugin.getUI();
		final String statusMsg = "Fitting " + p.toString();
		ui.showStatus(statusMsg, false);
		setEnabledCommands(false);

		final String text = "Once opened, you can peruse the fit by " +
			"navigating the 'Cross Section View' stack. Edit mode " +
			"will be activated and cross section planes automatically " +
			"synchronized with tracing canvas(es).";
		final JDialog msg = guiUtils.floatingMsg(text, false);

		new Thread(() -> {

			final Path existingFit = p.getFitted();

			// No image is displayed if run on EDT
			final SwingWorker<?, ?> worker = new SwingWorker<Object, Object>() {

				@Override
				protected Object doInBackground() throws Exception {

					try {

						// discard existing fit, in case a previous fit exists
						p.setUseFitted(false);
						p.setFitted(null);

						// Compute verbose fit using settings from previous PathFitterCmd
						// runs
						final PathFitter fitter = new PathFitter(plugin, p);
						fitter.setShowAnnotatedView(true);
						final PrefService prefService = plugin.getContext().getService(
							PrefService.class);
						final String rString = prefService.get(PathFitterCmd.class,
							PathFitterCmd.MAXRADIUS_KEY, String.valueOf(
								PathFitter.DEFAULT_MAX_RADIUS));
						fitter.setMaxRadius(Integer.valueOf(rString));
						fitter.setScope(PathFitter.RADII_AND_MIDPOINTS);
						final ExecutorService executor = Executors
							.newSingleThreadExecutor();
						final Future<Path> future = executor.submit(fitter);
						future.get();

					}
					catch (InterruptedException | ExecutionException
							| RuntimeException e)
					{
						msg.dispose();
						guiUtils.error(
							"Unfortunately an exception occured. See Console for details");
						e.printStackTrace();
					}
					return null;
				}

				@Override
				protected void done() {
					// this is just a preview cmd. Reinstate previous fit, if any
					p.setFitted(null);
					p.setFitted(existingFit);
					// It may take longer to read the text than to compute
					// Normal Views: we will not call msg.dispose();
					GuiUtils.setAutoDismiss(msg);
					setEnabledCommands(true);
					// Show both original and fitted paths
					if (plugin.showOnlySelectedPaths) ui.togglePathsChoice();
					plugin.enableEditMode(true);
					plugin.setEditingPath(p);
				}
			};
			worker.execute();
		}).start();
	}

	private void refreshManager(final boolean refreshCmds,
		final boolean refreshViewers, final Collection<Path> selectedPaths)
	{
		if (refreshViewers)
			plugin.updateAllViewers(); // will call #update()
		else if (tree.getSelectionCount() == 0)
			tree.repaintAllNodes();
		else
			tree.repaintSelectedNodes();
		if (refreshCmds && selectedPaths != null) {
			if (selectedPaths.size() == 1)
				updateCmdsOneSelected(selectedPaths.iterator().next());
			else
				updateCmdsManyOrNoneSelected(selectedPaths);
		}
	}

	/**
	 * Refreshes (Repaints) the Path Manager JTree. Gets called by
	 * {@link SNT#isOnlySelectedPathsVisible()} to reflect changes in path colors,
	 * etc.
	 */
	public void update() {
		tree.repaintAllNodes();
	}

	/** Reloads the contents of {@link PathAndFillManager} */
	public void reload() {
		setPathList(pathAndFillManager.getPaths(), null, true);
	}

	protected void closeTable() {
		final TableDisplay tableDisplay = getTableDisplay();
		if (tableDisplay != null) tableDisplay.close();
		table = null;
	}

	private boolean tableIsBeingDisplayed() {
		return getTableDisplay() != null;
	}

	protected void updateTable() {
		final TableDisplay tableDisplay = getTableDisplay();
		if (tableDisplay != null) tableDisplay.update();
	}

	private TableDisplay getTableDisplay() {
		if (table == null) return null;
		final List<TableDisplay> displays = plugin.getContext().getService(DisplayService.class)
				.getDisplaysOfType(TableDisplay.class);
		if (displays == null) return null;
		for (final TableDisplay d : displays) {
			if (d != null && d.isDisplaying(table)) return d;
		}
		return null;
	}

	protected SNTTable getTable() {
		if (table == null || !tableIsBeingDisplayed()) {
			// Create a new table if it does not exist or is cached but it has been closed.
			// Resetting the table on the latter scenario improves GUI usability.
			// TODO: add a window listener that prompts the user to save contents before closing it
			table = new SNTTable();
		}
		return table;
	}

	public SNT getSNT() {
		return plugin;
	}

	public PathAndFillManager getPathAndFillManager() {
		return pathAndFillManager;
	}

	public JTree getJTree() {
		return tree;
	}

	public Searchable getSearchable() {
		return tree.searchable;
	}

	/**
	 * Selects paths matching a morphometric criteria.
	 *
	 * @param property The morphometric property ("Length", "Path order", etc.) as
	 *                 listed in the "Morphology filter" menu (case sensitive).
	 * @param min      the lowest value (exclusive) in the filter
	 * @param max      the highest value (exclusive) in the filter
	 * @throws IllegalArgumentException if {@code property} was not recognized. Note
	 *                                  that some filtering options listed in the
	 *                                  GUI may not be supported.	 */
	public void applySelectionFilter(final String property, Number min, Number max) throws IllegalArgumentException {
		searchableBar.doMorphoFiltering(getSelectedPaths(true),
				(property.endsWith("...")) ? property.replace("...", "") : property, min, max);
	}

	/**
	 * Applies a custom tag/ color to selected Path(s).
	 *
	 * @param customTagOrColor The tag (or color) to be applied to selected Paths.
	 */
	public void applyTag(final String customTagOrColor) throws IllegalArgumentException {
		final ColorRGB color = Colors.getColor(customTagOrColor);
		if (color == null) {
			applyCustomTags(getSelectedPaths(true), customTagOrColor);
			update();
		} else {
			final Collection<Path> paths = getSelectedPaths(true);
			paths.forEach( p-> p.setColor(color));
			refreshManager(true, true, paths);
		}
	}

	/**
	 * Applies a default (built-in) tag to selected Path(s).
	 *
	 * @param defaultTags The tags to be applied to selected Paths, as listed in the
	 *                    "Tag" menu, e.g,
	 *                    {@value MultiPathActionListener#CHANNEL_TAG_CMD},
	 *                    {@value MultiPathActionListener#FRAME_TAG_CMD},
	 *                    {@value MultiPathActionListener#COUNT_TAG_CMD}, etc.
	 */
	public void applyDefaultTags(final String... defaultTags) throws IllegalArgumentException {
		final Collection<Path> paths = getSelectedPaths(true);
		for (final String tag : defaultTags) {
			removeOrReapplyDefaultTag(paths, tag, true, false);
		}
		refreshManager(false, false, paths);
	}

	/** Should be the only method called for toggling built-in tags **/
	private void removeOrReapplyDefaultTag(final Collection<Path> paths, final String cmd, final boolean reapply, final boolean interactiveUI) {
		switch (cmd) {
		case MultiPathActionListener.CHANNEL_TAG_CMD:
			paths.forEach(p -> p.setName(p.getName().replaceAll(" ?\\[C:\\d+\\]", "")));
			if (reapply) {
				paths.forEach( p -> {
					p.setName(p.getName() + " [C:" + p.getChannel() + "]");
				});
			}
			break;
		case MultiPathActionListener.FRAME_TAG_CMD:
			paths.forEach(p -> p.setName(p.getName().replaceAll(" ?\\[T:\\d+\\]", "")));
			if (reapply) {
				paths.forEach( p -> {
						p.setName(p.getName() + " [T:" + p.getFrame() + "]");
				});
			}
			break;
		case MultiPathActionListener.TREE_TAG_CMD:
			paths.forEach(p -> p.setName(p.getName().replaceAll(" ?\\|.*\\|", "")));
			if (reapply) {
				paths.forEach( p -> {
						p.setName(p.getName() + " |" + p.getTreeLabel() + "|");
				});
			}
			break;
		case MultiPathActionListener.ORDER_TAG_CMD:
			paths.forEach(p -> p.setName(p.getName().replaceAll(" ?\\[Order \\d+\\]", "")));
			if (reapply) {
				paths.forEach(p-> {
					p.setName(p.getName() + " [Order " + p.getOrder() + "]");
				});
			}
			break;
		case MultiPathActionListener.LENGTH_TAG_CMD:
			paths.forEach(p -> p.setName(p.getName().replaceAll(" ?\\[L:\\d+\\.?\\d+\\s?.+\\w+\\]", "")));
			if (reapply) {
				paths.forEach(p -> {
					final String lengthTag = " [L:" + p.getRealLengthString() + p.spacing_units + "]";
					p.setName(p.getName() + lengthTag);
				});
			}
			break;
		case MultiPathActionListener.MEAN_RADIUS_TAG_CMD:
			paths.forEach(p -> p.setName(p.getName().replaceAll(" ?\\[MR:\\d+\\.?\\d+\\s?.+\\w+\\]", "")));
			if (reapply) {
				paths.forEach(p -> {
					final String radiusTag = " [MR:" + SNTUtils.formatDouble(p.getMeanRadius(), 3) + p.spacing_units + "]";
					p.setName(p.getName() + radiusTag);
				});
			}
			break;
		case MultiPathActionListener.COUNT_TAG_CMD:
			paths.forEach(p -> p.setName(p.getName().replaceAll(" ?\\[#:\\d+\\]", "")));
			if (reapply) {
				paths.forEach(p -> {
					final String countTag = " [#:" + p.getSpineOrVaricosityCount() + "]";
					p.setName(p.getName() + countTag);
				});
			}
			break;
		case MultiPathActionListener.SLICE_LABEL_TAG_CMD:
			if (reapply) { // Special case: not toggleable: Nothing to remove
				int errorCounter = 0;
				for (final Path p : paths) {
					try {
						String label = plugin.getImagePlus().getStack().getShortSliceLabel(
							plugin.getImagePlus().getStackIndex(p.getChannel(), 1, p
								.getFrame()));
						if (label == null || label.isEmpty()) {
							errorCounter++;
							continue;
						}
						label = label.replace("[", "(");
						label = label.replace("]", ")");
						p.setName(p.getName() + "{" + label + "}");
					}
					catch (IllegalArgumentException | IndexOutOfBoundsException ignored) {
						errorCounter++;
					}
				}
				if (errorCounter > 0) {
					final String errorMsg = "It was not possible to retrieve labels for " +
						errorCounter + "/" + paths.size() + " paths.";
					if (interactiveUI)
						guiUtils.error(errorMsg);
					else
						System.out.println(errorMsg);
				}
			}
			break;
		default:
			throw new IllegalArgumentException("Unrecognized tag " + cmd + ". Not a default option?");
		}
		selectTagCheckBoxMenu(cmd, reapply);
		if (interactiveUI) refreshManager(false, false, paths);
	}

	/**
	 * Selects paths matching a text-based criteria.
	 *
	 * @param query The matching text, as it would have been typed in the "Text
	 *              filtering" box.
	 */
	public void applySelectionFilter(final String query) throws IllegalArgumentException {
		final List<Integer> hits = searchableBar.getSearchable().findAll(query);
		if (hits != null && !hits.isEmpty()) {
			final int[] array = hits.stream().mapToInt(i->i).toArray();
			tree.addSelectionRows(array);
		}
	}

	/**
	 * Selects paths matching a morphometric criteria.
	 *
	 * @param property The morphometric property ("Length", "Path order", etc.) as
	 *                 listed in the "Morphology filter" menu (case sensitive).
	 * @param criteria the filtering criteria.
	 * @throws IllegalArgumentException if {@code property} was not recognized. Note
	 *                                  that some filtering options listed in the
	 *                                  GUI may not be supported.
	 */
	public void applySelectionFilter(final String property, Number criteria) throws IllegalArgumentException {
		searchableBar.doMorphoFiltering(getSelectedPaths(true),
				(property.endsWith("...")) ? property.replace("...", "") : property, criteria, criteria);
	}

	/**
	 * Gets the collection of paths listed in the Path Manager as a {@link Tree}
	 * object. All paths are retrieved if none are currently selected.
	 *
	 * @return the Tree holding the Path collection
	 */
	public Tree geSelectedPathsAsTree() {
		return new Tree(getSelectedPaths(true));
	}

	public void clearSelection() {
		tree.clearSelection();
	}

	public void selectAll() {
		tree.setSelectionInterval(0, tree.getRowCount());
	}

	/**
	 * Runs a menu command.
	 *
	 * @param cmd The command to be run, exactly as listed in the PathManagerUI's
	 *            menu bar or Right-click contextual menu
	 * @throws IllegalArgumentException if {@code cmd} was not found.
	 */
	public void runCommand(final String cmd) throws IllegalArgumentException {
		try {
			SNTUI.runCommand(menuBar, cmd);
		} catch (final IllegalArgumentException ie) {
			for (final JMenuItem jmi : GuiUtils.getMenuItems(popup)) {
				if (cmd.equals(jmi.getText()) || cmd.equals(jmi.getActionCommand())) {
					jmi.doClick();
					return;
				}
			}
			throw new IllegalArgumentException("Not a recognizable command: " + cmd);
		}
	}

	/**
	 * Runs a menu command with options.
	 *
	 * @param cmd  The command to be run, exactly as listed in the PathManagerUI's
	 *             menu bar or Right-click contextual menu
	 * @param args the option(s) that would fill the commands's prompt. e.g.,
	 *             'runCommand("Color Code Cell(s)...", X coordinates", "Cyan Hot.lut")'
	 * @throws IllegalArgumentException if {@code cmd} was not found, or if it is
	 *                                  not supported.
	 */
	public void runCommand(final String cmd, String... args) throws IllegalArgumentException, IOException {
		if (MultiPathActionListener.HISTOGRAM_TREES_CMD.equals(cmd)) {
			if (args.length == 1) {
				runDistributionAnalysisCmd("All", args[0]);
			} else if (args.length > 1) {
				runDistributionAnalysisCmd(args[0], args[1]);
			} else {
				throw new IllegalArgumentException("Not enough arguments...");
			}
			return;
		} else if (MultiPathActionListener.HISTOGRAM_PATHS_CMD.equals(cmd)) {
			if (args.length > 0) {
				runHistogramPathsCmd(getSelectedPaths(true), args[0]);
			} else {
				throw new IllegalArgumentException("Not enough arguments...");
			}
			return;
		} else if (MultiPathActionListener.COLORIZE_TREES_CMD.equals(cmd)) {
			if (args.length == 2) {
				runColorCodingCmd("All", args[0], args[1]);
			} else if (args.length > 2) {
				runColorCodingCmd(args[0], args[1], args[2]);
			} else {
				throw new IllegalArgumentException("Not enough arguments...");
			}
			return;
		} else if (MultiPathActionListener.COLORIZE_PATHS_CMD.equals(cmd)) {
			if (args.length > 1) {
				runColorCodingCmd(geSelectedPathsAsTree(), true, args[0], args[1]);
			} else {
				throw new IllegalArgumentException("Not enough arguments...");
			}
			return;
		} else if (MultiPathActionListener.CONVERT_TO_ROI_CMD.equals(cmd)) {
			if (args.length == 0) {
				runRoiConverterCmd("All", null);
			} else if (args.length == 1) {
				runRoiConverterCmd(args[0], null);
			} else if (args.length > 1) {
				runRoiConverterCmd(args[0], args[1]);
			}
			return;
		} else if (args == null || args.length == 0) {
			runCommand(cmd);
			return;
		}
		throw new IllegalArgumentException("Unsupported command: " + cmd);
	}

	private void runRoiConverterCmd(final String type, final String view) throws IllegalArgumentException {
		final Map<String, Object> input = new HashMap<>();
		input.put("tree", geSelectedPathsAsTree());
		input.put("imp", plugin.getImagePlus());
		input.put("roiChoice", type);
		input.put("viewChoice", (view==null) ? "XY (default)" : view);
		input.put("useSWCcolors", false);
		input.put("avgWidth", true);
		input.put("discardExisting", false);
		final CommandService cmdService = plugin.getContext().getService(CommandService.class);
		cmdService.run(ROIExporterCmd.class, true, input);
		return;
	}

	private void runColorCodingCmd(final String singleTreeDescriptor, final String metric, final String lutName) throws IllegalArgumentException, IOException {
		final Tree tree =  getSingleTreeMimickingPrompt(singleTreeDescriptor);
		if (tree == null) throw new IllegalArgumentException("Not a recognized choice "+ singleTreeDescriptor);
		runColorCodingCmd(tree, false, metric, lutName);
	}

	private void runColorCodingCmd(final Tree tree, final boolean onlyConnectivitySafeMetrics, final String metric, final String lutName) throws IllegalArgumentException, IOException {
		final Map<String, Object> input = new HashMap<>();
		input.put("tree", tree);
		input.put("measurementChoice", metric);
		input.put("lutChoice", lutName);
		input.put("showInRecViewer", false);
		input.put("showPlot", false);
		input.put("setValuesFromSNTService", !plugin.tracingHalted);
		input.put("removeColorCoding", null);
		input.put("onlyConnectivitySafeMetrics", onlyConnectivitySafeMetrics);
		final LUTService lutService = plugin.getContext().getService(LUTService.class);
		input.put("colorTable", lutService.loadLUT(lutService.findLUTs().get(lutName)));
		final CommandService cmdService = plugin.getContext().getService(CommandService.class);
		cmdService.run(TreeMapperCmd.class, true, input);
		return;
	}

	private void runHistogramPathsCmd(final Collection<Path> paths, final String metric) {
		final Map<String, Object> input = new HashMap<>();
		final Tree tree = new Tree(paths);
		tree.setLabel("Selected Paths");
		input.put("tree", tree);
		input.put("calledFromPathManagerUI", true);
		input.put("onlyConnectivitySafeMetrics", true);
		if (metric != null) input.put("measurementChoice", metric);
		final CommandService cmdService = plugin.getContext().getService(
			CommandService.class);
		cmdService.run(DistributionBPCmd.class, true, input);
	}

	private void runDistributionAnalysisCmd(final String treeCollectionDescriptor, final String metric) throws IllegalArgumentException {
		final Collection<Tree> trees = getTreesMimickingPrompt(treeCollectionDescriptor);
		if (trees == null) throw new IllegalArgumentException("Not a recognized choice "+ treeCollectionDescriptor);
		runDistributionAnalysisCmd(trees, metric);
	}

	private void runDistributionAnalysisCmd(final Collection<Tree> trees, final String metric) {
		if (trees.size() == 1) {
			final Map<String, Object> input = new HashMap<>();
			input.put("trees", trees);
			if (metric != null) input.put("measurementChoice", metric);
			input.put("calledFromPathManagerUI", true);
			input.put("onlyConnectivitySafeMetrics", false);
			final CommandService cmdService = plugin.getContext().getService(
				CommandService.class);
			cmdService.run(DistributionBPCmd.class, true, input);
		} else {
			final Map<String, Object> input = new HashMap<>();
			input.put("trees", trees);
			input.put("calledFromPathManagerUI", true);
			if (metric != null) input.put("measurementChoice", metric);
			final CommandService cmdService = plugin.getContext().getService(
				CommandService.class);
			cmdService.run(DistributionCPCmd.class, true, input);
		}
		return;
	}

	protected boolean measurementsUnsaved() {
		return validTableMeasurements() && table.hasUnsavedData();
	}

	private boolean validTableMeasurements() {
		return table != null && table.getRowCount() > 0 && table
			.getColumnCount() > 0;
	}

	protected void saveTable() {
		if (!validTableMeasurements()) {
			plugin.error("There are no measurements to save.");
			return;
		}
		final File saveFile = plugin.getUI().saveFile("Save SNT Measurements...", "SNT_Measurements.csv", ".csv");
		if (saveFile == null) return; // user pressed cancel

		plugin.getUI().showStatus("Exporting Measurements..", false);
		try {
			table.save(saveFile);
		}
		catch (final IOException e) {
			plugin.error(
				"Unfortunately an Exception occured. See Console for details");
			plugin.getUI().showStatus("Exporting Failed..", true);
			e.printStackTrace();
		}
		plugin.getUI().showStatus(null, false);
	}

	/** ActionListener for commands that do not deal with paths */
	private class NoPathActionListener implements ActionListener {

		private final static String EXPAND_ALL_CMD = "Expand All";
		private final static String COLLAPSE_ALL_CMD = "Collapse All";
		private final static String SELECT_NONE_CMD = "Deselect (Same as Select All)";

		@Override
		public void actionPerformed(final ActionEvent e) {

			switch (e.getActionCommand()) {
				case SELECT_NONE_CMD:
					tree.clearSelection();
					return;
				case EXPAND_ALL_CMD:
					GuiUtils.expandAllTreeNodes(tree);
					return;
				case COLLAPSE_ALL_CMD:
					GuiUtils.collapseAllTreeNodes(tree);
					return;
				default:
					SNTUtils.error("Unexpectedly got an event from an unknown source: " + e);
					break;
			}
		}
	}

	/** ActionListener for commands operating exclusively on a single path */
	private class SinglePathActionListener implements ActionListener {

		private final static String RENAME_CMD = "Rename...";
		private final static String MAKE_PRIMARY_CMD = "Make Primary";
		private final static String DUPLICATE_CMD = "Duplicate...";
		private final static String EXPLORE_FIT_CMD = "Explore/Preview Fit";

		@Override
		public void actionPerformed(final ActionEvent e) {

			// Process nothing without a single path selection
			final Collection<Path> selectedPaths = getSelectedPaths(false);
			if (selectedPaths.size() != 1) {
				guiUtils.error("You must have exactly one path selected.");
				return;
			}
			final Path p = selectedPaths.iterator().next();
			switch (e.getActionCommand()) {
				case RENAME_CMD:
					final String s = guiUtils.getString(
							"Rename this path to (clear to reset name):", "Rename Path", p
									.getName());
					if (s == null) return; // user pressed cancel
					synchronized (getPathAndFillManager()) {
						if (s.trim().isEmpty()) {
							p.setName("");
						} else if (getPathAndFillManager().getPathFromName(s, false) != null) {
							displayTmpMsg("There is already a path named:\n('" + s + "')");
							return;
						} else {// Otherwise this is OK, change the name:
							p.setName(s);
						}
						refreshManager(false, false, Collections.singleton(p));
					}
					return;
				case MAKE_PRIMARY_CMD:
					final HashSet<Path> pathsExplored = new HashSet<>();
					p.setIsPrimary(true);
					pathsExplored.add(p);
					p.unsetPrimaryForConnected(pathsExplored);
					removeOrReapplyDefaultTag(selectedPaths, MultiPathActionListener.ORDER_TAG_CMD, false, true);
					return;

				case DUPLICATE_CMD:
					final HashMap<String, Object> inputs = new HashMap<>();
					inputs.put("path", p);
					(plugin.getUI().new DynamicCmdRunner(DuplicateCmd.class, inputs)).run();
					return;

				case EXPLORE_FIT_CMD:
					if (noValidImageDataError()) return;
					if (plugin.getImagePlus() == null) {
						displayTmpMsg(
								"Tracing image is not available. Fit cannot be computed.");
						return;
					}
					if (!plugin.uiReadyForModeChange()) {
						displayTmpMsg(
								"Please finish current operation before exploring fit.");
						return;
					}
					exploreFit(p);
					return;
			}

			SNTUtils.error("Unexpectedly got an event from an unknown source: " + e);
		}
	}

	private void applyCustomTags(final Collection<Path> selectedPaths, String customTag) {
		customTag = customTag.replace("[", "(");
		customTag = customTag.replace("]", ")");
		customTag = customTag.replace("|", "-"); // No need to replace {} braces
		for (final Path p : selectedPaths) {
			p.setName(p.getName() + "{" + customTag + "}");
		}
	}

	private void selectTagCheckBoxMenu(final String name, final boolean select) {
		for (final Component c1 : tagsMenu.getMenuComponents()) {
			if ((c1 instanceof JMenu)) {
				for (final Component c2 : ((JMenu) c1).getMenuComponents()) {
					if (c2 instanceof JCheckBoxMenuItem && ((JCheckBoxMenuItem) c2).getActionCommand().equals(name))
						((JCheckBoxMenuItem) c2).setSelected(select);
				}
			} else if (c1 instanceof JCheckBoxMenuItem && ((JCheckBoxMenuItem) c1).getActionCommand().equals(name))
				((JCheckBoxMenuItem) c1).setSelected(select);
		}
	}

	private List<String> guessTagsCurrentlyActive() {
		List<String> selected = new ArrayList<>();
		for (final Component c1 : tagsMenu.getMenuComponents()) {
			if ((c1 instanceof JMenu)) {
				for (final Component c2 : ((JMenu) c1).getMenuComponents()) {
					if (c2 instanceof JCheckBoxMenuItem && ((JCheckBoxMenuItem) c2).isSelected())
						selected.add(((JCheckBoxMenuItem) c2).getActionCommand());
				}
			} else if (c1 instanceof JCheckBoxMenuItem && ((JCheckBoxMenuItem) c1).isSelected())
				selected.add(((JCheckBoxMenuItem) c1).getActionCommand());
		}
		return selected;
	}

	private void setSelectAllTagsMenu(final boolean select) {
		for (final Component c1 : tagsMenu.getMenuComponents()) {
			if ((c1 instanceof JMenu)) {
				for (final Component c2 : ((JMenu) c1).getMenuComponents()) {
					if (c2 instanceof JCheckBoxMenuItem)
						((JCheckBoxMenuItem) c2).setSelected(select);
				}
			} else if (c1 instanceof JCheckBoxMenuItem)
				((JCheckBoxMenuItem) c1).setSelected(select);
		}
	}

	/** ActionListener for JCheckBoxMenuItem's "default tags" */
	private class TagMenuItem extends JCheckBoxMenuItem implements ActionListener {

		private static final long serialVersionUID = 1L;

		private TagMenuItem(final String tag) {
			super(tag, false);
			addActionListener(this);
		}

		@Override
		public void actionPerformed(final ActionEvent e) {
			final List<Path> selectedPaths = getPathAndFillManager().getPathsFiltered();
			final int n = selectedPaths.size();
			if (n == 0) {
				guiUtils.error("There are no traced paths.");
				return;
			}
			removeOrReapplyDefaultTag(selectedPaths, getActionCommand(), isSelected(), true);
		}

	}

	/** ActionListener for commands that can operate on multiple paths */
	private class MultiPathActionListener implements ActionListener {

		private static final String APPEND_ALL_CHILDREN_CMD = "Append All Children To Selection";
		private static final String APPEND_DIRECT_CHILDREN_CMD = "Append Direct Children To Selection";
		private final static String COLORS_MENU = "Color";
		private final static String DELETE_CMD = "Delete...";
		private final static String COMBINE_CMD = "Combine...";
		private final static String CONCATENATE_CMD = "Concatenate...";
		private final static String MERGE_PRIMARY_PATHS_CMD = "Merge Primary Paths(s) Into Shared Root...";
		private final static String REBUILD_CMD = "Rebuild...";
		private final static String DOWNSAMPLE_CMD = "Ramer-Douglas-Peucker Downsampling...";
		private final static String CUSTOM_TAG_CMD = "Custom...";
		private final static String LENGTH_TAG_CMD = "Length";
		private final static String MEAN_RADIUS_TAG_CMD = "Mean Radius";
		private final static String ORDER_TAG_CMD = "Path Order";
		private final static String TREE_TAG_CMD = "Cell ID";
		private final static String CHANNEL_TAG_CMD = "Traced Channel";
		private final static String FRAME_TAG_CMD = "Traced Frame";
		private final static String COUNT_TAG_CMD = "No. of Spines/Varicosities";
		private final static String SLICE_LABEL_TAG_CMD = "Slice Labels";

		private final static String REMOVE_ALL_TAGS_CMD = "Remove All Tags...";
		private static final String FILL_OUT_CMD = "Fill Out...";
		private static final String RESET_FITS = "Discard Fit(s)...";
		private final static String SPECIFY_RADIUS_CMD = "Specify Radius...";
		private final static String SPECIFY_COUNTS_CMD = "Specify No. Spines/Varicosities...";
		private final static String DISCONNECT_CMD = "Disconnect...";

		//private final static String MEASURE_CMD_SUMMARY = "Quick Measurements";
		private final static String CONVERT_TO_ROI_CMD = "Convert to ROIs...";
		private final static String CONVERT_TO_SKEL_CMD = "Skeletonize...";
		private final static String CONVERT_TO_SWC_CMD = "Save Subset as SWC...";
		private final static String PLOT_PROFILE_CMD = "Plot Profile";

		// color mapping commands
		private final static String COLORIZE_TREES_CMD = "Color Code Cell(s)...";
		private final static String COLORIZE_PATHS_CMD = "Color Code Path(s)...";
		// measure commands
		private final static String MEASURE_TREES_CMD = "Measure Cell(s)...";
		private final static String MEASURE_PATHS_CMD = "Measure Path(s)...";
		// distribution commands
		private final static String HISTOGRAM_PATHS_CMD = "Distribution of Path Properties...";
		private final static String HISTOGRAM_TREES_CMD = "Distribution of Cell Properties...";
		// timelapse analysis
		private final static String MATCH_PATHS_ACROSS_TIME_CMD = "Match Paths Across Time...";
		private final static String TIME_PROFILE_CMD = "Time Profile...";
		private final static String TIME_COLOR_CODING_CMD = "Color Code Paths Across Time...";
		private final static String SPINE_PROFILE_CMD = "Density Profiles...";
		private final static String SPINE_EXTRACT_CMD = "Extract Counts from Multi-point ROIs...";
		private final static String SPINE_COLOR_CODING_CMD = "Color Code Paths Using Densities...";

		// Custom tag definition: anything flanked by curly braces
		private final static String TAG_CUSTOM_PATTERN = " ?\\{.*\\}";
		// Built-in tag definition: anything flanked by square braces or |
		private final static String TAG_DEFAULT_PATTERN = " ?(\\[|\\|).*(\\]|\\|)";

		private void selectChildren(final List<Path> paths, final boolean recursive) {
			final ListIterator<Path> iter = paths.listIterator();
			while(iter.hasNext()){
				final Path p = iter.next();
				appendChildren(iter, p, recursive);
			}
			setSelectedPaths(paths, PathManagerUI.this);
			refreshManager(true, true, paths);
		}

		private void appendChildren(final ListIterator<Path> iter, final Path p, final boolean recursive) {
			if (p.children != null && !p.children.isEmpty()) {
				p.children.forEach(child -> {
					iter.add(child);
					if (recursive) appendChildren(iter, child, true);
				});
			}
		}

		@Override
		public void actionPerformed(final ActionEvent e) {

			final String cmd = e.getActionCommand();
			final List<Path> selectedPaths = getSelectedPaths(true);
			final int n = selectedPaths.size();

			if (n == 0) {
				guiUtils.error("There are no traced paths.");
				return;
			}

			// If no path is selected, remind user that action applies to all paths
			final boolean assumeAll = tree.getSelectionCount() == 0;
			// final boolean assumeAll = noSelection &&
			// guiUtils.getConfirmation("Currently
			// no paths are selected. Apply command to all paths?", cmd);
			// if (noSelection && !assumeAll) return;

			// Case 1: Non-destructive commands that do not require confirmation
			if (APPEND_ALL_CHILDREN_CMD.equals(cmd) || APPEND_DIRECT_CHILDREN_CMD.equals(cmd)) {
				if (assumeAll)
					guiUtils.error("No Path(s) are currently selected.");
				else 
					selectChildren(selectedPaths, APPEND_ALL_CHILDREN_CMD.equals(cmd));
				return;
			}
			else if (COLORS_MENU.equals(cmd)) {
				final SNTColor swcColor = colorMenu.getSelectedSWCColor();
				for (final Path p : selectedPaths)
					p.setColor(swcColor.color());
				refreshManager(true, true, selectedPaths);
				return;
			}
			else if (PLOT_PROFILE_CMD.equals(cmd)) {
				SwingUtilities.invokeLater(() -> {
					final ImagePlus imp = plugin.getImagePlus();
					if (noValidImageDataError()) return;
					if (imp != null && imp.getStack().isVirtual()) {
						guiUtils.error("Unfortunately virtual stacks cannot be profiled.");
							return;
					}

					final Tree tree = new Tree(selectedPaths);
					PathProfiler profiler = new PathProfiler(tree, imp);
					profiler.setNodeIndicesAsDistances(false);
					if (selectedPaths.size() == 1) {
						profiler.getPlot().show(); // all channels will be plotted
						return;
					}

					final int maxChannel = imp.getNChannels();
					int userChosenChannel = -1;
					if (maxChannel > 1) {
						final Double chPrompted = guiUtils.getDouble("<HTML><div WIDTH=550>"
								+ "Profile a specific channel? (If not, leave the choice at -1, "
								+ "to profile the channel in which the selected path(s) were traced.", 
								"Profile a Specific Channel?", -1);
						if (chPrompted == null) return;
						userChosenChannel = chPrompted.intValue();
						if (userChosenChannel == 0) userChosenChannel = -1;
						if (userChosenChannel > maxChannel) {
							guiUtils.error("Channel out of range! Image has only " + maxChannel + " channels.");
							profiler = null;
							return;
						}
					}
					profiler.getPlot(userChosenChannel).show();
					// NB: to use Scijava plotService instead:
					//profiler.setContext(plugin.getContext());
					//profiler.run();
				});
				return;
			}
//			else if (MEASURE_CMD_SUMMARY.equals(cmd)) {
//				final Tree tree = getSingleTree();
//				if (tree == null) return;
//				try {
//					final TreeAnalyzer ta = new TreeAnalyzer(tree);
//					ta.setContext(plugin.getContext());
//					if (ta.getParsedTree().isEmpty()) {
//						guiUtils.error("None of the selected paths could be measured.");
//						return;
//					}
//					ta.setTable(getTable(), TABLE_TITLE);
//					ta.summarize(tree.getLabel(), true);
//				}
//				catch (final IllegalArgumentException ignored) {
//					quickMeasurementsCmdError(guiUtils);
//					return;
//				}
//				return;
//			}
			else if (MEASURE_TREES_CMD.equals(cmd)) {
				final Collection<Tree> trees = getMultipleTrees();
				if (trees == null) return;
				final HashMap<String, Object> inputs = new HashMap<>();
				inputs.put("trees", trees);
				inputs.put("table", getTable());
				inputs.put("calledFromPathManagerUI", true);
				(plugin.getUI().new DynamicCmdRunner(AnalyzerCmd.class, inputs)).run();
				return;
			}
			else if (MEASURE_PATHS_CMD.equals(cmd)) {
				final HashMap<String, Object> inputs = new HashMap<>();
				inputs.put("paths", selectedPaths);
				inputs.put("proposedLabel", getDescription(selectedPaths));
				inputs.put("table", getTable());
				(plugin.getUI().new DynamicCmdRunner(PathAnalyzerCmd.class, inputs)).run();
				return;
			}
			else if (CONCATENATE_CMD.equals(cmd)) {
				if (selectedPaths.size() < 2) {
					guiUtils.error("You must have at least 2 Paths selected.");
					return;
				}
				final Map<Path, List<Path>> map = new HashMap<>();
				for (final Path p : selectedPaths) {
					if (p.getStartJoins() == null ||
							!selectedPaths.contains(p.getStartJoins()) ||
							!p.getStartJoinsPoint().isSameLocation(
									p.getStartJoins().getNode(p.getStartJoins().size() - 1)))
					{
						continue;
					}
					map.putIfAbsent(p.getStartJoins(), new ArrayList<>());
					map.get(p.getStartJoins()).add(p);
				}
				if (map.keySet().size() != selectedPaths.size() - 1 ||
						map.values().stream().anyMatch(l -> l.size() != 1))
				{
					guiUtils.error("Selected Paths must form a single, un-branched segment!");
					return;
				}
				final List<Path> sortedPaths = map.keySet().stream()
						.sorted(Comparator.comparingInt(Path::getOrder))
						.collect(Collectors.toList());
				final Path mergedPath = sortedPaths.get(0).createPath();
				mergedPath.setName(sortedPaths.get(0).getName());
				mergedPath.createCircles();
				mergedPath.setIsPrimary(sortedPaths.get(0).isPrimary());
				final Path firstStartJoin = sortedPaths.get(0).getStartJoins();
				final PointInImage firstStartJoinPoint = sortedPaths.get(0).getStartJoinsPoint();
				for (final Path p : sortedPaths) {
					mergedPath.add(p);
					// avoid CME
					for (final Path join : new ArrayList<>(p.somehowJoins)) {
						for (int i = 0; i < mergedPath.size(); ++i) {
							final PointInImage pim = mergedPath.getNode(i);
							if (join.getStartJoinsPoint() != null && join.getStartJoinsPoint().isSameLocation(pim)) {
								join.unsetStartJoin();
								join.setStartJoin(mergedPath, pim);
								break;
							}
						}
					}
					p.disconnectFromAll();
					pathAndFillManager.deletePath(p);
				}
				final Path lastChild = map.get(sortedPaths.get(sortedPaths.size()-1)).get(0);
				mergedPath.add(lastChild);
				// avoid CME
				for (final Path join : new ArrayList<>(lastChild.somehowJoins)) {
					for (int i = 0; i < mergedPath.size(); ++i) {
						final PointInImage pim = mergedPath.getNode(i);
						if (join.getStartJoins() != null && join.getStartJoinsPoint().isSameLocation(pim)) {
							join.unsetStartJoin();
							join.setStartJoin(mergedPath, pim);
							break;
						}
					}
				}
				lastChild.disconnectFromAll();
				pathAndFillManager.deletePath(lastChild);
				if (firstStartJoin != null) {
					mergedPath.setStartJoin(
							firstStartJoin,
							firstStartJoinPoint);
				}
				pathAndFillManager.addPath(mergedPath, false, false);
				// treeID is always overridden when adding a Path, so re-set it after adding
				mergedPath.setIDs(sortedPaths.get(0).getID(), sortedPaths.get(0).getTreeID());
			}
			else if (TIME_PROFILE_CMD.equals(cmd)) {
				final HashMap<String, Object> inputs = new HashMap<>();
				inputs.put("paths", selectedPaths);
				(plugin.getUI().new DynamicCmdRunner(PathTimeAnalysisCmd.class, inputs)).run();
				return;
			}
			else if (SPINE_PROFILE_CMD.equals(cmd)) {
				final HashMap<String, Object> inputs = new HashMap<>();
				inputs.put("paths", selectedPaths);
				(plugin.getUI().new DynamicCmdRunner(PathSpineAnalysisCmd.class, inputs)).run();
				return;
			}
			else if (SPINE_EXTRACT_CMD.equals(cmd)) {
				final HashMap<String, Object> inputs = new HashMap<>();
				inputs.put("paths", selectedPaths);
				(plugin.getUI().new DynamicCmdRunner(SpineExtractorCmd.class, inputs)).run();
				return;
			}
			else if (MATCH_PATHS_ACROSS_TIME_CMD.equals(cmd)) {
				final HashMap<String, Object> inputs = new HashMap<>();
				inputs.put("paths", selectedPaths);
				(plugin.getUI().new DynamicCmdRunner(PathMatcherCmd.class, inputs)).run();
				return;
			} else if (TIME_COLOR_CODING_CMD.equals(cmd)) {
				final Tree tree = new Tree(selectedPaths);
				if (tree == null || tree.isEmpty()) return;
				final Map<String, Object> input = new HashMap<>();
				input.put("tree", tree);
				input.put("onlyConnectivitySafeMetrics", true);
				input.put("measurementChoice", TreeColorMapper.PATH_FRAME);
				input.put("showInRecViewer", false);
				input.put("showPlot", false);
				final CommandService cmdService = plugin.getContext().getService(
						CommandService.class);
				cmdService.run(TreeMapperCmd.class, true, input); // will call #update() via SNT#updateAllViewers();
				return;
			} else if (SPINE_COLOR_CODING_CMD.equals(cmd)) {
				final Tree tree = new Tree(selectedPaths);
				if (tree == null || tree.isEmpty()) return;
				final Map<String, Object> input = new HashMap<>();
				input.put("tree", tree);
				input.put("onlyConnectivitySafeMetrics", true);
				input.put("measurementChoice", TreeColorMapper.AVG_SPINE_DENSITY);
				input.put("showInRecViewer", false);
				input.put("showPlot", false);
				final CommandService cmdService = plugin.getContext().getService(
						CommandService.class);
				cmdService.run(TreeMapperCmd.class, true, input); // will call #update() via SNT#updateAllViewers();
				return;
			} else if (CONVERT_TO_ROI_CMD.equals(cmd)) {
				final Map<String, Object> input = new HashMap<>();
				input.put("tree", new Tree(selectedPaths));
				input.put("imp", plugin.getImagePlus());
				final CommandService cmdService = plugin.getContext().getService(
					CommandService.class);
				cmdService.run(ROIExporterCmd.class, true, input);
				return;

			}
			else if (COLORIZE_TREES_CMD.equals(cmd)) {
				if (assumeAll) {
					Collection<Tree> trees = getMultipleTrees();
					if (trees == null || trees.isEmpty()) return;
					if (trees.size() == 1) {
						Tree tree = trees.iterator().next();
						Map<String, Object> input = new HashMap<>();
						input.put("tree", tree);
						CommandService cmdService = plugin.getContext().getService(
								CommandService.class);
						cmdService.run(TreeMapperCmd.class, true, input);
					}
					else {
						Map<String, Object> input = new HashMap<>();
						input.put("trees", trees);
						CommandService cmdService = plugin.getContext().getService(
								CommandService.class);
						cmdService.run(MultiTreeMapperCmd.class, true, input);
					}
				} else {
					final Tree tree = new Tree(selectedPaths);
					if (tree == null || tree.isEmpty()) return;
					final Map<String, Object> input = new HashMap<>();
					input.put("tree", tree);
					final CommandService cmdService = plugin.getContext().getService(
							CommandService.class);
					cmdService.run(TreeMapperCmd.class, true, input);
				}
				refreshManager(false, true, selectedPaths);
				return;

			} else if (COLORIZE_PATHS_CMD.equals(cmd)) {
				final Tree tree = new Tree(selectedPaths);
				if (tree == null || tree.isEmpty()) return;
				final Map<String, Object> input = new HashMap<>();
				input.put("tree", tree);
				input.put("onlyConnectivitySafeMetrics", true);
				final CommandService cmdService = plugin.getContext().getService(
						CommandService.class);
				cmdService.run(TreeMapperCmd.class, true, input);  // will call #update() via SNT#updateAllViewers();
				return;
			}
			else if (HISTOGRAM_TREES_CMD.equals(cmd)) {

				final Collection<Tree> trees = getMultipleTrees();
				if (trees == null) return;
				runDistributionAnalysisCmd(trees, null);
				return;
			}
			else if (HISTOGRAM_PATHS_CMD.equals(cmd)) {
				runHistogramPathsCmd(selectedPaths, null);
				return;
			}
			if (CUSTOM_TAG_CMD.equals(cmd)) {

				final String existingTags = extractTagsFromPath(selectedPaths.iterator()
					.next());
				String tags = guiUtils.getString(
					"Enter one or more (space or comma-separated list) tags:\n" +
						"(Clearing the field will remove existing tags)", "Custom Tags",
					existingTags);
				if (tags == null) return; // user pressed cancel
				tags = tags.trim();
				if (tags.isEmpty()) {
					selectedPaths.forEach(p -> {
						p.setName(p.getName().replaceAll(TAG_CUSTOM_PATTERN, ""));
					});
					displayTmpMsg("Tags removed");
				}
				else {
					applyCustomTags(selectedPaths, tags);
				}
				refreshManager(false, false, selectedPaths);
				return;

			}
			if (SLICE_LABEL_TAG_CMD.equals(cmd)) {
				removeOrReapplyDefaultTag(selectedPaths, SLICE_LABEL_TAG_CMD, true, true);
				return;
			}

			else if (CONVERT_TO_SKEL_CMD.equals(cmd)) {

				final Map<String, Object> input = new HashMap<>();
				final Tree tree = new Tree(selectedPaths);
				tree.setLabel("Paths");
				input.put("tree", tree);
				final CommandService cmdService = plugin.getContext().getService(
					CommandService.class);
				cmdService.run(SkeletonizerCmd.class, true, input);
				return;

			}
			else if (CONVERT_TO_SWC_CMD.equals(cmd)) {
				exportSelectedPaths(selectedPaths);
				return;

			}
			else if (FILL_OUT_CMD.equals(cmd)) {
				if (noValidImageDataError()) return;
				plugin.startFillingPaths(new HashSet<>(selectedPaths));
				return;

			}
			else if (SPECIFY_RADIUS_CMD.equals(e.getActionCommand())) {
				if (allUsingFittedVersion(selectedPaths)) {
					guiUtils.error("This command only applies to unfitted paths.");
					return;
				}
				final double rad = 2 * plugin.getMinimumSeparation();
				final Double userRad = guiUtils.getDouble(
					"<HTML><body><div style='width:" + Math.min(getWidth(), 500) + ";'>" +
						"Please specify a constant radius to be applied to all the nodes " +
						"of selected path(s). This setting only applies to unfitted " +
						"paths and <b>overrides</b> any existing values.<br> NB: You can use " +
						"the <i>Transform Paths</i> script to scale existing radii.",
					"Assign Constant Radius", rad);
				if (userRad == null) {
					return; // user pressed cancel
				}
				if (Double.isNaN(userRad) || userRad < 0) {
					guiUtils.error("Invalid value.");
					return;
				}
				if (userRad == 0d && !guiUtils.getConfirmation(
					"Discard thickness information from selected paths?",
					"Confirm?"))
				{
					return;
				}
				selectedPaths.forEach(p -> {
					if (!p.isFittedVersionOfAnotherPath()) p.setRadius(userRad);
				});
				guiUtils.tempMsg("Command finished. Fitted path(s) ignored.");
				plugin.updateAllViewers();
				return;
			}
			else if (SPECIFY_COUNTS_CMD.equals(e.getActionCommand())) {
				final Double userCounts = guiUtils.getDouble("<HTML><body><div style='width:"
						+ Math.min(getWidth(), 500) + ";'>"
						+ "Please specify the no. of spines (or varicosities) to be associated to selected path(s):",
						"Spine/Varicosity Counts", 0);
				if (userCounts == null) {
					return; // user pressed cancel
				}
				if (Double.isNaN(userCounts) || userCounts < 0) {
					guiUtils.error("Invalid value.");
					return;
				}
				selectedPaths.forEach(p -> {
					p.setSpineOrVaricosityCount(userCounts.intValue());
				});
				return;
			}
			// Case 2: Commands that require some sort of confirmation
			else if (DELETE_CMD.equals(cmd)) {
				if (guiUtils.getConfirmation((assumeAll) ? "Are you really sure you want to delete everything?"
						: "Delete the selected " + n + " path(s)?", "Confirm Deletion?")) {
					deletePaths(selectedPaths);
					if (assumeAll) setSelectAllTagsMenu(false);
				}
				return;
			}
			else if (REMOVE_ALL_TAGS_CMD.equals(cmd)) {
				if (plugin.getUI().askUserConfirmation && !guiUtils.getConfirmation(
					"Remove all tags from " + ((assumeAll) ? "all " : "the selected ") +
						n + " paths? (SWC-type tags will be preserved)",
					"Confirm Tag Removal?"))
				{
					return;
				}
				selectedPaths.forEach(p -> {
					p.setName(p.getName().replaceAll(TAG_CUSTOM_PATTERN, ""));
					p.setName(p.getName().replaceAll(TAG_DEFAULT_PATTERN, ""));
				});
				if (assumeAll || n == pathAndFillManager.size())
					setSelectAllTagsMenu(false);
				resetPathsColor(selectedPaths); // will call refreshManager
				return;
			}
			else if (COMBINE_CMD.equals(cmd)) {
				if (n == 1) {
					displayTmpMsg("You must have at least two paths selected.");
					return;
				}
				final Path refPath = selectedPaths.iterator().next();
				if (!guiUtils.getConfirmation("Combine " + n +
					" selected paths? (this destructive operation cannot be undone!)",
					"Confirm Destructive Operation?"))
				{
					return;
				}
				final HashSet<Path> pathsToMerge = new HashSet<>();
				for (final Path p : selectedPaths) {
					if (refPath.equals(p) || refPath.somehowJoins.contains(p) ||
						p.somehowJoins.contains(refPath)) continue;
					pathsToMerge.add(p);
				}
				if (pathsToMerge.isEmpty()) {
					guiUtils.error("Only non-connected paths can be combined.");
					return;
				}
				if (pathsToMerge.size() < n - 1 && !guiUtils.getConfirmation(
					"Some of the selected paths are connected and cannot be combined. " +
						"Proceed by combinining the " + pathsToMerge.size() +
						" disconnected path(s) in the selection?",
					"Only Disconnected Paths Can Be Combined"))
				{
					return;
				}
				for (final Path p : pathsToMerge) {
					refPath.add(p);
					p.disconnectFromAll();
					getPathAndFillManager().deletePath(p);
				}
				removeOrReapplyDefaultTag(selectedPaths, ORDER_TAG_CMD, false, false);
				refreshManager(true, true, selectedPaths);
				return;

			}
			else if (DISCONNECT_CMD.equals(cmd)) {
				if (!guiUtils.getConfirmation("Disconnect " + n + " path(s) from all connections? "
						+ "Connectivity will be re-assessed for <i>all</i> paths. IDs will be reset and "
						+ "existing fits discarded.", "Confirm Disconnect?"))
					return;
				for (final Path p : selectedPaths)
					p.disconnectFromAll();
				rebuildRelationShips(); // will call refreshManager()
				return;
			}
			else if (MERGE_PRIMARY_PATHS_CMD.equals(cmd)) {
				if (n == 1) {
					displayTmpMsg("You must have at least two primary paths selected.");
					return;
				}
				List<Path> primaryPaths = new ArrayList<>();
				List<PointInImage> rootNodes = new ArrayList<PointInImage>();
				for (Path path : selectedPaths) {
					if (path.isPrimary()) {
						primaryPaths.add(path);
						rootNodes.add(path.getNode(0));
					}
				}
				if (primaryPaths.size() < 2) {
					guiUtils.error(
							"There must be at least two primary paths selected.",
							"Invalid Merge Condition");
					return;
				}
				if (!guiUtils.getConfirmation("Merge " + primaryPaths.size() + " selected "
						+ "primary paths and rebuild Path relationships? (this destructive "
						+ "operation cannot be undone!)", "Confirm merge?")) {
					return;
				}
				// create a new empty Path with the same properties (i.e., spatial calibration)
				// of the first path found in the list (In SNT, scaling is set on a per-Path basis).
				// Assign unique IDs to avoid conflicts with existing IDs
				final Path newSoma = primaryPaths.get(0).createPath();
				newSoma.setIsPrimary(true);
				newSoma.setName("Root centroid");
				// Add a node to the newly defined path, corresponding to the centroid of
				// all other root nodes and add this new single-point path to the manager
				newSoma.addNode(SNTPoint.average(rootNodes));
				pathAndFillManager.addPath(newSoma, false, true);
				// Now connect all of root nodes to it
				primaryPaths.forEach(primaryPath -> {
					primaryPath.setStartJoin(newSoma, newSoma.getNode(0));
				});
				rebuildRelationShips(); // will call refreshManager()
				return;
			}
			else if (REBUILD_CMD.equals(cmd)) {
				if (!guiUtils.getConfirmation("Rebuild all path relationships? " +
						"This will reset all IDs and recompute connectivity for all " +
						"paths. Existing fits will be discarded.", "Confirm Rebuild?"))
				{
					return;
				}
				rebuildRelationShips();
				return;
			}
			else if (DOWNSAMPLE_CMD.equals(cmd)) {
				final double minSep = plugin.getMinimumSeparation();
				final Double userMaxDeviation = guiUtils.getDouble(
					"<HTML><body><div style='width:500;'>" +
						"Please specify the maximum permitted distance between nodes:<ul>" +
						"<li>This destructive operation cannot be undone!</li>" +
						"<li>Paths can only be downsampled: Smaller inter-node distances will not be interpolated</li>" +
						"<li>Currently, the smallest voxel dimension is " + SNTUtils
							.formatDouble(minSep, 3) + plugin.spacing_units + "</li>",
					"Downsampling: " + n + " Selected Path(s)", 2 * minSep);
				if (userMaxDeviation == null) return; // user pressed cancel

				final double maxDeviation = userMaxDeviation;
				if (Double.isNaN(maxDeviation) || maxDeviation <= 0) {
					guiUtils.error(
						"The maximum permitted distance must be a postive number",
						"Invalid Input");
					return;
				}
				for (final Path p : selectedPaths) {
					Path pathToUse = p;
					if (p.getUseFitted()) {
						pathToUse = p.getFitted();
					}
					pathToUse.downsample(maxDeviation);
				}
				// Make sure that the 3D viewer and the stacks are redrawn:
				plugin.updateAllViewers();
				return;

			}
			else if (RESET_FITS.equals(cmd)) {
				if (!guiUtils.getConfirmation("Discard existing fits?",
					"Confirm Discard?")) return;
				for (final Path p : selectedPaths) {
					p.discardFit();
				}
				refreshManager(true, false, selectedPaths);
				return;

			}
			else if (e.getSource().equals(fitVolumeMenuItem) || cmd.toLowerCase().contains("fit paths")) {

				// this MenuItem is a toggle: check if it is set for 'unfitting'
				if (fitVolumeMenuItem.getText().contains("Un-fit")) {
					for (final Path p : selectedPaths)
						p.setUseFitted(false);
					refreshManager(true, false, selectedPaths);
					return;
				}

				final boolean imageNotAvailable = !plugin.accessToValidImageData();
				final ArrayList<PathFitter> pathsToFit = new ArrayList<>();
				int skippedFits = 0;

				try {
					for (final Path p : selectedPaths) {

						// If the fitted version is already being used. Do nothing
						if (p.getUseFitted()) {
							continue;
						}

						// A fitted version does not exist
						else if (p.getFitted() == null) {
							if (imageNotAvailable) {
								// Keep a tally of how many computations we are skipping
								skippedFits++;
							} else {
								// Prepare for computation
								final PathFitter pathFitter = new PathFitter(plugin, p);
								pathsToFit.add(pathFitter);
							}
						}

						// Just use the existing fitted version:
						else {
							p.setUseFitted(true);
						}
					}

				} catch (final IllegalArgumentException ex) {
					guiUtils.centeredMsg(ex.getMessage(), "Error");
					return;
				}

				if (skippedFits == n) {
					noValidImageDataError();
				}
				else if (pathsToFit.size() > 0) {

					final int finalSkippedFits = skippedFits;
					if (fittingHelper == null) fittingHelper = new FitHelper();
					fittingHelper.pathsToFit = pathsToFit;
					fittingHelper.fitUsingPrompAsNeeded(); // call refreshManager
					if (finalSkippedFits > 0) {
						guiUtils.centeredMsg("Since no image data is available, " + finalSkippedFits + "/"
								+ selectedPaths.size() + " fits could not be computed", "Valid Image Data Unavailable");
					}
				}
				else {
					refreshManager(true, false, selectedPaths);
				}
				return;

			}
	
			else {
				SNTUtils.error("Unexpectedly got an event from an unknown source: " + e);
				return;
			}
		}

		private void rebuildRelationShips() {
			final List<String> activeTags = guessTagsCurrentlyActive();
			tree.clearSelection(); // existing selections could change after the rebuild
			pathAndFillManager.rebuildRelationships();
			activeTags.forEach( tag -> {
				removeOrReapplyDefaultTag(pathAndFillManager.getPaths(), tag, true, false);
			});
			refreshManager(true, true, null);
		}

		private boolean allPathNamesContain(final Collection<Path> selectedPaths,
			final String string)
		{
			if (string == null || string.trim().isEmpty()) return false;
			for (final Path p : selectedPaths) {
				if (!p.getName().contains(string)) return false;
			}
			return true;
		}

		private String getDescription(final Collection<Path> selectedPaths) {
			String description;
			final int n = selectedPaths.size();
			if (n == getPathAndFillManager().getPathsFiltered().size()) {
				description = "All Paths";
			}
			else if (n == 1) {
				description = selectedPaths.iterator().next().getName();
			}
			else if (n > 1 && allPathNamesContain(selectedPaths, getSearchable()
				.getSearchingText()))
			{
				description = "Filter [" + getSearchable().getSearchingText() + "]";
			}
			else {
				description = "Path IDs [" + Path.pathsToIDListString(new ArrayList<>(
					selectedPaths)) + "]";
			}
			return description;
		}

	}

	private boolean noValidImageDataError() {
		final boolean invalidImage = !plugin.accessToValidImageData();
		if (invalidImage) guiUtils.error(
			"There is currently no valid image data to process.");
		return invalidImage;
	}

	public static String extractTagsFromPath(final Path p) {
		final String name = p.getName();
		final int openingDlm = name.indexOf("{");
		final int closingDlm = name.lastIndexOf("}");
		if (closingDlm > openingDlm) {
			return name.substring(openingDlm + 1, closingDlm);
		}
		return "";
	}

	/* IDE debug method */
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		final ImagePlus imp = new ImagePlus();
		final SNT snt = new SNT(ij.context(), imp);
		final PathManagerUI pm = new PathManagerUI(snt);
		pm.setVisible(true);
	}

}
