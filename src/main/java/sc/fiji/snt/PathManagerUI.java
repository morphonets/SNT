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

package sc.fiji.snt;

import com.jidesoft.swing.Searchable;
import com.jidesoft.swing.TreeSearchable;
import ij.ImagePlus;
import ij.gui.Roi;
import net.imagej.ImageJ;
import net.imagej.lut.LUTService;
import org.scijava.command.CommandModule;
import org.scijava.command.CommandService;
import org.scijava.display.DisplayService;
import org.scijava.prefs.PrefService;
import org.scijava.table.TableDisplay;
import org.scijava.util.ColorRGB;
import sc.fiji.snt.analysis.*;
import sc.fiji.snt.gui.*;
import sc.fiji.snt.gui.cmds.*;
import sc.fiji.snt.io.SWCExportException;
import sc.fiji.snt.plugin.*;
import sc.fiji.snt.tracing.auto.SomaUtils;
import sc.fiji.snt.util.*;

import javax.swing.Timer;
import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * Implements the <i>Path Manager</i> Dialog.
 *
 * @author Tiago Ferreira
 */
public class PathManagerUI extends JDialog implements PathAndFillListener,
        TreeSelectionListener
{

    static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

    private static final long serialVersionUID = 1L;
    private static final String FIT_URI = "https://imagej.net/plugins/snt/manual#refinefit";
    private static final String SYM_MARKER = "M:"; //"\uD83D\uDD88"; // not displayed in macOS
    private static final String SYM_RADIUS = "MR:"; //"\u25CB";
    private static final String SYM_LENGTH = "L:";//"\uD800\uDCB3"; // not displayed in macOS
    private static final String SYM_TREE = "ID:"; //"\uD800\uDCB7"; // not displayed in macOS
    private static final String SYM_ORDER ="ORD:"; //"\uD800\uDC92"; // not displayed in macOS
    private static final String SYM_CHILDREN = "NC:";
    private static final String SYM_ANGLE ="DEG:";

    // Pre-compiled patterns for tag operations (avoid repeated regex compilation)
    private static final Pattern CHANNEL_TAG_PATTERN = Pattern.compile(" ?\\[Ch:\\d+]");
    private static final Pattern FRAME_TAG_PATTERN = Pattern.compile(" ?\\[T:\\d+]");
    private static final Pattern SLICE_TAG_PATTERN = Pattern.compile(" ?\\[Z:\\d+]");
    private static final Pattern TREE_TAG_PATTERN = Pattern.compile(" ?\\[" + SYM_TREE + ".*]");
    private static final Pattern CHILDREN_TAG_PATTERN = Pattern.compile(" ?\\[" + SYM_CHILDREN + "\\d+]");
    private static final Pattern ANGLE_TAG_PATTERN = Pattern.compile(" ?\\[" + SYM_ANGLE + "\\d+.\\d+]|\\[" + SYM_ANGLE + "NaN]");
    private static final Pattern ORDER_TAG_PATTERN = Pattern.compile(" ?\\[" + SYM_ORDER + "\\d+]");
    private static final Pattern LENGTH_TAG_PATTERN = Pattern.compile(" ?\\[" + SYM_LENGTH + "\\d+\\.?\\d+\\s?.+\\w+]");
    private static final Pattern RADIUS_TAG_PATTERN = Pattern.compile(" ?\\[" + SYM_RADIUS + "\\d+\\.?\\d+\\s?.+\\w+]");
    private static final Pattern MARKER_TAG_PATTERN = Pattern.compile(" ?\\[" + SYM_MARKER + "\\d+]");
    private static final Pattern TAG_SPLIT_PATTERN = Pattern.compile(",\\s*");

    private final HelpfulJTree tree;
    private final SNT plugin;
    private final PathAndFillManager pathAndFillManager;
    private SNTTable table;
    private MeasureUI measureUI;

    // --- Arbor navigation toolbar ---
    private final NavigationToolBar navToolbar;
    private HelpfulTreeModel fullTreeModel; // full, unfiltered model for the tree

    protected static final String TABLE_TITLE = "SNT Measurements";
    protected final GuiUtils guiUtils;
    private final JMenuBar menuBar;
    private final JMenu swcTypeMenu;
    private final JMenu tagsMenu;
    private final ProofReadingTagsToolBar proofReadingToolBar;
    private ButtonGroup swcTypeButtonGroup;
    private final ColorMenu colorMenu;
    private final JMenuItem fitVolumeMenuItem;
    private FitHelper fittingHelper;
    private final PathManagerUISearchableBar searchableBar;

    /**
     * Instantiates a new Path Manager Dialog.
     *
     * @param plugin the {@link SNT} instance to be associated
     *               with this Path Manager. It is assumed that its {@link SNTUI} is
     *               available.
     */
    public PathManagerUI(final SNT plugin) {

        super(plugin.getUI(), "Path Manager");
        getRootPane().putClientProperty("JRootPane.menuBarEmbedded", false);
        this.plugin = plugin;
        guiUtils = new GuiUtils(this);
        pathAndFillManager = plugin.getPathAndFillManager();
        pathAndFillManager.addPathAndFillListener(this);

        tree = new HelpfulJTree();
        tree.setRootVisible(false);
        tree.setVisibleRowCount(30);
        tree.setDoubleBuffered(true);
        tree.addTreeSelectionListener(this);
        proofReadingToolBar = new ProofReadingTagsToolBar();
        add(proofReadingToolBar, BorderLayout.PAGE_START);

        final JScrollPane scrollPane = new JScrollPane();
        scrollPane.getViewport().add(tree);
        navToolbar = new NavigationToolBar(tree, scrollPane);
        add(scrollPane, BorderLayout.CENTER);

        // Create all the menu items:
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

        JMenuItem jmi = new JMenuItem(MultiPathActionListener.AUTO_CONNECT_CMD, IconFactory.menuIcon(IconFactory.GLYPH.LINK));
        jmi.setToolTipText("<HTML>Connects 2 paths (parent-child) at an inferred fork point.<br>" +
                "Suggests parent based on path properties.");
        jmi.addActionListener(multiPathListener);
        editMenu.add(jmi);
        jmi = new JMenuItem(MultiPathActionListener.DISCONNECT_CMD, IconFactory.menuIcon(IconFactory.GLYPH.UNLINK));
        jmi.setToolTipText("Disconnects selected path(s) from all of their connections");
        jmi.addActionListener(multiPathListener);
        editMenu.add(jmi);
        editMenu.addSeparator();

        jmi = new JMenuItem(MultiPathActionListener.COMBINE_CMD, IconFactory.menuIcon('\uf247', true));
        jmi.setToolTipText("<HTML>Merges selected paths into one (<b>without spatial ordering</b>).<br>" +
                "Children are reparented.");
        jmi.addActionListener(multiPathListener);
        editMenu.add(jmi);
        jmi = new JMenuItem(MultiPathActionListener.CONCATENATE_CMD, IconFactory.menuIcon(IconFactory.GLYPH.TAPE));
        jmi.setToolTipText("<HTML>Joins paths <b>end-to-end</b> in spatial order.<br>" +
                "Order and orientation are auto-detected from endpoint proximity.<br>Children are reparented.");
        jmi.addActionListener(multiPathListener);
        editMenu.add(jmi);
        jmi = new JMenuItem(MultiPathActionListener.MERGE_PRIMARY_PATHS_CMD, IconFactory.menuIcon(IconFactory.GLYPH.ARROWS_TO_CIRCLE));
        jmi.setToolTipText("Connect selected primary paths to a shared root placed at their\n" +
                "averaged origin, or the centroid of a ROI-defining soma");
        jmi.addActionListener(multiPathListener);
        editMenu.add(jmi);
        jmi = new JMenuItem(MultiPathActionListener.REVERSE_CMD, IconFactory.menuIcon(IconFactory.GLYPH.ARROWS_LR));
        editMenu.add(jmi);
        jmi.setToolTipText("Reverses orientation of primary path(s), with option to update child connections");
        jmi.addActionListener(multiPathListener);
        editMenu.addSeparator();

        jmi = new JMenuItem(MultiPathActionListener.SPECIFY_CT_POSITION_CMD);
        jmi.setToolTipText("Changes CT position of selected path(s)");
        jmi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.LAYERS));
        jmi.addActionListener(multiPathListener);
        editMenu.add(jmi);
        jmi = new JMenuItem(MultiPathActionListener.SPECIFY_RADIUS_CMD);
        jmi.setToolTipText("Assigns a fixed radius to selected path(s)");
        jmi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.CIRCLE));
        jmi.addActionListener(multiPathListener);
        editMenu.add(jmi);
        jmi = new JMenuItem(MultiPathActionListener.SPECIFY_COUNTS_CMD);
        jmi.setToolTipText("Assigns a no. of varicosities/spines to selected path(s)");
        jmi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.MAP_PIN));
        jmi.addActionListener(multiPathListener);
        editMenu.add(jmi);
        editMenu.addSeparator();

        jmi = new JMenuItem(MultiPathActionListener.DOWNSAMPLE_CMD, IconFactory.menuIcon(IconFactory.GLYPH.ARROWS_LR_TO_LINE));
        jmi.setToolTipText("Reduces the no. of nodes in selected paths by increasing\ninter-node distance (lossy simplification)");
        jmi.addActionListener(multiPathListener);
        editMenu.add(jmi);
        editMenu.addSeparator();

        jmi = new JMenuItem(MultiPathActionListener.REBUILD_CMD);
        jmi.setToolTipText("Analyzes and re-computes all hierarchical connections in the list");
        jmi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.FIRST_AID));
        jmi.addActionListener(multiPathListener);
        editMenu.add(jmi);

        tagsMenu = new JMenu("Tag");
        menuBar.add(tagsMenu);
        swcTypeMenu = new JMenu("Type");
        swcTypeMenu.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.ID));
        tagsMenu.add(swcTypeMenu);
        assembleSWCtypeMenu(false);
        colorMenu = new ColorMenu(MultiPathActionListener.COLORS_MENU);
        colorMenu.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.COLOR));
        colorMenu.addActionListener(multiPathListener);
        colorMenu.addSeparator();
        jmi = new JMenuItem(MultiPathActionListener.ASSIGN_CUSTOM_COLOR);
        ScriptRecorder.setRecordingCall(jmi, null);
        jmi.setToolTipText("Tags selected paths with unlisted color");
        jmi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.EYE_DROPPER));
        jmi.addActionListener(multiPathListener);
        colorMenu.add(jmi);
        //colorMenu.addSeparator();
        jmi = new JMenuItem(MultiPathActionListener.ASSIGN_DISTINCT_COLORS);
        jmi.setToolTipText("Tags selected paths with distinct colors");
        jmi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.SHUFFLE));
        jmi.addActionListener(multiPathListener);
        colorMenu.add(jmi);
        tagsMenu.add(colorMenu);

        final JMenu imageTagsMenu = new JMenu("Image Properties");
        imageTagsMenu.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.IMAGE));
        imageTagsMenu.add(new TagMenuItem(MultiPathActionListener.CHANNEL_TAG_CMD));
        imageTagsMenu.add(new TagMenuItem(MultiPathActionListener.FRAME_TAG_CMD));
        imageTagsMenu.add(new TagMenuItem(MultiPathActionListener.SLICE_TAG_CMD));
        imageTagsMenu.addSeparator();
        jmi = new JMenuItem(MultiPathActionListener.SLICE_LABEL_TAG_CMD);
        jmi.setIcon(IconFactory.menuIcon('\uF02B', true));
        jmi.setToolTipText("Applies 'Slice labels' (AKA 'image subtitles') as custom tag(s).\n"
                + "NB: Unless explicitly set, most images do not have meaningful slice labels");
        jmi.addActionListener(multiPathListener);
        imageTagsMenu.add(jmi);
        tagsMenu.add(imageTagsMenu);

        final JMenu morphoTagsMenu = new JMenu("Morphometry");
        morphoTagsMenu.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.RULER));
        morphoTagsMenu.add(new TagMenuItem(MultiPathActionListener.TREE_TAG_CMD));
        morphoTagsMenu.add(tagAngleMenuItem());
        morphoTagsMenu.add(new TagMenuItem(MultiPathActionListener.LENGTH_TAG_CMD));
        morphoTagsMenu.add(new TagMenuItem(MultiPathActionListener.MEAN_RADIUS_TAG_CMD));
        morphoTagsMenu.add(new TagMenuItem(MultiPathActionListener.N_CHILDREN_TAG_CMD));
        morphoTagsMenu.add(new TagMenuItem(MultiPathActionListener.COUNT_TAG_CMD));
        morphoTagsMenu.add(new TagMenuItem(MultiPathActionListener.ORDER_TAG_CMD));
        tagsMenu.add(morphoTagsMenu);
        tagsMenu.add(proofReadingToolBar.getToggleMenuItem());
        tagsMenu.addSeparator();
        jmi = new JMenuItem(MultiPathActionListener.CUSTOM_TAG_CMD, IconFactory.menuIcon(IconFactory.GLYPH.TAG));
        jmi.setToolTipText("Ad hoc tags");
        jmi.addActionListener(multiPathListener);
        tagsMenu.add(jmi);
        jmi = new JMenuItem(MultiPathActionListener.REPLACE_TAG_CMD, IconFactory.menuIcon(IconFactory.GLYPH.SEARCH_ARROW));
        jmi.addActionListener(multiPathListener);
        tagsMenu.add(jmi);
        tagsMenu.addSeparator();
        jmi = new JMenuItem(MultiPathActionListener.REMOVE_TAGS_CMD);
        jmi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.TRASH));
        jmi.addActionListener(multiPathListener);
        tagsMenu.add(jmi);

        final JMenu fitMenu = new JMenu("Refine");
        menuBar.add(fitMenu);
        fitVolumeMenuItem = new JMenuItem("Fit Path(s)...");
        fitVolumeMenuItem.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.CROSSHAIR));
        fitVolumeMenuItem.addActionListener(multiPathListener);
        fitMenu.add(fitVolumeMenuItem);
        jmi = new JMenuItem(SinglePathActionListener.EXPLORE_FIT_CMD);
        jmi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.EXPLORE));
        jmi.setToolTipText("Displays fitting details for single paths");
        jmi.addActionListener(singlePathListener);
        fitMenu.add(jmi);
        jmi = new JMenuItem(MultiPathActionListener.RESET_FITS, IconFactory.menuIcon(
                IconFactory.GLYPH.BROOM));
        jmi.setToolTipText("Resets fits for selected path(s)");
        jmi.addActionListener(multiPathListener);
        fitMenu.add(jmi);
        jmi = new JMenuItem("Parameters...", IconFactory.menuIcon(IconFactory.GLYPH.SLIDERS));
        jmi.setToolTipText("Options for fitting operations");
        jmi.addActionListener(e -> {
            if (noValidImageDataError()) return;
            if (fittingHelper == null) fittingHelper = new FitHelper();
            fittingHelper.showPrompt();
        });
        fitMenu.add(jmi);
        fitMenu.addSeparator();

        jmi = new JMenuItem(MultiPathActionListener.INTERPOLATE_MISSING_RADII,
                IconFactory.menuIcon(IconFactory.GLYPH.DOTCIRCLE));
        jmi.setToolTipText("Corrects fitted nodes with invalid radius");
        jmi.addActionListener(multiPathListener);
        fitMenu.add(jmi);
        fitMenu.addSeparator();

        jmi = GuiUtils.MenuItems.openHelpURL("<HTML>Help on <i>Refining/Fitting", FIT_URI);
        fitMenu.add(jmi);

        final JMenu fillMenu = new JMenu("Fill");
        menuBar.add(fillMenu);
        jmi = new JMenuItem(MultiPathActionListener.FILL_OUT_CMD, IconFactory.menuIcon(IconFactory.GLYPH.FILL));
        jmi.setToolTipText("Initiates filling procedures for selected path(s)");
        jmi.addActionListener(multiPathListener);
        fillMenu.add(jmi);
        fillMenu.addSeparator();
        jmi = GuiUtils.MenuItems.openURL("<HTML>Help on <i>Filling", FillManagerUI.FILLING_URI);
        jmi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.QUESTION));
        fillMenu.add(jmi);

        final JMenu process = new JMenu("Process");
        menuBar.add(process);
        jmi = new JMenuItem(MultiPathActionListener.CONVERT_TO_ROI_CMD, IconFactory.menuIcon(IconFactory.GLYPH.BEZIER_CURVE));
        jmi.setToolTipText("Converts selected path(s) into ROIs (polylines and points)");
        jmi.addActionListener(multiPathListener);
        process.add(jmi);
        jmi = new JMenuItem(MultiPathActionListener.CONVERT_TO_SKEL_CMD, IconFactory.menuIcon(IconFactory.GLYPH.X_RAY));
        jmi.setToolTipText("Rasterizes selected path(s) into a skeletonized image");
        jmi.addActionListener(multiPathListener);
        process.add(jmi);
        jmi = new JMenuItem(SinglePathActionListener.STRAIGHTEN, IconFactory.menuIcon(IconFactory.GLYPH.STAIRS));
        jmi.setToolTipText("Creates a 'linear image' from the pixels associated with single paths");
        jmi.addActionListener(singlePathListener);
        process.add(jmi);
        process.addSeparator();
        jmi = new JMenuItem(MultiPathActionListener.SEND_TO_LABKIT_CMD, IconFactory.menuIcon(IconFactory.GLYPH.KIWI_BIRD));
        jmi.setToolTipText("Starts a Labkit session loaded with labels from selected paths");
        jmi.addActionListener(multiPathListener);
        process.add(jmi);
        jmi = new JMenuItem(MultiPathActionListener.SEND_TO_TWS_CMD, IconFactory.menuIcon(IconFactory.GLYPH.KIWI_BIRD));
        jmi.setToolTipText("Starts a Trainable Weka Segmentation session loaded with labels from selected paths");
        jmi.addActionListener(multiPathListener);
        process.add(jmi);

        final JMenu advanced = new JMenu("Analyze");
        menuBar.add(advanced);

        final JMenu colorMapMenu = new JMenu("Color Mapping");
        colorMapMenu.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.COLOR2));
        advanced.add(colorMapMenu);
        jmi = new JMenuItem(MultiPathActionListener.COLORIZE_TREES_CMD);
        jmi.setToolTipText("Color codes complete arbors using connectivity-dependent metrics");
        jmi.addActionListener(multiPathListener);
        colorMapMenu.add(jmi);
        jmi = new JMenuItem(MultiPathActionListener.COLORIZE_PATHS_CMD);
        jmi.setToolTipText("Color codes selected path(s) independently of their connectivity");
        jmi.addActionListener(multiPathListener);
        colorMapMenu.add(jmi);
        colorMapMenu.addSeparator();
        jmi = new JMenuItem(MultiPathActionListener.COLORIZE_REMOVE_CMD);
        jmi.setToolTipText("Removes existing color mappings of selected path(s)");
        jmi.addActionListener(multiPathListener);
        colorMapMenu.add(jmi);

        final JMenu distributionMenu = new JMenu("Frequency Analysis");
        distributionMenu.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.CHART));
        advanced.add(distributionMenu);
        jmi = new JMenuItem(MultiPathActionListener.HISTOGRAM_TREES_CMD);
        jmi.setToolTipText("Computes frequency histograms for measurements of complete arbors");
        jmi.addActionListener(multiPathListener);
        distributionMenu.add(jmi);
        jmi = new JMenuItem(MultiPathActionListener.HISTOGRAM_PATHS_CMD);
        jmi.setToolTipText("Computes frequency histograms for selected path(s), independently of their connectivity");
        jmi.addActionListener(multiPathListener);
        distributionMenu.add(jmi);
        distributionMenu.addSeparator();
        jmi = new JMenuItem(MultiPathActionListener.HISTOGRAM_2D_CMD);
        jmi.setToolTipText("Computes distributions of 2-dimensional data (3D histograms)");
        jmi.addActionListener(multiPathListener);
        distributionMenu.add(jmi);

        final JMenu measureMenu = new JMenu("Measurements");
        measureMenu.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.TABLE));
        advanced.add(measureMenu);
        jmi = new JMenuItem(MultiPathActionListener.MEASURE_TREES_CMD);
        jmi.setToolTipText("Measures complete arbors");
        jmi.addActionListener(multiPathListener);
        measureMenu.add(jmi);
        jmi = new JMenuItem(MultiPathActionListener.MEASURE_PATHS_CMD);
        jmi.setToolTipText("Measures selected path(s) independently of their connectivity");
        jmi.addActionListener(multiPathListener);
        measureMenu.add(jmi);

        advanced.addSeparator();
        jmi = new JMenuItem(MultiPathActionListener.MULTI_METRIC_PLOT_CMD, IconFactory.menuIcon(IconFactory.GLYPH.CHART_AREA));
        jmi.setToolTipText("Plots a Path metric against several others");
        jmi.addActionListener(multiPathListener);
        advanced.add(jmi);
        jmi = new JMenuItem(SinglePathActionListener.NODE_PROFILER, IconFactory.menuIcon(IconFactory.GLYPH.CHART_MAGNIFIED));
        jmi.setToolTipText("Cross-section profiles of single paths");
        jmi.addActionListener(singlePathListener);
        advanced.add(jmi);
        jmi = new JMenuItem(MultiPathActionListener.PLOT_PROFILE_CMD, IconFactory.menuIcon(IconFactory.GLYPH.CHART_LINE));
        jmi.setToolTipText("Multi-channel plots of pixel intensities along selected path(s)");
        jmi.addActionListener(multiPathListener);
        advanced.add(jmi);
        advanced.addSeparator();
        advanced.add(getSpineUtilsMenu(multiPathListener));
        advanced.add(getTimeSequenceMenu(multiPathListener));
        advanced.addSeparator();
        jmi = new JMenuItem(MultiPathActionListener.CONVERT_TO_SWC_CMD);
        jmi.setToolTipText("Exports selected path(s) into dedicated SWC file(s).\nConnectivity constrains apply");
        jmi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.EXPORT));
        jmi.addActionListener(multiPathListener);
        advanced.add(jmi);

        // Search Bar TreeSearchable
        searchableBar = new PathManagerUISearchableBar(this);
        final JPopupMenu popup = new JPopupMenu();
        popup.add(getDeleteMenuItem(multiPathListener));
        popup.add(getDuplicateMenuItem(singlePathListener));
        popup.add(getRenameMenuItem(singlePathListener));
        popup.addSeparator();
        popup.add(new JTreeMenuItem(JTreeMenuItem.COLLAPSE_ALL_CMD));
        popup.add(new JTreeMenuItem(JTreeMenuItem.COLLAPSE_SELECTED_LEVEL));
        popup.addSeparator();
        popup.add(new JTreeMenuItem(JTreeMenuItem.EXPAND_ALL_CMD));
        popup.add(new JTreeMenuItem(JTreeMenuItem.EXPAND_SELECTED_LEVEL));
        popup.addSeparator();
        JMenuItem pjmi = popup.add(MultiPathActionListener.APPEND_ALL_CHILDREN_CMD);
        pjmi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.CHILDREN));
        pjmi.addActionListener(multiPathListener);
        pjmi = popup.add(MultiPathActionListener.APPEND_DIRECT_CHILDREN_CMD);
        pjmi.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.CHILD));
        pjmi.addActionListener(multiPathListener);
        popup.addSeparator();
        popup.add(new JTreeMenuItem(JTreeMenuItem.SELECT_NONE_CMD));
        popup.addSeparator();
        popup.add(new JTreeMenuItem(JTreeMenuItem.TOGGLE_NAV_TOOLBAR));
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
        //       navToolbar.sync();
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
        final String tooltip = "Assumes spine/varicosity markers have already been assigned to selected path(s)";
        menu.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.MAP_PIN));
        JMenuItem jmi = new JMenuItem(MultiPathActionListener.SPINE_EXTRACT_CMD);
        jmi.setToolTipText(tooltip);
        jmi.addActionListener(multiPathListener);
        menu.add(jmi);
        menu.addSeparator();
        jmi = new JMenuItem(MultiPathActionListener.SPINE_COLOR_CODING_CMD);
        jmi.setToolTipText(tooltip);
        jmi.addActionListener(multiPathListener);
        menu.add(jmi);
        jmi = new JMenuItem(MultiPathActionListener.SPINE_PROFILE_CMD);
        jmi.setToolTipText(tooltip);
        jmi.addActionListener(multiPathListener);
        menu.add(jmi);
        menu.addSeparator();
        jmi = new JMenuItem("Start Spot Spine...");
        jmi.addActionListener(e -> plugin.getContext().getService(CommandService.class).run(SpotSpineLoaderCmd.class, true, new HashMap<>()));
        menu.add(jmi);
        menu.addSeparator();
        jmi = GuiUtils.MenuItems.openHelpURL("Spine/Varicosity Utilities Help",
                "https://imagej.net/plugins/snt/walkthroughs#spinevaricosity-analysis");
        menu.add(jmi);
        return menu;
    }

    private JMenu getTimeSequenceMenu(final MultiPathActionListener multiPathListener) {
        final JMenu menu = new JMenu("Time-lapse Utilities");
        final String tooltip = "Assumes selected path(s) belong to a time-lapse series";
        menu.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.VIDEO));
        JMenuItem jmi = new JMenuItem(MultiPathActionListener.MATCH_PATHS_ACROSS_TIME_CMD);
        jmi.setToolTipText(tooltip);
        jmi.addActionListener(multiPathListener);
        menu.add(jmi);
        jmi = new JMenuItem(MultiPathActionListener.GROWTH_ANALYSIS_CMD);
        jmi.setToolTipText("Growth rate and growth phase analysis for matched time-lapse paths");
        jmi.addActionListener(multiPathListener);
        menu.add(jmi);
        menu.addSeparator();
        jmi = new JMenuItem(MultiPathActionListener.TIME_COLOR_CODING_CMD);
        jmi.setToolTipText(tooltip);
        jmi.addActionListener(multiPathListener);
        menu.add(jmi);
        jmi = new JMenuItem(MultiPathActionListener.TIME_PROFILE_CMD);
        jmi.setToolTipText(tooltip);
        jmi.addActionListener(multiPathListener);
        menu.add(jmi);
        menu.addSeparator();
        jmi = GuiUtils.MenuItems.openHelpURL("Time-lapse Utilities Help",
                "https://imagej.net/plugins/snt/walkthroughs#time-lapse-analysis");
        menu.add(jmi);
        return menu;
    }

    private JMenuItem getRenameMenuItem(final SinglePathActionListener singlePathListener) {
        final JMenuItem renameMitem = new JMenuItem(
                SinglePathActionListener.RENAME_CMD);
        renameMitem.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.PEN));
        renameMitem.addActionListener(singlePathListener);
        renameMitem.setToolTipText("Renames a single path");
        return renameMitem;
    }

    private JMenuItem getDuplicateMenuItem(final SinglePathActionListener singlePathListener) {
        final JMenuItem duplicateMitem = new JMenuItem(
                SinglePathActionListener.DUPLICATE_CMD);
        duplicateMitem.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.CLONE));
        duplicateMitem.addActionListener(singlePathListener);
        duplicateMitem.setToolTipText("Duplicates a full path and its children or a sub-section");
        return duplicateMitem;
    }

    private JMenuItem getDeleteMenuItem(final MultiPathActionListener multiPathListener) {
        final JMenuItem deleteMitem = new JMenuItem(
                MultiPathActionListener.DELETE_CMD);
        deleteMitem.setToolTipText("Deletes selected path(s)");
        deleteMitem.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.TRASH));
        deleteMitem.addActionListener(multiPathListener);
        return deleteMitem;
    }

    private void assembleSWCtypeMenu(final boolean applyPromptOptions) {
        swcTypeMenu.removeAll();
        swcTypeButtonGroup = new ButtonGroup();
        final SWCTypeOptionsCmd optionsCmd = new SWCTypeOptionsCmd();
        optionsCmd.setContext(plugin.getContext());
        final TreeMap<Integer, Color> map = optionsCmd.getColorMap();
        map.put(-1, null);
        final boolean assignColors = optionsCmd.isColorPairingEnabled();
        map.forEach((key, value) -> {

            final Color color = (assignColors) ? value : null;
            final JRadioButtonMenuItem rbmi = new JRadioButtonMenuItem(Path
                    .getSWCtypeName(key, true), IconFactory.nodeIcon(color));
            rbmi.setName(String.valueOf(key)); // store SWC type flag as name
            swcTypeButtonGroup.add(rbmi);
            rbmi.addActionListener(e -> {
                final Collection<Path> selectedPaths = getSelectedPaths(true);
                if (selectedPaths.isEmpty()) {
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
        final JMenuItem jmi = new JMenuItem("Options...", IconFactory.menuIcon(IconFactory.GLYPH.SLIDERS));
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
        plugin.setUnsavedChanges(true);
        refreshManager(true, true, paths);
    }

    private void deletePaths(final Collection<Path> pathsToBeDeleted) {
        final boolean all = pathsToBeDeleted.size() == pathAndFillManager.size();
        boolean rebuild = false;
        for (final Path p : pathsToBeDeleted) {
            if (plugin !=null && p.isBeingEdited()) plugin.enableEditMode(false);
            if (!p.connectedPaths.isEmpty()) {
                rebuild = true;
            }
            p.disconnectFromAll();
            pathAndFillManager.deletePath(p);
        }
        if (all) {
            pathAndFillManager.resetIDs();
            deselectAllTagsMenu();
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
            if (ifNoneSelectedGetAll && tree.getSelectionCount() == 0) {
                // If the view is filtered (e.g., "Hide others is toggled in navigation bar"), prefer the paths actually
                // listed in the JTree. Detect filtering by comparing against the full model captured during last rebuild.
                final TreeModel current = tree.getModel();
                if (current != null && current != fullTreeModel) {
                    final List<Path> visible = getPathsFromCurrentTreeModel();
                    if (!visible.isEmpty()) return visible;
                }
                // Fallback to backend when unfiltered or if something went wrong.
                return pathAndFillManager.getPathsFiltered();
            }
            final List<Path> result = new ArrayList<>();
            final TreePath[] selectedPaths = tree.getSelectionPaths();
            if (selectedPaths == null) {
                return result;
            }
            for (final TreePath tp : selectedPaths) {
                final DefaultMutableTreeNode node = (DefaultMutableTreeNode) (tp.getLastPathComponent());
                if (!node.isRoot()) {
                    final Path p = (Path) node.getUserObject();
                    result.add(p);
                }
            }
            return result;
        });
    }

    /**
     * Returns all {@link Path} objects currently listed by the JTree's model.
     * This reflects any active filtering (e.g., "Hide others in navigation bar")
     * because it walks the tree model rather than querying the backend manager.
     */
    private List<Path> getPathsFromCurrentTreeModel() {
        final TreeModel m = tree.getModel();
        if (m == null) return Collections.emptyList();
        final Object rootObj = m.getRoot();
        final DefaultMutableTreeNode rootNode =
                (rootObj instanceof DefaultMutableTreeNode) ? (DefaultMutableTreeNode) rootObj : null;
        if (rootNode == null) return Collections.emptyList();
        final List<Path> out = new ArrayList<>();
        for (final Enumeration<?> e = rootNode.depthFirstEnumeration(); e.hasMoreElements();) {
            final Object n = e.nextElement();
            if (!(n instanceof DefaultMutableTreeNode dmtn)) continue;
            final Object uo = dmtn.getUserObject();
            if (uo instanceof Path p) out.add(p);
        }
        return out;
    }

    protected boolean selectionExists() {
        return tree.getSelectionCount() > 0;
    }

    protected synchronized void cancelFit(final boolean updateUIState) {
        if (fittingHelper != null) fittingHelper.cancelFit(updateUIState);
    }

    private void exportSelectedPaths(final Collection<Path> selectedPaths) {

        List<SWCPoint> swcPoints;
        try {
            swcPoints = pathAndFillManager.getSWCFor(selectedPaths);
        }
        catch (final SWCExportException see) {
            guiUtils.error(see.getMessage());
            return;
        }

        final SWCExportDialog sed = new SWCExportDialog(plugin.getUI(), plugin.getImagePlus(), null, false);
        sed.setTitle("Export Selected Path(s) to SWC...");
        sed.setLocationRelativeTo(this);
        sed.setVisible(true);
        if (!sed.succeeded())
            return;
        final File saveFile = sed.getFile();
        plugin.statusService.showStatus("Exporting SWC data to " + saveFile.getAbsolutePath());
        try {
            final PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                    Files.newOutputStream(saveFile.toPath()), StandardCharsets.UTF_8));
            pathAndFillManager.flushSWCPoints(swcPoints, pw, sed.getFileHeader());
            pw.close();
        }
        catch (final IOException ioe) {
            guiUtils.error("Saving to " + saveFile.getAbsolutePath() + " failed:\n" + ioe.getMessage());
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
                    : "<html>Path has already been fitted:\nCached properties will be applied");
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
        if (index < 0 && swcTypeButtonGroup != null) {
            swcTypeButtonGroup.clearSelection();
            return;
        }
        for (final Component component : swcTypeMenu.getMenuComponents()) {
            if (!(component instanceof JRadioButtonMenuItem mi)) continue;
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

    private void assignDistinctColors(final List<Path> paths) {
        final ColorRGB[] colors = SNTColor.getDistinctColors(paths.size());
        int idx = 0;
        for (final Path p : paths)
            p.setColor(colors[idx++]);
        refreshManager(true, true, paths);
        plugin.setUnsavedChanges(true);
    }

    private boolean allUsingFittedVersion(final Collection<Path> paths) {
        for (Path p : paths) {
            if (!p.getUseFitted())
                return false;
        }
        return true;
    }

    @Override
    public void dispose() {
        closeTable();
        if (measureUI != null) measureUI.dispose();
        if (tree != null) tree.removeAll();
        if (plugin != null) plugin.dispose(); // will dispose pathAndFillManager
        fittingHelper = null;
        measureUI = null;
        swcTypeButtonGroup = null;
        super.dispose();
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
        navToolbar.selectionModelChanged(selectedPaths);
    }

    private void updateHyperstackPosition(final Path p) {
        final ImagePlus imp = plugin.getImagePlus();
        // Keep slice where it is for tracing ergonomics
        if (imp != null) imp.setPosition(p.getChannel(), imp.getSlice(), p.getFrame()); // 1-based indices
    }

    private void displayTmpMsg(final String msg) {
        assert SwingUtilities.isEventDispatchThread();
        guiUtils.tempMsg(msg);
    }

    /* (non-Javadoc)
     * @see PathAndFillListener#setSelectedPaths(java.util.HashSet, java.lang.Object)
     */
    @Override
    public void setSelectedPaths(final Collection<Path> selectedPaths, final Object source) {
        SwingUtilities.invokeLater(() -> {
            if (source == PathManagerUI.this || !pathAndFillManager.enableUIupdates) return;
            if (navToolbar != null) navToolbar.selectedPathsChangedElsewhere(selectedPaths);
            tree.clearSelection();
            tree.setSelectedPaths(selectedPaths);
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
            final HelpfulTreeModel model = new HelpfulTreeModel();
            final Path[] primaryPaths = pathAndFillManager.getPathsStructured();
            for (final Path primaryPath : primaryPaths) {
                // Add the primary path if it's not just a fitted version of another:
                if (!primaryPath.isFittedVersionOfAnotherPath())
                    model.addNode(model.root(), primaryPath);
            }
            tree.setModel(fullTreeModel = model);
            if (navToolbar != null) navToolbar.initFromFullModel();

            // Set back the expanded state:
            if (expandAll)
                GuiUtils.JTrees.expandAllNodes(tree);
            else {
                expandedPathsBefore.add(justAdded);
                tree.setExpandedPaths(expandedPathsBefore);
            }

            // Set back the selection state
            tree.setSelectedPaths(selectedPathsBefore);
        });
    }

    /* Should be called only outside PathManagerUI */
    protected Tree getSingleTree() {
        final Collection<Tree> trees = pathAndFillManager.getTrees();
        if (trees.size() == 1) {
            restoreFullModelAsNeeded(trees);
            return trees.iterator().next();
        }
        final ArrayList<String> treeLabels = new ArrayList<>(trees.size());
        trees.forEach(t -> treeLabels.add(t.getLabel()));
        Collections.sort(treeLabels);
        final String defChoice = plugin.getPrefs().getTemp("singletree", treeLabels.getFirst());
        final GuiUtils gUtilsActive = (hasFocus()) ? guiUtils : new GuiUtils(plugin.getActiveWindow());
        final String choice = gUtilsActive.getChoice("Multiple rooted structures exist. Which one should be considered?",
                "Which Structure?", treeLabels.toArray(new String[trees.size()]), defChoice);
        if (choice != null) {
            plugin.getPrefs().setTemp("singletree", choice);
            for (final Tree t : trees) {
                if (t.getLabel().equals(choice)) {
                    restoreFullModelAsNeeded(trees);
                    return t;
                }
            }
        }
        return null; // user pressed canceled prompt. No need to check if full model needs to be reinstated
    }

    /* Should be called only outside PathManagerUI (#getMultipleTreesInternal() being the only exception)  */
    protected Collection<Tree> getMultipleTrees() {
        final Collection<Tree> trees = pathAndFillManager.getTrees();
        if (trees.size() == 1) {
            restoreFullModelAsNeeded(trees);
            return trees;
        }
        final ArrayList<String> treeLabels = new ArrayList<>(trees.size() + 1);
        trees.forEach(t -> treeLabels.add(t.getLabel()));
        Collections.sort(treeLabels);
        treeLabels.addFirst("   -- All --  ");
        final GuiUtils gUtilsActive = (hasFocus()) ? guiUtils : new GuiUtils(plugin.getActiveWindow());
        final List<String> choices = gUtilsActive.getMultipleChoices("Which Structure(s)?",
                treeLabels.toArray(new String[trees.size()]),"   -- All --  ");
        if (choices == null)
            return null; // no need to restore full model
        if (choices.contains("   -- All --  ")) {
            restoreFullModelAsNeeded(trees);
            return trees;
        }
        final List<Tree> toReturn = new ArrayList<>();
        for (final Tree t : trees) {
            if (choices.contains(t.getLabel())) {
                toReturn.add(t);
            }
        }
        restoreFullModelAsNeeded(toReturn);
        return (toReturn.isEmpty()) ? null : toReturn;
    }

    private Collection<Tree> getMultipleTreesInternal() {
        if (tree.getModel() == fullTreeModel)
            return this.getMultipleTrees();
        // the navigationToolbar is currently filtering for a single arbor
        return List.of(pathAndFillManager.getTree(navToolbar.arborChoice));
    }

    private Collection<Tree> getMultipleTreesFromScriptCall(final String description) {
        Collection<Tree> result = null;
        final Collection<Tree> trees = pathAndFillManager.getTrees();
        if (trees.size() == 1 || description.toLowerCase().contains("all")) {
            result = trees;
        } else for (final Tree t : trees) {
            if (t.getLabel().equals(description)) {
                result = Collections.singleton(t);
                break;
            }
        }
        restoreFullModelAsNeeded(result);
        return result;
    }

    /* Currently GUI Operations work as follows: Only commands called from PathMangerUI respect navigation toolbar
     * filtering. Outside commands use all tree(s) currently available. This method ensures that navigation toolbar
     * filtering is disable if user decided to perform an operation on a tree that is currently hidden by the nav.
     * toolbar filtering.
     */
    private void restoreFullModelAsNeeded(final Collection<Tree> treesChosenByUserElsewhere) {
        // do nothing if user did not select a valid collection or the navToolbar filtering is not active
        if (treesChosenByUserElsewhere == null || treesChosenByUserElsewhere.isEmpty() ||
                tree.getModel() == fullTreeModel || navToolbar == null)
            return;
        // if user selected more than one arbor or if user choice does not match the arbor currently being filtered,
        // // then filtering needs to be disabled
        if (treesChosenByUserElsewhere.size() > 1 ||
                (navToolbar.arborChoice != null && !navToolbar.arborChoice.equals(treesChosenByUserElsewhere.iterator().next().getLabel()))) {
            navToolbar.restoreFullModelState();
        }
    }

    protected void quickMeasurementsCmdError(final GuiUtils guiUtils) {
        guiUtils.error("Selected paths do not fulfill requirements for retrieval of choiceless measurements. "
                + "This can happen if e.g., paths are disconnected, or have been tagged with different type "
                + "flags combined in an unexpected way. Please use the options in the \"Measure\" prompt to "
                + "retrieve measurements.");
        updateTable();
    }

    /* (non-Javadoc)
     * @see PathAndFillListener#setFillList(java.lang.String[])
     */
    @Override
    public void setFillList(final List<Fill> fillList) {
        // ignored
    }

    public JToolBar getNavigationToolBar() {
        return navToolbar;
    }

    private class FitHelper {

        private boolean promptHasBeenDisplayed = false;
        private SwingWorker<Object, Object> fitWorker;
        private List<PathFitter> pathsToFit;

        public void showPrompt() {
            new FittingOptionsWorker(this, false).execute();
        }

        private Boolean displayPromptRequired() {
            final boolean prompt = !promptHasBeenDisplayed && plugin.getUI() != null
                    && plugin.getUI().askUserConfirmation;
            final String[] options = new String[] { "Yes. Adjust parameters...", "No. Use defaults.",
                    "No. Use last used settings." };
            if (prompt) {
                String choice = guiUtils.getChoice(
                        "You have not yet adjusted fitting parameters. It is recommended that you do so at least once. Adjust them now?",
                        "Adjust Parameters?", options, options[0]);
                if (choice == null) {
                    return null;
                } else if (choice.equals(options[0])) {
                    return true;
                } else if (choice.equals(options[1])) {
                    plugin.getContext().getService(PrefService.class).clear(PathFitterCmd.class);
                    return false;
                }
            }
            return false;
        }

        protected synchronized void cancelFit(final boolean updateUIState) {
            if (fitWorker != null) {
                synchronized (fitWorker) {
                    fitWorker.cancel(true);
                    if (updateUIState) plugin.getUI().resetState();
                    fitWorker = null;
                }
            }
        }

        public void fitUsingPrompAsNeeded() {
            if (pathsToFit == null || pathsToFit.isEmpty())
                return; // nothing to fit
            final Boolean prompt = displayPromptRequired();
            if (prompt == null) {
                return; // user pressed cancel
            }
            if (prompt) {
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

            fitWorker = new SwingWorker<>() {

                @Override
	                protected Object doInBackground() {

	                    try (final ExecutorService es = Executors.newFixedThreadPool(processors)) {
	                        final FittingProgress progress = new FittingProgress(plugin.getUI(),
	                                plugin.statusService, numberOfPathsToFit);
	                        try {
	                            final PathFitter refFitter = pathsToFit.getFirst();
	                            refFitter.readPreferences();
	                            for (int i = 0; i < numberOfPathsToFit; ++i) {
	                                final PathFitter pf = pathsToFit.get(i);
	                                pf.applySettings(refFitter);
	                                pf.setProgressCallback(i, progress);
	                            }
	                            final List<Future<Path>> results = es.invokeAll(pathsToFit);
	                            for (final Future<Path> result : results) {
	                                result.get();
	                            }
	                        } catch (final InterruptedException e) {
	                            Thread.currentThread().interrupt();
	                            msg.dispose();
	                            guiUtils.error("Unfortunately an Exception occurred. See Console for details.");
	                            e.printStackTrace();
	                        } catch (final ExecutionException | RuntimeException e) {
	                            msg.dispose();
	                            guiUtils.error("Unfortunately an Exception occurred. See Console for details.");
	                            e.printStackTrace();
	                        } finally {
	                            progress.done();
                            try {
                                es.shutdown();
                            } catch (final Exception ex) {
                                ex.printStackTrace();
                            }
                        }
                    }
                    return null;
                }

                @Override
                protected void done() {
                    if (pathsToFit != null) {
                        // since paths were fitted asynchronously, we need to apply fit
                        // sequentially after all parallel fitting is complete.
                        pathsToFit.forEach(PathFitter::applyFit);
                        // no longer needed: pathAndFillManager.rebuildRelationships();
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
            final CommandService cmdService = plugin.getContext().getService(CommandService.class);
            try {
                final CommandModule cm = cmdService.run(PathFitterCmd.class, true).get();
                if (!cm.isCanceled()) {
                    return "";
                }
            } catch (final InterruptedException | ExecutionException ignored) {
                // do nothing
            }
            return null;
        }

        @Override
        protected void done() {
            try {
                fitHelper.promptHasBeenDisplayed = get() != null;
            } catch (final InterruptedException | ExecutionException ex) {
                SNTUtils.error(ex.getMessage(), ex);
                fitHelper.promptHasBeenDisplayed = false;
            }
            if (fit) {
                fitHelper.fit();
            }
        }

    }

    /** This class defines the model for the JTree hosting traced paths */
    class HelpfulTreeModel extends DefaultTreeModel {

        private static final long serialVersionUID = 1L;
        private final Map<Path, DefaultMutableTreeNode> pathToNodeMap = new HashMap<>();

        HelpfulTreeModel() {
            super(new DefaultMutableTreeNode(HelpfulJTree.ROOT_LABEL));
        }

        HelpfulTreeModel(final Path[] primaryPaths, final String treeLabel) {
            this();
            for (final Path primaryPath : primaryPaths) {
                if (treeLabel.equals(primaryPath.getTreeLabel()))
                    addNode(root(), primaryPath);
            }
        }

        DefaultMutableTreeNode root() {
            return (DefaultMutableTreeNode) root;
        }

        void addNode(final MutableTreeNode parent, final Path childPath) {
            final DefaultMutableTreeNode newNode = new DefaultMutableTreeNode(childPath);
            pathToNodeMap.put(childPath, newNode);
            insertNodeInto(newNode, parent, parent.getChildCount());
            childPath.getChildren().forEach(p -> addNode(newNode, p));
        }

        Map<Path, DefaultMutableTreeNode> getPathToNodeMap() {
            return pathToNodeMap;
        }
    }

    /** This class defines the JTree hosting traced paths */
    private class HelpfulJTree extends JTree {

        private static final long serialVersionUID = 1L;
        private static final String ROOT_LABEL = "All Paths";
        private final TreeSearchable searchable;

        public HelpfulJTree() {
            super(new DefaultMutableTreeNode(HelpfulJTree.ROOT_LABEL));
            //putClientProperty(com.formdev.flatlaf.FlatClientProperties.TREE_WIDE_SELECTION, true );
            setLargeModel(true);
            setCellRenderer(new NodeRender());
            getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
            setExpandsSelectedPaths(true);
            setScrollsOnExpand(true);
            setRowHeight(getFontMetrics(getFont()).getHeight()); // otherwise viewport too small!?
            searchable = new TreeSearchable(this);
            final Timer timer = new Timer(400, ev -> getSNT().getUI().getRecorder(false)
                    .recordCmd(String.format("snt.getUI().getPathManager().applySelectionFilter(\"%s\")",
                            searchable.getSearchingText())));
            timer.setRepeats(false);
            searchable.addSearchableListener(e -> {
                if (!timer.isRunning() && getSNT().getUI().getRecorder(false) != null && searchable.getSearchingText() != null
                        && !searchable.getSearchingText().isEmpty()) {
                    timer.start();
                }
            });
        }

        @Override
        public void setModel(final TreeModel newModel) {
            super.setModel(newModel);
            if (searchableBar != null)
                searchableBar.setStatusLabelPlaceholder(String.format("%d Path(s) listed", getNumberOfNodes()));
        }

        @Override
        protected void paintComponent(final Graphics g) {
            super.paintComponent(g);
            if (getModel() != null && getModel().getChildCount(getRoot()) < 1) { // if no model yet: avoid NPE until setPathList provides one
                GuiUtils.drawDragAndDropPlaceHolder(this, (Graphics2D) g);
            }
        }

        private DefaultMutableTreeNode getRoot() {
            final TreeModel m = getModel();
            if (m == null) return null;
            return (DefaultMutableTreeNode) m.getRoot();
        }

        public Set<Path> getSelectedPaths() {
            final TreePath[] selectionTreePath = getSelectionPaths();
            final Set<Path> selectedPaths = new HashSet<>();
            if (selectionTreePath == null)
                return selectedPaths;
            for (final TreePath tp : selectionTreePath) {
                final DefaultMutableTreeNode node = (DefaultMutableTreeNode) tp.getLastPathComponent();
                if (!node.isRoot()) {
                    selectedPaths.add((Path) node.getUserObject());
                }
            }
            return selectedPaths;
        }

        public Set<Path> getExpandedPaths() {
            final Set<Path> set = new HashSet<>();
            final TreeModel model = getModel();
            if (!(model instanceof HelpfulTreeModel))
                return set;

            final Map<Path, DefaultMutableTreeNode> pathToNodeMap = ((HelpfulTreeModel) model).getPathToNodeMap();
            for (final Map.Entry<Path, DefaultMutableTreeNode> entry : pathToNodeMap.entrySet()) {
                final TreePath treePath = new TreePath(entry.getValue().getPath());
                if (isExpanded(treePath)) {
                    set.add(entry.getKey());
                }
            }
            return set;
        }

        public void setSelectedPaths(final Collection<Path> set) {
            assert SwingUtilities.isEventDispatchThread();
            if (set == null || set.isEmpty()) return;

            final TreeModel model = getModel();
            if (!(model instanceof HelpfulTreeModel)) return;

            final Map<Path, DefaultMutableTreeNode> pathToNodeMap = ((HelpfulTreeModel) model).getPathToNodeMap();
            final boolean updateCTposition = set.size() == 1;

            for (final Path path : set) {
                final DefaultMutableTreeNode node = pathToNodeMap.get(path);
                if (node != null) {
                    addSelectionPath(new TreePath(node.getPath()));
                    if (updateCTposition && plugin != null) {
                        updateHyperstackPosition(path);
                    }
                }
            }
        }

        public void setExpandedPaths(final Collection<Path> set) {
            assert SwingUtilities.isEventDispatchThread();
            if (set == null || set.isEmpty()) return;

            final TreeModel model = getModel();
            if (!(model instanceof HelpfulTreeModel)) return;

            final Map<Path, DefaultMutableTreeNode> pathToNodeMap = ((HelpfulTreeModel) model).getPathToNodeMap();

            for (final Path path : set) {
                final DefaultMutableTreeNode node = pathToNodeMap.get(path);
                if (node != null) {
                    expandPath(new TreePath(node.getPath()));
                }
            }
        }

        public void repaintSelectedNodes() {
            final TreePath[] selectedPaths = getSelectionPaths();
            if (selectedPaths != null) {
                final DefaultTreeModel model = (DefaultTreeModel) getModel();
                for (final TreePath tp : selectedPaths) {
                    final DefaultMutableTreeNode node = (DefaultMutableTreeNode) (tp.getLastPathComponent());
                    model.nodeChanged(node);
                }
            }
        }

        public void reload() {
            final List<TreePath> expanded = new ArrayList<>();
            for (int i = 0; i < getRowCount() - 1; i++) {
                TreePath currPath = getPathForRow(i);
                TreePath nextPath = getPathForRow(i + 1);
                if (currPath.isDescendant(nextPath))
                    expanded.add(currPath);
            }
            ((DefaultTreeModel)getModel()).reload();
            for (final TreePath path : expanded)
                expandPath(path);
        }

        int getNumberOfNodes() {
            return getNumberOfNodes(getModel(), getRoot()) - 1;// exclude hidden root
        }

        private int getNumberOfNodes(final TreeModel model, final Object node) {
            int count = 1;
            int nChildren = model.getChildCount(node);
            for (int i = 0; i < nChildren; i++) {
                count += getNumberOfNodes(model, model.getChild(node, i));
            }
            return count;
        }

    }

    private static class NodeRender extends DefaultTreeCellRenderer {

        private static final long serialVersionUID = 1L;

        public NodeRender() {
            super();
            setClosedIcon(IconFactory.nodeIcon( null, false, false));
            setOpenIcon(IconFactory.nodeIcon( null, false, true));
            setLeafIcon(IconFactory.nodeIcon( null, true, false));
        }

        @Override
        public Component getTreeCellRendererComponent(final JTree tree,
                                                      final Object value, final boolean selected, final boolean expanded,
                                                      final boolean isLeaf, final int row, final boolean focused)
        {
            final Component c = super.getTreeCellRendererComponent(tree, value,
                    selected, expanded, isLeaf, row, focused);

            final TreePath tp = tree.getPathForRow(row);
            if (tp == null) {
                return c;
            }
            final DefaultMutableTreeNode node = (DefaultMutableTreeNode) (tp
                    .getLastPathComponent());
            if (node == null || node.isRoot()) return c;
            final Path p = (Path) node.getUserObject();
            if (p.getColor() == null && !p.hasNodeColors()) {
                return c;
            }
            if (p.hasNodeColors()) {
                setIcon(IconFactory.nodeIconMulticolor(isLeaf, expanded));
            } else {
                setIcon(IconFactory.nodeIcon( p.getColor(), isLeaf, expanded));
            }
            return c;
        }

    }

	private static class TreeTransferHandler extends TransferHandler {

        private static final long serialVersionUID = 1L;
        DataFlavor nodesFlavor;
        final DataFlavor[] flavors = new DataFlavor[1];
        DefaultMutableTreeNode[] nodesToRemove;

        public TreeTransferHandler() {
            try {
                final String mimeType = DataFlavor.javaJVMLocalObjectMimeType +
                        ";class=\"" + DefaultMutableTreeNode[].class
                        .getName() + "\"";
                nodesFlavor = new DataFlavor(mimeType);
                flavors[0] = nodesFlavor;
            }
            catch (final ClassNotFoundException e) {
                System.out.println("ClassNotFound: " + e.getMessage());
            }
        }

        @Override
        public boolean canImport(final TransferSupport support) {
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
            assert selRows != null;
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
            assert selRows != null;
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
        public boolean importData(final TransferSupport support) {
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
            catch (final IOException ioe) {
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
            assert nodes != null;
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

            final DefaultMutableTreeNode[] nodes;

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

    private void setEnabledCommands(final boolean enabled) {
        assert SwingUtilities.isEventDispatchThread();
        tree.setEnabled(enabled);
        menuBar.setEnabled(enabled);
    }

    private void refreshManager(final boolean refreshCmds,
                                final boolean refreshViewers, final Collection<Path> selectedPaths)
    {
        if (refreshViewers)
            plugin.updateAllViewers(); // will call #update()
        else if (tree.getSelectionCount() == 0)
            tree.reload();
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
        update(false);
    }

    /**
     * Refreshes (Repaints) the Path Manager JTree.
     *
     * @param selectedPathsOnly Whether only selected nodes in the JTree should be
     *                          updated (repainted). If {@code false} all nodes are
     *                          updated.
     */
    public void update(final boolean selectedPathsOnly) {
        if (selectedPathsOnly)
            tree.repaintSelectedNodes();
        else
            tree.reload();
    }

    /** Reloads the contents of {@link PathAndFillManager} */
    public void reload() {
        setPathList(pathAndFillManager.getPaths(), null, true);
    }

    private void closeTable() {
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
			table = new SNTTable();
		}
		return table;
	}

    /**
     * Gets the SNT instance associated with this Path Manager.
     *
     * @return the SNT instance
     */
    public SNT getSNT() {
        return plugin;
    }

    /**
     * Gets the PathAndFillManager instance.
     *
     * @return the PathAndFillManager instance
     */
    public PathAndFillManager getPathAndFillManager() {
        return pathAndFillManager;
    }

    /**
     * Gets the JTree component used to display paths.
     *
     * @return the JTree component
     */
    public JTree getJTree() {
        return tree;
    }

    /**
     * Gets the Searchable interface for the JTree.
     *
     * @return the Searchable interface
     */
    public Searchable getSearchable() {
        return tree.searchable;
    }

    /**
     * Selects paths matching a morphometric criteria.
     *
     * @param property The morphometric property ("Length", "Path order", etc.) as
     *                 listed in the "Morphology filter" menu (case-sensitive).
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
     * Sets whether selection filters should be combined. This is equivalent to toggling the 'filter' button
     * in Path Manager's UI
     *
     * @param enable if true, selection filters are combined
     */
    public void setCombinedSelectionFilters(final boolean enable) {
        searchableBar.setSubFilteringEnabled(enable);
    }

    /**
     * Applies a custom tag/ color to selected Path(s).
     *
     * @param customTagOrColor The tag (or color) to be applied to selected Paths. Specifying "null color" will remove
     *                         color tags from selected paths.
     */
    @SuppressWarnings("unused")
    public void applyTag(final String customTagOrColor) throws IllegalArgumentException {
        final ColorRGB color = ("null color".equalsIgnoreCase(customTagOrColor)) ? null : new ColorRGB(customTagOrColor);
        if (color != null && color.getRed() == 0 && color.getGreen() == 0 && color.getBlue() == 0) {
            // then parsing failed
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
     *                    "Tag" menu, e.g.,
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

    void applyActiveTags(final Collection<Path> paths) {
        final List<String> activeTags = guessTagsCurrentlyActive();
        activeTags.forEach( tag -> removeOrReapplyDefaultTag(paths, tag, true, false));
    }

    String untaggedPathName(final Path p) {
        return MultiPathActionListener.TAG_DEFAULT_PATTERN.matcher(p.getName()).replaceAll("");
    }

    /** Should be the only method called for toggling built-in tags **/
    private void removeOrReapplyDefaultTag(final Collection<Path> paths, final String cmd, final boolean reapply, final boolean interactiveUI) {
        switch (cmd) {
            case MultiPathActionListener.CHANNEL_TAG_CMD -> {
                paths.forEach(p -> p.setName(CHANNEL_TAG_PATTERN.matcher(p.getName()).replaceAll("")));
                if (reapply)
                    paths.forEach(p -> p.setName(p.getName() + " [Ch:" + p.getChannel() + "]"));
            }
            case MultiPathActionListener.FRAME_TAG_CMD -> {
                paths.forEach(p -> p.setName(FRAME_TAG_PATTERN.matcher(p.getName()).replaceAll("")));
                if (reapply)
                    paths.forEach(p -> p.setName(p.getName() + " [T:" + p.getFrame() + "]"));
            }
            case MultiPathActionListener.SLICE_TAG_CMD -> {
                paths.forEach(p -> p.setName(SLICE_TAG_PATTERN.matcher(p.getName()).replaceAll("")));
                if (reapply)
                    paths.forEach(p -> p.setName(p.getName() + " [Z:" + (p.getZUnscaled(0) + 1) + "]"));
            }
            case MultiPathActionListener.TREE_TAG_CMD -> {
                paths.forEach(p -> p.setName(TREE_TAG_PATTERN.matcher(p.getName()).replaceAll("")));
                if (reapply)
                    paths.forEach(p -> p.setName(p.getName() + " [" + SYM_TREE + p.getTreeLabel() + "]"));
            }
            case MultiPathActionListener.N_CHILDREN_TAG_CMD -> {
                paths.forEach(p -> p.setName(CHILDREN_TAG_PATTERN.matcher(p.getName()).replaceAll("")));
                if (reapply)
                    paths.forEach(p -> p.setName(p.getName() + " [" + SYM_CHILDREN + p.getChildren().size() + "]"));
            }
            case "Extension Angle...", PathStatistics.PATH_EXT_ANGLE_XY, PathStatistics.PATH_EXT_ANGLE_XZ,
                 PathStatistics.PATH_EXT_ANGLE_ZY, PathStatistics.PATH_EXT_ANGLE, PathStatistics.PATH_EXT_ANGLE_REL -> {
                paths.forEach(p -> p.setName(ANGLE_TAG_PATTERN.matcher(p.getName()).replaceAll("")));
                if (reapply) {
                    final boolean relative = cmd.toLowerCase().contains("rel");
                    paths.forEach(p -> {
                        double value;
                        if (cmd.contains("XZ"))
                            value = p.getExtensionAngleXZ();
                        else if (cmd.contains("ZY"))
                            value = p.getExtensionAngleZY();
                        else if (cmd.contains("XY"))
                            value = p.getExtensionAngleXY();
                        else
                            value = p.getExtensionAngle3D(relative);
                        p.setName(String.format(Locale.US, "%s [%s%.1f]", p.getName(), SYM_ANGLE, value));
                    });
                }
            }
            case MultiPathActionListener.ORDER_TAG_CMD -> {
                paths.forEach(p -> p.setName(ORDER_TAG_PATTERN.matcher(p.getName()).replaceAll("")));
                if (reapply)
                    paths.forEach(p -> p.setName(p.getName() + " [" + SYM_ORDER + p.getOrder() + "]"));
            }
            case MultiPathActionListener.LENGTH_TAG_CMD -> {
                paths.forEach(p -> p.setName(LENGTH_TAG_PATTERN.matcher(p.getName()).replaceAll("")));
                if (reapply) {
                    paths.forEach(p -> {
                        final String lengthTag = " [" + SYM_LENGTH + p.getRealLengthString() + p.spacing_units + "]";
                        p.setName(p.getName() + lengthTag);
                    });
                }
            }
            case MultiPathActionListener.MEAN_RADIUS_TAG_CMD -> {
                paths.forEach(p -> p.setName(RADIUS_TAG_PATTERN.matcher(p.getName()).replaceAll("")));
                if (reapply) {
                    paths.forEach(p -> {
                        final String radiusTag = " [" + SYM_RADIUS + SNTUtils.formatDouble(p.getMeanRadius(), 3) + p.spacing_units + "]";
                        p.setName(p.getName() + radiusTag);
                    });
                }
            }
            case MultiPathActionListener.COUNT_TAG_CMD -> {
                paths.forEach(p -> p.setName(MARKER_TAG_PATTERN.matcher(p.getName()).replaceAll("")));
                if (reapply) {
                    paths.forEach(p -> {
                        final String countTag = " [" + SYM_MARKER + p.getSpineOrVaricosityCount() + "]";
                        p.setName(p.getName() + countTag);
                    });
                }
            }
            case MultiPathActionListener.SLICE_LABEL_TAG_CMD -> {
                if (reapply) { // Special case: not toggleable: Nothing to remove
                    if (noValidImageDataError()) return;
                    int errorCounter = 0;
                    for (final Path p : paths) {
                        try {
                            String label = plugin.getImagePlus().getStack().getShortSliceLabel(
                                    plugin.getImagePlus().getStackIndex(p.getChannel(), p.getZUnscaled(0) + 1, // 1-based index,
                                            p.getFrame()));
                            if (label == null || label.isEmpty()) {
                                errorCounter++;
                                continue;
                            }
                            label = label.replace("[", "(");
                            label = label.replace("]", ")");
                            final String existingTags = extractTagsFromPath(p);
                            if (!existingTags.contains(label))
                                p.setName(p.getName() + " {" + label + ((existingTags.isEmpty()) ? ""
                                        : ", ") + existingTags + "}");
                        } catch (final IllegalArgumentException | IndexOutOfBoundsException ignored) {
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
            }
            default -> throw new IllegalArgumentException("Unrecognized tag '" + cmd + "'. Not a default option?");
        }
        selectTagCheckBoxMenu(cmd, reapply);
        if (interactiveUI) refreshManager(false, false, paths);
    }

	/**
	 * Selects paths matching a text-based criteria, or a list of SWC type flags
	 *
	 * @param query The matching text, as it would have been typed in the "Text filtering" box.
	 *              If query encodes an integer list (e.g., "[1,3,5]"), paths are selected based on their SWC type flag
	 */
	public void applySelectionFilter(final String query) throws IllegalArgumentException {
		if (query != null && query.startsWith("[") && query.endsWith("]")) {
			final String q =  query.substring( 1, query.length() - 1);
			final List<Integer> types = Arrays.stream(q.split("\\s*,\\s*")).map(Integer::parseInt).toList();
			if (!types.isEmpty()) {
				final Collection<Path> paths = searchableBar.getPaths();
				paths.removeIf(path -> !types.contains(path.getSWCType()));
                tree.setSelectedPaths(paths);
			}
			return;
		}
		final List<Integer> hits = searchableBar.getSearchable().findAll(query);
		if (hits != null && !hits.isEmpty()) {
			final int[] array = hits.stream().mapToInt(i -> i).toArray();
			tree.addSelectionRows(array);
		}
	}

    /**
     * Selects paths matching a morphometric criteria.
     *
     * @param property The morphometric property ("Length", "Path order", etc.) as
     *                 listed in the "Morphology filter" menu (case-sensitive).
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

    /**
     * Clears the current path selection.
     */
    public void clearSelection() {
        SwingUtilities.invokeLater(tree::clearSelection);
    }

    /**
     * Selects all paths.
     */
    public void selectAll() {
        tree.setSelectionInterval(0, tree.getRowCount());
    }

    /**
     * Selects a specific path in the Path Manager.
     *
     * @param path the path to be selected. If the path is not found in the manager,
     *             the selection remains unchanged. If null active selection (if any)
     *             is cleared.
     */
    public void select(final Path path) {
        SwingUtilities.invokeLater(() -> {
            tree.clearSelection();
            if (path != null) tree.setSelectedPaths(List.of(path));
        });
    }

    /**
     * Runs a menu command.
     *
     * @param cmd The command to be run, exactly as listed in the PathManagerUI's
     *            menu bar or Right-click contextual menu
     * @throws IllegalArgumentException if {@code cmd} was not found.
     */
    public void runCommand(final String cmd) throws IllegalArgumentException {
        if (!runCustomCommand(cmd) && !plugin.getUI().runCustomCommand(cmd))
            plugin.getUI().runSNTCommandFinderCommand(cmd);
    }

    protected boolean runCustomCommand(final String cmd) {
        if (MultiPathActionListener.DELETE_CMD.equals(cmd)) {
            deletePaths(getSelectedPaths(true));
            return true;
        } else if (MultiPathActionListener.REBUILD_CMD.equals(cmd)) {
            rebuildRelationShips();
            return true;
        } else if (MultiPathActionListener.MATCH_PATHS_ACROSS_TIME_CMD.equals(cmd)) {
            final HashMap<String, Object> inputs = new HashMap<>();
            inputs.put("paths", getSelectedPaths(true));
            inputs.put("applyDefaults", true);
            (plugin.getUI().new DynamicCmdRunner(PathMatcherCmd.class, inputs)).run();
            return true;
        } else if (MultiPathActionListener.ASSIGN_DISTINCT_COLORS.equals(cmd)) {
            assignDistinctColors(getSelectedPaths(true));
            return true;
        }
        return false;
    }

    /**
     * Runs a menu command with options.
     *
     * @param cmd  The command to be run, exactly as listed in the PathManagerUI's
     *             menu bar or Right-click contextual menu
     * @param args the option(s) that would fill the command's prompt. e.g.,
     *             'runCommand("Branch-based Color Mapping...", "X coordinates", "Cyan Hot.lut")'
     * @throws IllegalArgumentException if {@code cmd} was not found, or if it is
     *                                  not supported.
     */
    public void runCommand(final String cmd, String... args) throws IllegalArgumentException, IOException {
        final boolean noArgs = args == null || args.length == 0 || args[0].trim().isEmpty();
        if (noArgs) {
            runCommand(cmd);
            return;
        }
        switch (cmd) {
            case MultiPathActionListener.HISTOGRAM_TREES_CMD -> {
                if (args.length == 1) {
                    runDistributionAnalysisCmd("All", args[0], false);
                } else {
                    runDistributionAnalysisCmd(args[0], args[1], (args.length > 2) && Boolean.parseBoolean(args[2]));
                }
            }
            case MultiPathActionListener.HISTOGRAM_PATHS_CMD -> runHistogramPathsCmd(getSelectedPaths(true), args[0], //
                    (args.length > 1) && Boolean.parseBoolean(args[1]), //
                    (args.length > 2) ? args[2] : null, //
                    (args.length > 3) && Boolean.parseBoolean(args[3]));
            case MultiPathActionListener.COLORIZE_TREES_CMD -> {
                if (args.length == 2) {
                    runColorCodingCmd("All", args[0], args[1]);
                } else if (args.length > 2) {
                    runColorCodingCmd(args[0], args[1], args[2]);
                } else {
                    throw new IllegalArgumentException("Not enough arguments...");
                }
            }
            case (MultiPathActionListener.HISTOGRAM_2D_CMD) -> {
                final Map<String, Object> input = new HashMap<>();
                input.put("tree", new Tree(getSelectedPaths(true)));
                if (plugin.accessToValidImageData()) input.put("dataset", plugin.getDataset());
                input.put("measurementChoice1", args[0]);
                if (args.length > 1) input.put("measurementChoice2", args[1]);
                if (args.length > 2) input.put("colorMapChoice", args[2]);
                final CommandService cmdService = plugin.getContext().getService(CommandService.class);
                cmdService.run(TwoDHistCmd.class, true, input);
            }
            case MultiPathActionListener.COLORIZE_PATHS_CMD -> {
                if (args.length > 1) {
                    runColorCodingCmd(geSelectedPathsAsTree(), true, args[0], args[1]);
                } else {
                    throw new IllegalArgumentException("Not enough arguments...");
                }
            }
            case MultiPathActionListener.CONVERT_TO_ROI_CMD -> {
                if (args.length == 1) {
                    runRoiConverterCmd(args[0], null);
                } else {
                    runRoiConverterCmd(args[0], args[1]);
                }
            }
            case MultiPathActionListener.COLORIZE_REMOVE_CMD -> {
                ColorMapper.unMap(getSelectedPaths(true));
                plugin.updateAllViewers();
            }
            case null, default ->
                    throw new IllegalArgumentException("Unsupported command or invalid options for '" + cmd + "'");
        }
    }

    private void runRoiConverterCmd(final String type, final String view) throws IllegalArgumentException {
        final Map<String, Object> input = new HashMap<>();
        input.put("tree", geSelectedPathsAsTree());
        input.put("imp", (plugin.accessToValidImageData()) ? plugin.getImagePlus() : null);
        input.put("roiChoice", type);
        input.put("viewChoice", (view==null) ? "XY (default)" : view);
        input.put("useSWCcolors", false);
        input.put("avgWidth", true);
        input.put("discardExisting", false);
        final CommandService cmdService = plugin.getContext().getService(CommandService.class);
        cmdService.run(ROIExporterCmd.class, true, input);
    }

    private void runColorCodingCmd(final String singleTreeDescriptor, final String metric, final String lutName) throws IllegalArgumentException, IOException {
        final Collection<Tree> trees = getMultipleTreesFromScriptCall(singleTreeDescriptor);
        if (trees == null) throw new IllegalArgumentException("Not a recognized choice "+ singleTreeDescriptor);
        runColorCodingCmd(trees.iterator().next(), false, metric, lutName);
    }

    private void runColorCodingCmd(final Tree tree, final boolean onlyConnectivitySafeMetrics, final String metric, final String lutName) throws IllegalArgumentException, IOException {
        final Map<String, Object> input = new HashMap<>();
        input.put("trees", List.of(tree));
        input.put("measurementChoice", metric);
        input.put("lutChoice", lutName);
        input.put("runFigCreator", false);
        input.put("dataset", plugin.accessToValidImageData() ? plugin.getDataset() : null);
        input.put("removeColorCoding", null);
        input.put("onlyConnectivitySafeMetrics", onlyConnectivitySafeMetrics);
        final LUTService lutService = plugin.getContext().getService(LUTService.class);
        input.put("colorTable", lutService.loadLUT(lutService.findLUTs().get(lutName)));
        final CommandService cmdService = plugin.getContext().getService(CommandService.class);
        cmdService.run(TreeMapperCmd.class, true, input);
    }

    private void runHistogramPathsCmd(final Collection<Path> paths, final String metric1, final Boolean polar1,
                                      final String metric2, final Boolean polar2) {
        final Map<String, Object> input = new HashMap<>();
        final Tree tree = new Tree(paths);
        tree.setLabel("Selected Paths");
        input.put("tree", tree);
        input.put("calledFromPathManagerUI", true);
        input.put("onlyConnectivitySafeMetrics", true);
        if (metric1 != null) input.put("measurementChoice1", metric1);
        if (polar1 != null) input.put("polar1", polar1);
        if (metric2 != null) input.put("measurementChoice2", metric2);
        if (polar2 != null) input.put("polar2", polar2);
        if (plugin.accessToValidImageData()) input.put("dataset", plugin.getDataset());
        final CommandService cmdService = plugin.getContext().getService(CommandService.class);
        cmdService.run(DistributionBPCmd.class, true, input);
    }

    private void runDistributionAnalysisCmd(final String treeCollectionDescriptor, final String metric, final boolean polar) throws IllegalArgumentException {
        final Collection<Tree> trees = getMultipleTreesFromScriptCall(treeCollectionDescriptor);
        if (trees == null) throw new IllegalArgumentException("Not a recognized choice "+ treeCollectionDescriptor);
        runDistributionAnalysisCmd(trees, metric, polar);
    }

    private void runDistributionAnalysisCmd(final Collection<Tree> trees, final String metric, final Boolean polar) {
        final CommandService cmdService = plugin.getContext().getService(CommandService.class);
        final Map<String, Object> input = new HashMap<>();
        input.put("trees", trees);
        input.put("calledFromPathManagerUI", true);
        if (metric != null) input.put("measurementChoice", metric);
        if (polar != null) input.put("polar", polar);
        cmdService.run(DistributionCPCmd.class, true, input);
    }

    protected boolean measurementsUnsaved() {
        return validTableMeasurements() && table.hasUnsavedData();
    }

    private boolean validTableMeasurements() {
        return table != null && table.getRowCount() > 0 && table
                .getColumnCount() > 0;
    }

    protected void measureCells() {
        if (measureUI != null) {
            if (plugin.isUnsavedChanges() || guiUtils.getConfirmation("A Measurements prompt seems to be already open. Close it?",
                    "Measurements Prompt Already Open")) {
                measureUI.dispose();
                measureUI = null;
            } else {
                measureUI.toFront();
            }
        }
        if (measureUI == null) {
            final Collection<Tree> trees = getMultipleTreesInternal();
            if (trees == null) return;
            measureUI = new MeasureUI(plugin, trees);
            measureUI.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(final WindowEvent e) {
                    measureUI = null;
                }
            });
            measureUI.setVisible(true);
        }
    }

    private class JTreeMenuItem extends JMenuItem implements ActionListener {

        private static final long serialVersionUID = 1L;
        private static final String COLLAPSE_ALL_CMD = "Collapse All";
        private static final String COLLAPSE_SELECTED_LEVEL= "Collapse Selected Level";
        private static final String EXPAND_ALL_CMD = "Expand All";
        private static final String EXPAND_SELECTED_LEVEL = "Expand Selected Level";
        private static final String SELECT_NONE_CMD = "Deselect / Select All";
        private static final String TOGGLE_NAV_TOOLBAR = "Toggle Navigation Toolbar";

        private JTreeMenuItem(final String tag) {
            super(tag);
            setIcon(tag);
            addActionListener(this);
            if (SELECT_NONE_CMD.equals(tag)) {
                setToolTipText("Commands processing multiple paths will\n"
                        + "process the entire list when no selection exists");
                ScriptRecorder.setRecordingCall(this, "snt.getUI().getPathManager().clearSelection()");
            }
        }

        void setIcon(final String tag) {
            final IconFactory.GLYPH iconGlyph = switch (tag) {
                case COLLAPSE_ALL_CMD -> IconFactory.GLYPH.ARROWS_DLUR;
                case COLLAPSE_SELECTED_LEVEL -> IconFactory.GLYPH.CARET_DOWN;
                case EXPAND_ALL_CMD -> IconFactory.GLYPH.RESIZE;
                case EXPAND_SELECTED_LEVEL -> IconFactory.GLYPH.CARET_UP;
                case SELECT_NONE_CMD -> IconFactory.GLYPH.CHECK_DOUBLE;
                case TOGGLE_NAV_TOOLBAR -> IconFactory.GLYPH.NAVIGATE;
                default -> null;
            };
            if (iconGlyph != null) setIcon(IconFactory.menuIcon(iconGlyph));
        }

        @Override
        public void actionPerformed(final ActionEvent e) {
            switch (e.getActionCommand()) {
                case TOGGLE_NAV_TOOLBAR -> navToolbar.setVisible(!navToolbar.isVisible());
                case SELECT_NONE_CMD -> tree.clearSelection();
                case EXPAND_ALL_CMD -> GuiUtils.JTrees.expandAllNodes(tree);
                case COLLAPSE_ALL_CMD -> GuiUtils.JTrees.collapseAllNodes(tree);
                case COLLAPSE_SELECTED_LEVEL, EXPAND_SELECTED_LEVEL -> {
                    final TreePath selectedPath = tree.getSelectionPath();
                    if (selectedPath == null) {
                        guiUtils.error("No path selected.");
                        return;
                    }
                    if (EXPAND_SELECTED_LEVEL.equals(e.getActionCommand()))
                        GuiUtils.JTrees.expandNodesOfSameLevel(tree, selectedPath);
                    else
                        GuiUtils.JTrees.collapseNodesOfSameLevel(tree, selectedPath);
                }
                default -> SNTUtils.error("Unexpectedly got an event from an unknown source: " + e);
            }
        }
    }

    /** ActionListener for commands operating exclusively on a single path */
    private class SinglePathActionListener implements ActionListener {

        private static final String RENAME_CMD = "Rename...";
        private static final String DUPLICATE_CMD = "Duplicate...";
        private static final String EXPLORE_FIT_CMD = "Explore/Preview Fit";
        private static final String STRAIGHTEN = "Straighten...";
        private static final String NODE_PROFILER = "Node Profiler...";

        @Override
        public void actionPerformed(final ActionEvent e) {

            // Process nothing without a single path selection
            final Collection<Path> selectedPaths = getSelectedPaths(false);
            if (selectedPaths.size() != 1) {
                guiUtils.error("This command accepts only a single path. Please re-run after having only one path selected.");
                return;
            }
            final Path p = selectedPaths.iterator().next();
            switch (e.getActionCommand()) {
                case RENAME_CMD -> {
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
                            plugin.setUnsavedChanges(true);
                        }
                        refreshManager(false, false, Collections.singleton(p));
                    }
                }
                case DUPLICATE_CMD -> {
                    final HashMap<String, Object> inputs = new HashMap<>();
                    inputs.put("path", p);
                    (plugin.getUI().new DynamicCmdRunner(DuplicateCmd.class, inputs)).run();
                }
                case EXPLORE_FIT_CMD -> {
                    if (noValidImageDataError())
                        return;
                    if (!plugin.uiReadyForModeChange() && plugin.getEditingPath() != null
                            && !p.equals(plugin.getEditingPath())) {
                        guiUtils.error("Please finish current operation before exploring fit.");
                        return;
                    }
                    if (fittingHelper == null)
                        fittingHelper = new FitHelper();
                    exploreFit(p, fittingHelper);
                }
                case STRAIGHTEN -> straightenPath(p);
                case NODE_PROFILER -> {
                    if (noValidImageDataError())
                        return;
                    final HashMap<String, Object> input = new HashMap<>();
                    input.put("path", p);
                    input.put("dataset", plugin.getDataset());
                    (plugin.getUI().new DynamicCmdRunner(NodeProfiler.class, input)).run();
                }
                default -> SNTUtils.error("Unexpectedly got an event from an unknown source: " + e);
            }
        }
    }

    private void exploreFit(final Path p, final FitHelper fittingHelper) {
        assert SwingUtilities.isEventDispatchThread();

        // Announce computation
        final SNTUI ui = plugin.getUI();
        final String statusMsg = "Fitting " + p.toString();
        ui.showStatus(statusMsg, false);
        setEnabledCommands(false);
        new Thread(() -> {

            final Path existingFit = p.getFitted();

            // No image is displayed if run on EDT
            final SwingWorker<?, ?> worker = new SwingWorker<>() {

                @Override
                protected Object doInBackground() throws Exception {

                    try {

                        final Boolean prompt = fittingHelper.displayPromptRequired();
                        if (prompt == null) {
                            return null; // user pressed cancel
                        } else if (prompt) {
                            final CommandService cmdService = plugin.getContext().getService(CommandService.class);
                            final CommandModule cm = cmdService.run(PathFitterCmd.class, true).get();
                            if (cm.isCanceled())
                                return null;
                        }
                        // discard existing fit, in case a previous fit exists
                        p.setUseFitted(false);
                        p.setFitted(null);

                        // Compute verbose fit using settings from last PathFitterCmd run
                        final PathFitter fitter = new PathFitter(plugin, p);
                        fitter.setShowAnnotatedView(true);
                        fitter.readPreferences();
                        final Future<Path> future;
                        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                            future = executor.submit(fitter);
                        }
                        future.get();

                    } catch (InterruptedException | ExecutionException
                             | RuntimeException e) {
                        guiUtils.error(
                                "Unfortunately an exception occurred. See Console for details");
                        e.printStackTrace();
                    }
                    return "";
                }

                @Override
                protected void done() {
                    try {
                        if (get() != null) { // otherwise user pressed cancel in a prompt
                            // this is just a preview cmd: Reinstate previous fit, if any
                            p.setFitted(null);
                            p.setFitted(existingFit);
                            // Show both original and fitted paths
                            if (plugin.showOnlySelectedPaths)
                                ui.togglePathsChoice();
                            plugin.enableEditMode(true);
                            plugin.setEditingPath(p);
                            promptForExploreFitManual();
                        }
                    } catch (final InterruptedException | ExecutionException e) {
                        e.printStackTrace();
                    } finally {
                        setEnabledCommands(true);
                    }
                }
            };
            worker.execute();
        }).start();
    }

    private void promptForExploreFitManual() {
        if (!plugin.getUI().askUserConfirmation || !plugin.getPrefs().getBoolean("efprompt", true))
            return;
        final boolean[] options = guiUtils.getConfirmationAndOption(
                "You can peruse the fit by navigating the <i>Cross-section View</i> stack. " //
                        + "Cross section planes will automatically synchronize with tracing canvas(es).<br>"
                        + "Would you like to open the online manual for further details?", //
                "Fly-through Animation Usage", "Do not remind me again about this",
                true, new String[] {"Yes. Open Manual", "Dismiss"});
        if (options != null && options[0])
            GuiUtils.openURL("https://imagej.net/plugins/snt/manual#explorepreview-fit");
        plugin.getPrefs().set("efprompt", options !=null && !options[1]);
    }

    private void straightenPath(final Path p) {
        if (noValidImageDataError()) return;
        if (p.size() < 2) {
            guiUtils.error("Path must have at least two nodes.");
            return;
        }
        final String defChoice = plugin.getPrefs().getTemp("straight-w", "40");
        final Double width = guiUtils.getDouble("Width of straightened image (in pixels):", "Width...",
                Integer.valueOf(defChoice));
        if (width == null)
            return;
        if (Double.isNaN(width)) {
            guiUtils.error("Invalid value. Width must be a valid non-negative integer.");
            return;
        }
        plugin.getPrefs().setTemp("straight-w", String.valueOf(width.intValue()));
        try {
            final PathStraightener straightener = new PathStraightener(p, plugin);
            straightener.setWidth(width.intValue());
            straightener.straighten().show();
        } catch (final Exception ex) {
            guiUtils.error("An exception occurred during straightening. This typically happens when "
                    + "decomposed polyline(s) of small paths become too short. See Console for details.");
            ex.printStackTrace();
        }
    }

    private void applyCustomTags(final Collection<Path> selectedPaths, String customTag) {
        customTag = customTag.replace("[", "(");
        customTag = customTag.replace("]", ")");
        for (final Path p : selectedPaths) {
            p.setName(p.getName() + " {" + customTag + "}");
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
            } else if (c1 instanceof JCheckBoxMenuItem && c1 != proofReadingToolBar.toggleMenuItem && ((JCheckBoxMenuItem) c1).isSelected())
                selected.add(((JCheckBoxMenuItem) c1).getActionCommand());
        }
        return selected;
    }

    private void deselectAllTagsMenu() {
        for (final Component c1 : tagsMenu.getMenuComponents()) {
            if ((c1 instanceof JMenu)) {
                for (final Component c2 : ((JMenu) c1).getMenuComponents()) {
                    if (c2 instanceof JCheckBoxMenuItem)
                        ((JCheckBoxMenuItem) c2).setSelected(false);
                }
            } else if (c1 instanceof JCheckBoxMenuItem && c1 != proofReadingToolBar.toggleMenuItem )
                ((JCheckBoxMenuItem) c1).setSelected(false);
        }
    }

    /** ActionListener for JCheckBoxMenuItem's "default tags" */
    private class TagMenuItem extends JCheckBoxMenuItem implements ActionListener {

        private static final long serialVersionUID = 1L;

        private TagMenuItem(final String tag) {
            super(tag, false);
            addActionListener(this);
            switch (tag) {
                case MultiPathActionListener.SLICE_TAG_CMD -> {
                    setToolTip("Z:");
                    setIcon(IconFactory.menuIcon('Z', true));
                }
                case MultiPathActionListener.CHANNEL_TAG_CMD -> {
                    setToolTip("Ch:");
                    setIcon(IconFactory.menuIcon('C', true));
                }
                case MultiPathActionListener.FRAME_TAG_CMD -> {
                    setToolTip("T:");
                    setIcon(IconFactory.menuIcon('T', true));
                }
                case MultiPathActionListener.LENGTH_TAG_CMD -> {
                    setIcon(IconFactory.menuIcon(IconFactory.GLYPH.RULER_VERTICAL));
                    setToolTip(SYM_LENGTH);
                }
                case MultiPathActionListener.TREE_TAG_CMD -> {
                    setIcon(IconFactory.menuIcon(IconFactory.GLYPH.ID_ALT));
                    setToolTip(SYM_TREE);
                }
                case MultiPathActionListener.N_CHILDREN_TAG_CMD -> {
                    setIcon(IconFactory.menuIcon(IconFactory.GLYPH.CHILDREN));
                    setToolTip(SYM_CHILDREN);
                }
                case MultiPathActionListener.MEAN_RADIUS_TAG_CMD -> {
                    setIcon(IconFactory.menuIcon(IconFactory.GLYPH.CIRCLE));
                    setToolTip(SYM_RADIUS);
                }
                case MultiPathActionListener.COUNT_TAG_CMD -> {
                    setIcon(IconFactory.menuIcon(IconFactory.GLYPH.MAP_PIN));
                    setToolTip(SYM_MARKER);
                }
                case MultiPathActionListener.ORDER_TAG_CMD -> {
                    setIcon(IconFactory.menuIcon(IconFactory.GLYPH.BRANCH_CODE));
                    setToolTip(SYM_ORDER);
                }
                default -> {
                }
                // do nothing
            }
            ScriptRecorder.setRecordingCall(this, "snt.getUI().getPathManager().applyDefaultTags(\"" + tag + "\")");
        }

        private void setToolTip(final String symbol) {
            setToolTipText("List symbol: '[" + symbol + "]'");
        }

        @Override
        public void actionPerformed(final ActionEvent e) {
            final List<Path> selectedPaths = getSelectedPaths(true);
            if (selectedPaths.isEmpty()) {
                guiUtils.error("There are no traced paths.");
                return;
            }
            removeOrReapplyDefaultTag(selectedPaths, getActionCommand(), isSelected(), true);
        }

    }

    private JMenuItem tagAngleMenuItem() {
        final JMenuItem jmi = new JMenuItem("Extension Angle...", IconFactory.menuIcon(IconFactory.GLYPH.ANGLE_RIGHT));
        jmi.setToolTipText("List symbol: '[" + SYM_ANGLE + "]'");
        ScriptRecorder.setRecordingCall(jmi, null);
        jmi.addActionListener(e -> {
            final List<Path> selectedPaths = getSelectedPaths(true);
            if (selectedPaths.isEmpty()) {
                guiUtils.error("There are no traced paths.");
                return;
            }
            final HashMap<String, Object> input = new HashMap<>();
            input.put("paths", selectedPaths);
            input.put("tagOnly", true);
            (plugin.getUI().new DynamicCmdRunner(FilterOrTagPathsByAngleCmd.class, input)).run();
        });
        return jmi;

    }

    /** ActionListener for commands that can operate on multiple paths */
    private class MultiPathActionListener implements ActionListener {

        private static final String APPEND_ALL_CHILDREN_CMD = "Append All Children To Selection";
        private static final String APPEND_DIRECT_CHILDREN_CMD = "Append Direct Children To Selection";
        private static final String ASSIGN_DISTINCT_COLORS = "Assign Distinct Colors";
        private static final String ASSIGN_CUSTOM_COLOR = "Choose Other...";
        private static final String COLORS_MENU = "Color";
        private static final String DELETE_CMD = "Delete...";
        private static final String AUTO_CONNECT_CMD = "Auto-connect...";
        private static final String COMBINE_CMD = "Combine...";
        private static final String CONCATENATE_CMD = "Concatenate...";
        private static final String REVERSE_CMD = "Reverse...";
        private static final String MERGE_PRIMARY_PATHS_CMD = "Create Shared Root (Soma)...";
        private static final String REBUILD_CMD = "Rebuild...";
        private static final String DOWNSAMPLE_CMD = "Ramer-Douglas-Peucker Downsampling...";
        private static final String CUSTOM_TAG_CMD = "Other...";
        private static final String REPLACE_TAG_CMD = "Replace...";
        private static final String LENGTH_TAG_CMD = "Length";
        private static final String MEAN_RADIUS_TAG_CMD = "Mean Radius";
        private static final String ORDER_TAG_CMD = "Path Order";
        private static final String TREE_TAG_CMD = "Arbor ID";
        private static final String N_CHILDREN_TAG_CMD = "No. of Children";
        private static final String CHANNEL_TAG_CMD = "Traced Channel";
        private static final String FRAME_TAG_CMD = "Traced Frame";
        private static final String SLICE_TAG_CMD = "Z-slice of First Node";
        private static final String COUNT_TAG_CMD = "No. of Spine/Varicosity Markers";
        private static final String SLICE_LABEL_TAG_CMD = "Slice Labels";

        private static final String REMOVE_TAGS_CMD = "Remove Tags...";
        private static final String FILL_OUT_CMD = "Fill Out...";
        private static final String RESET_FITS = "Discard Fit(s)...";
        private static final String SPECIFY_CT_POSITION_CMD = "Specify Channel/Frame...";
        private static final String SPECIFY_RADIUS_CMD = "Specify Constant Radius...";
        private static final String SPECIFY_COUNTS_CMD = "Specify No. Spine/Varicosity Markers...";
        private static final String DISCONNECT_CMD = "Disconnect...";
        private static final String INTERPOLATE_MISSING_RADII = "Correct Radii...";

        private static final String CONVERT_TO_ROI_CMD = "Convert to ROIs...";
        private static final String SEND_TO_LABKIT_CMD = "Load Labkit With Selected Path(s)...";
        private static final String SEND_TO_TWS_CMD = "Load TWS With Selected Path(s)...";
        private static final String CONVERT_TO_SKEL_CMD = "Skeletonize...";
        private static final String CONVERT_TO_SWC_CMD = "Export Selection as SWC...";
        private static final String PLOT_PROFILE_CMD = "Path Profiler...";

        // color mapping commands
        private static final String COLORIZE_TREES_CMD = "Cell-based Color Mapping...";
        private static final String COLORIZE_PATHS_CMD = "Path-based Color Mapping...";
        private static final String COLORIZE_REMOVE_CMD = "Remove Existing Color Mapping(s)...";

        // measure commands
        private static final String MEASURE_TREES_CMD = "Measure Cell(s)...";
        private static final String MEASURE_PATHS_CMD = "Measure Path(s)...";
        // distribution commands
        private static final String HISTOGRAM_PATHS_CMD = "Path-based Distributions...";
        private static final String HISTOGRAM_TREES_CMD = "Cell-based Distributions...";
        private static final String HISTOGRAM_2D_CMD = "Two-Dimensional Histograms...";

        // timelapse analysis
        private static final String MATCH_PATHS_ACROSS_TIME_CMD = "Match Paths Across Time...";
        private static final String TIME_PROFILE_CMD = "Time Profile...";
        private static final String TIME_COLOR_CODING_CMD = "Color Code Paths Across Time...";
        private static final String GROWTH_ANALYSIS_CMD = "Growth Analysis...";
        private static final String SPINE_PROFILE_CMD = "Density Profiles...";
        private static final String MULTI_METRIC_PLOT_CMD = "Multimetric Plot...";
        private static final String SPINE_EXTRACT_CMD = "Extract Counts from Multipoint ROIs...";
        private static final String SPINE_COLOR_CODING_CMD = "Color Code Paths Using Densities...";

        // Custom tag definition: anything flanked by curly braces
        private static final Pattern TAG_CUSTOM_PATTERN = Pattern.compile(" ?\\{.*}");
        private static final Pattern TAG_DEFAULT_PATTERN = Pattern.compile(" ?\\[.*]");

        // Command interface for handling path operations
        private interface PathCommand {
            void execute(List<Path> selectedPaths, String cmd);

            boolean canExecute(List<Path> selectedPaths);
        }

        // Command registry
        private final Map<String, PathCommand> commands = new HashMap<>();

        // Initialize commands in constructor
        private void initializeCommands() {
            // Selection commands
            commands.put(APPEND_ALL_CHILDREN_CMD, new AppendChildrenCommand(true));
            commands.put(APPEND_DIRECT_CHILDREN_CMD, new AppendChildrenCommand(false));

            // Color commands
            commands.put(ASSIGN_DISTINCT_COLORS, new AssignDistinctColorsCommand());
            commands.put(ASSIGN_CUSTOM_COLOR, new AssignCustomColorCommand());
            commands.put(COLORS_MENU, new ColorMenuCommand());
            commands.put(COLORIZE_TREES_CMD, new ColorizeTreesCommand());
            commands.put(COLORIZE_PATHS_CMD, new ColorizePathsCommand());
            commands.put(COLORIZE_REMOVE_CMD, new ColorizeRemoveCommand());
            commands.put(TIME_COLOR_CODING_CMD, new TimeColorCodingCommand());
            commands.put(SPINE_COLOR_CODING_CMD, new SpineColorCodingCommand());

            // Analysis commands
            commands.put(PLOT_PROFILE_CMD, new PlotProfileCommand());
            commands.put(MEASURE_TREES_CMD, new MeasureTreesCommand());
            commands.put(MEASURE_PATHS_CMD, new MeasurePathsCommand());
            commands.put(HISTOGRAM_PATHS_CMD, new HistogramPathsCommand());
            commands.put(HISTOGRAM_TREES_CMD, new HistogramTreesCommand());
            commands.put(HISTOGRAM_2D_CMD, new Histogram2DCommand());

            // Time analysis commands
            commands.put(TIME_PROFILE_CMD, new TimeProfileCommand());
            commands.put(SPINE_PROFILE_CMD, new SpineProfileCommand());
            commands.put(MULTI_METRIC_PLOT_CMD, new MultiMetricPlotCommand());
            commands.put(SPINE_EXTRACT_CMD, new SpineExtractCommand());
            commands.put(MATCH_PATHS_ACROSS_TIME_CMD, new MatchPathsAcrossTimeCommand());
            commands.put(GROWTH_ANALYSIS_CMD, new GrowthAnalysisCommand());

            // Modification commands
            commands.put(REVERSE_CMD, new ReverseCommand());
            commands.put(CONCATENATE_CMD, new ConcatenateCommand());
            commands.put(COMBINE_CMD, new CombineCommand());
            commands.put(AUTO_CONNECT_CMD, new AutoConnectCommand());
            commands.put(MERGE_PRIMARY_PATHS_CMD, new MergePrimaryPathsCommand());
            commands.put(DISCONNECT_CMD, new DisconnectCommand());
            commands.put(REBUILD_CMD, new RebuildCommand());
            commands.put(DOWNSAMPLE_CMD, new DownsampleCommand());

            // Tag commands
            commands.put(CUSTOM_TAG_CMD, new CustomTagCommand());
            commands.put(REPLACE_TAG_CMD, new ReplaceTagCommand());
            commands.put(REMOVE_TAGS_CMD, new RemoveTagsCommand());
            commands.put(SLICE_LABEL_TAG_CMD, new SliceLabelTagCommand());

            // Export/conversion commands
            commands.put(CONVERT_TO_ROI_CMD, new ConvertToRoiCommand());
            commands.put(CONVERT_TO_SWC_CMD, new ConvertToSwcCommand());
            commands.put(CONVERT_TO_SKEL_CMD, new ConvertToSkelCommand());
            commands.put(SEND_TO_LABKIT_CMD, new SendToLabkitCommand());
            commands.put(SEND_TO_TWS_CMD, new SendToTwsCommand());

            // Path property commands
            commands.put(FILL_OUT_CMD, new FillOutCommand());
            commands.put(SPECIFY_CT_POSITION_CMD, new SpecifyCTPosCommand());
            commands.put(SPECIFY_RADIUS_CMD, new SpecifyRadiusCommand());
            commands.put(SPECIFY_COUNTS_CMD, new SpecifyCountsCommand());
            commands.put(INTERPOLATE_MISSING_RADII, new InterpolateMissingRadiiCommand());
            commands.put(RESET_FITS, new ResetFitsCommand());

            // Utility commands
            commands.put(DELETE_CMD, new DeleteCommand());

            // Fit command (special case with dynamic text)
            commands.put("fit", new FitCommand()); // handles both fit and unfit
        }

        private void selectChildren(final List<Path> paths, final boolean recursive) {
            final ListIterator<Path> iter = paths.listIterator();
            while(iter.hasNext()){
                final Path p = iter.next();
                appendChildren(iter, p, recursive);
            }
            refreshManager(true, false, paths); // refreshViewers=false to avoid clearing selection
            tree.setSelectedPaths(paths);
        }

        private void appendChildren(final ListIterator<Path> iter, final Path p, final boolean recursive) {
            if (p.children != null && !p.children.isEmpty()) {
                p.children.forEach(child -> {
                    iter.add(child);
                    if (recursive) appendChildren(iter, child, true);
                });
            }
        }

        // Constructor
        public MultiPathActionListener() {
            initializeCommands();
        }

        @Override
        public void actionPerformed(final ActionEvent e) {
            final String cmd = e.getActionCommand();
            final List<Path> selectedPaths = getSelectedPaths(true);

            if (selectedPaths.isEmpty()) {
                guiUtils.error("There are no traced paths.");
                return;
            }

            // Handle fit command specially due to dynamic text
            if (e.getSource().equals(fitVolumeMenuItem) || cmd.toLowerCase().contains("fit paths")) {
                commands.get("fit").execute(selectedPaths, cmd);
                return;
            }

            // Execute command
            PathCommand command = commands.get(cmd);
            if (command != null) {
                if (command.canExecute(selectedPaths)) {
                    command.execute(selectedPaths, cmd);
                }
            } else {
                SNTUtils.error("Unexpectedly got an event from an unknown source: " + e);
            }
        }

        // Command implementations
        private class AppendChildrenCommand implements PathCommand {
            private final boolean includeAll;

            public AppendChildrenCommand(boolean includeAll) {
                this.includeAll = includeAll;
            }

            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                if (tree.getSelectionCount() == 0) {
                    guiUtils.error("No Path(s) are currently selected.");
                } else {
                    selectChildren(selectedPaths, includeAll);
                }
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class AssignDistinctColorsCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                removeColorNodesPrompt(selectedPaths);
                assignDistinctColors(selectedPaths);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class AssignCustomColorCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final Color color = guiUtils.getColor("Choose New Color Tag for Selected Path(s):",
                        null, (String[]) null);
                if (color != null) applyColorToSelectedPaths(new SNTColor(color), selectedPaths);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class ColorMenuCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                applyColorToSelectedPaths(colorMenu.getSelectedSWCColor(), selectedPaths);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class PlotProfileCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                if (noValidImageDataError()) return;
                final HashMap<String, Object> input = new HashMap<>();
                input.put("tree", new Tree(selectedPaths));
                input.put("dataset", plugin.getDataset());
                (plugin.getUI().new DynamicCmdRunner(PathProfiler.class, input)).run();
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class MeasureTreesCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                selectionDoesNotReflectCompleteTreesWarning(selectedPaths);
                measureCells();
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class MeasurePathsCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final HashMap<String, Object> inputs = new HashMap<>();
                inputs.put("paths", selectedPaths);
                inputs.put("table", getTable());
                (plugin.getUI().new DynamicCmdRunner(PathAnalyzerCmd.class, inputs)).run();
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class ReverseCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                // Filter to primary paths only
                final List<Path> primaryPaths = selectedPaths.stream()
                        .filter(p -> p.getOrder() < 2)
                        .toList();

                if (primaryPaths.isEmpty()) {
                    guiUtils.error("Only primary paths can be reversed.");
                    return;
                }

                // Count children across all selected primary paths
                final int totalChildren = primaryPaths.stream().mapToInt(this::countAllDescendants).sum();

                // Warn if some selected paths are not primary
                if (primaryPaths.size() < selectedPaths.size()) {
                    if (!guiUtils.getConfirmation(
                            (selectedPaths.size() - primaryPaths.size()) + " of the selected paths are not primary " +
                                    "and will be skipped. Continue with " + primaryPaths.size() + " primary path(s)?",
                            "Non-Primary Paths Excluded")) {
                        return;
                    }
                }

                // Build options
                final String optionSimple = "Reverse orientation only";
                final String optionWithChildren = "Reverse and update " + totalChildren + " child branch point(s)";

                final String[] options;
                final String message;

                if (totalChildren > 0) {
                    options = new String[]{optionSimple, optionWithChildren};
                    message = String.format(
                            "<html>Reverse <b>%d</b> primary path(s) affecting <b>%d</b> child path(s)?<br><br>" +
                                    "Choose how to handle children:<ul>" +
                                    "<li><b>%s</b>: Flips node order. Children remain attached but<br>" +
                                    "    their branch point indices become invalid (use with caution).</li>" +
                                    "<li><b>%s</b>: Flips node order and recalculates all<br>" +
                                    "    child branch point indices to maintain correct connections.</li>" +
                                    "</ul></html>",
                            primaryPaths.size(), totalChildren, optionSimple, "Reverse and update...");
                } else {
                    options = new String[]{optionSimple};
                    message = String.format(
                            """
                                    Reverse %d primary path(s)?
                                    
                                    This will flip the orientation so that the starting node
                                    becomes the end node and vice versa.""",
                            primaryPaths.size());
                }

                final String choice = guiUtils.getChoice(message, "Reverse Path(s)", options, options[0]);
                if (choice == null) return;

                // Execute reversal
                final boolean updateChildren = choice.equals(optionWithChildren);
                int reversedCount = 0;
                int updatedChildrenCount = 0;

                for (final Path p : primaryPaths) {
                    if (updateChildren) {
                        updatedChildrenCount += reverseWithChildrenUpdate(p);
                    } else {
                        p.reverse();
                    }
                    reversedCount++;
                }

                // Feedback
                String feedback = "Reversed " + reversedCount + " path(s)";
                if (updateChildren && updatedChildrenCount > 0) {
                    feedback += ", updated " + updatedChildrenCount + " child branch point(s)";
                }
                displayTmpMsg(feedback);

                plugin.updateAllViewers();
                plugin.setUnsavedChanges(true);
            }

            /**
             * Counts all descendants (children, grandchildren, etc.) of a path.
             */
            private int countAllDescendants(final Path path) {
                int count = 0;
                for (final Path child : path.getChildren()) {
                    count++; // Count this child
                    count += countAllDescendants(child); // Count grandchildren recursively
                }
                return count;
            }

            /**
             * Reverses a path and updates all children's branch point indices.
             * <p>
             * When a path is reversed, node indices change:
             * - Old index i becomes new index (size - 1 - i)
             * <p>
             * Children store their branch point as an index into the parent,
             * so we need to recalculate these indices after reversal.
             *
             * @param path the path to reverse
             * @return the number of children whose branch points were updated
             */
            private int reverseWithChildrenUpdate(final Path path) {
                final List<Path> children = path.getChildren();
                final int oldSize = path.size();

                // Store old branch point indices before reversal
                final Map<Path, Integer> oldIndices = new HashMap<>();
                for (final Path child : children) {
                    oldIndices.put(child, child.getBranchPointIndex());
                }

                // Reverse the path
                path.reverse();

                // Update children's branch point indices
                int updatedCount = 0;
                for (final Path child : children) {
                    final Integer oldIndex = oldIndices.get(child);
                    if (oldIndex != null && oldIndex >= 0) {
                        // Calculate new index: old index i becomes (size - 1 - i)
                        final int newIndex = oldSize - 1 - oldIndex;
                        updateBranchPointIndex(child, newIndex);
                        updatedCount++;
                    }
                }

                return updatedCount;
            }

            /**
             * Updates a child's branch point index directly.
             * This is a workaround since Path doesn't expose a setter for branchPointIndex.
             * <p>
             * NOTE: This requires adding a package-private or protected method to Path,
             * OR using the existing setBranchFrom with the new node coordinates.
             */
            private void updateBranchPointIndex(final Path child, final int newIndex) {
                // Get the parent path and the node at the new index
                final Path parent = child.getParentPath();
                if (parent == null || newIndex < 0 || newIndex >= parent.size()) {
                    return;
                }

                // Get the actual PointInImage at the new index
                final PointInImage newBranchPoint = parent.getNode(newIndex);

                // Detach and reattach with the correct branch point
                // This is safe because we're just updating the index, not changing the parent
                child.detachFromParent();
                child.setBranchFrom(parent, newBranchPoint);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class ConcatenateCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final int n = selectedPaths.size();
                if (n < 2) {
                    guiUtils.error("You must have at least two paths selected.");
                    return;
                }

                // Order paths by endpoint proximity
                final List<Path> orderedPaths = TreeUtils.orderByEndpointProximity(selectedPaths);
                if (orderedPaths.isEmpty()) {
                    guiUtils.error("Selected paths do not form a continuous chain.\n" +
                            "Ensure path endpoints are spatially close to each other.");
                    return;
                }

                // Check connectivity with a reasonable tolerance
                final double tolerance = estimateTolerance(orderedPaths);
                if (!TreeUtils.canFormContinuousChain(orderedPaths, tolerance)) {
                    guiUtils.error("Selected paths have large gaps between endpoints.\n" +
                            "Use 'Combine' instead for non-continuous paths.");
                    return;
                }

                if (!guiUtils.getConfirmation("Concatenate " + n +
                                " paths in detected spatial order? (this destructive operation cannot be undone!)",
                        "Confirm Destructive Operation?")) {
                    return;
                }

                // Orient paths for end-to-start merging
                TreeUtils.orientPathsForMerging(orderedPaths);

                // Preserve IDs from first path
                final Path firstPath = orderedPaths.getFirst();
                final int origId = firstPath.getID();
                final int origTreeId = firstPath.getTreeID();

                // Merge paths
                final Path mergedPath = TreeUtils.mergePaths(orderedPaths, true);
                if (mergedPath == null) {
                    guiUtils.error("Failed to merge paths.");
                    return;
                }

                // Delete original paths
                for (final Path p : orderedPaths) {
                    p.disconnectFromAll();
                    getPathAndFillManager().deletePath(p);
                }

                // Add merged path and restore IDs
                getPathAndFillManager().addPath(mergedPath, false, false);
                mergedPath.setIDs(origId, origTreeId);

                // Clean up invalid tags
                List.of(CHANNEL_TAG_CMD, COUNT_TAG_CMD, LENGTH_TAG_CMD, ORDER_TAG_CMD, MEAN_RADIUS_TAG_CMD,
                        N_CHILDREN_TAG_CMD, TREE_TAG_CMD, "Extension Angle...").forEach(tag -> {
                    removeOrReapplyDefaultTag(List.of(mergedPath), tag, false, false);
                });
                refreshManager(true, true, List.of(mergedPath));
                plugin.setUnsavedChanges(true);
            }

            /**
             * Estimates a reasonable tolerance for endpoint connectivity based on
             * average node spacing in the paths.
             */
            private double estimateTolerance(final List<Path> paths) {
                double totalLength = 0;
                int totalNodes = 0;
                for (final Path p : paths) {
                    totalLength += p.getLength();
                    totalNodes += p.size();
                }
                if (totalNodes <= paths.size()) {
                    return 10.0; // fallback for very short paths
                }
                final double avgSpacing = totalLength / (totalNodes - paths.size());
                return avgSpacing * 3; // Allow 3x average spacing as tolerance
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return true;
            }
        }

        private class TimeProfileCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final HashMap<String, Object> inputs = new HashMap<>();
                inputs.put("paths", selectedPaths);
                (plugin.getUI().new DynamicCmdRunner(PathTimeAnalysisCmd.class, inputs)).run();
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class GrowthAnalysisCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final HashMap<String, Object> inputs = new HashMap<>();
                inputs.put("paths", selectedPaths);
                (plugin.getUI().new DynamicCmdRunner(GrowthAnalyzerCmd.class, inputs)).run();
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class SpineProfileCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final HashMap<String, Object> inputs = new HashMap<>();
                inputs.put("paths", selectedPaths);
                inputs.put("anyMetric", MULTI_METRIC_PLOT_CMD.equals(cmd));
                (plugin.getUI().new DynamicCmdRunner(PathSpineAnalysisCmd.class, inputs)).run();
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class MultiMetricPlotCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final HashMap<String, Object> inputs = new HashMap<>();
                inputs.put("paths", selectedPaths);
                inputs.put("anyMetric", true);
                (plugin.getUI().new DynamicCmdRunner(PathSpineAnalysisCmd.class, inputs)).run();
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class SpineExtractCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final HashMap<String, Object> inputs = new HashMap<>();
                inputs.put("paths", selectedPaths);
                (plugin.getUI().new DynamicCmdRunner(SpineExtractorCmd.class, inputs)).run();
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class MatchPathsAcrossTimeCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final HashMap<String, Object> inputs = new HashMap<>();
                inputs.put("paths", selectedPaths);
                (plugin.getUI().new DynamicCmdRunner(PathMatcherCmd.class, inputs)).run();
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class TimeColorCodingCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final Tree tree = new Tree(selectedPaths);
                if (tree.isEmpty() || delineationsExistError(List.of(tree))) return;
                final Map<String, Object> input = new HashMap<>();
                input.put("trees", List.of(tree));
                input.put("onlyConnectivitySafeMetrics", true);
                input.put("measurementChoice", TreeColorMapper.PATH_FRAME);
                input.put("runFigCreator", false);
                final CommandService cmdService = plugin.getContext().getService(CommandService.class);
                cmdService.run(TreeMapperCmd.class, true, input);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class SpineColorCodingCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final Tree tree = new Tree(selectedPaths);
                if (tree.isEmpty() || delineationsExistError(List.of(tree))) return;
                final Map<String, Object> input = new HashMap<>();
                input.put("trees", List.of(tree));
                input.put("onlyConnectivitySafeMetrics", true);
                input.put("measurementChoice", TreeColorMapper.PATH_AVG_SPINE_DENSITY);
                input.put("runFigCreator", false);
                final CommandService cmdService = plugin.getContext().getService(CommandService.class);
                cmdService.run(TreeMapperCmd.class, true, input);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class ConvertToRoiCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final Map<String, Object> input = new HashMap<>();
                input.put("tree", new Tree(selectedPaths));
                if (plugin.accessToValidImageData()) {
                    input.put("imp", plugin.getImagePlus());
                    if (plugin.is2D())
                        input.put("viewChoice", "XY (default)");
                } else {
                    input.put("imp", null);
                }
                plugin.getContext().getService(CommandService.class).run(ROIExporterCmd.class, true, input);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class SendToLabkitCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final Map<String, Object> input = new HashMap<>();
                input.put("paths", selectedPaths);
                plugin.getContext().getService(CommandService.class).run(LabkitLoaderCmd.class, true, input);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class SendToTwsCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final Map<String, Object> input = new HashMap<>();
                input.put("paths", selectedPaths);
                input.put("imp", plugin.getImagePlus());
                plugin.getContext().getService(CommandService.class).run(TWSLoaderCmd.class, true, input);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class InterpolateMissingRadiiCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final Map<String, Object> input = new HashMap<>();
                input.put("paths", selectedPaths);
                plugin.getContext().getService(CommandService.class).run(InterpolateRadiiCmd.class, true, input);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class ColorizeTreesCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                selectionDoesNotReflectCompleteTreesWarning(selectedPaths);
                final Collection<Tree> trees = getMultipleTreesInternal();
                if (trees == null || trees.isEmpty() || delineationsExistError(trees))
                    return;
                final Map<String, Object> input = new HashMap<>();
                input.put("trees", trees);
                plugin.getContext().getService(CommandService.class).run(TreeMapperCmd.class, true, input);
                refreshManager(false, true, selectedPaths);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class ColorizePathsCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final Tree tree = new Tree(selectedPaths);
                if (!delineationsExistError(List.of(tree))) runColorMapper(tree, true);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class ColorizeRemoveCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                if (guiUtils.getConfirmation(
                        "Remove color mappings from selected paths (color tags may also be reset)?",
                        "Confirm?")) {
                    ColorMapper.unMap(selectedPaths);
                    plugin.updateAllViewers();
                }
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class Histogram2DCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final Map<String, Object> input = new HashMap<>();
                input.put("tree", new Tree(selectedPaths));
                input.put("onlyConnectivitySafeMetrics", true);
                if (plugin.accessToValidImageData()) input.put("dataset", plugin.getDataset());
                final CommandService cmdService = plugin.getContext().getService(CommandService.class);
                cmdService.run(TwoDHistCmd.class, true, input);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class HistogramTreesCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                selectionDoesNotReflectCompleteTreesWarning(selectedPaths);
                final Collection<Tree> trees = getMultipleTreesInternal();
                if (trees == null) return;
                runDistributionAnalysisCmd(trees, null, null);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class HistogramPathsCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                runHistogramPathsCmd(selectedPaths, null, null, null, null);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class ReplaceTagCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                replaceTags(selectedPaths);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class CustomTagCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final Set<String> existingTags = extractTagsFromPaths(selectedPaths);
                final Set<String> tags = guiUtils.getStringSet(
                        "Enter one or more tags (comma separated list):<br>" +
                                "(Clearing the field will remove existing tags)", "Custom Tags",
                        existingTags);
                if (tags == null) return; // user pressed cancel
                if (tags.isEmpty() && existingTags.isEmpty()) {
                    return;
                } else if (tags.isEmpty()) {
                    deleteCustomTags(selectedPaths);
                    return;
                }

                // remove any existing tags to avoid duplicates. New tags will contain these
                selectedPaths.forEach(p -> p.setName(TAG_CUSTOM_PATTERN.matcher(p.getName()).replaceAll("")));
                applyCustomTags(selectedPaths, GuiUtils.toString(tags));
                refreshManager(false, false, selectedPaths);
                plugin.setUnsavedChanges(true);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class SliceLabelTagCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                removeOrReapplyDefaultTag(selectedPaths, SLICE_LABEL_TAG_CMD, true, true);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class ConvertToSkelCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final Map<String, Object> input = new HashMap<>();
                final Tree tree = new Tree(selectedPaths);
                tree.setLabel("Paths");
                input.put("tree", tree);
                final CommandService cmdService = plugin.getContext().getService(CommandService.class);
                cmdService.run(SkeletonizerCmd.class, true, input);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class ConvertToSwcCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                exportSelectedPaths(selectedPaths);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class FillOutCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                if (noValidImageDataError()) return;
                plugin.initPathsToFill(new HashSet<>(selectedPaths), 
                        plugin.getPrefs().getBoolean(SNTPrefs.SPLIT_FILLS_KEY, true));
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class SpecifyCTPosCommand implements PathCommand {
            @Override
            public void execute(final List<Path> selectedPaths, String cmd) {

                final Number[] pos = guiUtils.getTwoNumbers("New CT position:  ", "Modify Assigned CT Position",
                        new Number[]{selectedPaths.getFirst().getChannel(), selectedPaths.getFirst().getFrame()},
                        new String[]{"Channel", "Frame"}, 0);
                if (pos == null) {
                    return; // user pressed cancel
                }
                if (Double.isNaN(pos[0].doubleValue()) || pos[0].intValue() < 0 ||
                        Double.isNaN(pos[1].doubleValue()) || pos[1].intValue() < 0) {
                    guiUtils.error("Invalid channel/frame position.");
                    return;
                }
                selectedPaths.forEach(p -> p.setCTposition(pos[0].intValue(), pos[1].intValue()));
                if (navToolbar != null) {
                    navToolbar.restoreFullModelState();
                }
                applyActiveTags(selectedPaths);
                guiUtils.tempMsg("Command finished.");
                plugin.updateAllViewers();
                plugin.setUnsavedChanges(true);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class SpecifyRadiusCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                if (allUsingFittedVersion(selectedPaths)) {
                    guiUtils.error("This command only applies to unfitted paths.");
                    return;
                }
                final double rad = 2 * plugin.getMinimumSeparation();
                final Double userRad = guiUtils.getDouble(
                        "Please specify a constant radius to be applied to all the nodes " +
                                "of selected path(s). This setting only applies to unfitted " +
                                "paths and <b>overrides</b> any existing values.<br>NB: You can use " +
                                "the <i>Transform Paths</i> script to scale existing radii.",
                        "Assign Constant Radius", rad);
                if (userRad == null) {
                    return; // user pressed cancel
                }
                if (Double.isNaN(userRad) || userRad < 0) {
                    guiUtils.error("Invalid value.");
                    return;
                }
                final boolean noRadius = userRad == 0d;
                if (noRadius && !guiUtils.getConfirmation(
                        "Discard thickness information from selected paths?",
                        "Confirm?")) {
                    return;
                }
                selectedPaths.forEach(p -> {
                    if (!p.isFittedVersionOfAnotherPath()) p.setRadius(userRad);
                });
                removeOrReapplyDefaultTag(selectedPaths, MultiPathActionListener.MEAN_RADIUS_TAG_CMD, !noRadius, false);
                guiUtils.tempMsg("Command finished. Fitted path(s) ignored.");
                plugin.updateAllViewers();
                plugin.setUnsavedChanges(true);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class SpecifyCountsCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final Double userCounts = guiUtils.getDouble(
                        "Please specify the no. of markers (e.g., spines or varicosities) to be associated to selected path(s).",
                        "Spine/Varicosity Counts", 0);
                if (userCounts == null) {
                    return; // user pressed cancel
                }
                if (Double.isNaN(userCounts) || userCounts < 0) {
                    guiUtils.error("Invalid value.");
                    return;
                }
                selectedPaths.forEach(p -> p.setSpineOrVaricosityCount(userCounts.intValue()));
                plugin.setUnsavedChanges(true);
                removeOrReapplyDefaultTag(selectedPaths, MultiPathActionListener.COUNT_TAG_CMD, true, false);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class DeleteCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                boolean assumeAll = tree.getSelectionCount() == 0 || tree.getSelectionCount() == tree.getNumberOfNodes();
                String message = assumeAll ? "Are you really sure you want to delete all paths?"
                        : "Delete the selected " + selectedPaths.size() + " path(s)?";
                if (guiUtils.getConfirmation(message, "Confirm Deletion?")) {
                    deletePaths(selectedPaths);
                }
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class RemoveTagsCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                removeTagsThroughPrompt(selectedPaths);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class AutoConnectCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                if (selectedPaths.size() != 2) {
                    guiUtils.error("This command requires exactly 2 paths to be selected.");
                    return;
                }
                autoConnect(selectedPaths.get(0), selectedPaths.get(1));
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return true; // Let execute() handle validation and show appropriate error
            }
        }

        private void autoConnect(final Path p1, final Path p2) {
            // Check if already connected
            final boolean alreadyConnected = p1.isConnectedTo(p2) || p2.isConnectedTo(p1) ||
                    (p1.getChildren() != null && p1.getChildren().contains(p2)) ||
                    (p2.getChildren() != null && p2.getChildren().contains(p1));
            if (alreadyConnected) {
                guiUtils.error("There is already a parent-child relationship between the two paths.");
                return;
            }

            // Analyze paths to suggest parent/child and compute distances
            final ConnectionAnalysis analysis = analyzeConnection(p1, p2);

            // Build choice labels with info
            final String choice1 = formatPathChoice(p1, analysis.suggestedParent == p1);
            final String choice2 = formatPathChoice(p2, analysis.suggestedParent == p2);
            final String[] choices = new String[]{choice1, choice2};
            final String defaultChoice = (analysis.suggestedParent == p1) ? choice1 : choice2;

            // Build info text showing connection distances
            final String infoText = String.format(
                    "Connection distance: %.2f %s (child's %s → parent)",
                    analysis.minDistance, plugin.spacing_units,
                    analysis.childStartIsCloser ? "start" : "end");

            // Build checkbox label if reversal would help
            final String checkboxLabel;
            final boolean showCheckbox = !analysis.childStartIsCloser;
            if (showCheckbox) {
                checkboxLabel = String.format(
                        "Reverse child orientation (reduces distance from %.2f to %.2f %s)",
                        analysis.distanceFromStart, analysis.distanceFromEnd, plugin.spacing_units);
            } else {
                checkboxLabel = null;
            }

            // Show dialog
            final Object[] result = guiUtils.getChoiceWithOptionAndInfo(
                    "Auto-Connect Paths",
                    "Which path should be the parent?",
                    choices, defaultChoice,
                    infoText,
                    checkboxLabel, true); // Default checkbox to true if shown

            if (result == null) return; // User cancelled

            // Parse result
            final String selectedChoice = (String) result[0];
            final boolean reverseChild = (Boolean) result[1];

            // Determine parent and child based on selection
            final Path parent = selectedChoice.equals(choice1) ? p1 : p2;
            final Path child = (parent == p1) ? p2 : p1;

            // Perform the connection
            performConnection(parent, child, reverseChild);
        }

        /**
         * Analyzes two paths to suggest parent/child relationship and compute distances.
         */
        private ConnectionAnalysis analyzeConnection(final Path p1, final Path p2) {
            // Compute distances from both ends of each path to the other
            final double p1StartToP2 = minDistanceToPath(p1.firstNode(), p2);
            final double p1EndToP2 = minDistanceToPath(p1.lastNode(), p2);
            final double p2StartToP1 = minDistanceToPath(p2.firstNode(), p1);
            final double p2EndToP1 = minDistanceToPath(p2.lastNode(), p1);

            // Find the minimum distance configuration
            double minDist = p1StartToP2;
            Path suggestedParent = p2;
            Path suggestedChild = p1;
            boolean childStartIsCloser = true;

            if (p1EndToP2 < minDist) {
                minDist = p1EndToP2;
            }
            if (p2StartToP1 < minDist) {
                minDist = p2StartToP1;
                suggestedParent = p1;
                suggestedChild = p2;
            }
            if (p2EndToP1 < minDist) {
                suggestedParent = p1;
                suggestedChild = p2;
            }

            // Also consider path order: primary paths should generally be parents
            // Override suggestion if one is primary and other isn't
            if (p1.isPrimary() && !p2.isPrimary()) {
                suggestedParent = p1;
                suggestedChild = p2;
            } else if (p2.isPrimary() && !p1.isPrimary()) {
                suggestedParent = p2;
                suggestedChild = p1;
            }
            // If both same order, prefer longer path as parent
            else if (p1.getOrder() == p2.getOrder() && p1.size() > p2.size() * 1.5) {
                suggestedParent = p1;
                suggestedChild = p2;
            } else if (p1.getOrder() == p2.getOrder() && p2.size() > p1.size() * 1.5) {
                suggestedParent = p2;
                suggestedChild = p1;
            }

            // Recompute which end of the child is closer for the suggested configuration
            final double distFromStart = minDistanceToPath(suggestedChild.firstNode(), suggestedParent);
            final double distFromEnd = minDistanceToPath(suggestedChild.lastNode(), suggestedParent);
            childStartIsCloser = distFromStart <= distFromEnd;

            return new ConnectionAnalysis(
                    suggestedParent, suggestedChild,
                    Math.min(distFromStart, distFromEnd),
                    distFromStart, distFromEnd,
                    childStartIsCloser);
        }

        /**
         * Computes the minimum distance from a point to any node on a path.
         */
        private double minDistanceToPath(final PointInImage point, final Path path) {
            double minDist = Double.MAX_VALUE;
            for (int i = 0; i < path.size(); i++) {
                final double dist = point.distanceTo(path.getNode(i));
                if (dist < minDist) {
                    minDist = dist;
                }
            }
            return minDist;
        }

        /**
         * Formats a path choice label with relevant info.
         */
        private String formatPathChoice(final Path path, final boolean isRecommended) {
            final String orderInfo = path.isPrimary() ? "primary" : "order " + path.getOrder();
            final String recommendation = isRecommended ? " (recommended)" : "";
            return String.format("\"%s\": %s, %d nodes%s", removeTags(path), orderInfo, path.size(), recommendation);
        }

        /**
         * Performs the actual connection between parent and child paths.
         */
        private void performConnection(final Path parent, final Path child, final boolean reverseChild) {
            // Detach child from current parent if needed
            if (child.getParentPath() != null) {
                child.detachFromParent();
            }

            // Reverse child if requested
            if (reverseChild) {
                child.reverse();
            }

            // Find the closest point on parent to child's start
            final PointInImage childStart = child.firstNode();
            final PointInImage branchPoint = parent.nearestNodeTo(childStart, Double.MAX_VALUE);

            if (branchPoint == null) {
                guiUtils.error("Could not find a suitable branch point on the parent path.");
                return;
            }

            // Insert branch point node at child's start if needed and establish connection
            final double distance = childStart.distanceTo(branchPoint);
            if (distance > 0) {
                // Insert the branch point as the first node of the child
                child.insertNode(0, branchPoint);
            }

            // Establish the parent-child connection
            child.setBranchFrom(parent, branchPoint);

            // Update tree IDs
            child.setIDs(child.getID(), parent.getTreeID());
            child.getChildren().forEach(c -> c.setIDs(c.getID(), parent.getTreeID()));

            // Rebuild relationships and refresh
            rebuildRelationShips();

            // Select both paths after operation
            SwingUtilities.invokeLater(() -> tree.setSelectedPaths(List.of(parent, child)));

            plugin.setUnsavedChanges(true);
        }

        /**
         * Holds the result of analyzing a potential connection between two paths.
         */
        private record ConnectionAnalysis(
                Path suggestedParent,
                Path suggestedChild,
                double minDistance,
                double distanceFromStart,
                double distanceFromEnd,
                boolean childStartIsCloser) {
        }

        private class CombineCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final int n = selectedPaths.size();
                if (n < 2) {
                    guiUtils.error("You must have at least two paths selected.");
                    return;
                }

                if (!guiUtils.getConfirmation("Combine " + n +
                                " selected paths into one? (this destructive operation cannot be undone!)",
                        "Confirm Destructive Operation?")) {
                    return;
                }

                // Use the first selected path as the base
                final Path basePath = selectedPaths.getFirst();
                final List<Path> pathsToMerge = new ArrayList<>(selectedPaths.subList(1, n));

                // Collect all children that need reparenting
                final List<Path> allChildren = new ArrayList<>();
                for (final Path p : pathsToMerge) {
                    allChildren.addAll(new ArrayList<>(p.getChildren()));
                }

                // Merge all paths into base
                for (final Path p : pathsToMerge) {
                    // Detach children first
                    for (final Path child : new ArrayList<>(p.getChildren())) {
                        child.detachFromParent();
                    }

                    // Detach from parent if connected
                    if (p.getParentPath() != null) {
                        p.detachFromParent();
                    }

                    // Add nodes to base path
                    basePath.add(p);

                    // Delete the merged path
                    p.disconnectFromAll();
                    getPathAndFillManager().deletePath(p);
                }

                // Reparent children to the combined path
                for (final Path child : allChildren) {
                    final PointInImage origBranchPoint = child.getBranchPoint();
                    if (origBranchPoint != null) {
                        final PointInImage closest = basePath.nearestNodeTo(origBranchPoint, Double.MAX_VALUE);
                        if (closest != null) {
                            child.setBranchFrom(basePath, closest);
                        }
                    }
                }

                // Clean up invalid tags
                List.of(CHANNEL_TAG_CMD, COUNT_TAG_CMD, LENGTH_TAG_CMD, ORDER_TAG_CMD, MEAN_RADIUS_TAG_CMD,
                        N_CHILDREN_TAG_CMD, TREE_TAG_CMD, "Extension Angle...").forEach(tag -> {
                    removeOrReapplyDefaultTag(List.of(basePath), tag, false, false);
                });
                refreshManager(true, true, List.of(basePath));
                plugin.setUnsavedChanges(true);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return true;
            }
        }

        private class DisconnectCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                if (!guiUtils.getConfirmation("Disconnect " + selectedPaths.size() + " path(s) from all connections? "
                                + "Connectivity will be re-assessed for <i>all</i> paths and IDs will be reset.",
                        "Confirm Disconnect?"))
                    return;
                for (final Path p : selectedPaths)
                    p.disconnectFromAll();
                rebuildRelationShips(); // will call refreshManager()
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class MergePrimaryPathsCommand implements PathCommand {

            private Roi getRoi() {
                return (plugin.getImagePlus() == null) ? null : plugin.getImagePlus().getRoi();
            }

            private Boolean getUseRoiFromUser(final int nPrimaryPaths) {
                final Object[] result = guiUtils.getChoiceWithOptionAndInfo(
                        "Create Shared Root", // title
                        "Place shared root at:", // msg
                        new String[] {"Averaged origin of selected paths", "Centroid of soma ROI"}, // choices
                        "Averaged origin of selected paths", // default choice
                        "The " + nPrimaryPaths + " primary paths will become children of a new " +  // info message
                        "single- node root path at the chosen location. This operation cannot be undone.",
                        null, false); // no checkbox
                if (result == null) return null;
                return "Centroid of soma ROI".equals(result[0]);
            }

            private void noRoiError() {
                final String msg = "No active ROI exists. Make sure the soma ROI is active, or " +
                        "run Auto-trace > Detect Soma(s)... with ROI output.";
                final String ttl = "Invalid Merge Condition";
                if (plugin.accessToValidImageData()) {
                    if (guiUtils.getConfirmation(msg, ttl, "Run Soma Detection Now", "Dismiss")) {
                        plugin.getUI().runCommand(SomaDetectorCmd.class, null);
                    }
                } else {
                    guiUtils.error(msg, ttl);
                }
            }

            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                if (selectedPaths.size() < 2) {
                    guiUtils.error("You must have at least two primary paths selected.");
                    return;
                }
                final List<Path> primaryPaths = new ArrayList<>();
                final List<PointInImage> rootNodes = new ArrayList<>();
                for (final Path path : selectedPaths) {
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
                if (mixedCTPathSelectionError(primaryPaths)) {
                    return;
                }
                final Boolean useRoi = getUseRoiFromUser(primaryPaths.size());
                if (useRoi == null) {
                    return; // user pressed cancel
                }
                // Retrieve the average position of all root node coordinates
                final PointInImage centroid = SNTPoint.average(rootNodes);
                final Path newSoma;
                if (useRoi) {
                    final Roi roi = getRoi();
                    if (roi == null) {
                        noRoiError();
                        return;
                    }
                    // Create a single-node path from the ROI. Assign the common centroid.
                    // position to the coordinates of the ROI
                    newSoma = SomaUtils.roiToSomaPath(roi, primaryPaths.getFirst());
                    centroid.x = newSoma.getNode(0).x;
                    centroid.y = newSoma.getNode(0).y;
                    if (roi.getZPosition() > 0)
                        centroid.z = newSoma.getNode(0).z;
                } else {
                    // Create a single node path from centroid
                    newSoma = primaryPaths.getFirst().createPath();
                    newSoma.setIsPrimary(true);
                    newSoma.setName("Shared root");
                    newSoma.addNode(centroid);
                }
                pathAndFillManager.addPath(newSoma, false, true);
                // Now connect all root nodes to it
                primaryPaths.forEach(primaryPath -> {
                    primaryPath.insertNode(0, centroid);
                    primaryPath.setBranchFrom(newSoma, centroid);
                });
                rebuildRelationShips(); // will call refreshManager()
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return true;
            }
        }

        private class RebuildCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                final List<Path> allPaths = pathAndFillManager.getPaths();

                if (allPaths.isEmpty()) {
                    guiUtils.error("No paths exist to rebuild.");
                    return;
                }

                // Run analysis
                final RelationshipAnalysis analysis = analyzeRelationships(allPaths);

                // Build the dialog message based on analysis results
                final String message = buildAnalysisMessage(analysis);
                final String title = "Rebuild Path Relationships";

                if (analysis.hasIssues()) {
                    // Issues found - show detailed analysis and offer to rebuild
                    if (guiUtils.getConfirmation(message, title, "Rebuild", "Cancel")) {
                        rebuildRelationShips();
                        showRebuildSummary(analysis);
                    }
                } else {
                    // No issues - ask if they want to rebuild anyway
                    if (guiUtils.getConfirmation(message, title, "Rebuild Anyway", "Cancel")) {
                        rebuildRelationShips();
                        displayTmpMsg("Relationships rebuilt successfully.");
                    }
                }
            }

            private String buildAnalysisMessage(final RelationshipAnalysis analysis) {
                final StringBuilder sb = new StringBuilder("<HTML><body>");

                if (analysis.hasIssues()) {
                    sb.append("<b>Analysis found ").append(analysis.totalIssues()).append(" issue(s):</b><ul>");

                    if (analysis.orphanedPaths > 0) {
                        sb.append("<li>").append(analysis.orphanedPaths)
                                .append(" orphaned path(s) (no parent, not primary)</li>");
                    }
                    if (analysis.inconsistentTreeIds > 0) {
                        sb.append("<li>").append(analysis.inconsistentTreeIds)
                                .append(" path(s) with inconsistent tree IDs</li>");
                    }
                    if (analysis.inconsistentOrders > 0) {
                        sb.append("<li>").append(analysis.inconsistentOrders)
                                .append(" path(s) with incorrect order values</li>");
                    }
                    if (analysis.disconnectedChildren > 0) {
                        sb.append("<li>").append(analysis.disconnectedChildren)
                                .append(" child path(s) not in parent's children list</li>");
                    }
                    sb.append("</ul><br>");

                    sb.append("Rebuilding will:<ul>")
                            .append("<li>Reset tree IDs (").append(analysis.currentTreeCount)
                            .append(" tree(s) will be renumbered 1-").append(analysis.currentTreeCount).append(")</li>")
                            .append("<li>Recalculate all path orders</li>")
                            .append("<li>Reestablish parent-child connections</li>")
                            .append("</ul>");
                } else {
                    sb.append("<b>✓ No issues detected</b><br><br>");
                    sb.append("All ").append(analysis.totalPaths).append(" path(s) in ")
                            .append(analysis.currentTreeCount).append(" tree(s) appear to have valid connections.<br><br>");
                    sb.append("Rebuild anyway? This will reset all tree IDs and recalculate orders.");
                }

                sb.append("</body></HTML>");
                return sb.toString();
            }

            private void showRebuildSummary(final RelationshipAnalysis analysisBefore) {
                final String msg = String.format("Rebuilt %d path(s) in %d tree(s). Fixed %d issue(s).",
                        analysisBefore.totalPaths,
                        analysisBefore.currentTreeCount,
                        analysisBefore.totalIssues());
                displayTmpMsg(msg);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return true;
            }
        }

        /**
         * Analyzes path relationships to detect structural issues.
         *
         * @param paths the paths to analyze
         * @return analysis results
         */
        private RelationshipAnalysis analyzeRelationships(final List<Path> paths) {
            int orphanedPaths = 0;
            int inconsistentTreeIds = 0;
            int inconsistentOrders = 0;
            int disconnectedChildren = 0;
            final Set<Integer> treeIds = new HashSet<>();

            for (final Path p : paths) {
                if (p == null || p.isFittedVersionOfAnotherPath()) continue;

                // Track tree IDs
                treeIds.add(p.getTreeID());

                // Check for orphaned paths (not primary but no parent)
                if (!p.isPrimary() && p.getParentPath() == null) {
                    orphanedPaths++;
                }

                // Check for tree ID consistency (children should have same tree ID as parent)
                if (p.getParentPath() != null && p.getTreeID() != p.getParentPath().getTreeID()) {
                    inconsistentTreeIds++;
                }

                // Check for order consistency (child order should be parent order + 1)
                if (p.getParentPath() != null) {
                    final int expectedOrder = p.getParentPath().getOrder() + 1;
                    if (p.getOrder() != expectedOrder) {
                        inconsistentOrders++;
                    }
                } else if (p.isPrimary() && p.getOrder() != 1) {
                    // Primary paths should have order 1
                    inconsistentOrders++;
                }

                // Check if this path is properly registered in its parent's children list
                if (p.getParentPath() != null && !p.getParentPath().getChildren().contains(p)) {
                    disconnectedChildren++;
                }
            }

            // Count actual paths (excluding fitted versions)
            final int totalPaths = (int) paths.stream()
                    .filter(p -> p != null && !p.isFittedVersionOfAnotherPath())
                    .count();

            // Count primary paths (trees)
            final int treeCount = (int) paths.stream()
                    .filter(p -> p != null && !p.isFittedVersionOfAnotherPath() && p.isPrimary())
                    .count();

            return new RelationshipAnalysis(
                    totalPaths,
                    treeCount,
                    orphanedPaths,
                    inconsistentTreeIds,
                    inconsistentOrders,
                    disconnectedChildren
            );
        }

        /**
         * Holds the results of relationship analysis.
         */
        private record RelationshipAnalysis(
                int totalPaths,
                int currentTreeCount,
                int orphanedPaths,
                int inconsistentTreeIds,
                int inconsistentOrders,
                int disconnectedChildren) {

            boolean hasIssues() {
                return totalIssues() > 0;
            }

            int totalIssues() {
                return orphanedPaths + inconsistentTreeIds + inconsistentOrders + disconnectedChildren;
            }
        }

        private class DownsampleCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                dowsamplePaths(selectedPaths);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class ResetFitsCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
                if (!guiUtils.getConfirmation("Discard existing fits?", "Confirm Discard?")) return;
                for (final Path p : selectedPaths) {
                    p.discardFit();
                }
                refreshManager(true, false, selectedPaths);
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        private class FitCommand implements PathCommand {
            @Override
            public void execute(List<Path> selectedPaths, String cmd) {
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

                if (skippedFits == selectedPaths.size()) {
                    noValidImageDataError();
                }
                else if (!pathsToFit.isEmpty()) {
                    if (fittingHelper == null) fittingHelper = new FitHelper();
                    fittingHelper.pathsToFit = pathsToFit;
                    fittingHelper.fitUsingPrompAsNeeded(); // call refreshManager
                    if (skippedFits > 0) {
                        guiUtils.centeredMsg("Since no image data is available, " + skippedFits + "/"
                                + selectedPaths.size() + " fits could not be computed", "Valid Image Data Unavailable");
                    }
                    plugin.setUnsavedChanges(true);
                }
                else {
                    refreshManager(true, false, selectedPaths);
                }
            }

            @Override
            public boolean canExecute(List<Path> selectedPaths) {
                return !selectedPaths.isEmpty();
            }
        }

        @SuppressWarnings("deprecated")
        private void dowsamplePaths(final List<Path> selectedPaths) {
            final double minSep = plugin.getMinimumSeparation();
            final Double userMaxDeviation = guiUtils.getDouble(
                    "Please specify the maximum permitted distance between nodes:<ul>" +
                            "<li>This destructive operation cannot be undone!</li>" +
                            "<li>Paths can only be downsampled: Smaller inter-node distances will not be interpolated</li>" +
                            "<li>Paths with less than three nodes are ignored</li>" +
                            "<li>Currently, the smallest voxel dimension is " + SNTUtils
                            .formatDouble(minSep, 3) + plugin.spacing_units + "</li>",
                    "Downsampling: " + selectedPaths.size() + " Selected Path(s)", 2 * minSep);
            if (userMaxDeviation == null) return;
            final double maxDeviation = userMaxDeviation;
            if (Double.isNaN(maxDeviation) || maxDeviation <= 0) {
                guiUtils.error(
                        "The maximum permitted distance must be a positive number",
                        "Invalid Input");
                return;
            }
            if (!guiUtils.getConfirmation("Downsample " + selectedPaths.size() + " path(s)? " +
                    "This operation cannot be undone.", "Confirm Downsample?")) {
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
            if (plugin.univ != null) {
                selectedPaths.forEach(p -> {
                    p.removeFrom3DViewer(plugin.univ);
                    //noinspection deprecation
                    p.addTo3DViewer(plugin.univ, p.getColor(), null);
                });
            }
            plugin.updateAllViewers();
            plugin.setUnsavedChanges(true);
        }

        void removeColorNodesPrompt(final Collection<Path> selectedPaths) {
            final boolean hasNodes = selectedPaths.stream().anyMatch(Path::hasNodeColors);
            if (!hasNodes) return;
            final boolean nag = plugin.getPrefs().getTemp("nodecolors-nag", true);
            boolean reset = plugin.getPrefs().getTemp("nodecolors", true);
            if (nag) {
                final boolean[] options = guiUtils.getPersistentConfirmation("Selected path(s) have been color-coded, " +
                        "or have multiple node colors. Discard existing colors and apply single hue?", "Discard Node Colors?");
                plugin.getPrefs().setTemp("nodecolors", reset = options[0]);
                plugin.getPrefs().setTemp("nodecolors-nag", !options[1]);
            }
            if (reset)
                selectedPaths.forEach( p-> p.setNodeColors(null));
        }

        boolean delineationsExistError(final Collection<Tree> trees) {
            return DelineationsManager.hasDelineationLabels(trees) && !new GuiUtils().getConfirmation(
                    "Some paths have assigned delineations that may be lost. Proceed nevertheless?",
                    "Ignore Delineations?");
        }

        void runColorMapper(final Tree tree, final boolean safeMetricsOnly) {
            if (tree == null || tree.isEmpty()) return;
            final Map<String, Object> input = new HashMap<>();
            input.put("trees", List.of(tree));
            input.put("onlyConnectivitySafeMetrics", safeMetricsOnly);
            input.put("dataset", plugin.accessToValidImageData() ? plugin.getDataset() : null);
            final CommandService cmdService = plugin.getContext().getService(CommandService.class);
            cmdService.run(TreeMapperCmd.class, true, input); // will call #update() via SNT#updateAllViewers();
        }

        void applyColorToSelectedPaths(final SNTColor color, final Collection<Path> selectedPaths) {
            removeColorNodesPrompt(selectedPaths);
            selectedPaths.forEach(p -> 	p.setColor(color.color()));
            refreshManager(true, true, selectedPaths);
            plugin.setUnsavedChanges(true);
            if (plugin.getUI().getRecorder(false) != null)
                plugin.getUI().getRecorder(false)
                        .recordCmd(String.format("snt.getUI().getPathManager().applyTag(\"%s\")", color));
        }

        private void removeTagsThroughPrompt(final List<Path> selectedPaths) {
            final String[] choices = new String[3];
            choices[0] = "Color tags";
            choices[1] = "Custom tags (undoable operation)";
            choices[2] = "Metadata and morphometry tags";
            final String defChoice = plugin.getPrefs().getTemp("remove-tags", choices[2]);
            final String choice = guiUtils.getChoice(
                    "Remove which type of tags?<br>NB: SWC-type tags are always preserved. "
                            + "Programmatic colors, metadata, and morphometry tags can be re-applied after deletion.",
                    "Remove Tags", choices, defChoice);
            if (choice == null) {
                return;
            }
            plugin.getPrefs().setTemp("remove-tags", choice);
            if (choices[0].equals(choice)) {
                resetPathsColor(selectedPaths); // will call refreshManager
                return;
            }
            if (choices[1].equals(choice)) {
                deleteCustomTags(selectedPaths); // will call refreshManager
                return;
            }
            if (choices[2].equals(choice)) {
                selectedPaths.forEach(p -> p.setName(TAG_DEFAULT_PATTERN.matcher(p.getName()).replaceAll("")));
                if (selectedPaths.size() == pathAndFillManager.size()) deselectAllTagsMenu();
                refreshManager(false, false, selectedPaths);
            }
        }

        private void replaceTags(final List<Path> selectedPaths) {
            final String[] labels = { "Find (case-sensitive) ", "Replace with " };
            final String[] defaults = { GuiUtils.toString(extractTagsFromPaths(selectedPaths)), "" };
            final String[] findReplace = guiUtils.getStrings("Replace Tag(s)...", labels, defaults);
            if (findReplace == null || findReplace[0] == null || findReplace[0].isEmpty() || findReplace[1] == null)
                return; // nothing to replace
            findReplace[1] = findReplace[1].replace("[", "(").replace("]", ")")
                    .replace("{", "").replace("}", "");
            int counter = 0;
            for (final Path p : selectedPaths) {
                final String existingTags = extractTagsFromPath(p);
                if (existingTags.contains(findReplace[0])) {
                    final String replacedTags = existingTags.replace(findReplace[0], findReplace[1]);
                    p.setName(removeTags(p) + ((replacedTags.isEmpty()) ? "" : "{" + replacedTags + "}"));
                    counter++;
                }
            }
            refreshManager(false, false, selectedPaths);
            guiUtils.centeredMsg(counter + " occurrence(s) replaced.", "Operation Completed");
        }

        private void deleteCustomTags(final List<Path> paths) {
            if (!plugin.getUI().askUserConfirmation || guiUtils.getConfirmation("Remove custom tags from "
                    + ((paths.size() == pathAndFillManager.size()) ? "all " : "the selected ")
                    + paths.size() + " paths?", "Confirm Tag Removal?")) {
                paths.forEach(p -> p.setName(TAG_CUSTOM_PATTERN.matcher(p.getName()).replaceAll("")));
                plugin.setUnsavedChanges(true);
                refreshManager(false, false, paths);
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

        void selectionDoesNotReflectCompleteTreesWarning(final Collection<Path> selectedPaths) {
            if (selectedPaths.isEmpty() || !selectionExists()
                    || plugin.getPrefs().getTemp("misc-paths-skipnag", false)) {
                return;
            }
            final int refId = selectedPaths.iterator().next().getTreeID();
            if (getSelectedPaths(false).stream().anyMatch(path -> path.getTreeID() != refId)
                    || pathAndFillManager.getTree(refId).size() != selectedPaths.size()) {
                final Boolean skipNag = guiUtils.getPersistentWarning("Current selection of " + selectedPaths.size() +
                        " path(s) is going to be ignored because this command operates on whole tree(s).", "Ignored Selection");
                if (skipNag != null) plugin.getPrefs().setTemp("misc-paths-skipnag", skipNag);
            }
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

    private void rebuildRelationShips() {
        final List<String> activeTags = guessTagsCurrentlyActive();
        if (navToolbar != null) navToolbar.restoreFullModelState();
        tree.clearSelection(); // existing selections could change after the rebuild
        pathAndFillManager.rebuildRelationships();
        activeTags.forEach( tag -> removeOrReapplyDefaultTag(pathAndFillManager.getPaths(), tag, true, false));
        refreshManager(true, true, null);
        plugin.setUnsavedChanges(true);
    }

    private boolean noValidImageDataError() {
        final boolean invalidImage = !plugin.accessToValidImageData();
        if (invalidImage)
            guiUtils.error("This option requires valid image data to be loaded.");
        return invalidImage;
    }

    private boolean mixedCTPathSelectionError(final List<Path> selectedPaths) {
        final int channel = selectedPaths.getFirst().getChannel();
        final int frame = selectedPaths.getFirst().getFrame();
        for (final Path path : selectedPaths) {
            if (path.getChannel() != channel || path.getFrame() != frame) {
                guiUtils.error(
                        "Selected paths span multiple channels or frames.\n" +
                                "All paths must belong to the same channel and frame.",
                        "Selection Spans Different CT Positions");
                return true;
            }
        }
        return false;
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

    static String removeTags(final Path p) {
        final String name = p.getName();
        final int delimiterIdx = name.indexOf("{");
        if (delimiterIdx == -1) {
            return name;
        } else {
            return name.substring(0, delimiterIdx).trim();
        }
    }

    public static Set<String> extractTagsFromPaths(final Collection<Path> paths) {
        final TreeSet<String> uniqueTags = new TreeSet<>();
        paths.forEach(p -> uniqueTags.addAll(Arrays.asList(TAG_SPLIT_PATTERN.split(extractTagsFromPath(p)))));
        uniqueTags.remove("");
        return uniqueTags;
    }

    /** Builds the top navigation toolbar */
    private class NavigationToolBar extends JToolBar {

        private final JComponent embeddingParent;
        private final JComboBox<String> arborChoiceCombo;
        private final JToggleButton hideOthersButton;
        private final JButton showAllArborsButton;
        private final JButton sortArborsButton;
        private final JButton nextArborButton;
        private String arborChoice = null; // currently chosen tree label
        private boolean navSyncGuard = false;   // prevents feedback loops between combobox and jtree

        NavigationToolBar(final JTree jTree, final JScrollPane scrollPaneOfJTree) {
            super("Navigation Toolbar", HORIZONTAL);

            // Tweak look so that toolbar blends in with JTree's background, etc.
            scrollPaneOfJTree.setColumnHeaderView(this);
            setBackground(jTree.getBackground());
            setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            setFocusable(false);
            setFloatable(true);

            this.embeddingParent = scrollPaneOfJTree.getColumnHeader();
            arborChoiceCombo = new JComboBox<>();
            hideOthersButton = new JToggleButton();
            sortArborsButton = sortButton();
            nextArborButton = nextArborButton();
            showAllArborsButton = showAllButton();

            // assemble combo box
            arborChoiceCombo.setToolTipText("Jump to an arbor (rooted structure)");
            arborChoiceCombo.setPrototypeDisplayValue("XXXXXXXXXXXXXX"); // 14 chars - safe cross-platform!?
            arborChoiceCombo.addActionListener(e -> {
                if (navSyncGuard) return;
                readArborChoice();
                if (arborChoice == null) return;
                if (hideOthersButton.isSelected())
                    applyHideOthers(true);
                else
                    expandAndRevealTree(arborChoice);
            });

            // assemble "hide others" button
            IconFactory.assignIcon(hideOthersButton, IconFactory.GLYPH.ARROWS_TO_EYE, IconFactory.GLYPH.ARROWS_TO_EYE);
            hideOthersButton.setToolTipText("When selected, show only paths from the chosen arbor/structure");
            hideOthersButton.addActionListener(e -> {
                if (arborChoiceCombo.getSelectedIndex() == -1) {
                    hideOthersButton.setSelected(false);
                    guiUtils.error("No arbor/structure chosen.");
                } else
                    applyHideOthers(hideOthersButton.isSelected());
            });

            // add components
            add(sortArborsButton);
            addSeparator();
            add(showAllArborsButton);
            add(arborChoiceCombo);
            add(nextArborButton);
            add(hideOthersButton);
            addSeparator();

            add(zoomToPathsButton());
            add(zoomToNodeButton());
            addSeparator();

            add(bookmarkButton());
            setVisible(plugin.getPrefs().getBoolean("navBar", true));
        }

        private void readArborChoice() {
            final String label = (String) arborChoiceCombo.getSelectedItem();
            arborChoice = (label == null || label.isEmpty()) ? null : label;
            // Keep the toggle enabled if filtering is active, even if combo shows no selection
            hideOthersButton.setEnabled(arborChoice != null || hideOthersButton.isSelected());
        }

        private Collection<Path> getSelectedPathsUsingToolbarOptions(final boolean ifNoneSelectedGetAll) {
            // if no rows are selected and combo box choice is empty return null:
            final boolean nothingInCombo = arborChoice == null || arborChoiceCombo.getSelectedIndex() == -1;
            if (nothingInCombo && !selectionExists()) {
                return null;
            }
            // if no rows are selected, 'hide others' is not active, and a valid combo box choice exists,
            // retrieve only the arbor listed in the combo box:
            if (!nothingInCombo && tree.getModel() == fullTreeModel && !selectionExists()) {
                return pathAndFillManager.getTree(arborChoice).list();
            }
            //... otherwise retrieve paths as usual
            return getSelectedPaths(ifNoneSelectedGetAll);
        }

        private JButton nextArborButton() {
            final JButton b = new JButton(); //
            IconFactory.assignIcon(b, IconFactory.GLYPH.CARET_UP_DOWN, IconFactory.GLYPH.CARET_UP_DOWN, 1f);
            b.setToolTipText("Next arbor");
            b.addActionListener(e -> {
                final int n = arborChoiceCombo.getItemCount();
                if ( n== 0) return;
                int i = arborChoiceCombo.getSelectedIndex();
                if (i == n - 1 || i == -1) {
                    arborChoiceCombo.setSelectedIndex(0);
                } else {
                    arborChoiceCombo.setSelectedIndex(i+1);
                }
            });
            return b;
        }

        private JButton sortButton() {
            final JButton b = new JButton(); //
            IconFactory.assignIcon(b, IconFactory.GLYPH.SORT, IconFactory.GLYPH.SORT, 1f);
            b.setToolTipText("Sort Arbors/Root-level Paths...");
            b.setActionCommand("Sort Arbors/Root-level Paths...");
            b.addActionListener( e -> {
                sortArbors(); // full model will be restored
                arborChoiceCombo.setSelectedIndex(-1);
            });
            return b;
        }

        private JButton showAllButton() {
            final JButton b = new JButton(); // home
            IconFactory.assignIcon(b, IconFactory.GLYPH.UNDO, IconFactory.GLYPH.UNDO, .8f);
            b.setToolTipText("Show all arbors");
            b.setActionCommand("Show All Arbors");
            b.addActionListener(e -> {
                restoreFullModelState(); // Reset to full model, clear filtering, and ensure hide others is off
                arborChoiceCombo.setSelectedIndex(-1);
            });
            return b;
        }

        private JButton zoomToNodeButton() {
            final JButton button = new JButton(IconFactory.buttonIcon('\uf601', true));
            button.setActionCommand("Zoom To Selected Nodes");
            button.addActionListener( e -> zoomToNodes(getSelectedPathsUsingToolbarOptions(false)));
            button.setToolTipText("Zoom to selected nodes");
            return button;
        }

        private JButton zoomToPathsButton() {
            final JButton button = new JButton(IconFactory.buttonIcon('\uf248', false));
            button.setActionCommand("Zoom To Selected Paths");
            button.addActionListener( e -> {
                zoomToBoundingBox(getSelectedPathsUsingToolbarOptions(true));
            });
            button.setToolTipText("Zoom to selected path(s)");
            return button;
        }

        private JButton bookmarkButton() {
            final ActionListener action = e -> {
                final Collection<Path> paths = getSelectedPathsUsingToolbarOptions(true);
                if (paths == null || paths.isEmpty()) {
                    guiUtils.error("No path(s) selected.");
                    return;
                }
                final String cmd = e.getActionCommand();
                final Map<Path, Set<Integer>> map = new LinkedHashMap<>(paths.size());
                String suffix = null;
                switch (cmd) {
                    case "Branch Points" -> {
                        suffix = "BP";
                        for (final Path p : paths) {
                            final TreeSet<Integer> junctionIndices = p.findJunctionIndices(); // sorted indices
                            // exclude the starting node if its itself
                            if (!junctionIndices.isEmpty() && p.getBranchPoint() != null && !p.isPrimary())
                                junctionIndices.pollFirst();
                            if (!junctionIndices.isEmpty()) map.put(p, junctionIndices);
                        }
                    }
                    case "Start Points" -> {
                        suffix = "SP";
                        for (final Path path : paths) map.put(path, Set.of(0));

                    }
                    case "End Points" -> {
                        suffix = "EP";
                        for (final Path path : paths) map.put(path, Set.of(path.size()-1));
                    }
                    case "Nodes With Invalid Radius" -> {
                        suffix = "Inv. rad. ";
                        for (final Path path : paths) {
                            if (!path.hasRadii()) continue;
                            final TreeSet<Integer> result = new TreeSet<>();
                            for (int i = 0; i < path.size(); i++) {
                                final double r = path.getNodeRadius(i);
                                if (r == 0d || Double.isNaN(r)) {
                                    result.add(i);
                                }
                            }
                            if (!result.isEmpty()) map.put(path, result);
                        }
                    }
                    case "Manually Tagged Nodes" -> {
                        suffix = "";
                        for (final Path path : paths) {
                            if (!path.getName().toLowerCase().contains("tagged node(s)") || !path.hasNodeColors())
                                continue; // see Tag Active Node... command in InteractiveTracerCanvas
                            final TreeSet<Integer> result = new TreeSet<>();
                            for (int i = 0; i < path.size(); i++) {
                                final Color c = path.getNodeColor(i);
                                if (c != null) result.add(i);
                            }
                            if (!result.isEmpty()) map.put(path, result);
                        }
                    }
                    default -> { /* no-op */ }
                }
                if (map.isEmpty()) {
                    guiUtils.error(String.format("No %s in selected path(s).", cmd.toLowerCase()));
                } else {
                    plugin.getUI().getBookmarkManager().add(map, suffix);
                    plugin.getUI().selectTab("Bookmarks");
                }
            };

            final JPopupMenu menu = new JPopupMenu();
            List.of("-Bookmark Topological Locations:", "Branch Points", "End Points", "Start Points",
                            "-Bookmark QC Locations:", "Manually Tagged Nodes", "Nodes With Invalid Radius")
                    .forEach(cmd -> {
                        if (cmd.startsWith("-")) {
                            GuiUtils.addSeparator(menu, cmd.substring(1));
                        } else {
                            final JMenuItem mi = new JMenuItem(cmd);
                            mi.addActionListener(action);
                            menu.add(mi);
                        }
                    });
            menu.add(getBookmarkCrossoverMenuItem());
            final JButton button = GuiUtils.Buttons.OptionsButton(IconFactory.GLYPH.BOOKMARK, .9f, menu);
            button.putClientProperty("cmdFinder", "Bookmarks");
            button.setToolTipText("Bookmark key locations along selected path(s)");
            return button;
        }

        private JMenuItem getBookmarkCrossoverMenuItem() {
            final JMenuItem mi = new JMenuItem("Putative Crossovers");
            mi.setToolTipText("Detect crossovers between paths");
            mi.addActionListener(e -> {
                final Collection<Path> paths = getSelectedPathsUsingToolbarOptions(true);
                if (paths == null || paths.isEmpty()) {
                    guiUtils.error("No path(s) selected.");
                    return;
                }
                final double defProximity = plugin.getAverageSeparation() * 10;
                final Double n = guiUtils.getDouble(
                        String.format("Specify the size (in %s) of the search neighborhood for cross-over detection. "
                                        + "Only paths interacting within this distance will be considered as candidates.<br>"
                                        + "Default is <i>%.2f%s</i>, i.e., <i>≈10 voxels</i>.",
                                plugin.getSpacingUnits(), defProximity, plugin.getSpacingUnits()),
                        "Cross-over Parameters", defProximity);
                if (n == null) return;
                if (Double.isNaN(n) || n <= 0)
                    guiUtils.error("Invalid search neighborhood: Must be > 0.");
                else
                    bookmarkCrossOvers(paths, n);
            });
            return mi;
        }

        private void bookmarkCrossOvers(final Collection<Path> selectedPaths, final double proximity) {
            var cfg = new CrossoverFinder.Config()
                    .proximity(proximity)
                    .thetaMinDeg(0) // disable angle filtering
                    .minRunNodes(2)
                    .sameCTOnly(true)
                    .includeSelfCrossovers(true)
                    .nodeWitnessRadius(-1); // assign proximity threshold
            final List<CrossoverFinder.CrossoverEvent> events = CrossoverFinder.find(selectedPaths, cfg);
            final List<double[]> output = new ArrayList<>(events.size());
            events.forEach(event -> output.add(event.xyzct()));
            if (output.isEmpty()) {
                guiUtils.error("No crossover locations detected.");
            } else {
                plugin.getUI().getBookmarkManager().add("Put. Crossover", output);
                plugin.getUI().selectTab("Bookmarks");
            }
        }

        void sortArbors() {
            if (tree.getModel() != fullTreeModel) {
                restoreFullModelState();
            }
            final List<Path> primaryPaths = Arrays.asList(pathAndFillManager.getPathsStructured());
            if (primaryPaths.size() == 1) {
                guiUtils.error("Only a single root path exists.");
                return;
            }
            final String[] choices = { "Arbor ID", "Cell label", "Primary path name", "Primary path length",
                    "Primary path mean radius", "Traced channel", "Traced frame" };
            final String choice = guiUtils.getChoice("Sorting criterion:", "Sort Root-level Paths", choices, choices[0]);
            if (choice == null)
                return;
            switch (choice) {
                case "Arbor ID" -> primaryPaths.sort(Comparator.comparingInt(Path::getTreeID));
                case "Cell label" -> primaryPaths.sort(Comparator.comparing(Path::getTreeLabel));
                case "Primary path name" -> primaryPaths.sort(Comparator.comparing(Path::getName));
                case "Primary path length" -> primaryPaths.sort(Comparator.comparingDouble(Path::getLength));
                case "Primary path mean radius" -> primaryPaths.sort(Comparator.comparingDouble(Path::getMeanRadius));
                case "Traced channel" -> primaryPaths.sort(Comparator.comparingInt(Path::getChannel));
                case "Traced frame" -> primaryPaths.sort(Comparator.comparingInt(Path::getFrame));
                default -> {
                    guiUtils.error("Not a recognized sorting option.");
                    return;
                }
            }
            final HelpfulTreeModel model = (HelpfulTreeModel) tree.getModel();
            final DefaultMutableTreeNode jTreeRoot = ((DefaultMutableTreeNode) model.getRoot());
            jTreeRoot.removeAllChildren();
            for (final Path primaryPath : primaryPaths) {
                if (!primaryPath.isFittedVersionOfAnotherPath())
                    model.addNode(jTreeRoot, primaryPath);
            }
            model.reload();
        }

        private void zoomToNodes(final Collection<Path> paths) {
            if (!canExecuteZoomOperation(paths)) return;

            // Create options for the user to choose from
            final String[] options = (paths.size() == 1) ?
                    new String[]{"First node", "First branch-point", "Last branch-point", "Midpoint", "Last node",
                            "Smallest radius node", "Largest-radius node"}
                    : new String[]{"First node", "First branch-point", "Last branch-point", "Midpoint", "Last node"};
            final String prevChoice = plugin.getPrefs().getTemp("gtChoice", options[0]);
            final String choice = guiUtils.getChoice("Navigate to which node(s) of selected path(s)?",
                    "Zoom To Nodes...", options, prevChoice);
            if (choice == null) return; // User cancelled
            plugin.getPrefs().setTemp("gtChoice", choice);

            final Path placeholder = paths.iterator().next().createPath(); // empty path from a representative path
            for (final Path path : paths) {
                switch (choice) {
                    case "First node" -> placeholder.addNode(path.getNode(0));
                    case "First branch-point" -> {
                        final TreeSet<Integer> junctions = path.findJunctionIndices();
                        if (junctions.size() > 1) {
                            final var it = junctions.iterator();
                            it.next(); // skip first (root)
                            placeholder.addNode(path.getNode(it.next()));
                        }
                    }
                    case "Last branch-point" -> {
                        final TreeSet<Integer> junctions = path.findJunctionIndices(); // sorted
                        if (!junctions.isEmpty()) placeholder.addNode(path.getNode(junctions.last()));
                    }
                    case "Midpoint" -> placeholder.addNode(path.getNode(path.size() / 2));
                    case "Last node" -> placeholder.addNode(path.getNode(path.size() - 1));
                    case "Smallest radius node" -> {
                        final Path.PathNode thinnest = path.getNode("min radius");
                        if (thinnest != null) placeholder.addNode(thinnest);
                    }
                    case "Largest-radius node" -> {
                        final Path.PathNode widest = path.getNode("max radius");
                        if (widest != null) placeholder.addNode(widest);
                    }
                    default -> {
                    } // Do nothing
                }
            }
            if (placeholder.size() == 0) {
                final String msg = (choice.contains("radius")) ? "radii" : "branch-points";
                guiUtils.error(String.format("Selected path(s) have no %s.", msg));
            } else {
                zoomToBoundingBox(List.of(placeholder));
            }
        }

        private boolean canExecuteZoomOperation(final Collection<Path> paths) {
            if (paths == null || paths.isEmpty()) {
                guiUtils.error("No path(s) selected.");
                return false;
            }
            if (plugin.getImagePlus() == null) {
                guiUtils.error("Image is not available.");
                return false;
            }
            return true;
        }

        private void zoomToBoundingBox(final Collection<Path> paths) {
            if (!canExecuteZoomOperation(paths)) return;

            final double prevMag = plugin.getImagePlus().getCanvas().getMagnification();
            final double zoom = ImpUtils.zoomTo(plugin.getImagePlus(), paths);
            plugin.setCanvasLabelAllPanes((zoom == prevMag) ? "Selected paths already in view" :
                    String.format("Zoomed to selected paths: (%.0f%%)", zoom * 100));

            // Sync side views to same zoom level if enabled
            if (!plugin.getSinglePane()) {
                final ImagePlus zyImp = plugin.getImagePlus(SNT.ZY_PLANE);
                if (zyImp != null)
                    ImpUtils.zoomTo(zyImp, zoom, paths, RoiConverter.ZY_PLANE);
                final ImagePlus xzImp = plugin.getImagePlus(SNT.XZ_PLANE);
                if (xzImp != null)
                    ImpUtils.zoomTo(xzImp, zoom, paths, RoiConverter.XZ_PLANE);
            }
            final Timer timer = new Timer(600, ae -> plugin.setCanvasLabelAllPanes(null));
            timer.setRepeats(false);
            timer.start();
        }

        void restoreFullModelState() {
            arborChoice = null;
            tree.setModel(fullTreeModel);
            hideOthersButton.setSelected(false);
            GuiUtils.JTrees.expandAllNodes(tree);
        }

        /** After the model is rebuilt, ensure the filter state matches what's available. */
        void ensureFilterStateConsistentWithModel() {
            // Only relevant if filtering is currently ON
            if (fullTreeModel == tree.getModel()) return;
            // If there's no chosen arbor, turn filtering off
            if (arborChoice == null || arborChoice.isEmpty()) {
                hideOthersButton.setSelected(false);
                applyHideOthers(false);
                return;
            }
            // Check whether the chosen arbor still exists in the backend
            if (getAllTreeLabels().stream().noneMatch( label -> arborChoice.equals(label))) {
                // The arbor was deleted: disable filter, clear combo, and restore full model
                hideOthersButton.setSelected(false);
                arborChoiceCombo.setSelectedIndex(-1);
                applyHideOthers(false);
            }
        }

        void selectedPathsChangedElsewhere(final Collection<Path> selectedPaths) {
            if (!getTreeLabels(selectedPaths).contains(arborChoice)) {
                // paths were selected outside PathManagerUI (e.g., via keystroke): we need to clear any filters
                restoreFullModelState();  // subsequent selection of path will update combobox
            }
        }

        void initFromFullModel() {
            arborChoice = null;
            ensureFilterStateConsistentWithModel();
            populateArborChoices();
        }

        /** Populate the combobox from current trees and enable/disable controls. */
        private void populateArborChoices() {
            final List<String> labels = getAllTreeLabels();
            navSyncGuard = true;
            try {
                arborChoiceCombo.removeAllItems();
                for (String s : labels) arborChoiceCombo.addItem(s);
                final boolean single = labels.size() <= 1;
                sortArborsButton.setEnabled(!single);
                showAllArborsButton.setEnabled(!single);
                arborChoiceCombo.setEnabled(!single);
                nextArborButton.setEnabled(!single);
                hideOthersButton.setEnabled(!single);
                if (arborChoice != null && labels.contains(arborChoice)) {
                    arborChoiceCombo.setSelectedItem(arborChoice);
                } else if (!labels.isEmpty()) {
                    arborChoiceCombo.setSelectedIndex(0);
                    arborChoice = labels.getFirst();
                } else {
                    arborChoice = null;
                    arborChoiceCombo.setSelectedIndex(-1);
                }
            } finally {
                navSyncGuard = false;
            }
        }

        private List<String> getAllTreeLabels() {
            return getTreeLabels(getPathAndFillManager().getPathsFiltered());
        }

        private List<String> getTreeLabels(final Collection<Path> paths) {
            return paths.stream()
                    .filter(Path::isPrimary)
                    .map(Path::getTreeLabel)
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }

        /** Expand the tree and scroll to the first occurrence of the given arbor label. */
        private void expandAndRevealTree(final String label) {
            if (label == null) return;
            //GuiUtils.JTrees.expandAllNodes(tree);
            for (int row = 0; row < tree.getRowCount(); row++) {
                final TreePath tp = tree.getPathForRow(row);
                if (tp == null) continue;
                final Object uo = ((DefaultMutableTreeNode) tp.getLastPathComponent()).getUserObject();
                if (uo instanceof Path p && label.equals(p.getTreeLabel())) {
                    tree.scrollRowToVisible(row);
                    break;
                }
            }
        }

        /** Show only the chosen arbor or restore the full list. */
        private void applyHideOthers(final boolean hide) {
            if (fullTreeModel == null || arborChoice == null) return;

            // 1) Remember the current selection so we can restore it after swapping models
            final List<Path> prevSelection = getSelectedPaths(false);
            // 2) Apply model
            if (hide) {
                final Path[] primaryPaths = pathAndFillManager.getPathsStructured();
                tree.setModel(new HelpfulTreeModel(primaryPaths, arborChoice));
                sortArborsButton.setEnabled(false);
            } else {
                hideOthersButton.setSelected(false); // ensure sync
                tree.setModel(fullTreeModel); // will call reload
                sortArborsButton.setEnabled(true);
            }
            GuiUtils.JTrees.expandAllNodes(tree);
            // 3) Restore selection if that exists in the new model
            if (prevSelection != null && !prevSelection.isEmpty()) {
                final List<Path> toRestore = hide
                        ? prevSelection.stream()
                        .filter(p -> arborChoice.equals(p.getTreeLabel()))
                        .collect(Collectors.toList())
                        : prevSelection;

                if (!toRestore.isEmpty()) {
                    tree.setSelectedPaths(toRestore);
                }
            }
        }

        /** Programmatically select an arbor label in the combobox without triggering listeners. */
        private void selectArborInTop(final String label) {
            navSyncGuard = true;
            try {
                arborChoiceCombo.setSelectedItem(label);
                arborChoice = label;
            } finally {
                navSyncGuard = false;
            }
        }

        void selectionModelChanged(final Collection<Path> selectedPaths) {
            // Reflect a coherent, single-arbor selection
            final Set<String> selectedLabels = selectedPaths.stream()
                    .map(Path::getTreeLabel)
                    .collect(Collectors.toSet());

            if (selectedLabels.size() == 1) {
                selectArborInTop(selectedLabels.iterator().next());
                return;
            }

            // If the list is filtered, keep the top choice pinned even if the user clicks empty space
            if (hideOthersButton.isSelected() && arborChoice != null) {
                selectArborInTop(arborChoice);
            } else if (arborChoiceCombo != null) {
                navSyncGuard = true;
                try {
                    arborChoiceCombo.setSelectedIndex(-1); // only when not filtered
                } finally {
                    navSyncGuard = false;
                }
            }
        }

        @Override
        public void setVisible(final boolean b) {
            super.setVisible(b);
            embeddingParent.setVisible(b);
            if (!b && fullTreeModel != null) restoreFullModelState();
            plugin.getPrefs().set("navBar", b);
        }

    }

    private class ProofReadingTagsToolBar extends JToolBar {

        final Map<String, Color> tagsMap;
        final JCheckBoxMenuItem toggleMenuItem;
        SNTChart ringChart;

        ProofReadingTagsToolBar() {
            super("Proofreading Tags", HORIZONTAL);
            setFocusable(false);
            setFloatable(true);
            final boolean isVisible = plugin.getPrefs().getBoolean("proofReadBar", true);
            toggleMenuItem = new JCheckBoxMenuItem("Proofreading Toolbar", isVisible);
            toggleMenuItem.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.GLASSES));
            toggleMenuItem.addItemListener(e -> {
                super.setVisible(toggleMenuItem.isSelected());
                plugin.getPrefs().set("proofReadBar", toggleMenuItem.isSelected());
            });
            tagsMap = new TreeMap<>();
            initializeTagColors();
            rebuildToolbar();
            setVisible(isVisible);
        }

        private void initializeTagColors() {
            // Load colors from preferences or use defaults
            tagsMap.put("active", loadTagColor("active", "B7E3F2"));
            tagsMap.put("complete", loadTagColor("complete", "C1E561"));
            tagsMap.put("partial", loadTagColor("partial", "FFF8BF"));
            tagsMap.put("unsure", loadTagColor("unsure", "FFDF9E"));
            tagsMap.put("wrong", loadTagColor("wrong", "EBB8BC"));
        }

        private Color loadTagColor(final String tagName, final String defaultTagColor) {
            final String key = "proofReadTag." + tagName;
            return SNTColor.fromHex(plugin.getPrefs().get(key, defaultTagColor));
        }

        private void saveTagColor(final String tagName, final Color tagColor ) {
            final String key = "proofReadTag." + tagName;
            plugin.getPrefs().set(key, (tagColor == null) ? null : SNTColor.colorToString(tagColor)); // setting to null removes pref
        }

        private void rebuildToolbar() {
            removeAll();
            add(summaryButton());
            addSeparator();
            tagsMap.forEach((name, color) -> add(tagButton(name, color)));
            add(clearButton());
            addExtras();
            revalidate();
            repaint();
        }

        @Override
        public void setVisible(boolean aFlag) {
            toggleMenuItem.setSelected(aFlag);
            super.setVisible(aFlag);
        }

        JCheckBoxMenuItem getToggleMenuItem() {
            return toggleMenuItem;
        }

        void addExtras() {
            final JPopupMenu popupMenu = new JPopupMenu();
            final JMenuItem customizeItem = new JMenuItem("Change Colors...", IconFactory.menuIcon(IconFactory.GLYPH.EYE_DROPPER));
            customizeItem.addActionListener(e -> showColorCustomizationDialog());
            popupMenu.add(customizeItem);
            popupMenu.addSeparator();
            final JMenuItem hideItem = new JMenuItem("Hide " + getName(), IconFactory.menuIcon('\uf070', true));
            hideItem.addActionListener(e -> setVisible(false));
            popupMenu.add(hideItem);
            setComponentPopupMenu(popupMenu);
            add(Box.createHorizontalGlue());
            add(GuiUtils.Buttons.help("https://imagej.net/plugins/snt/manual#tag-"));
        }

        private void showColorCustomizationDialog() {
            // Create the panel with color chooser buttons
            final JPanel panel = new JPanel(new GridBagLayout());
            final GridBagConstraints c = GuiUtils.defaultGbc();
            // Create color selection components for each tag
            c.insets = new Insets(0, 0, 5, 0);
            final Map<String, ColorChooserButton> colorButtons = new HashMap<>();
            tagsMap.forEach( (tagName, color) -> {
                final ColorChooserButton colorButton = new ColorChooserButton(color, capitalized(tagName));
                panel.add(colorButton, c);
                colorButtons.put(tagName, colorButton);
                c.gridy++;
            });
            // Add reset button
            c.insets.top += 10;
            final JButton resetButton = new JButton("Reset to Defaults");
            resetButton.addActionListener(e -> {
                tagsMap.keySet().forEach( tagName -> saveTagColor(tagName, null));
                tagsMap.clear();
                initializeTagColors();
                colorButtons.forEach( (tagName, button) -> {
                    button.setSelectedColor(tagsMap.get(tagName), true);
                });
            });
            panel.add(resetButton, c);

            // Show the option pane
            final int result = JOptionPane.showConfirmDialog(PathManagerUI.this,
                    panel, "Proofreading Tag Colors", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

            // save the new colors if user pressed OK
            if (result == JOptionPane.OK_OPTION) {
                colorButtons.forEach( (tagName, button) -> {
                    final Color selectedColor = button.getSelectedColor();
                    if (selectedColor != null) {
                        tagsMap.put(tagName, selectedColor);
                        saveTagColor(tagName, selectedColor);
                    }
                });
                rebuildToolbar();
            }
        }

        JButton summaryButton() {
            final JButton sButton = GuiUtils.Buttons.toolbarButton(IconFactory.GLYPH.CHART_PIE, new Color(0xF8F8F8), .9f);
            sButton.setToolTipText("Summarizes applied tags in a donut chart");
            sButton.setBackground(new Color(0x8F8F8F));
            sButton.addActionListener(e -> summarizeTags());
            return sButton;
        }

        JButton clearButton() {
            final JButton clearButton = GuiUtils.Buttons.toolbarButton(" None ");
            clearButton.setBackground(new Color(0x5D5D5D));
            clearButton.setForeground(Color.WHITE);
            clearButton.addActionListener(e -> {
                final Collection<Path> selectedPaths = getSelectedPaths(true);
                if (selectedPaths.isEmpty()) {
                    guiUtils.error("There are no traced paths.");
                    return;
                }
                selectedPaths.forEach(p -> {
                    final String tags = extractTagsFromPath(p);
                    if (tags.isEmpty()) return;
                    final String cleansedTags = removePreviousTag(tags);
                    if (cleansedTags.isEmpty())
                        p.setName(removeTags(p));
                    else
                        p.setName(String.format("%s {%s}", removeTags(p), cleansedTags));
                    p.setColor((Color) null);
                });
                refreshManager(true, true, selectedPaths);
                plugin.setUnsavedChanges(true);
                if (plugin.getUI().getRecorder(false) != null)
                    plugin.getUI().getRecorder(false)
                            .recordComment(String.format("Proofreading tags removed from %d path(s)", selectedPaths.size()));
            });
            return clearButton;
        }

        String capitalized(final String string) {
            return string.substring(0, 1).toUpperCase() + string.substring(1);
        }

        JButton tagButton(final String tagName, final Color tagColor) {
            final JButton tagButton = GuiUtils.Buttons.toolbarButton(capitalized(tagName));
            tagButton.setBackground(tagColor);
            tagButton.setForeground(SNTColor.contrastColor(tagColor));
            tagButton.addActionListener(e -> {
                final Collection<Path> selectedPaths = getSelectedPaths(true);
                if (selectedPaths.isEmpty()) {
                    guiUtils.error("There are no traced paths.");
                    return;
                }
                selectedPaths.forEach(p -> {
                    String tags = extractTagsFromPath(p);
                    String oldTag = extractPreviousTag(tags);
                    if (oldTag == null) {
                        p.setName(String.format("%s {%s}", removeTags(p), (tags.isEmpty()) ? tagName : tags + ", " + tagName));
                    } else {
                        p.setName(String.format("%s {%s}", removeTags(p), tags.replace(oldTag, tagName)));
                    }
                    p.setColor(tagColor);
                });
                refreshManager(true, true, selectedPaths);
                plugin.setUnsavedChanges(true);
                if (plugin.getUI().getRecorder(false) != null)
                    plugin.getUI().getRecorder(false)
                            .recordComment(String.format("Proofreading tag '%s' applied to %d path(s)", tagName, selectedPaths.size()));
            });
            return tagButton;
        }

        void summarizeTags() {
            final Collection<Path> paths = pathAndFillManager.getPathsFiltered();
            if (paths.isEmpty()) {
                guiUtils.error("There are no traced paths.");
                return;
            }
            final HashMap<String, Double> dataset = new HashMap<>();
            final HashMap<String, Color> colors = new HashMap<>();
            tagsMap.forEach( (tagName, tagColor) -> {
                dataset.put(tagName, 0d);
                colors.put(tagName, tagColor);
            });
            dataset.put("none", 0d);
            colors.put("none", Color.LIGHT_GRAY);
            paths.forEach(p -> {
                final String tag = getFirstTag(p);
                if (tag != null)
                    dataset.put(tag, dataset.get(tag)+1);
                else
                    dataset.put("none", dataset.get("none")+1);
            });
            final SNTChart chart = AnalysisUtils.ringPlot(String.format("Tags (All %d Paths)", paths.size()), dataset, colors);
            if (ringChart != null && ringChart.isVisible()) {
                ringChart.replace(chart);
                ringChart.getFrame().setTitle(String.format("Tags (All %d Paths)", paths.size()));
                ringChart.show();
            } else {
                chart.getFrame().setLocationRelativeTo(ProofReadingTagsToolBar.this);
                chart.show();
                ringChart = chart;
            }
        }

        String extractPreviousTag(final String pathTags) {
            for (final String tag : tagsMap.keySet())
                if (pathTags.contains(tag)) return tag;
            return null;
        }

        String removePreviousTag(final String pathTags) {
            for (final String tag : tagsMap.keySet())
                if (pathTags.contains(tag)) {
                    String cleanedPathTags = pathTags.replace(tag, "");
                    cleanedPathTags = Arrays.stream(cleanedPathTags.split(","))
                            .filter(s -> !s.isBlank())
                            .collect(Collectors.joining(","));
                    return cleanedPathTags;
                }
            return pathTags;
        }

        String getFirstTag(final Path path) {
            final String tags = extractTagsFromPath(path);
            if (tags.isEmpty())
                return null;
            for (final String tag : tagsMap.keySet())
                if (tags.contains(tag)) return tag;
            return null;
        }
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
