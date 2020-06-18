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
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import org.scijava.command.CommandModule;
import org.scijava.command.CommandService;
import org.scijava.display.Display;
import org.scijava.display.DisplayService;
import org.scijava.prefs.PrefService;

import com.jidesoft.swing.Searchable;
import com.jidesoft.swing.TreeSearchable;

import ij.ImagePlus;
import net.imagej.ImageJ;
import sc.fiji.snt.analysis.PathProfiler;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.TreeAnalyzer;
import sc.fiji.snt.gui.ColorMenu;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.gui.PathManagerUISearchableBar;
import sc.fiji.snt.gui.SwingSafeResult;
import sc.fiji.snt.gui.cmds.DistributionBPCmd;
import sc.fiji.snt.gui.cmds.PathFitterCmd;
import sc.fiji.snt.gui.cmds.SWCTypeOptionsCmd;
import sc.fiji.snt.plugin.AnalyzerCmd;
import sc.fiji.snt.plugin.ROIExporterCmd;
import sc.fiji.snt.plugin.SkeletonizerCmd;
import sc.fiji.snt.plugin.TreeMapperCmd;
import sc.fiji.snt.util.SNTColor;
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
	private DefaultMutableTreeNode root;
	private final SNT plugin;
	private final PathAndFillManager pathAndFillManager;
	private SNTTable table;

	protected static final String TABLE_TITLE = "SNT Measurements";
	protected final GuiUtils guiUtils;
	private final JScrollPane scrollPane;
	private final JMenuBar menuBar;
	private final JPopupMenu popup;
	private final JMenu swcTypeMenu;
	private final JMenu morphoTagsMenu;
	private final JMenu imageTagsMenu;
	private ButtonGroup swcTypeButtonGroup;
	private final ColorMenu colorMenu;
	private final JMenuItem fitVolumeMenuItem;
	private FitHelper fittingHelper;

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

		root = new DefaultMutableTreeNode("All Paths");
		tree = new HelpfulJTree(root);
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

		menuBar = new JMenuBar();
		setJMenuBar(menuBar);

		final JMenu editMenu = new JMenu("Edit");
		menuBar.add(editMenu);
		editMenu.add(getDeleteMenuItem(multiPathListener));
		editMenu.add(getRenameMenuItem(singlePathListener));
		editMenu.addSeparator();
		final JMenuItem primaryMitem = new JMenuItem(
			SinglePathActionListener.MAKE_PRIMARY_CMD);
		primaryMitem.addActionListener(singlePathListener);
		editMenu.add(primaryMitem);
		JMenuItem jmi = new JMenuItem(SinglePathActionListener.DISCONNECT_CMD);
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.UNLINK));
		jmi.addActionListener(singlePathListener);
		editMenu.add(jmi);
		jmi = new JMenuItem(MultiPathActionListener.MERGE_CMD);
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.LINK));
		jmi.addActionListener(multiPathListener);
		editMenu.add(jmi);
		editMenu.addSeparator();
		jmi = new JMenuItem(MultiPathActionListener.SPECIFY_RADIUS_CMD);
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.CIRCLE));
		jmi.addActionListener(multiPathListener);
		editMenu.add(jmi);
		jmi = new JMenuItem(MultiPathActionListener.DOWNSAMPLE_CMD);
		jmi.addActionListener(multiPathListener);
		editMenu.add(jmi);

		final JMenu tagsMenu = new JMenu("Tag ");
		menuBar.add(tagsMenu);
		swcTypeMenu = new JMenu("Type");
		swcTypeMenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.ID));
		tagsMenu.add(swcTypeMenu);
		assembleSWCtypeMenu(false);
		colorMenu = new ColorMenu(MultiPathActionListener.COLORS_MENU);
		colorMenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.COLOR));
		colorMenu.addActionListener(multiPathListener);
		tagsMenu.add(colorMenu);

		imageTagsMenu = new JMenu("Image Metadata");
		imageTagsMenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.IMAGE));
		final JCheckBoxMenuItem tagChannelCbmi = new JCheckBoxMenuItem(
			MultiPathActionListener.CHANNEL_TAG_CMD, false);
		tagChannelCbmi.addItemListener(multiPathListener);
		imageTagsMenu.add(tagChannelCbmi);
		final JCheckBoxMenuItem tagFrameCbmi = new JCheckBoxMenuItem(
			MultiPathActionListener.FRAME_TAG_CMD, false);
		tagFrameCbmi.addItemListener(multiPathListener);
		imageTagsMenu.add(tagFrameCbmi);
		jmi = new JMenuItem(MultiPathActionListener.SLICE_LABEL_TAG_CMD);
		jmi.addActionListener(multiPathListener);
		imageTagsMenu.add(jmi);
		tagsMenu.add(imageTagsMenu);

		morphoTagsMenu = new JMenu("Morphometry");
		morphoTagsMenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.RULER));
		final JCheckBoxMenuItem tagTreeCbmi = new JCheckBoxMenuItem(
				MultiPathActionListener.TREE_TAG_CMD, false);
		tagTreeCbmi.addItemListener(multiPathListener);
		morphoTagsMenu.add(tagTreeCbmi);
		final JCheckBoxMenuItem tagLengthCbmi = new JCheckBoxMenuItem(
			MultiPathActionListener.LENGTH_TAG_CMD, false);
		tagLengthCbmi.addItemListener(multiPathListener);
		morphoTagsMenu.add(tagLengthCbmi);
		final JCheckBoxMenuItem tagRadiusCbmi = new JCheckBoxMenuItem(
			MultiPathActionListener.MEAN_RADIUS_TAG_CMD, false);
		tagRadiusCbmi.addItemListener(multiPathListener);
		morphoTagsMenu.add(tagRadiusCbmi);
		tagsMenu.add(morphoTagsMenu);
		final JCheckBoxMenuItem tagOrderCbmi = new JCheckBoxMenuItem(
				MultiPathActionListener.ORDER_TAG_CMD, false);
			tagOrderCbmi.addItemListener(multiPathListener);
			morphoTagsMenu.add(tagOrderCbmi);
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

		final JMenu fitMenu = new JMenu("Refine/Fit");
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
		jmi = SNTUI.menuItemTriggeringURL("<HTML>Help on <i>Fitting", FIT_URI);
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.QUESTION));
		fitMenu.add(jmi);

		final JMenu fillMenu = new JMenu("Fill");
		menuBar.add(fillMenu);
		jmi = new JMenuItem(MultiPathActionListener.FILL_OUT_CMD);
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.FILL));
		jmi.addActionListener(multiPathListener);
		fillMenu.add(jmi);
		fillMenu.addSeparator();
		jmi = SNTUI.menuItemTriggeringURL("<HTML>Help on <i>Filling", FillManagerUI.FILLING_URI);
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.QUESTION));
		fillMenu.add(jmi);

		final JMenu advanced = new JMenu("Analyze");
		menuBar.add(advanced);
		jmi = new JMenuItem(MultiPathActionListener.COLORIZE_PATH_CMD);
		// jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.COLOR2));
		jmi.addActionListener(multiPathListener);
		advanced.add(jmi);
		jmi = new JMenuItem(MultiPathActionListener.HISTOGRAM_CMD);
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.CHART));
		jmi.addActionListener(multiPathListener);
		advanced.add(jmi);
		jmi = new JMenuItem(MultiPathActionListener.MEASURE_CMD_OPTIONS);
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.TABLE));
		jmi.addActionListener(multiPathListener);
		advanced.add(jmi);
		jmi = new JMenuItem(MultiPathActionListener.MEASURE_CMD_SUMMARY);
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.ROCKET));
		jmi.addActionListener(multiPathListener);
		advanced.add(jmi);
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
		jmi = new JMenuItem(MultiPathActionListener.CONVERT_TO_SWC_CMD);
		jmi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.EXPORT));
		jmi.addActionListener(multiPathListener);
		advanced.add(jmi);

		// Search Bar TreeSearchable
		final PathManagerUISearchableBar searchableBar = new PathManagerUISearchableBar(this);
		popup = new JPopupMenu();
		popup.add(getDeleteMenuItem(multiPathListener));
		popup.add(getRenameMenuItem(singlePathListener));
		popup.addSeparator();
		JMenuItem pjmi = popup.add(NoPathActionListener.COLLAPSE_ALL_CMD);
		pjmi.addActionListener(noPathListener);
		pjmi = popup.add(NoPathActionListener.EXPAND_ALL_CMD);
		pjmi.addActionListener(noPathListener);
		pjmi = popup.add(NoPathActionListener.SELECT_NONE_CMD);
		pjmi.addActionListener(noPathListener);
		pjmi = popup.add(MultiPathActionListener.APPEND_CHILDREN_CMD);
		pjmi.addActionListener(multiPathListener);
		popup.addSeparator();
		final JMenu selectByColorMenu = searchableBar.getColorFilterMenu();
		selectByColorMenu.setText("Select by Color Tag");
		popup.add(selectByColorMenu);
		final JMenu selectByMorphoMenu = searchableBar.getMorphoFilterMenu();
		selectByMorphoMenu.setText("Select by Morphometric Trait");
		popup.add(selectByMorphoMenu);
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
				if (e.isConsumed())
					return;
				if (e.isPopupTrigger()) {
					showPopup(e);
				} else if (tree.getRowForLocation(e.getX(), e.getY()) == -1) {
					tree.clearSelection(); // Deselect when clicking on 'empty space'
				}
				e.consume();
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

	private JMenuItem getRenameMenuItem(final SinglePathActionListener singlePathListener) {
		final JMenuItem renameMitem = new JMenuItem(
			SinglePathActionListener.RENAME_CMD);
		renameMitem.addActionListener(singlePathListener);
		return renameMitem;
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
				refreshManager(true, assignColors);
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
			if (assignColors) {
				pathAndFillManager.getPathsFiltered().forEach(p -> p.setColor(map.get(p
					.getSWCType())));
			}
			else {
				pathAndFillManager.getPathsFiltered().forEach(p -> p.setColor((Color)null));
			}
			refreshManager(false, true);
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
		refreshManager(true, true);
	}

	private void deletePaths(final Collection<Path> pathsToBeDeleted) {
		final boolean resetIDs = pathsToBeDeleted.size() == pathAndFillManager.size();
		for (final Path p : pathsToBeDeleted) {
			if (plugin !=null && p.isBeingEdited()) plugin.enableEditMode(false);
			p.disconnectFromAll();
			pathAndFillManager.deletePath(p);
		}
		if (resetIDs) {
			pathAndFillManager.resetIDs();
			plugin.unsavedPaths = false;
		}
		refreshManager(false, true);
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
	public Collection<Path> getSelectedPaths(final boolean ifNoneSelectedGetAll) {
		return SwingSafeResult.getResult(() -> {
			if (ifNoneSelectedGetAll && tree.getSelectionCount() == 0)
				return pathAndFillManager.getPathsFiltered();
			final Collection<Path> result = new ArrayList<>();
			final TreePath[] selectedPaths = tree.getSelectionPaths();
			if (selectedPaths == null || selectedPaths.length == 0) {
				return result;
			}
			for (final TreePath tp : selectedPaths) {
				final DefaultMutableTreeNode node = (DefaultMutableTreeNode) (tp
					.getLastPathComponent());
				if (node != root) {
					final Path p = (Path) node.getUserObject();
					result.add(p);
				}
			}
			return result;
		});
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

	private void showPopup(final MouseEvent me) {
		assert SwingUtilities.isEventDispatchThread();
		popup.show(me.getComponent(), me.getX(), me.getY());
	}

	private void getExpandedPaths(final HelpfulJTree tree, final TreeModel model,
		final MutableTreeNode node, final HashSet<Path> set)
	{
		assert SwingUtilities.isEventDispatchThread();
		final int count = model.getChildCount(node);
		for (int i = 0; i < count; i++) {
			final DefaultMutableTreeNode child = (DefaultMutableTreeNode) model
				.getChild(node, i);
			final Path p = (Path) child.getUserObject();
			if (tree.isExpanded(child.getPath())) {
				set.add(p);
			}
			if (!model.isLeaf(child)) getExpandedPaths(tree, model, child, set);
		}
	}

	private void setExpandedPaths(final HelpfulJTree tree, final TreeModel model,
		final MutableTreeNode node, final HashSet<Path> set, final Path justAdded)
	{
		assert SwingUtilities.isEventDispatchThread();
		final int count = model.getChildCount(node);
		for (int i = 0; i < count; i++) {
			final DefaultMutableTreeNode child = (DefaultMutableTreeNode) model
				.getChild(node, i);
			final Path p = (Path) child.getUserObject();
			if (set.contains(p) || ((justAdded != null) && (justAdded == p))) {
				tree.setExpanded(child.getPath(), true);
			}
			if (!model.isLeaf(child)) setExpandedPaths(tree, model, child, set,
				justAdded);
		}

	}

	/* (non-Javadoc)
	 * @see PathAndFillListener#setSelectedPaths(java.util.HashSet, java.lang.Object)
	 */
	@Override
	public void setSelectedPaths(final HashSet<Path> selectedPaths,
		final Object source)
	{
		SwingUtilities.invokeLater(new Runnable() {

			@Override
			public void run() {
				if (source == this || !pathAndFillManager.enableUIupdates) return;
				final TreePath[] noTreePaths = {};
				tree.setSelectionPaths(noTreePaths);
				setSelectedPaths(tree, tree.getModel(), root, selectedPaths, selectedPaths.size()==1);
			}
		});
	}

	private void setSelectedPaths(final HelpfulJTree tree, final TreeModel model,
		final MutableTreeNode node, final HashSet<Path> set, final boolean updateCTpositon)
	{
		assert SwingUtilities.isEventDispatchThread();
		final int count = model.getChildCount(node);
		for (int i = 0; i < count; i++) {
			final DefaultMutableTreeNode child = (DefaultMutableTreeNode) model
				.getChild(node, i);
			final Path p = (Path) child.getUserObject();
			if (set.contains(p)) {
				tree.setSelected(child.getPath());
				if (updateCTpositon && plugin != null) {
					updateHyperstackPosition(p);
				}
			}
			if (!model.isLeaf(child)) setSelectedPaths(tree, model, child, set, updateCTpositon);
		}
	}

	/* (non-Javadoc)
	 * @see PathAndFillListener#setPathList(java.lang.String[], Path, boolean)
	 */
	@Override
	public void setPathList(final String[] pathList, final Path justAdded,
		final boolean expandAll)
	{

		if (!pathAndFillManager.enableUIupdates) return;
		SwingUtilities.invokeLater(() -> {

			// Save the selection state:
			final TreePath[] selectedBefore = tree.getSelectionPaths();
			final HashSet<Path> selectedPathsBefore = new HashSet<>();
			final HashSet<Path> expandedPathsBefore = new HashSet<>();

			if (selectedBefore != null) for (final TreePath tp : selectedBefore) {
				final DefaultMutableTreeNode dmtn = (DefaultMutableTreeNode) tp
						.getLastPathComponent();
				if (dmtn != root) {
					final Path p = (Path) dmtn.getUserObject();
					selectedPathsBefore.add(p);
				}
			}

			// Save the expanded state:
			getExpandedPaths(tree, tree.getModel(), root, expandedPathsBefore);

			/*
			 * Ignore the arguments and get the real path list from the PathAndFillManager:
			 */
			final DefaultMutableTreeNode newRoot = new DefaultMutableTreeNode(
				"All Paths");
			final DefaultTreeModel model = new DefaultTreeModel(newRoot);
			// DefaultTreeModel model = (DefaultTreeModel)tree.getModel();
			final Path[] primaryPaths = pathAndFillManager.getPathsStructured();
			for (final Path primaryPath : primaryPaths) {
				// Add the primary path if it's not just a fitted version of
				// another:
				if (!primaryPath.isFittedVersionOfAnotherPath()) addNode(newRoot,
						primaryPath, model);
			}
			root = newRoot;
			tree.setModel(model);

			model.reload();

			// Set back the expanded state:
			if (expandAll) {
				for (int i3 = 0; i3 < tree.getRowCount(); ++i3)
					tree.expandRow(i3);
			}
			else setExpandedPaths(tree, model, root, expandedPathsBefore, justAdded);

			setSelectedPaths(tree, model, root, selectedPathsBefore, false);
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
		final Collection<Tree> singletonTree = getTreesPrompt(false);
		return (singletonTree == null) ? null : singletonTree.iterator().next();
	}

	protected Collection<Tree> getTrees() {
		return getTreesPrompt(true);
	}

	private Collection<Tree> getTreesPrompt(final boolean includeAll) {
		final Collection<Tree> trees = pathAndFillManager.getTrees();
		if (trees.size() == 1) return trees;
		final ArrayList<String> treeLabels = new ArrayList<>(trees.size());
		if (includeAll)
			treeLabels.add("   -- All --  ");
		trees.forEach(t -> treeLabels.add(t.getLabel()));
		final String choice = guiUtils.getChoice("Multiple rooted structures exist. Which one should be considered?",
				"Which Structure?", treeLabels.toArray(new String[trees.size()]), treeLabels.get(0));
		if (includeAll && "   -- All --  ".equals(choice))
			return trees;
		for (final Tree t : trees) {
			if (t.getLabel().equals(choice)) return Collections.singleton(t);
		}
		
		return null; // user pressed canceled prompt
	}

	/* (non-Javadoc)
	 * @see PathAndFillListener#setFillList(java.lang.String[])
	 */
	@Override
	public void setFillList(final String[] fillList) {}

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
			final int processors = Math.min(numberOfPathsToFit, plugin.getPrefs().getThreads());
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
						pathsToFit = null;
					}
					return null;
				}

				@Override
				protected void done() {
					refreshManager(true, false);
					msg.dispose();
					plugin.changeUIState(preFittingState);
					setEnabledCommands(true);
					ui.showStatus(null, false);
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
		private final TreeSearchable searchable;

		public HelpfulJTree(final TreeNode root) {
			super(root);
			@SuppressWarnings("serial")
			final DefaultTreeCellRenderer renderer = new DefaultTreeCellRenderer() {

				@Override
				public Component getTreeCellRendererComponent(final JTree tree,
					final Object value, final boolean selected, final boolean expanded,
					final boolean isLeaf, final int row, final boolean focused)
				{
					final Component c = super.getTreeCellRendererComponent(tree, value,
						selected, expanded, isLeaf, row, focused);
					final TreePath tp = tree.getPathForRow(row);
					if (tp == null) return c;
					final DefaultMutableTreeNode node = (DefaultMutableTreeNode) (tp
						.getLastPathComponent());
					if (node == null || node == root) return c;
					final Path p = (Path) node.getUserObject();
					final Color color = p.getColor();
					if (color == null) return c;
					if (isLeaf) setIcon(new NodeIcon(NodeIcon.EMPTY, color));
					else if (!expanded) setIcon(new NodeIcon(NodeIcon.PLUS, color));
					else setIcon(new NodeIcon(NodeIcon.MINUS, color));
					return c;
				}
			};
			renderer.setClosedIcon(new NodeIcon(NodeIcon.PLUS));
			renderer.setOpenIcon(new NodeIcon(NodeIcon.MINUS));
			renderer.setLeafIcon(new NodeIcon(NodeIcon.EMPTY));
			setCellRenderer(renderer);
			getSelectionModel().setSelectionMode(
				TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
			setExpandsSelectedPaths(true);
			setScrollsOnExpand(true);
			setRowHeight(getPreferredRowSize());
			searchable = new TreeSearchable(this);
		}

		public boolean isExpanded(final Object[] path) {
			assert SwingUtilities.isEventDispatchThread();
			final TreePath tp = new TreePath(path);
			return isExpanded(tp);
		}

		public void setExpanded(final Object[] path, final boolean expanded) {
			assert SwingUtilities.isEventDispatchThread();
			final TreePath tp = new TreePath(path);
			setExpandedState(tp, expanded);
		}

		public void setSelected(final Object[] path) {
			assert SwingUtilities.isEventDispatchThread();
			final TreePath tp = new TreePath(path);
			addSelectionPath(tp);
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
		final boolean refreshViewers)
	{
		pathAndFillManager.resetListeners(null);
		if (refreshViewers) plugin.updateAllViewers();
		if (!refreshCmds) return;
		final Collection<Path> selectedPaths = getSelectedPaths(true);
		if (tree.getSelectionCount() == 1) updateCmdsOneSelected(selectedPaths
			.iterator().next());
		else updateCmdsManyOrNoneSelected(selectedPaths);
	}

	/**
	 * Refreshes (Repaints) the Path Manager JTree to reflect changes in Path
	 * names/colors.
	 */
	public void update() {
		final DefaultTreeModel model = ((DefaultTreeModel)tree.getModel());
		model.nodeChanged((TreeNode) model.getRoot());
	}

	protected void closeTable() {
		final Display<?> display = plugin.getContext().getService(
			DisplayService.class).getDisplay(TABLE_TITLE);
		if (display != null && display.isDisplaying(table)) display.close();
	}

	protected SNTTable getTable() {
		if (table == null) table = new SNTTable();
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

	private void removeTags(final Collection<Path> selectedPaths,
		final String pattern)
	{
		for (final Path p : selectedPaths) {
			p.setName(p.getName().replaceAll(pattern, ""));
		}
	}

	private void removeAllOrderTags() {
		tree.clearSelection();
		removeTags(getSelectedPaths(true), MultiPathActionListener.TAG_ORDER_PATTERN);
	}

	/** ActionListener for commands that do not deal with paths */
	private class NoPathActionListener implements ActionListener {

		private final static String EXPAND_ALL_CMD = "Expand All";
		private final static String COLLAPSE_ALL_CMD = "Collapse All";
		private final static String SELECT_NONE_CMD = "Deselect / Select All";

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
		private final static String DISCONNECT_CMD = "Disconnect...";
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
						refreshManager(false, false);
					}
					return;
				case MAKE_PRIMARY_CMD:
					final HashSet<Path> pathsExplored = new HashSet<>();
					p.setIsPrimary(true);
					pathsExplored.add(p);
					p.unsetPrimaryForConnected(pathsExplored);
					removeAllOrderTags();
					refreshManager(false, false);
					return;

				case DISCONNECT_CMD:
					if (!guiUtils.getConfirmation("Disconnect \"" + p.toString() +
							"\" from all it connections?", "Confirm Disconnect")) return;
					p.disconnectFromAll();
					removeAllOrderTags();
					refreshManager(false, false);
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

	/** ActionListener for commands that can operate on multiple paths */
	private class MultiPathActionListener implements ActionListener,
		ItemListener
	{

		private static final String APPEND_CHILDREN_CMD = "Append Children To Selection";
		private final static String COLORS_MENU = "Color";
		private final static String DELETE_CMD = "Delete...";
		private final static String MERGE_CMD = "Merge...";
		private final static String DOWNSAMPLE_CMD =
			"Ramer-Douglas-Peucker Downsampling...";
		private final static String CUSTOM_TAG_CMD = "Custom...";
		private final static String LENGTH_TAG_CMD = "Length";
		private final static String MEAN_RADIUS_TAG_CMD = "Mean Radius";
		private final static String ORDER_TAG_CMD = "Path Order";
		private final static String TREE_TAG_CMD = "Cell ID";
		private final static String CHANNEL_TAG_CMD = "Traced Channel";
		private final static String FRAME_TAG_CMD = "Traced Frame";
		private final static String SLICE_LABEL_TAG_CMD = "Slice Labels";

		private final static String REMOVE_ALL_TAGS_CMD = "Remove All Tags...";
		private static final String FILL_OUT_CMD = "Fill Out...";
		private static final String RESET_FITS = "Discard Fit(s)...";
		private final static String SPECIFY_RADIUS_CMD = "Specify Radius...";
		private final static String MEASURE_CMD_SUMMARY = "Quick Measurements";
		private final static String MEASURE_CMD_OPTIONS = "Measure...";

		private final static String CONVERT_TO_ROI_CMD = "Convert to ROIs...";
		private final static String COLORIZE_PATH_CMD = "Color Coding...";
		private final static String HISTOGRAM_CMD = "Distribution Analysis...";
		private final static String CONVERT_TO_SKEL_CMD = "Skeletonize...";
		private final static String CONVERT_TO_SWC_CMD = "Save Subset as SWC...";
		private final static String PLOT_PROFILE_CMD = "Plot Profile";

		private final static String TAG_LENGTH_PATTERN =
			" ?\\[L:\\d+\\.?\\d+\\s?.+\\w+\\]";
		private final static String TAG_RADIUS_PATTERN =
			" ?\\[MR:\\d+\\.?\\d+\\s?.+\\w+\\]";
		private final static String TAG_TREE_PATTERN = " ?\\|.*\\|";
		private final static String TAG_ORDER_PATTERN = " ?\\[Order \\d+\\]";
		private final static String TAG_CHANNEL_PATTERN = " ?\\[C:\\d+\\]";
		private final static String TAG_FRAME_PATTERN = " ?\\[T:\\d+\\]";
		private final static String TAG_CUSTOM_PATTERN = " ?\\{.*\\}"; // anything
																																		// flanked
																																		// by curly
																																		// braces

		private void selectChildren(final Collection<Path> paths) {
			final HashSet<Path> set = new HashSet<>(paths);
			for (final Path p : paths) addChildrenToCollection(p, set);
			setSelectedPaths(set, PathManagerUI.this);
			refreshManager(true, true);
		}

		private void addChildrenToCollection(final Path p, final Collection<Path> collection) {
			if (p.children != null && !p.children.isEmpty()) {
				collection.addAll(p.children);
				for (final Path cp : p.children) addChildrenToCollection(cp, collection);
			}
		}

		@Override
		public void actionPerformed(final ActionEvent e) {

			final String cmd = e.getActionCommand();
			final Collection<Path> selectedPaths = getSelectedPaths(true);
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
			if (APPEND_CHILDREN_CMD.equals(cmd)) {
				if (assumeAll)
					guiUtils.error("No Path(s) are currently selected.");
				else 
					selectChildren(selectedPaths);
				return;
			}
			else if (COLORS_MENU.equals(cmd)) {
				final SNTColor swcColor = colorMenu.getSelectedSWCColor();
				for (final Path p : selectedPaths)
					p.setColor(swcColor.color());
				refreshManager(true, true);
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
					final PathProfiler profiler = new PathProfiler(tree, imp);
					profiler.setNodeIndicesAsDistances(false);
					profiler.getPlot().show(); // IJ1 plot, arguably more suitable for profile data
					// NB: to use Scijava plotService instead:
					//profiler.setContext(plugin.getContext());
					//profiler.run();
				});
				return;
			}
			else if (MEASURE_CMD_SUMMARY.equals(cmd)) {
				final Tree tree = getSingleTree();
				if (tree == null) return;
				try {
					final TreeAnalyzer ta = new TreeAnalyzer(tree);
					ta.setContext(plugin.getContext());
					if (ta.getParsedTree().isEmpty()) {
						guiUtils.error("None of the selected paths could be measured.");
						return;
					}
					ta.setTable(getTable(), TABLE_TITLE);
					ta.summarize(getDescription(selectedPaths), true);
				}
				catch (final IllegalArgumentException ignored) {
					guiUtils.error("Selected paths do not fullfill requirements for retrieval of choiceless measurements.");
				}
				return;
			}
			else if (MEASURE_CMD_OPTIONS.equals(cmd)) {
				final Collection<Tree> trees = getTrees();
				if (trees == null) return;
				final HashMap<String, Object> inputs = new HashMap<>();
				inputs.put("trees", trees);
				inputs.put("table", getTable());
				(plugin.getUI().new DynamicCmdRunner(AnalyzerCmd.class, inputs)).run();
				return;
			}
			else if (CONVERT_TO_ROI_CMD.equals(cmd)) {
				final Map<String, Object> input = new HashMap<>();
				input.put("tree", new Tree(selectedPaths));
				input.put("imp", plugin.getImagePlus());
				final CommandService cmdService = plugin.getContext().getService(
					CommandService.class);
				cmdService.run(ROIExporterCmd.class, true, input);
				return;

			}
			else if (COLORIZE_PATH_CMD.equals(cmd)) {
				final Tree tree = (assumeAll) ? getSingleTree() : new Tree(selectedPaths);
				if (tree == null) return;
				final Map<String, Object> input = new HashMap<>();
				input.put("tree", tree);
				input.put("setValuesFromSNTService", !plugin.tracingHalted);
				final CommandService cmdService = plugin.getContext().getService(
					CommandService.class);
				cmdService.run(TreeMapperCmd.class, true, input);
				return;

			}
			else if (HISTOGRAM_CMD.equals(cmd)) {

				final Map<String, Object> input = new HashMap<>();
				final Collection<Tree> trees = getTrees();
				if (trees == null) return;
				input.put("trees", trees);
				input.put("calledFromPathManagerUI", true);
				final CommandService cmdService = plugin.getContext().getService(
					CommandService.class);
				cmdService.run(DistributionBPCmd.class, true, input);
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
					removeTags(selectedPaths, TAG_CUSTOM_PATTERN);
					displayTmpMsg("Tags removed");
				}
				else {
					for (final Path p : selectedPaths) {
						tags = tags.replace("[", "(");
						tags = tags.replace("]", ")");
						p.setName(p.getName() + "{" + tags + "}");
					}
				}
				refreshManager(false, false);
				return;

			}
			if (SLICE_LABEL_TAG_CMD.equals(cmd)) {

				int errorCounter = 0;
				for (final Path p : selectedPaths) {
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
				refreshManager(false, false);
				if (errorCounter > 0) {
					guiUtils.error("It was not possible to retrieve labels from " +
						errorCounter + "/" + n + " paths.");
				}
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
						"paths and <b>overrides</b> any existing values.",
					"Assign Constant Diameter", rad);
				if (userRad == null) {
					return; // user pressed cancel
				}
				if (Double.isNaN(userRad) || userRad < 0) {
					guiUtils.error("Invalid diameter value.");
					return;
				}
				if (userRad == 0d && !guiUtils.getConfirmation(
					"Discard thickness information from selected paths?",
					"Confirm Removal of Diameters"))
				{
					return;
				}
				selectedPaths.parallelStream().forEach(p -> {
					if (!p.isFittedVersionOfAnotherPath()) p.setRadius(userRad);
				});
				guiUtils.tempMsg("Command finished. Fitted path(s) ignored.");
				plugin.updateAllViewers();
				return;
			}

			// Case 2: Commands that require some sort of confirmation
			else if (DELETE_CMD.equals(cmd)) {
				if (guiUtils.getConfirmation((assumeAll)
					? "Are you really sure you want to delete everything?"
					: "Delete the selected " + n + " paths?", "Confirm Deletion?"))
					deletePaths(selectedPaths);
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
				removeTags(selectedPaths, TAG_TREE_PATTERN);
				removeTags(selectedPaths, TAG_ORDER_PATTERN);
				removeTags(selectedPaths, TAG_CHANNEL_PATTERN);
				removeTags(selectedPaths, TAG_FRAME_PATTERN);
				removeTags(selectedPaths, TAG_CUSTOM_PATTERN);
				removeTags(selectedPaths, TAG_LENGTH_PATTERN);
				removeTags(selectedPaths, TAG_RADIUS_PATTERN);
				resetPathsColor(selectedPaths); // will call refreshManager
				resetMenu(morphoTagsMenu);
				resetMenu(imageTagsMenu);
				return;
			}
			else if (MERGE_CMD.equals(cmd)) {
				if (n == 1) {
					displayTmpMsg("You must have at least two paths selected.");
					return;
				}
				final Path refPath = selectedPaths.iterator().next();
				if (refPath.getEndJoins() != null) {
					guiUtils.error(
						"The first path in the selection cannot have an end-point junction.",
						"Invalid Merge Selection");
					return;
				}
				if (!guiUtils.getConfirmation("Merge " + n +
					" selected paths? (this destructive operation cannot be undone!)",
					"Confirm merge?"))
				{
					return;
				}
				final HashSet<Path> pathsToMerge = new HashSet<>();
				for (final Path p : selectedPaths) {
					if (refPath.equals(p) || refPath.somehowJoins.contains(p) ||
						p.somehowJoins.contains(refPath)) continue;
					pathsToMerge.add(p);
				}
				if (pathsToMerge.size() < n - 1 && !guiUtils.getConfirmation(
					"Some of the selected paths are connected and cannot be merged. " +
						"Proceed with the merge of the " + pathsToMerge.size() +
						" disconnected path(s) in the selection?",
					"Only Disconnected Paths Can Be Merged"))
				{
					return;
				}
				for (final Path p : pathsToMerge) {
					refPath.add(p);
					getPathAndFillManager().deletePath(p);
				}
				removeAllOrderTags();
				refreshManager(true, true);
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
					p.setUseFitted(false);
					p.setFitted(null);
				}
				refreshManager(true, false);
				return;

			}
			else if (e.getSource().equals(fitVolumeMenuItem) || cmd.toLowerCase().contains("fit paths")) {

				// this MenuItem is a toggle: check if it is set for 'unfitting'
				if (fitVolumeMenuItem.getText().contains("Un-fit")) {
					for (final Path p : selectedPaths)
						p.setUseFitted(false);
					refreshManager(true, false);
					return;
				}

				final boolean imageNotAvailable = !plugin.accessToValidImageData();
				final ArrayList<PathFitter> pathsToFit = new ArrayList<>();
				int skippedFits = 0;

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
						}
						else {
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
					refreshManager(true, false);
				}
				return;

			}
			else {
				SNTUtils.error("Unexpectedly got an event from an unknown source: " + e);
				return;
			}
		}

		private void resetMenu(final JMenu menu) {
			for (int i = 0; i < menu.getItemCount(); i++) {
				final JMenuItem c = menu.getItem(i);
				if (c != null) c.setSelected(false);
			}
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

		@Override
		public void itemStateChanged(final ItemEvent e) {
			// NB: Length & order tagging apply to all paths, independent of selection
			final List<Path> selectedPaths = getPathAndFillManager()
				.getPathsFiltered();
			final int n = selectedPaths.size();
			if (n == 0) {
				guiUtils.error("There are no traced paths.");
				return;
			}
			final JCheckBoxMenuItem jcbmi = (JCheckBoxMenuItem) e.getSource();
			final String cmd = jcbmi.getActionCommand();
			if (LENGTH_TAG_CMD.equals(cmd)) {
				if (jcbmi.isSelected()) {
					for (final Path p : selectedPaths) {
						final String lengthTag = " [L:" + p.getRealLengthString() +
							p.spacing_units + "]";
						p.setName(p.getName() + lengthTag);
					}
				}
				else {
					removeTags(selectedPaths, TAG_LENGTH_PATTERN);
				}

			}
			else if (MEAN_RADIUS_TAG_CMD.equals(cmd)) {
				if (jcbmi.isSelected()) {
					for (final Path p : selectedPaths) {
						final String radiusTag = " [MR:" + SNTUtils.formatDouble(p
							.getMeanRadius(), 3) + p.spacing_units + "]";
						p.setName(p.getName() + radiusTag);
					}
				}
				else {
					removeTags(selectedPaths, TAG_RADIUS_PATTERN);
				}

			}
			else if (TREE_TAG_CMD.equals(cmd)) {
				if (jcbmi.isSelected()) {
					for (final Path p : selectedPaths) {
						p.setName(p.getName() + " |" + p.getTreeLabel() + "|");
					}
				}
				else {
					removeTags(selectedPaths, TAG_TREE_PATTERN);
				}
			}
			else if (ORDER_TAG_CMD.equals(cmd)) {
				if (jcbmi.isSelected()) {
					for (final Path p : selectedPaths) {
						p.setName(p.getName() + " [Order " + p.getOrder() + "]");
					}
				}
				else {
					removeTags(selectedPaths, TAG_ORDER_PATTERN);
				}
			}
			else if (CHANNEL_TAG_CMD.equals(cmd)) {
				if (jcbmi.isSelected()) {
					for (final Path p : selectedPaths) {
						p.setName(p.getName() + " [C:" + p.getChannel() + "]");
					}
				}
				else {
					removeTags(selectedPaths, TAG_CHANNEL_PATTERN);
				}
			}
			else if (FRAME_TAG_CMD.equals(cmd)) {
				if (jcbmi.isSelected()) {
					for (final Path p : selectedPaths) {
						p.setName(p.getName() + " [T:" + p.getFrame() + "]");
					}
				}
				else {
					removeTags(selectedPaths, TAG_FRAME_PATTERN);
				}
			}
			// update GUI
			jcbmi.setSelected(jcbmi.isSelected());
			refreshManager(false, false);
			return;
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
		GuiUtils.setSystemLookAndFeel();
		final ImageJ ij = new ImageJ();
		final ImagePlus imp = new ImagePlus();
		final SNT snt = new SNT(ij.context(), imp);
		final PathManagerUI pm = new PathManagerUI(snt);
		pm.setVisible(true);
	}

}
