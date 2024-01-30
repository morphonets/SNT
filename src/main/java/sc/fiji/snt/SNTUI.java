/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.util.Map.Entry;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;

import javax.swing.Timer;
import javax.swing.border.BevelBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeListener;

import org.apache.commons.lang3.StringUtils;
import org.scijava.command.Command;
import org.scijava.command.CommandModule;
import org.scijava.command.CommandService;
import org.scijava.ui.UIService;
import org.scijava.util.ColorRGB;
import org.scijava.util.Types;

import ij.ImageListener;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.measure.Calibration;
import ij.plugin.frame.Recorder;
import ij.process.ImageStatistics;
import ij3d.Content;
import ij3d.ContentConstants;
import ij3d.Image3DUniverse;
import ij3d.ImageWindow3D;
import net.imagej.Dataset;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.TreeAnalyzer;
import sc.fiji.snt.analysis.sholl.ShollUtils;
import sc.fiji.snt.event.SNTEvent;
import sc.fiji.snt.gui.*;
import sc.fiji.snt.gui.DemoRunner.Demo;
import sc.fiji.snt.gui.cmds.*;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;
import sc.fiji.snt.gui.IconFactory.GLYPH;
import sc.fiji.snt.io.FlyCircuitLoader;
import sc.fiji.snt.io.NeuroMorphoLoader;
import sc.fiji.snt.io.WekaModelLoader;
import sc.fiji.snt.plugin.*;
import sc.fiji.snt.tracing.cost.OneMinusErf;
import sc.fiji.snt.viewer.Viewer3D;

import javax.swing.*;
import java.util.*;

/**
 * Implements SNT's main dialog.
 *
 * @author Tiago Ferreira
 */
@SuppressWarnings("serial")
public class SNTUI extends JDialog {

	/* UI */
	private static final int MARGIN = 2;
	private JCheckBox showPathsSelected;
	protected CheckboxSpinner partsNearbyCSpinner;
	protected JCheckBox useSnapWindow;
	private JCheckBox onlyActiveCTposition;
	protected JSpinner snapWindowXYsizeSpinner;
	protected JSpinner snapWindowZsizeSpinner;
	private JButton showOrHidePathList;
	private JButton showOrHideFillList = new JButton(); // must be initialized
	private JMenuItem saveMenuItem;
	private JMenuItem exportCSVMenuItem;
	private JMenuItem quitMenuItem;
	private JMenuItem sendToTrakEM2;
	private JLabel statusText;
	private JLabel statusBarText;
	private JButton keepSegment;
	private JButton junkSegment;
	private JButton completePath;
	private JButton rebuildCanvasButton;
	private JCheckBox debugCheckBox;

	// UI controls for auto-tracing
	private JComboBox<String> searchAlgoChoice;
	private JPanel aStarPanel;
	private JCheckBox aStarCheckBox;
	private SigmaPalette sigmaPalette;
	private JTextArea settingsArea;
	private JRadioButtonMenuItem autoRbmi;

	// UI controls for CT data source
	private JPanel sourcePanel;

	// UI controls for loading  on 'secondary layer'
	private JCheckBox secLayerActivateCheckbox;
	private CheckboxSpinner secLayerImgOverlayCSpinner;

	private final SNTCommandFinder commandFinder;
	private ActiveWorker activeWorker;
	private volatile int currentState = -1;

	private SNT plugin;
	private PathAndFillManager pathAndFillManager;
	protected GuiUtils guiUtils;
	private final PathManagerUI pmUI;
	private final FillManagerUI fmUI;

	/* Reconstruction Viewer */
	protected Viewer3D recViewer;
	protected Frame recViewerFrame;
	private JButton openRecViewer;

	/* SciView */
	protected SciViewSNT sciViewSNT;
	private JButton openSciView;
	private JButton svSyncPathManager;


	protected final GuiListener listener;

	/* These are the states that the UI can be in: */
	/**
	 * Flag specifying that image data is available and the UI is not waiting on any
	 * pending operations, thus 'ready to trace'
	 */
	public static final int READY = 0;
	static final int WAITING_TO_START_PATH = 0; /* legacy flag */
	static final int PARTIAL_PATH = 1;
	static final int SEARCHING = 2;
	static final int QUERY_KEEP = 3;
	public static final int RUNNING_CMD = 4;
	static final int CACHING_DATA = 5;
	static final int FILLING_PATHS = 6;
	static final int CALCULATING_HESSIAN_I = 7;
	static final int CALCULATING_HESSIAN_II = 8;
	public static final int WAITING_FOR_SIGMA_POINT_I = 9;
	//static final int WAITING_FOR_SIGMA_POINT_II = 10;
	static final int WAITING_FOR_SIGMA_CHOICE = 11;
	static final int SAVING = 12;
	/** Flag specifying UI is currently waiting for I/0 operations to conclude */
	public static final int LOADING = 13;
	/** Flag specifying UI is currently waiting for fitting operations to conclude */
	public static final int FITTING_PATHS = 14;
	/**Flag specifying UI is currently waiting for user to edit a selected Path */
	public static final int EDITING = 15;
	/**
	 * Flag specifying all SNT are temporarily disabled (all user interactions are
	 * waived back to ImageJ)
	 */
	public static final int SNT_PAUSED = 16;
	/**
	 * Flag specifying tracing functions are (currently) disabled. Tracing is
	 * disabled when the user chooses so or when no valid image data is available
	 * (e.g., when no image has been loaded and a placeholder display canvas is
	 * being used)
	 */
	public static final int TRACING_PAUSED = 17;


	// TODO: Internal preferences: should be migrated to SNTPrefs
	protected boolean confirmTemporarySegments = true;
	protected boolean finishOnDoubleConfimation = true;
	protected boolean discardOnDoubleCancellation = true;
	protected boolean askUserConfirmation = true;
	private boolean openingSciView;
	private SigmaPaletteListener sigmaPaletteListener;
	private ScriptRecorder recorder;

	/**
	 * Instantiates SNT's main UI and associated {@link PathManagerUI} and
	 * {@link FillManagerUI} instances.
	 *
	 * @param plugin the {@link SNT} instance associated with this
	 *               UI
	 */
	public SNTUI(final SNT plugin) {
		this(plugin, null, null);
	}

	private SNTUI(final SNT plugin, final PathManagerUI pmUI, final FillManagerUI fmUI) {

		super(ij.IJ.getInstance(), "SNT v" + SNTUtils.VERSION, false);
		// if embedded, menu bar becomes truncated on M. Windows 10/11
		getRootPane().putClientProperty("JRootPane.menuBarEmbedded", false);
		guiUtils = new GuiUtils(this);
		this.plugin = plugin;
		new ClarifyingKeyListener(plugin).addKeyAndContainerListenerRecursively(this);
		listener = new GuiListener();
		pathAndFillManager = plugin.getPathAndFillManager();
		commandFinder = new SNTCommandFinder(this);
		commandFinder.register(getTracingCanvasPopupMenu(), new ArrayList<>(Collections.singletonList("Image Contextual Menu")));
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {

			@Override
			public void windowClosing(final WindowEvent e) {
				exitRequested();
			}
		});

		GuiUtils.removeIcon(this);

		assert SwingUtilities.isEventDispatchThread();
		final JTabbedPane tabbedPane = getTabbedPane();

		{ // Main tab
			final GridBagConstraints c1 = GuiUtils.defaultGbc();
			{
				final JPanel tab1 = InternalUtils.getTab();
				c1.insets.top = MARGIN * 2;
				c1.anchor = GridBagConstraints.NORTHEAST;
				InternalUtils.addSeparatorWithURL(tab1, "Data Source:", false, c1);
				c1.insets.top = 0;
				++c1.gridy;
				tab1.add(sourcePanel = sourcePanel(plugin.getImagePlus()), c1);
				++c1.gridy;
				InternalUtils.addSeparatorWithURL(tab1, "Cursor Auto-snapping:", true, c1);
				++c1.gridy;
				tab1.add(snappingPanel(), c1);
				++c1.gridy;
				InternalUtils.addSeparatorWithURL(tab1, "Auto-tracing:", true, c1);
				++c1.gridy;
				tab1.add(aStarPanel(), c1);
				++c1.gridy;
				tab1.add(secondaryDataPanel(), c1);
				++c1.gridy;
				tab1.add(settingsPanel(), c1);
				++c1.gridy;
				InternalUtils.addSeparatorWithURL(tab1, "Filters for Visibility of Paths:", true, c1);
				++c1.gridy;
				tab1.add(renderingPanel(), c1);
				++c1.gridy;
				InternalUtils.addSeparatorWithURL(tab1, "Default Path Colors:", true, c1);
				++c1.gridy;
				tab1.add(colorOptionsPanel(), c1);
				++c1.gridy;
				GuiUtils.addSeparator(tab1, "", true, c1); // empty separator
				++c1.gridy;
				c1.fill = GridBagConstraints.HORIZONTAL;
				c1.insets = new Insets(0, 0, 0, 0);
				c1.insets.bottom = MARGIN * 2;
				tab1.add(hideWindowsPanel(), c1);
				tabbedPane.addTab("<HTML>Main ", tab1);
			}
		}

		{ // Options Tab
			final JPanel tab2 = InternalUtils.getTab();
			tab2.setLayout(new GridBagLayout());
			final GridBagConstraints c2 = GuiUtils.defaultGbc();
			// c2.insets.left = MARGIN * 2;
			c2.anchor = GridBagConstraints.NORTHEAST;
			c2.gridwidth = GridBagConstraints.REMAINDER;
			InternalUtils.addSeparatorWithURL(tab2, "Views:", true, c2);
			++c2.gridy;
			tab2.add(viewsPanel(), c2);
			++c2.gridy;
			{
				InternalUtils.addSeparatorWithURL(tab2, "Temporary Paths:", true, c2);
				++c2.gridy;
				tab2.add(tracingPanel(), c2);
				++c2.gridy;
			}
			InternalUtils.addSeparatorWithURL(tab2, "Path Rendering:", true, c2);
			++c2.gridy;
			tab2.add(pathOptionsPanel(), c2);
			++c2.gridy;
			InternalUtils.addSeparatorWithURL(tab2, "Misc:", true, c2);
			++c2.gridy;
			c2.weighty = 1;
			tab2.add(miscPanel(), c2);
			tabbedPane.addTab("<HTML>Options ", tab2);
		}

		{ // 3D tab
			final JPanel tab3 = InternalUtils.getTab();
			tab3.setLayout(new GridBagLayout());
			final GridBagConstraints c3 = GuiUtils.defaultGbc();
			// c3.insets.left = MARGIN * 2;
			c3.anchor = GridBagConstraints.NORTHEAST;
			c3.gridwidth = GridBagConstraints.REMAINDER;

			tabbedPane.addTab("<HTML> 3D ", tab3);
			InternalUtils.addSeparatorWithURL(tab3, "Reconstruction Viewer:", true, c3);
			c3.gridy++;
			final String msg = "A dedicated OpenGL visualization tool specialized in Neuroanatomy, " +
				"supporting morphometric annotations, reconstructions and meshes. For " +
				"performance reasons, some Path Manager changes may need to be synchronized " +
				"manually from the \"Scene Controls\" menu.";
			tab3.add(largeMsg(msg), c3);
			c3.gridy++;
			tab3.add(reconstructionViewerPanel(), c3);
			c3.gridy++;
			addSpacer(tab3, c3);
			InternalUtils.addSeparatorWithURL(tab3, "sciview:", true, c3);
			++c3.gridy;
			final String msg3 =
				"Modern 3D visualization framework supporting large image volumes, " +
				"reconstructions, meshes, virtual reality, and Cx3D simulations. Discrete graphics card recommended. " +
				"For performance reasons, some Path Manager changes may need to be synchronized " +
				"manually using \"Sync Changes\".";
			tab3.add(largeMsg(msg3), c3);
			c3.gridy++;
			tab3.add(sciViewerPanel(), c3);
			c3.gridy++;
			addSpacer(tab3, c3);
			InternalUtils.addSeparatorWithURL(tab3, "Legacy 3D Viewer:", true, c3);
			++c3.gridy;
			final String msg2 =
				"The Legacy 3D Viewer is a functional tracing canvas " +
					"but it depends on outdated services that are now deprecated. " +
					"It may not function reliably on recent operating systems.";
			tab3.add(largeMsg(msg2), c3);
			c3.gridy++;
			try {
				tab3.add(legacy3DViewerPanel(), c3);
			} catch (final NoClassDefFoundError ignored) {
				tab3.add(largeMsg("Error: Legacy 3D Viewer could not be initialized!"), c3);
			}
			c3.gridy++;
			tab3.add(largeMsg(""), c3); // add bottom spacer

			{
				tabbedPane.setIconAt(0, IconFactory.getTabbedPaneIcon(IconFactory.GLYPH.HOME));
				tabbedPane.setIconAt(1, IconFactory.getTabbedPaneIcon(IconFactory.GLYPH.TOOL));
				tabbedPane.setIconAt(2, IconFactory.getTabbedPaneIcon(IconFactory.GLYPH.CUBE));
			}
		}

		setJMenuBar(createMenuBar());
		setLayout(new GridBagLayout());
		final GridBagConstraints dialogGbc = GuiUtils.defaultGbc();
		add(statusPanel(), dialogGbc);
		dialogGbc.gridy++;
		add(new JLabel(" "), dialogGbc);
		dialogGbc.gridy++;

		add(tabbedPane, dialogGbc);
		dialogGbc.gridy++;
		add(statusBar(), dialogGbc);
		addFileDrop(this, guiUtils);
		//registerCommandFinder(menuBar); // spurious addition if added here!?
		pack();
		toFront();

		if (pmUI == null) {
			this.pmUI = new PathManagerUI(plugin);
			this.pmUI.setLocation(getX() + getWidth(), getY());
			if (showOrHidePathList != null) {
				this.pmUI.addWindowStateListener(evt -> {
					if ((evt.getNewState() & Frame.ICONIFIED) == Frame.ICONIFIED) {
						showOrHidePathList.setText("Show Path Manager");
					}
				});
				this.pmUI.addWindowListener(new WindowAdapter() {

					@Override
					public void windowClosing(final WindowEvent e) {
						showOrHidePathList.setText("Show Path Manager");
					}
				});
			}
			this.pmUI.getJMenuBar().add(Box.createHorizontalGlue());
			this.pmUI.getJMenuBar().add(commandFinder.getMenuItem(this.pmUI.getJMenuBar(), true));
			addFileDrop(this.pmUI, this.pmUI.guiUtils);
			commandFinder.attach(this.pmUI);
		} else {
			this.pmUI = pmUI;
		}
		if (fmUI == null) {
			this.fmUI = new FillManagerUI(plugin);
			this.fmUI.setLocationRelativeTo(this);
			if (showOrHidePathList != null) {
				this.fmUI.addWindowStateListener(evt -> {
					if (showOrHideFillList != null && (evt.getNewState() & Frame.ICONIFIED) == Frame.ICONIFIED) {
						showOrHideFillList.setText("Show Fill Manager");
					}
				});
				this.fmUI.addWindowListener(new WindowAdapter() {

					@Override
					public void windowClosing(final WindowEvent e) {
						showOrHideFillList.setText("Show Fill Manager");
					}
				});
			}
			commandFinder.attach(this.fmUI);
		} else {
			this.fmUI = fmUI;
		}

	}

	private JTabbedPane getTabbedPane() {
		final JTabbedPane tabbedPane = GuiUtils.getTabbedPane();
		tabbedPane.addChangeListener(e -> {
			final JTabbedPane source = (JTabbedPane) e.getSource();
			final int selectedTab = source.getSelectedIndex();

			// Do not allow secondary tabs to be selected while operations are pending 
			if (selectedTab > 0 && userInteractionConstrained()) {
				tabbedPane.setSelectedIndex(0);
				guiUtils.blinkingError(statusText,
						"Please complete current task before selecting the "+ source.getTitleAt(selectedTab) +" tab.");
				return;
			}

			// Highlight active tab. This assumes tab's title contains "HTML"
			//TF: With FlatLaf, this is no longer useful!?
//			for (int i = 0; i < source.getTabCount(); i++) {
//				final String existingTile = source.getTitleAt(i);
//				final String newTitle = (i == selectedTab) ? existingTile.replace("<HTML>", "<HTML><b>")
//						: existingTile.replace("<HTML><b>", "<HTML>");
//				source.setTitleAt(i, newTitle);
//			}
		});
		return tabbedPane;
	}

	/**
	 * Gets the current UI state.
	 *
	 * @return the current UI state, e.g., {@link SNTUI#READY},
	 *         {@link SNTUI#RUNNING_CMD}, etc.
	 */
	public int getState() {
		if (plugin.tracingHalted && currentState == READY)
			currentState = TRACING_PAUSED;
		return currentState;
	}

	/**
	 * 
	 * @return the preferences associated with this instance
	 */
	public SNTPrefs getPrefs() {
		return plugin.getPrefs();
	}

	private boolean userInteractionConstrained() {
		switch (getState()) {
		case PARTIAL_PATH:
		case SEARCHING:
		case QUERY_KEEP:
		case RUNNING_CMD:
		case CALCULATING_HESSIAN_I:
		case CALCULATING_HESSIAN_II:
		case WAITING_FOR_SIGMA_POINT_I:
		case WAITING_FOR_SIGMA_CHOICE:
		case LOADING:
			return true;
		default:
			return false;
		}
	}

	/**
	 * Assesses whether the UI is blocked.
	 *
	 * @return true if the UI is currently unblocked, i.e., ready for
	 *         tracing/editing/analysis *
	 */
	public boolean isReady() {
		final int state = getState();
		return isVisible() && (state == SNTUI.READY || state == SNTUI.TRACING_PAUSED || state == SNTUI.SNT_PAUSED);
	}

	/**
	 * Enables/disables debug mode
	 *
	 * @param enable true to enable debug mode, otherwise false
	 */
	public void setEnableDebugMode(final boolean enable) {
		debugCheckBox.setSelected(enable);
		if (getReconstructionViewer(false) == null) {
			SNTUtils.setDebugMode(enable);
		} else {
			// will call SNT.setDebugMode(enable);
			getReconstructionViewer(false).setEnableDebugMode(enable);
		}
	}

	/**
	 * Runs a menu command (as listed in the menu bar hierarchy).
	 *
	 * @param cmd The command to be run, exactly as listed in its menu (either in
	 *            this dialog, or {@link PathManagerUI})
	 * @throws IllegalArgumentException if {@code cmd} was not found.
	 */
	public void runCommand(final String cmd) throws IllegalArgumentException {
		if (!runCustomCommand(cmd) && !getPathManager().runCustomCommand(cmd))
			runSNTCommandFinderCommand(cmd);
	}

	/**
	 * Runs a menu command with options.
	 *
	 * @param cmd  The command to be run, exactly as listed in SNTUI's menu bar
	 * @param args the option(s) that would fill the command's prompt. e.g.,
	 *             'runCommand("Load Demo Dataset...", "4. Hippocampal neuron (DIC
	 *             timelapse)")'
	 * @throws IllegalArgumentException if {@code cmd} is not found or supported.
	 */
	public void runCommand(final String cmd, final String... args) throws IllegalArgumentException {
		if (args == null || args.length == 1 && args[0].trim().isEmpty()) {
			runCommand(cmd);
			return;
		}
		final int type = InternalUtils.getImportActionType(cmd);
		if (type > -1) {
			// then this is an import action
			new ImportAction(type, new File(args[0])).run();
		} else {
			throw new IllegalArgumentException("Unsupported options for '" + cmd + "'");
		}
	}

	protected void runSNTCommandFinderCommand(final String cmd) {
		commandFinder.runCommand(cmd);
	}

	protected boolean runCustomCommand(final String cmd) {
		if ("validateImgDimensions".equals(cmd)) {
			validateImgDimensions();
			return true;
		} else if ("cmdPalette".equals(cmd)) {
			commandFinder.toggleVisibility();
			return true;
		}
		return false;
	}

	/**
	 * Runs the autotracing prompt on the active image, assumed to be
	 * pre-processed/binary (script friendly method).
	 *
	 * @param simplified whether wizard should omit advanced options
	 */
	public void runAutotracingWizard(final boolean simplified) {
		SwingUtilities.invokeLater(() -> {
			if (plugin.accessToValidImageData() && plugin.getImagePlus().getProcessor().isBinary()) {
				runAutotracingOnImage(simplified);
			} else {
				noValidImageDataError();
			}
		});
	}

	/**
	 * Runs the autotracing prompt on the specified image, assumed to be
	 * pre-processed/binary (script friendly method).
	 *
	 * @param imp        the processed image from which paths are to be extracted
	 * @param simplified whether wizard should omit advanced options
	 */
	public void runAutotracingWizard(final ImagePlus imp, final boolean simplified) {
		final HashMap<String, Object> inputs = new HashMap<>();
		inputs.put("useFileChoosers", false);
		inputs.put("maskImgChoice", imp.getTitle());
		inputs.put("originalImgChoice", (plugin.accessToValidImageData()) ? plugin.getImagePlus().getTitle() : "None");
		inputs.put("simplifyPrompt", simplified);
		if (imp.getRoi() == null) {
			inputs.put("rootChoice", SkeletonConverterCmd.ROI_UNSET);
			inputs.put("roiPlane", false);
		} else {
			inputs.put("rootChoice", SkeletonConverterCmd.ROI_EDGE);
			inputs.put("roiPlane", true);
		}
		(new DynamicCmdRunner(SkeletonConverterCmd.class, inputs)).run();
	}

	/**
	 * Runs the 'secondary layer' wizard prompt for built-in filters
	 */
	public void runSecondaryLayerWizard() {
		if (!okToReplaceSecLayer())
			return;
		if (!plugin.accessToValidImageData()) {
			noValidImageDataError();
		} else if (!plugin.invalidStatsError(false)) {
			plugin.flushSecondaryData();
			(new DynamicCmdRunner(ComputeSecondaryImg.class, null, RUNNING_CMD)).run();
		}
	}

	/**
	 * Runs the 'secondary layer wizard' in the background, without displaying
	 * prompt.
	 *
	 * @param filter either "Frangi Vesselness", "Tubeness", or "Gaussian Blur"
	 * @param scales a list of aprox. thicknesses (radius) of the structures being
	 *               traced
	 * @throws IllegalArgumentException if no valid image data is currently loaded
	 */
	public void runSecondaryLayerWizard(final String filter, final double[] scales) throws IllegalArgumentException {
		if (!plugin.accessToValidImageData()) {
			throw new IllegalArgumentException("No valid image data loaded");
		}
		if (!(filter.startsWith("Frangi Vesselness") || filter.startsWith("Tubeness") || filter.startsWith("Gaussian Blur")
				|| filter.startsWith("Median"))) {
			throw new IllegalArgumentException("Invalid filter option");
		}
		plugin.flushSecondaryData();
		if (plugin.getStats().max == 0) {
			final ImageStatistics stats = plugin.getLoadedDataAsImp().getStatistics(ImagePlus.MIN_MAX);
			plugin.getStats().min = stats.min;
			plugin.getStats().max = stats.max;
		}
		final HashMap<String, Object> inputs = new HashMap<>();
		inputs.put("filter", filter);
		inputs.put("sizeOfStructuresString", Arrays.toString(scales).replace("[", "").replace("]", ""));
		inputs.put("calledFromScript", true);
		final Object syncObject = new Object();
		inputs.put("syncObject", syncObject);
		(new DynamicCmdRunner(ComputeSecondaryImg.class, inputs, RUNNING_CMD)).run();
		synchronized (syncObject) {
			try {
				// block this thread until ComputeSecondaryImg calls syncObject.notify()
				syncObject.wait();
			} catch (final InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	private void updateStatusText(final String newStatus, final boolean includeStatusBar) {
		updateStatusText(newStatus);
		if (includeStatusBar)
			showStatus(newStatus, true);
	}

	private void updateStatusText(final String newStatus) {
		statusText.setText("<html><strong>" + newStatus + "</strong></html>");
	}

	protected void gaussianCalculated(final boolean succeeded) {
		SwingUtilities.invokeLater(() -> {
			changeState(READY);
			showStatus("Gaussian " + ((succeeded) ? " completed" : "failed"), true);
		});
	}

	/**
	 * Updates the dialog, including status bar and 'computation settings' widget.
	 */
	public void refresh() {
		updateSettingsString();
		refreshStatus();
	}

	/**
	 * Sets filters for visibility of paths, as per respective widget in dialog.
	 *
	 * @param filter a reference to the visibility filter checkbox. Either the
	 *               checkbox complete label or relevant keyword, e.g., "selected",
	 *               "Z-slices", "channel", etc. "all" can also be used to toggle all
	 *               checkboxes in the widget
	 * @param state  whether the filter should be active or not.
	 */
	public void setVisibilityFilter(final String filter, final boolean state) {
		assert SwingUtilities.isEventDispatchThread();
		final String normFilter = filter.toLowerCase();
		if (normFilter.contains("selected")) {
			showPathsSelected.setSelected(state);
		} else if (normFilter.contains("z") || normFilter.contains("slices")) {
			partsNearbyCSpinner.getCheckBox().setSelected(state);
		} else if (normFilter.contains("channel") || normFilter.contains("frame")) {
			onlyActiveCTposition.setSelected(state);
		}
	}

	/**
	 * Sets rendering scale of Paths as per respective widget in dialog.
	 * @param scale the scale value (-1 for default scale)
	 */
	public void setRenderingScale(final double scale) {
		plugin.getXYCanvas().setNodeDiameter(scale);
		if (!plugin.getSinglePane()) {
			plugin.getXZCanvas().setNodeDiameter(scale);
			plugin.getZYCanvas().setNodeDiameter(scale);
		}
		plugin.updateTracingViewers(false);
	}

	protected void updateSettingsString() {
		final StringBuilder sb = new StringBuilder();
		sb.append("Auto-tracing: ").append((plugin.isAstarEnabled()) ? searchAlgoChoice.getSelectedItem() : "Disabled");
		sb.append("\n");
		sb.append("    Data structure: ").append(plugin.searchImageType);
		sb.append("\n");
		sb.append("    Cost function: ").append(plugin.getCostType());
		if (plugin.getCostType() == SNT.CostType.PROBABILITY) {
			sb.append("; Z-fudge: ").append(SNTUtils.formatDouble(plugin.getOneMinusErfZFudge(), 3));
		}
		sb.append("\n");
		sb.append("    Min-Max: ").append(SNTUtils.formatDouble(plugin.getStats().min, 3)).append("-")
				.append(SNTUtils.formatDouble(plugin.getStats().max, 3));
		sb.append("\n");
		if (plugin.getSecondaryData() != null) {
			sb.append("Secondary layer: Active");
			sb.append("\n");
			sb.append("    Filter: ")
					.append((plugin.isSecondaryImageFileLoaded()) ? "External" : plugin.getFilterType());
			sb.append("\n");
			sb.append("    Min-Max: ").append(SNTUtils.formatDouble(plugin.getStatsSecondary().min, 3)).append("-")
					.append(SNTUtils.formatDouble(plugin.getStatsSecondary().max, 3));
		} else {
			sb.append("Secondary layer: Disabled");
		}
		assert SwingUtilities.isEventDispatchThread();
		settingsArea.setText(sb.toString());
		settingsArea.setCaretPosition(0);
		if (fmUI != null) fmUI.updateSettingsString();
	}

	protected void exitRequested() {
		assert SwingUtilities.isEventDispatchThread();
		String msg = "Exit SNT?";
		if (plugin.isUnsavedChanges())
			msg = "There are unsaved paths. Do you really want to quit?";
		if (pmUI.measurementsUnsaved())
			msg = "There are unsaved measurements. Do you really want to quit?";
		if (!guiUtils.getConfirmation(msg, "Really Quit?"))
			return;
		commandFinder.dispose();
		abortCurrentOperation();
		plugin.dispose();
		setAutosaveFile(null); // forget last saved file
		plugin.getPrefs().savePluginPrefs(true);
		pmUI.dispose();
		pmUI.closeTable();
		fmUI.dispose();
		if (recViewer != null)
			recViewer.dispose();
		if (recorder != null)
			recorder.dispose();
		dispose();
		// NB: If visible Reconstruction Plotter will remain open
		ImagePlus.removeImageListener(listener);
		plugin = null;
		pathAndFillManager = null;
		guiUtils = null;
		GuiUtils.restoreLookAndFeel();
	}

	private void setEnableAutoTracingComponents(final boolean enable, final boolean enableAstar) {
		GuiUtils.enableComponents(aStarPanel, enableAstar);
		updateSettingsString();
		updateSecLayerWidgets();
	}

	protected void disableImageDependentComponents() {
		assert SwingUtilities.isEventDispatchThread();
		fmUI.setEnabledNone();
		setEnableAutoTracingComponents(false, false);
	}

	private void disableEverything() {
		assert SwingUtilities.isEventDispatchThread();
		disableImageDependentComponents();
		sendToTrakEM2.setEnabled(false);
		saveMenuItem.setEnabled(false);
		quitMenuItem.setEnabled(false);
	}

	private void updateRebuildCanvasButton() {
		final ImagePlus imp = plugin.getImagePlus();
		final String label = (imp == null || imp.getProcessor() == null || plugin.accessToValidImageData()) ? "Create Canvas"
				: "Resize Canvas";
		rebuildCanvasButton.setText(label);
	}

	/**
	 * Changes this UI to a new state. Does nothing if {@code newState} is the
	 * current UI state
	 *
	 * @param newState the new state, e.g., {@link SNTUI#READY},
	 *                 {@link SNTUI#TRACING_PAUSED}, etc.
	 */
	public void changeState(final int newState) {

		if (newState == currentState) return;
		currentState = newState;
		SwingUtilities.invokeLater(() -> {
			switch (newState) {

			case WAITING_TO_START_PATH:
				keepSegment.setEnabled(false);
				junkSegment.setEnabled(false);
				completePath.setEnabled(false);

				partsNearbyCSpinner.setEnabled(isStackAvailable());
				setEnableAutoTracingComponents(plugin.isAstarEnabled(), true);
				fmUI.setEnabledWhileNotFilling();
				saveMenuItem.setEnabled(true);

				exportCSVMenuItem.setEnabled(true);
				sendToTrakEM2.setEnabled(plugin.anyListeners());
				quitMenuItem.setEnabled(true);
				showPathsSelected.setEnabled(true);
				updateStatusText("Click somewhere to start a new path...");
				showOrHideFillList.setEnabled(true);
				updateRebuildCanvasButton();
				break;

			case TRACING_PAUSED:

				keepSegment.setEnabled(false);
				junkSegment.setEnabled(false);
				completePath.setEnabled(false);
				pmUI.valueChanged(null); // Fake a selection change in the path tree:
				partsNearbyCSpinner.setEnabled(isStackAvailable());
				setEnableAutoTracingComponents(false, false);
				plugin.discardFill();
				fmUI.setEnabledWhileNotFilling();
				// setFillListVisible(false);
				saveMenuItem.setEnabled(true);

				exportCSVMenuItem.setEnabled(true);
				sendToTrakEM2.setEnabled(plugin.anyListeners());
				quitMenuItem.setEnabled(true);
				showPathsSelected.setEnabled(true);
				updateRebuildCanvasButton();
				updateStatusText("Tracing functions disabled...");
				break;

			case PARTIAL_PATH:
				updateStatusText("Select a point further along the structure...");
				disableEverything();
				keepSegment.setEnabled(false);
				junkSegment.setEnabled(false);
				completePath.setEnabled(true);
				partsNearbyCSpinner.setEnabled(isStackAvailable());
				setEnableAutoTracingComponents(plugin.isAstarEnabled(), true);
				quitMenuItem.setEnabled(false);
				break;

			case SEARCHING:
				updateStatusText("Searching for path between points...");
				disableEverything();
				break;

			case QUERY_KEEP:
				updateStatusText("Keep this new path segment?");
				disableEverything();
				keepSegment.setEnabled(true);
				junkSegment.setEnabled(true);
				break;

			case FILLING_PATHS:
				updateStatusText("Filling out selected paths...");
				disableEverything();
				fmUI.setEnabledWhileFilling();
				break;

			case FITTING_PATHS:
				updateStatusText("Fitting volumes around selected paths...");
				break;

			case RUNNING_CMD:
				updateStatusText("Running Command...");
				disableEverything();
				break;

			case CACHING_DATA:
				updateStatusText("Caching data. This could take a while...");
				disableEverything();
				break;

			case CALCULATING_HESSIAN_I:
				updateStatusText("Calculating Hessian...");
				showStatus("Computing Hessian for main image...", false);
				disableEverything();
				break;

			case CALCULATING_HESSIAN_II:
				updateStatusText("Calculating Hessian (II Image)..");
				showStatus("Computing Hessian (secondary image)...", false);
				disableEverything();
				break;

			case WAITING_FOR_SIGMA_POINT_I:
				updateStatusText("Click on a representative structure...");
				showStatus("Adjusting Hessian (main image)...", false);
				//disableEverything();
				break;

			case WAITING_FOR_SIGMA_CHOICE:
				updateStatusText("Close 'Pick Sigma &amp; Max' to continue...");
				//disableEverything();
				break;

			case LOADING:
				updateStatusText("Loading...");
				disableEverything();
				break;

			case SAVING:
				updateStatusText("Saving...");
				disableEverything();
				break;

			case EDITING:
				if (noPathsError())
					return;
				plugin.setCanvasLabelAllPanes(InteractiveTracerCanvas.EDIT_MODE_LABEL);
				updateStatusText("Editing Mode. Tracing functions disabled...");
				disableEverything();
				keepSegment.setEnabled(false);
				junkSegment.setEnabled(false);
				completePath.setEnabled(false);
				partsNearbyCSpinner.setEnabled(isStackAvailable());
				setEnableAutoTracingComponents(false, false);
				getFillManager().setVisible(false);
				showOrHideFillList.setEnabled(false);
				break;

			case SNT_PAUSED:
				updateStatusText("SNT is paused. Core functions disabled...");
				disableEverything();
				keepSegment.setEnabled(false);
				junkSegment.setEnabled(false);
				completePath.setEnabled(false);
				partsNearbyCSpinner.setEnabled(isStackAvailable());
				setEnableAutoTracingComponents(false, false);
				getFillManager().setVisible(false);
				showOrHideFillList.setEnabled(false);
				break;

			default:
				SNTUtils.error("BUG: switching to an unknown state");
				return;
			}
			SNTUtils.log("UI state: " + getState(currentState));
			plugin.updateTracingViewers(true);
		});

	}

	protected void resetState() {
		plugin.pauseTracing(!plugin.accessToValidImageData() || plugin.tracingHalted, false); // will set UI state
	}

	public void error(final String msg) {
		plugin.error(msg);
	}

	public void showMessage(final String msg, final String title) {
		plugin.showMessage(msg, title);
	}

	private boolean isStackAvailable() {
		return plugin != null && !plugin.is2D();
	}

	/* User inputs for multidimensional images */
	private JPanel sourcePanel(final ImagePlus imp) {
		final JPanel sourcePanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = GuiUtils.defaultGbc();
		gdb.gridwidth = 1;
		final boolean hasChannels = imp != null && imp.getNChannels() > 1;
		final boolean hasFrames = imp != null && imp.getNFrames() > 1;
		final JPanel positionPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
		positionPanel.add(GuiUtils.leftAlignedLabel("Channel", true));
		final JSpinner channelSpinner = GuiUtils.integerSpinner(plugin.channel, 1,
				(hasChannels) ? imp.getNChannels() : 1, 1, true);
		positionPanel.add(channelSpinner);
		positionPanel.add(GuiUtils.leftAlignedLabel(" Frame", true));
		final JSpinner frameSpinner = GuiUtils.integerSpinner(plugin.frame, 1, (hasFrames) ? imp.getNFrames() : 1, 1, true);
		positionPanel.add(frameSpinner);
		final JButton applyPositionButton = new JButton("Reload");
		registerInCommandFinder(applyPositionButton, null, "Main Tab");
		final ChangeListener spinnerListener = e -> applyPositionButton.setText(
				((int) channelSpinner.getValue() == plugin.channel && (int) frameSpinner.getValue() == plugin.frame)
						? "Reload"
						: "Apply");
		channelSpinner.addChangeListener(spinnerListener);
		frameSpinner.addChangeListener(spinnerListener);
		channelSpinner.setEnabled(hasChannels);
		frameSpinner.setEnabled(hasFrames);
		applyPositionButton.addActionListener(e -> {
			if (!plugin.accessToValidImageData()) {
				guiUtils.error("There is no valid image data to be loaded.");
				return;
			}
			if (imgDimensionsChanged() && guiUtils.getConfirmation(
					"Image properties seem to have changed significantly since it was last imported. "
							+ "You may need to re-initialize SNT to avoid conflicts with cached properties. Re-initialize now?",
					"Modified Image", "Yes. Re-inialize", "No. Attempt Reloading")) {
				plugin.initialize(plugin.getImagePlus());
				return;
			}
			final int newC = (int) channelSpinner.getValue();
			final int newT = (int) frameSpinner.getValue();
			loadImagefromGUI(newC, newT);
		});
		positionPanel.add(applyPositionButton);
		sourcePanel.add(positionPanel, gdb);
		return sourcePanel;
	}

	private boolean imgDimensionsChanged() {
		final ImagePlus imp = plugin.getImagePlus();
		if (imp.getWidth() != plugin.getWidth() || plugin.getHeight() != imp.getHeight()
				|| plugin.getDepth() != imp.getNSlices() || plugin.getChannel() > imp.getNChannels()
				|| plugin.getFrame() > imp.getNFrames())
			return true;
		final Calibration cal = plugin.getImagePlus().getCalibration();
		return (plugin.getPixelWidth() != cal.pixelWidth || plugin.getPixelHeight() != cal.pixelHeight
				|| plugin.getPixelDepth() != cal.pixelDepth);
	}

	protected void loadImagefromGUI(final int newC, final int newT) {
		final boolean reload = newC == plugin.channel && newT == plugin.frame;
		if (!reload && askUserConfirmation
				&& !guiUtils
				.getConfirmation(
						"You are currently tracing position C=" + plugin.channel + ", T=" + plugin.frame
						+ ". Start tracing C=" + newC + ", T=" + newT + "?",
						"Change Hyperstack Position?")) {
			return;
		}
		// take this opportunity to update 3-pane status
		updateSinglePaneFlag();
		abortCurrentOperation();
		changeState(LOADING);
		plugin.reloadImage(newC, newT); // nullifies hessianData
		if (!reload)
			plugin.getImagePlus().setPosition(newC, plugin.getImagePlus().getZ(), newT);
		plugin.showMIPOverlays(0);
		if (plugin.isSecondaryDataAvailable()) {
			flushSecondaryDataPrompt();
		}
		resetState();
		showStatus(reload ? "Image reloaded into memory..." : null, true);
	}

	private JPanel viewsPanel() {
		final JPanel viewsPanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = GuiUtils.defaultGbc();
		gdb.gridwidth = 1;

		final CheckboxSpinner mipCS = new CheckboxSpinner(new JCheckBox("Overlay MIP(s) at"),
				GuiUtils.integerSpinner(20, 10, 80, 1, true));
		registerInCommandFinder(mipCS.getCheckBox(), "Overlay MIP(s)", "Options Tab");
		mipCS.getSpinner().addChangeListener(e -> mipCS.setSelected(false));
		mipCS.appendLabel(" % opacity");
		mipCS.getCheckBox().addActionListener(e -> {
			if (!plugin.accessToValidImageData()) {
				noValidImageDataError();
				mipCS.setSelected(false);
			} else if (plugin.is2D()) {
				guiUtils.error(plugin.getImagePlus().getTitle() + " has no depth. Cannot generate projection.");
				mipCS.setSelected(false);
			} else {
				plugin.showMIPOverlays(false, (mipCS.isSelected()) ? (int) mipCS.getValue() * 0.01 : 0);
			}
		});
		viewsPanel.add(mipCS, gdb);
		++gdb.gridy;

		final JCheckBox zoomAllPanesCheckBox = new JCheckBox("Apply zoom changes to all views",
				!plugin.isZoomAllPanesDisabled());
		zoomAllPanesCheckBox
				.addItemListener(e -> plugin.disableZoomAllPanes(e.getStateChange() == ItemEvent.DESELECTED));
		viewsPanel.add(zoomAllPanesCheckBox, gdb);
		++gdb.gridy;

		final String bLabel = (plugin.getSinglePane()) ? "Display" : "Rebuild";
		final JButton refreshPanesButton = new JButton(bLabel + " ZY/XZ Views");
		registerInCommandFinder(refreshPanesButton, "Display/Rebuild ZY/XZ Views", "Options Tab");
		refreshPanesButton.addActionListener(e -> {
			final boolean noImageData = !plugin.accessToValidImageData();
			if (noImageData && pathAndFillManager.size() == 0) {
				guiUtils.error("No paths exist to compute side-view canvases.");
				return;
			}
			if (plugin.getImagePlus() == null) {
				guiUtils.error("There is no loaded image. Please load one or create a display canvas.",
						"No Canvas Exist");
				return;
			}
			if (plugin.is2D()) {
				guiUtils.error(plugin.getImagePlus().getTitle() + " has no depth. Cannot generate side views!");
				return;
			}
			showStatus("Rebuilding ZY/XZ views...", false);
			changeState(LOADING);
			try {
				plugin.setSinglePane(false);
				plugin.rebuildZYXZpanes();
				arrangeCanvases(false);
				showStatus("ZY/XZ views reloaded...", true);
				refreshPanesButton.setText("Rebuild ZY/XZ views");
			} catch (final Throwable t) {
				if (t instanceof OutOfMemoryError) {
					guiUtils.error("Out of Memory: There is not enough RAM to load side views!");
				} else {
					guiUtils.error("An error occured. See Console for details.");
					t.printStackTrace();
				}
				plugin.setSinglePane(true);
				if (noImageData) {
					plugin.rebuildDisplayCanvases();
					arrangeCanvases(false);
				}
				showStatus("Out of memory error...", true);
			} finally {
				resetState();
			}
		});

		rebuildCanvasButton = new JButton();
		registerInCommandFinder(rebuildCanvasButton, "Create/Rebuild Canvas", "Options Tab");
		updateRebuildCanvasButton();
		rebuildCanvasButton.addActionListener(e -> {
			if (pathAndFillManager.size() == 0) {
				guiUtils.error("No paths exist to compute a display canvas.");
				return;
			}

			String msg = "";
			if (plugin.accessToValidImageData()) {
				msg = "Replace current image with a display canvas and ";
			} else if (plugin.getPrefs().getTemp(SNTPrefs.NO_IMAGE_ASSOCIATED_DATA, false)) {
				msg = "You have loaded paths without loading an image.";
			} else if (!plugin.getPathAndFillManager().allPathsShareSameSpatialCalibration())
				msg = "You seem to have loaded paths associated with images with conflicting spatial calibration.";
			if (!msg.isEmpty()) {
				resetPathSpacings(msg);
			}

			if (!plugin.accessToValidImageData()) {
				// depending on what the user chose in the resetPathSpacings() prompt
				// we need to check again if the plugin has access to a valid image
				changeState(LOADING);
				showStatus("Resizing Canvas...", false);
				updateSinglePaneFlag();
				plugin.rebuildDisplayCanvases(); // will change UI state
				arrangeCanvases(false);
				showStatus("Canvas rebuilt...", true);
			}
		});

		final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 2, 0));
		buttonPanel.add(rebuildCanvasButton);
		buttonPanel.add(refreshPanesButton);
		gdb.fill = GridBagConstraints.NONE;
		viewsPanel.add(buttonPanel, gdb);
		return viewsPanel;
	}

	private boolean resetPathSpacings(final String promptReason) {
		final boolean nag = plugin.getPrefs().getTemp("pathscaling-nag", true);
		boolean reset = plugin.getPrefs().getTemp("pathscaling", true);
		if (nag) {
			final boolean[] options = guiUtils.getPersistentConfirmation(promptReason //
					+ " Reset spatial calibration of paths?<br>" //
					+ "This will force paths and display canvas(es) to have unitary spacing (e.g.,"//
					+ "1px&rarr;1" + GuiUtils.micrometer() + "). Path lengths will be preserved.",//
					"Reset Path Calibrations?");
			plugin.getPrefs().setTemp("pathscaling", reset = options[0]);
			plugin.getPrefs().setTemp("pathscaling-nag", !options[1]);
		}
		if (reset) {
			if (plugin.accessToValidImageData()) {
				plugin.getImagePlus().close(); 
				if (plugin.getImagePlus() != null) {
					// user canceled the "save changes" dialog
					return false;
				}
				plugin.closeAndResetAllPanes();
				plugin.tracingHalted = true;
			}
			plugin.getPathAndFillManager().resetSpatialSettings(true);
		}
		return reset;
	}

	private void validateImgDimensions() {
		if (plugin.getPrefs().getTemp(SNTPrefs.RESIZE_REQUIRED, false)) {
			final boolean nag = plugin.getPrefs().getTemp("canvasResize-nag", true);
			if (nag) {
				final StringBuilder sb = new StringBuilder("Some nodes are being displayed outside the image canvas. To visualize them you can:<ul>");
				String type = "canvas";
				if (plugin.accessToValidImageData()) {
					type = "image";
					sb.append("<li>Use IJ's command Image&rarr;Adjust&rarr;Canvas Size... and press <i>Reload</i> in the Data Source widget of the Options pane</li>");
					sb.append("<li>Close the current image and create a Display Canvas using the <i>Create Canvas</i> command in the Options pane</li>");
				}
				else {
					sb.append("<li>Use the <i>Create/Resize Canvas</i> commands in the Options pane</li>");
				}
				sb.append("<li>Replace the current ").append(type).append(" using File&rarr;Choose Tracing Image...</li>");
				final Boolean userPrompt = guiUtils.getPersistentWarning(sb.toString(), "Image Needs Resizing");
				if (userPrompt != null) // do nothing if user dismissed the dialog
					plugin.getPrefs().setTemp("canvasResize-nag", !userPrompt.booleanValue());
			} else {
				showStatus("Some nodes rendered outside image!", false);
			}
		}
	}

	private void updateSinglePaneFlag() {
		if (plugin.getImagePlus(MultiDThreePanes.XZ_PLANE) == null
				&& plugin.getImagePlus(MultiDThreePanes.ZY_PLANE) == null)
			plugin.setSinglePane(true);
	}

	private JPanel tracingPanel() {
		final JPanel tPanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = GuiUtils.defaultGbc();

		final JCheckBox confirmTemporarySegmentsCheckbox = new JCheckBox("Confirm temporary segments",
				confirmTemporarySegments);
		registerInCommandFinder(confirmTemporarySegmentsCheckbox, null, "Options Tab");
		tPanel.add(confirmTemporarySegmentsCheckbox, gdb);
		++gdb.gridy;

		final JCheckBox confirmCheckbox = new JCheckBox("Pressing 'Y' twice finishes path", finishOnDoubleConfimation);
		final JCheckBox finishCheckbox = new JCheckBox("Pressing 'N' twice cancels path", discardOnDoubleCancellation);
		confirmTemporarySegmentsCheckbox.addItemListener(e -> {
			confirmTemporarySegments = (e.getStateChange() == ItemEvent.SELECTED);
			confirmCheckbox.setEnabled(confirmTemporarySegments);
			finishCheckbox.setEnabled(confirmTemporarySegments);
		});

		confirmCheckbox.addItemListener(e -> finishOnDoubleConfimation = (e.getStateChange() == ItemEvent.SELECTED));
		confirmCheckbox.addItemListener(e -> discardOnDoubleCancellation = (e.getStateChange() == ItemEvent.SELECTED));
		gdb.insets.left = (int) new JCheckBox("").getPreferredSize().getWidth();
		tPanel.add(confirmCheckbox, gdb);
		++gdb.gridy;
		tPanel.add(finishCheckbox, gdb);
		++gdb.gridy;
		gdb.insets.left = 0;

		final JCheckBox activateFinishedPathCheckbox = new JCheckBox("Finishing a path selects it",
				plugin.activateFinishedPath);
		registerInCommandFinder(activateFinishedPathCheckbox, null, "Options Tab");
		GuiUtils.addTooltip(activateFinishedPathCheckbox, "Whether the path being traced should automatically be selected once finished.");
		activateFinishedPathCheckbox.addItemListener(e -> plugin.enableAutoSelectionOfFinishedPath(e.getStateChange() == ItemEvent.SELECTED));
		tPanel.add(activateFinishedPathCheckbox, gdb);
		++gdb.gridy;

		final JCheckBox requireShiftToForkCheckbox = new JCheckBox("Require 'Shift' to branch off a path", plugin.requireShiftToFork);
		GuiUtils.addTooltip(requireShiftToForkCheckbox, "When branching off a path: Use Shift+Alt+click or Alt+click at the forking node? "
				+ "NB: Alt+click is a common trigger for window dragging on Linux. Use Super+Alt+click to circumvent OS conflics.");
		requireShiftToForkCheckbox.addItemListener(e ->plugin.requireShiftToFork = e.getStateChange() == ItemEvent.SELECTED);
		tPanel.add(requireShiftToForkCheckbox, gdb);
		return tPanel;

	}

	private JPanel pathOptionsPanel() {
		final JPanel intPanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = GuiUtils.defaultGbc();
		final JCheckBox diametersCheckBox = new JCheckBox("Draw diameters", plugin.getDrawDiameters());
		registerInCommandFinder(diametersCheckBox, null, "Options Tab");
		diametersCheckBox.addItemListener(e -> plugin.setDrawDiameters(e.getStateChange() == ItemEvent.SELECTED));
		intPanel.add(diametersCheckBox, gdb);
		++gdb.gridy;
		intPanel.add(nodePanel(), gdb);
		++gdb.gridy;
		intPanel.add(transparencyDefPanel(), gdb);
		++gdb.gridy;
		intPanel.add(transparencyOutOfBoundsPanel(), gdb);
		return intPanel;
	}

	private JPanel nodePanel() {
		final JSpinner nodeSpinner = GuiUtils.doubleSpinner((plugin.getXYCanvas() == null) ? 1 : plugin.getXYCanvas().nodeDiameter(), 0.5, 100, .5, 1);
		 ((JSpinner.DefaultEditor)nodeSpinner.getEditor()).getTextField().addFocusListener(new FocusAdapter() {
			@Override
			public void focusGained(final FocusEvent e) {
				// sync value in case it has been changed by script via #setRenderingScale
				nodeSpinner.setValue(plugin.getXYCanvas().nodeDiameter());
			}
			@Override
			public void focusLost(final FocusEvent e) {
				if (recorder != null)
					recorder.recordCmd("snt.getUI().setRenderingScale("
							+ SNTUtils.formatDouble((double) nodeSpinner.getValue(), 1) + ")");
			}

		});
		nodeSpinner.addChangeListener(e -> {
			setRenderingScale((double) nodeSpinner.getValue());
		});
		final JButton defaultsButton = new JButton("Reset");
		defaultsButton.addActionListener(e -> {
			setRenderingScale(-1);
			nodeSpinner.setValue(plugin.getXYCanvas().nodeDiameter());
			if (recorder != null)
				recorder.recordCmd("snt.getUI().setRenderingScale(-1)");
			showStatus("Node scale reset", true);
		});
		final JPanel p = new JPanel();
		p.setLayout(new GridBagLayout());
		final GridBagConstraints c = GuiUtils.defaultGbc();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridx = 0;
		c.gridy = 0;
		c.gridwidth = 3;
		c.ipadx = 0;
		p.add(GuiUtils.leftAlignedLabel("Rendering scale: ", true));
		c.gridx = 1;
		p.add(nodeSpinner, c);
		c.fill = GridBagConstraints.NONE;
		c.gridx = 2;
		p.add(defaultsButton);
		GuiUtils.addTooltip(p, "The scaling factor for path nodes");
		return p;
	}

	private JPanel transparencyDefPanel() {
		final JSpinner defTransparencySpinner = GuiUtils.integerSpinner(
				(plugin.getXYCanvas() == null) ? 100 : plugin.getXYCanvas().getDefaultTransparency(), 0, 100, 1, true);
		defTransparencySpinner.addChangeListener(e -> {
			setDefaultTransparency((int)(defTransparencySpinner.getValue()));
		});
		final JButton defTransparencyButton = new JButton("Reset");
		defTransparencyButton.addActionListener(e -> {
			setDefaultTransparency(100);
			defTransparencySpinner.setValue(100);
			showStatus("Default transparency reset", true);
		});

		final JPanel p = new JPanel();
		p.setLayout(new GridBagLayout());
		final GridBagConstraints c = GuiUtils.defaultGbc();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridx = 0;
		c.gridy = 0;
		c.gridwidth = 3;
		c.ipadx = 0;
		p.add(GuiUtils.leftAlignedLabel("Centerline opacity (%): ", true));
		c.gridx = 1;
		p.add(defTransparencySpinner, c);
		c.fill = GridBagConstraints.NONE;
		c.gridx = 2;
		p.add(defTransparencyButton);
		GuiUtils.addTooltip(p, "Rendering opacity (0-100%) for diameters and segments connecting path nodes");
		return p;
	}

	private JPanel transparencyOutOfBoundsPanel() {
		final JSpinner transparencyOutOfBoundsSpinner = GuiUtils.integerSpinner(
				(plugin.getXYCanvas() == null) ? 100 : plugin.getXYCanvas().getOutOfBoundsTransparency(), 0, 100, 1,
				true);
		transparencyOutOfBoundsSpinner.addChangeListener(e -> {
			setOutOfBoundsTransparency((int)(transparencyOutOfBoundsSpinner.getValue()));
		});
		final JButton defaultOutOfBoundsButton = new JButton("Reset");
		defaultOutOfBoundsButton.addActionListener(e -> {
			setOutOfBoundsTransparency(50);
			transparencyOutOfBoundsSpinner.setValue(50);
			showStatus("Default transparency reset", true);
		});

		final JPanel p = new JPanel();
		p.setLayout(new GridBagLayout());
		final GridBagConstraints c = GuiUtils.defaultGbc();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridx = 0;
		c.gridy = 0;
		c.gridwidth = 3;
		c.ipadx = 0;
		p.add(GuiUtils.leftAlignedLabel("Out-of-plane opacity (%): ", true));
		c.gridx = 1;
		p.add(transparencyOutOfBoundsSpinner, c);
		c.fill = GridBagConstraints.NONE;
		c.gridx = 2;
		p.add(defaultOutOfBoundsButton);
		GuiUtils.addTooltip(p, "The opacity (0-100%) of path segments that are out-of-plane. "
				+ "Only considered when tracing 3D images and the visibility filter is "
				+ "<i>Only nodes within # nearby Z-slices</i>");
		return p;
	}

	private void setDefaultTransparency(final int value) {
		plugin.getXYCanvas().setDefaultTransparency(value);
		if (!plugin.getSinglePane()) {
			plugin.getXZCanvas().setDefaultTransparency(value);
			plugin.getZYCanvas().setDefaultTransparency(value);
		}
		plugin.updateTracingViewers(false);
	}

	private void setOutOfBoundsTransparency(final int value) {
		plugin.getXYCanvas().setOutOfBoundsTransparency(value);
		if (!plugin.getSinglePane()) {
			plugin.getXZCanvas().setOutOfBoundsTransparency(value);
			plugin.getZYCanvas().setOutOfBoundsTransparency(value);
		}
		plugin.updateTracingViewers(false);
	}

	private JPanel extraColorsPanel() {

		final LinkedHashMap<String, Color> hm = new LinkedHashMap<>();
		final InteractiveTracerCanvas canvas = plugin.getXYCanvas();
		hm.put("Canvas annotations", (canvas == null) ? null : canvas.getAnnotationsColor());
		hm.put("Fills", (canvas == null) ? null : canvas.getFillColor());
		hm.put("Temporary paths", (canvas == null) ? null : canvas.getTemporaryPathColor());
		hm.put("Unconfirmed paths", (canvas == null) ? null : canvas.getUnconfirmedPathColor());

		final JComboBox<String> colorChoice = new JComboBox<>();
		for (final Entry<String, Color> entry : hm.entrySet())
			colorChoice.addItem(entry.getKey());

		final String selectedKey = String.valueOf(colorChoice.getSelectedItem());
		final ColorChooserButton cChooser = new ColorChooserButton(hm.get(selectedKey), "Change", 1,
				SwingConstants.RIGHT);
		cChooser.setPreferredSize(new Dimension(cChooser.getPreferredSize().width, colorChoice.getPreferredSize().height));
		cChooser.setMinimumSize(new Dimension(cChooser.getMinimumSize().width, colorChoice.getMinimumSize().height));
		cChooser.setMaximumSize(new Dimension(cChooser.getMaximumSize().width, colorChoice.getMaximumSize().height));

		colorChoice.addActionListener(
				e -> cChooser.setSelectedColor(hm.get(String.valueOf(colorChoice.getSelectedItem())), false));

		cChooser.addColorChangedListener(newColor -> {
			final String selectedKey1 = String.valueOf(colorChoice.getSelectedItem());
			switch (selectedKey1) {
			case "Canvas annotations":
				plugin.setAnnotationsColorAllPanes(newColor);
				plugin.updateTracingViewers(false);
				break;
			case "Fills":
				plugin.getXYCanvas().setFillColor(newColor);
				if (!plugin.getSinglePane()) {
					plugin.getZYCanvas().setFillColor(newColor);
					plugin.getXZCanvas().setFillColor(newColor);
				}
				plugin.updateTracingViewers(false);
				break;
			case "Unconfirmed paths":
				plugin.getXYCanvas().setUnconfirmedPathColor(newColor);
				if (!plugin.getSinglePane()) {
					plugin.getZYCanvas().setUnconfirmedPathColor(newColor);
					plugin.getXZCanvas().setUnconfirmedPathColor(newColor);
				}
				plugin.updateTracingViewers(true);
				break;
			case "Temporary paths":
				plugin.getXYCanvas().setTemporaryPathColor(newColor);
				if (!plugin.getSinglePane()) {
					plugin.getZYCanvas().setTemporaryPathColor(newColor);
					plugin.getXZCanvas().setTemporaryPathColor(newColor);
				}
				plugin.updateTracingViewers(true);
				break;
			default:
				throw new IllegalArgumentException("Unrecognized option");
			}
		});

		final JPanel p = new JPanel();
		p.setLayout(new GridBagLayout());
		final GridBagConstraints c = GuiUtils.defaultGbc();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridx = 0;
		c.gridy = 0;
		c.gridwidth = 3;
		c.ipadx = 0;
		p.add(GuiUtils.leftAlignedLabel("Colors: ", true));
		c.gridx = 1;
		p.add(colorChoice, c);
		c.fill = GridBagConstraints.NONE;
		c.gridx = 2;
		p.add(cChooser);
		return p;
	}

	private JPanel miscPanel() {
		final JPanel miscPanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = GuiUtils.defaultGbc();
		miscPanel.add(extraColorsPanel(), gdb);
		++gdb.gridy;
		final JCheckBox canvasCheckBox = new JCheckBox("Activate canvas on mouse hovering",
				plugin.autoCanvasActivation);
		GuiUtils.addTooltip(canvasCheckBox, "Whether the image window should be brought to front as soon as the mouse "
				+ "pointer enters it. This may be needed to ensure single key shortcuts work as expected when tracing.");
		canvasCheckBox.addItemListener(e -> plugin.enableAutoActivation(e.getStateChange() == ItemEvent.SELECTED));
		miscPanel.add(canvasCheckBox, gdb);
		++gdb.gridy;
		final JCheckBox askUserConfirmationCheckBox = new JCheckBox("Skip confirmation dialogs", !askUserConfirmation);
		GuiUtils.addTooltip(askUserConfirmationCheckBox,
				"Whether \"Are you sure?\" type of prompts should precede major operations");
		askUserConfirmationCheckBox
				.addItemListener(e -> askUserConfirmation = e.getStateChange() == ItemEvent.DESELECTED);
		miscPanel.add(askUserConfirmationCheckBox, gdb);
		++gdb.gridy;
		debugCheckBox = new JCheckBox("Debug mode", SNTUtils.isDebugMode());
		debugCheckBox.addItemListener(e -> {
			final boolean d = e.getStateChange() == ItemEvent.SELECTED;
			SNTUtils.setDebugMode(d);
			if (recorder != null)
				recorder.recordCmd("snt.getUI().setEnableDebugMode(" + d + ")", true);
		});
		registerInCommandFinder(debugCheckBox,"Toggle Debug Mode", "Options Tab");
		miscPanel.add(debugCheckBox, gdb);
		++gdb.gridy;
		final JButton prefsButton = new JButton("Preferences...");
		prefsButton.addActionListener(e -> {
			(new CmdRunner(PrefsCmd.class)).execute();
		});
		gdb.fill = GridBagConstraints.NONE;
		miscPanel.add(prefsButton, gdb);
		commandFinder.register(prefsButton, "Options tab");
		return miscPanel;
	}

	@SuppressWarnings("deprecation")
	private JPanel legacy3DViewerPanel() throws java.lang.NoClassDefFoundError {

		// Build panel
		final JPanel p = new JPanel();
		p.setLayout(new GridBagLayout());
		final GridBagConstraints c = new GridBagConstraints();
		c.ipadx = 0;
		c.insets = new Insets(0, 0, 0, 0);
		c.anchor = GridBagConstraints.LINE_START;
		c.fill = GridBagConstraints.HORIZONTAL;
	
		if (!GuiUtils.isLegacy3DViewerAvailable()) {
			p.add(new JLabel("Viewer not found in your installation."));
			return p;
		}

		final String VIEWER_NONE = "None";
		final String VIEWER_WITH_IMAGE = "New with image...";
		final String VIEWER_EMPTY = "New without image";

		// Define UI components
		final JComboBox<String> univChoice = new JComboBox<>();
		final JButton applyUnivChoice = new JButton("Apply");
		final JComboBox<String> displayChoice = new JComboBox<>();
		final JButton applyDisplayChoice = new JButton("Apply");
		final JButton refreshList = GuiUtils.smallButton("Refresh List");
		final JComboBox<String> actionChoice = new JComboBox<>();
		final JButton applyActionChoice = new JButton("Apply");

		final LinkedHashMap<String, Image3DUniverse> hm = new LinkedHashMap<>();
		hm.put(VIEWER_NONE, null);
		hm.put(VIEWER_WITH_IMAGE, null);
		hm.put(VIEWER_EMPTY, null);

		/*
		// TF: We'll suppress this chunk as it triggers a GLException when using X11 forwarding.
		// The same functionality can be obtained by pressing the refresh button
		try {
			for (final Image3DUniverse univ : Image3DUniverse.universes) {
				hm.put(univ.allContentsString(), univ);
			}
		} catch (final Exception ex) {
			SNTUtils.error("Legacy Image3DUniverse unavailable?", ex);
			hm.put("Initialization Error...", null);
		}
		 */

		// Build choices widget for viewers
		univChoice.setPrototypeDisplayValue(VIEWER_WITH_IMAGE);
		for (final Entry<String, Image3DUniverse> entry : hm.entrySet()) {
			univChoice.addItem(entry.getKey());
		}
		univChoice.addActionListener(e -> {
			final boolean none = VIEWER_NONE.equals(String.valueOf(univChoice.getSelectedItem()))
					|| String.valueOf(univChoice.getSelectedItem()).endsWith("Error...");
			applyUnivChoice.setEnabled(!none);
		});
		applyUnivChoice.addActionListener(new ActionListener() {

			private void resetChoice() {
				try {
					univChoice.setSelectedItem(plugin.get3DUniverse().getWindow().getTitle());
				} catch (final Throwable ignored) {
					univChoice.setSelectedItem(VIEWER_NONE);
				}
				final boolean validViewer = plugin.use3DViewer && plugin.get3DUniverse() != null;
				displayChoice.setEnabled(validViewer);
				applyDisplayChoice.setEnabled(validViewer);
				actionChoice.setEnabled(validViewer);
				applyActionChoice.setEnabled(validViewer);
			}

			@Override
			public void actionPerformed(final ActionEvent e) {

				assert SwingUtilities.isEventDispatchThread();
				try {

					applyUnivChoice.setEnabled(false);
					final String selectedKey = String.valueOf(univChoice.getSelectedItem());
					if (VIEWER_NONE.equals(selectedKey)) {
						plugin.set3DUniverse(null);
						resetChoice();
						return;
					}

					Image3DUniverse univ;
					univ = hm.get(selectedKey);
					if (univ == null) {

						// Presumably a new viewer was chosen. Let's double-check
						final boolean newViewer = selectedKey.equals(VIEWER_WITH_IMAGE)
								|| selectedKey.equals(VIEWER_EMPTY);
						if (!newViewer && !guiUtils.getConfirmation(
								"The chosen viewer does not seem to be available. Create a new one?",
								"Viewer Unavailable")) {
							resetChoice();
							return;
						}
						univ = new Image3DUniverse(512, 512);
					}
					// If other viewers have been set(!), remember their 'identifying' suffix,
					// hopefully unique
					int idSuffix;
					try {
						idSuffix = Integer
								.parseInt(plugin.get3DUniverse().getWindow().getTitle().split("Viewer #")[1].trim());
					} catch (final Exception ignored) {
						idSuffix = 0;
					}

					if (VIEWER_WITH_IMAGE.equals(selectedKey)) {
						if (null == plugin.getImagePlus()) {
							guiUtils.error("There is no valid image data to initialize the viewer with.");
							resetChoice();
							return;
						}
						final int defResFactor = Content.getDefaultResamplingFactor(plugin.getImagePlus(),
								ContentConstants.VOLUME);
						final Double userResFactor = guiUtils.getDouble(
								"Please specify the image resampling factor. The default factor for current image is "
										+ defResFactor + ".",
								"Image Resampling Factor", defResFactor);

						if (userResFactor == null) { // user pressed cancel
							plugin.set3DUniverse(null);
							resetChoice();
							return;
						}
						final int resFactor = (Double.isNaN(userResFactor) || userResFactor < 1) ? defResFactor
								: userResFactor.intValue();
						plugin.getPrefs().set3DViewerResamplingFactor(resFactor);

					}
					ImageWindow3D window = univ.getWindow();
					if (univ.getWindow() == null) {
						window = new ImageWindow3D(("SNT Leg. 3D Viewer #" + (idSuffix + 1)), univ);
						window.setSize(512, 512);
						try {
							univ.init(window);
						} catch (final Throwable ignored) {
							// see https://github.com/morphonets/SNT/issues/136
							guiUtils.error(
									"An exception occured. Viewer may not be functional. Please consider using previous viewers.");
						}
					} else {
						univ.resetView();
					}

					window.addWindowListener(new WindowAdapter() {

						@Override
						public void windowClosed(final WindowEvent e) {
							univChoice.removeItem(((ImageWindow3D) e.getWindow()).getTitle());
							resetChoice();
						}
					});

					new QueueJumpingKeyListener(plugin, univ);

					// these calls must occur after proper ImageWindow3D initialization
					window.setVisible(true);
					plugin.set3DUniverse(univ);
					if (VIEWER_WITH_IMAGE.equals(selectedKey))
						plugin.updateImageContent(plugin.getPrefs().get3DViewerResamplingFactor());

					refreshList.doClick();
					showStatus("3D Viewer enabled: " + selectedKey, true);

				} catch (final Throwable ex) {
					guiUtils.error("An error occured. Legacy 3D viewer may not be available. See Console for details.");
					ex.printStackTrace();
				} finally {
					resetChoice();
				}
			}
		});

		// Build widget for rendering choices
		displayChoice.addItem("Lines and discs");
		displayChoice.addItem("Lines");
		displayChoice.addItem("Surface reconstructions");
		applyDisplayChoice.addActionListener(e -> {

			switch (String.valueOf(displayChoice.getSelectedItem())) {
			case "Lines":
				plugin.setPaths3DDisplay(SNT.DISPLAY_PATHS_LINES);
				break;
			case "Lines and discs":
				plugin.setPaths3DDisplay(SNT.DISPLAY_PATHS_LINES_AND_DISCS);
				break;
			default:
				plugin.setPaths3DDisplay(SNT.DISPLAY_PATHS_SURFACE);
				break;
			}
		});

		// Build refresh button
		refreshList.addActionListener(e -> {
			try {
				for (final Image3DUniverse univ : Image3DUniverse.universes) {
					final ImageWindow3D iw3d = univ.getWindow();
					if (iw3d == null || hm.containsKey(iw3d.getTitle()))
						continue;
					hm.put(iw3d.getTitle(), univ);
					univChoice.addItem(iw3d.getTitle());
				}
			} catch (final Throwable ex) {
				guiUtils.error("An error occured. Legacy 3D viewer may not be available. See Console for details.");
				ex.printStackTrace();
			}
			showStatus("Viewers list updated...", true);
		});

		// Build actions
		class ApplyLabelsAction extends AbstractAction {

			static final String LABEL = "Apply Color Labels...";

			@Override
			public void actionPerformed(final ActionEvent e) {
				final File imageFile = openReconstructionFile("labels");
				if (imageFile == null)
					return; // user pressed cancel
				try {
					plugin.statusService.showStatus(("Loading " + imageFile.getName()));
					final Dataset ds = plugin.datasetIOService.open(imageFile.getAbsolutePath());
					final ImagePlus colorImp = plugin.convertService.convert(ds, ImagePlus.class);
					showStatus("Applying color labels...", false);
					plugin.setColorImage(colorImp);
					showStatus("Labels image loaded...", true);

				} catch (final IOException exc) {
					guiUtils.error("Could not open " + imageFile.getAbsolutePath() + ". Maybe it is not a valid image?",
							"IO Error");
					exc.printStackTrace();
					return;
				}
			}
		}

		// Assemble widget for actions
		final String COMPARE_AGAINST = "Compare Reconstructions...";
		actionChoice.addItem(ApplyLabelsAction.LABEL);
		actionChoice.addItem(COMPARE_AGAINST);
		applyActionChoice.addActionListener(new ActionListener() {

			final ActionEvent ev = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, null);

			@Override
			public void actionPerformed(final ActionEvent e) {
				
				if (noPathsError()) return;
				switch (String.valueOf(actionChoice.getSelectedItem())) {
				case ApplyLabelsAction.LABEL:
					new ApplyLabelsAction().actionPerformed(ev);
					break;
				case COMPARE_AGAINST:
					(new CmdRunner(ShowCorrespondencesCmd.class)).execute();
					break;
				default:
					break;
				}
			}
		});

		// Set defaults
		univChoice.setSelectedItem(VIEWER_NONE);
		applyUnivChoice.setEnabled(false);
		displayChoice.setEnabled(false);
		applyDisplayChoice.setEnabled(false);
		actionChoice.setEnabled(false);
		applyActionChoice.setEnabled(false);

		// row 1
		c.gridy = 0;
		c.gridx = 0;
		p.add(GuiUtils.leftAlignedLabel("Viewer: ", true), c);
		c.gridx++;
		c.weightx = 1;
		p.add(univChoice, c);
		c.gridx++;
		c.weightx = 0;
		p.add(applyUnivChoice, c);
		c.gridx++;

		// row 2
		c.gridy++;
		c.gridx = 1;
		c.gridwidth = 1;
		c.anchor = GridBagConstraints.EAST;
		c.fill = GridBagConstraints.NONE;
		p.add(refreshList, c);

		// row 3
		c.gridy++;
		c.gridx = 0;
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.NONE;
		p.add(GuiUtils.leftAlignedLabel("Mode: ", true), c);
		c.gridx++;
		c.fill = GridBagConstraints.HORIZONTAL;
		p.add(displayChoice, c);
		c.gridx++;
		c.fill = GridBagConstraints.NONE;
		p.add(applyDisplayChoice, c);
		c.gridx++;

		// row 4
		c.gridy++;
		c.gridx = 0;
		c.fill = GridBagConstraints.NONE;
		p.add(GuiUtils.leftAlignedLabel("Actions: ", true), c);
		c.gridx++;
		c.fill = GridBagConstraints.HORIZONTAL;
		p.add(actionChoice, c);
		c.gridx++;
		c.fill = GridBagConstraints.NONE;
		p.add(applyActionChoice, c);
		return p;
	}

	private void addSpacer(final JPanel panel, final GridBagConstraints c) {
		// extremely lazy implementation of a vertical spacer
		IntStream.rangeClosed(1, 4).forEach(i -> {
			panel.add(new JPanel(), c);
			c.gridy++;
		});
	}

	private JPanel largeMsg(final String msg) {
		final JTextArea ta = new JTextArea();
		final Font defFont = new JLabel().getFont();
		final Font font = defFont.deriveFont(defFont.getSize() * .85f);
		ta.setBackground(getBackground());
		ta.setEditable(false);
		ta.setMargin(null);
		ta.setColumns(20);
		ta.setBorder(null);
		ta.setAutoscrolls(true);
		ta.setLineWrap(true);
		ta.setWrapStyleWord(true);
		ta.setFocusable(false);
		ta.setText(msg);
		ta.setEnabled(false);
		ta.setFont(font);
		final JPanel p = new JPanel(new BorderLayout());
		p.setBackground(getBackground());
		p.add(ta, BorderLayout.NORTH);
		return p;
	}

	private JPanel reconstructionViewerPanel() {
		InitViewer3DSystemProperties.init(); // needs to be called as early as possible to be effective
		openRecViewer = new JButton("Open Reconstruction Viewer");
		registerInCommandFinder(openRecViewer, null, "3D Tab");
		openRecViewer.addActionListener(e -> {
			// if (noPathsError()) return;
			class RecWorker extends SwingWorker<Boolean, Object> {

				@Override
				protected Boolean doInBackground() {
					try {
						recViewer = new SNTViewer3D();
					} catch (final NoClassDefFoundError | RuntimeException exc) {
						exc.printStackTrace();
						return false;
					}
					if (pathAndFillManager.size() > 0) recViewer.syncPathManagerList();
					recViewerFrame = recViewer.show();
					return true;
				}

				@Override
				protected void done() {
					try {
						if (!get())
							no3DcapabilitiesError("Reconstruction Viewer");
					} catch (final InterruptedException | ExecutionException e) {
						guiUtils.error("Unfortunately an error occured. See Console for details.");
						e.printStackTrace();
					} finally {
						setReconstructionViewer(recViewer);
					}
				}
			}
			if (recViewer == null) {
				new RecWorker().execute();
			} else { // button should be now disable. Code below is moot.
				recViewerFrame.setVisible(true);
				recViewerFrame.toFront();
			}
		});

		// Build panel
		final JPanel panel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = new GridBagConstraints();
		gdb.fill = GridBagConstraints.HORIZONTAL;
		gdb.weightx = 0.5;
		panel.add(openRecViewer, gdb);
		return panel;
	}

	private JPanel sciViewerPanel() {
		openSciView = new JButton("Open sciview");
		registerInCommandFinder(openSciView, null, "3D Tab");
		openSciView.addActionListener(e -> {
			if (!EnableSciViewUpdateSiteCmd.isSciViewAvailable()) {
				final CommandService cmdService = plugin.getContext().getService(CommandService.class);
				cmdService.run(EnableSciViewUpdateSiteCmd.class, true);
				return;
			}
			if (openingSciView && sciViewSNT != null) {
				openingSciView = false;
			}
			try {
				if (!openingSciView && sciViewSNT == null
						|| (sciViewSNT.getSciView() == null || sciViewSNT.getSciView().isClosed())) {
					openingSciView = true;
					new Thread(() -> new SciViewSNT(plugin).getSciView()).start();
				}
			} catch (final Throwable exc) {
				exc.printStackTrace();
				no3DcapabilitiesError("sciview");
			}
		});

		svSyncPathManager = new JButton("Sync Changes");
		registerInCommandFinder(svSyncPathManager, "Sync sciview", "3D Tab");
		svSyncPathManager.setToolTipText("Refreshes Viewer contents to reflect Path Manager changes");
		svSyncPathManager.addActionListener(e -> {
			if (sciViewSNT == null || sciViewSNT.getSciView() == null || sciViewSNT.getSciView().isClosed()) {
				guiUtils.error("sciview is not open.");
				openSciView.setEnabled(true);
			} else {
				sciViewSNT.syncPathManagerList();
				final String msg = (pathAndFillManager.size() == 0) ? "There are no traced paths" : "sciview synchronized";
				showStatus(msg, true);
			}
		});

		// Build panel
		final JPanel panel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = new GridBagConstraints();
		gdb.fill = GridBagConstraints.HORIZONTAL;
		gdb.weightx = 0.5;
		panel.add(openSciView, gdb);
		panel.add(svSyncPathManager, gdb);
		return panel;
	}

	private void no3DcapabilitiesError(final String viewer) {
		SwingUtilities.invokeLater(() -> {
			guiUtils.error(viewer + " could not be initialized. Your installation seems "
				+ "to be missing essential 3D libraries. Please use the updater to install any "
				+ "missing files. See Console for details.", "Error: Dependencies Missing");
		});
	}

	private JPanel statusButtonPanel() {
		keepSegment = GuiUtils.smallButton(InternalUtils.hotKeyLabel("Yes", "Y"));
		keepSegment.addActionListener(listener);
		junkSegment = GuiUtils.smallButton(InternalUtils.hotKeyLabel("No", "N"));
		junkSegment.addActionListener(listener);
		completePath = GuiUtils.smallButton(InternalUtils.hotKeyLabel("Finish", "F"));
		completePath.addActionListener(listener);
		final JButton abortButton = GuiUtils.smallButton(InternalUtils.hotKeyLabel(InternalUtils.hotKeyLabel("Cancel/Esc", "C"), "Esc"));
		abortButton.addActionListener(e -> abortCurrentOperation());

		// Build panel
		return buttonPanel(keepSegment, junkSegment, completePath, abortButton);
	}

	private void registerInCommandFinder(final AbstractButton button, final String recordedString,
			final String... location) {
		if (commandFinder != null) {
			if (recordedString != null)
				button.setActionCommand(recordedString);
			commandFinder.register(button, location);
		}
	}

	protected static JPanel buttonPanel(final JButton... buttons) {
		final JPanel p = new JPanel();
		p.setLayout(new GridBagLayout());
		final GridBagConstraints c = new GridBagConstraints();
		c.ipadx = 0;
		c.insets = new Insets(0, 0, 0, 0);
		c.anchor = GridBagConstraints.LINE_START;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridy = 0;
		c.gridx = 0;
		c.weightx = 0.1;
		for (final JButton button: buttons) {
			p.add(button, c);
			c.gridx++;
		}
		return p;
	}

	private JPanel settingsPanel() {
		final JPanel settingsPanel = new JPanel(new GridBagLayout());
		settingsArea = new JTextArea();
		final JScrollPane sp = new JScrollPane(settingsArea);
		// sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
		settingsArea.setRows(4); // TODO: CHECK this height on all OSes. May be too large for MacOS
		// settingsArea.setEnabled(false);
		settingsArea.setEditable(false);
		settingsArea.setFont(settingsArea.getFont().deriveFont((float) (settingsArea.getFont().getSize() * .85)));
		settingsArea.setFocusable(false);

		final GridBagConstraints c = GuiUtils.defaultGbc();
		GuiUtils.addSeparator(settingsPanel, GuiUtils.leftAlignedLabel("Computation Settings:", true), true, c);
		c.fill = GridBagConstraints.BOTH; // avoid collapsing of panel on resizing
		++c.gridy;
		settingsPanel.add(sp, c);

		final JPopupMenu pMenu = new JPopupMenu();
		JMenuItem mi = new JMenuItem("Copy", IconFactory.getMenuIcon(GLYPH.COPY));
		mi.addActionListener(e -> {
			settingsArea.selectAll();
			settingsArea.copy();
			settingsArea.setCaretPosition(0);
		});
		pMenu.add(mi);
		mi = new JMenuItem("Refresh", IconFactory.getMenuIcon(GLYPH.REDO));
		mi.addActionListener(e -> refresh());
		pMenu.add(mi);
		pMenu.addSeparator();
		mi = new JMenuItem("Log to Script Recorder", IconFactory.getMenuIcon(GLYPH.CODE));
		mi.addActionListener(e -> {
			updateSettingsString();
			getRecorder(true).recordComment("Current Computation Settings:");
			for (final String s : settingsArea.getText().split("\n")) {
				recorder.recordComment("  " + s);
			}
			recorder.recordComment("");
			SwingUtilities.invokeLater(() -> recorder.setVisible(true));
		});
		pMenu.add(mi);
		settingsArea.setComponentPopupMenu(pMenu);

		return settingsPanel;
	}

	private JPanel statusPanel() {
		final JPanel statusPanel = new JPanel();
		statusPanel.setLayout(new BorderLayout());
		statusText = new JLabel("Loading SNT...");
		statusText.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createBevelBorder(BevelBorder.LOWERED),
				BorderFactory.createEmptyBorder(MARGIN, MARGIN, MARGIN, MARGIN)));
		statusPanel.add(statusText, BorderLayout.CENTER);
		statusPanel.add(statusButtonPanel(), BorderLayout.SOUTH);
		statusPanel.setBorder(BorderFactory.createEmptyBorder(MARGIN, MARGIN, MARGIN * MARGIN, MARGIN));
		return statusPanel;
	}

	private JPanel secondaryDataPanel() {

		secLayerActivateCheckbox = new JCheckBox(InternalUtils.hotKeyLabel("Trace/Fill on Secondary Layer", "L"));
		GuiUtils.addTooltip(secLayerActivateCheckbox,
				"Whether auto-tracing should be computed on a filtered flavor of current image");
		secLayerActivateCheckbox.addActionListener(listener);
		registerInCommandFinder(secLayerActivateCheckbox, "Toggle Secondary Layer", "Main Tab");
		// Options for externalImagePanel
		final JPopupMenu secLayerMenu = new JPopupMenu();
		final JButton secLayerActionButton = optionsButton(IconFactory.GLYPH.LAYERS, secLayerMenu);
		GuiUtils.addTooltip(secLayerActionButton, "Actions for handling secondary layer imagery");
		final JMenuItem mi1 = new JMenuItem("Secondary Layer Creation Wizard...",
				IconFactory.getMenuIcon(IconFactory.GLYPH.WIZARD));
		ScriptRecorder.setRecordingCall(mi1, "snt.getUI().runSecondaryLayerWizard()");
		commandFinder.register(mi1, "Main tab", "Auto-tracing (II Layer)");
		mi1.setToolTipText("Create a secondary layer using built-in image processing routines");
		mi1.addActionListener(e -> runSecondaryLayerWizard());
		final JMenuItem mi2 = GuiUtils.MenuItems.fromOpenImage();
		mi2.addActionListener(e -> loadSecondaryImage(true));
		commandFinder.register(mi2, "Main tab", "Auto-tracing (II Layer)");
		final JMenuItem mi3 = GuiUtils.MenuItems.fromFileImage();
		mi3.addActionListener(e -> loadSecondaryImage(false));
		commandFinder.register(mi3, "Main tab", "Auto-tracing (II Layer)");
		final JMenuItem mi4 = new JMenuItem("Flush Current Layer...", IconFactory.getMenuIcon(IconFactory.GLYPH.TOILET));
		registerInCommandFinder(mi4, "Flush Secondary Layer", "Main tab", "Auto-tracing");
		mi4.addActionListener(e -> {
			if (!noSecondaryDataAvailableError()
					&& guiUtils.getConfirmation("Flush secondary layer? (RAM will be released but "
							+ "tracing on secondary layer will be disabled)", "Discard Existing Layer?")) {
				plugin.flushSecondaryData();
			}
		});
		commandFinder.register(mi4, "Main tab", "Auto-tracing (II Layer)");
		final JMenuItem mi5 = new JMenuItem("From Labkit/TWS Model...", IconFactory.getMenuIcon(IconFactory.GLYPH.KIWI_BIRD));
		mi5.addActionListener(e -> {
			if (!okToReplaceSecLayer())
				return;
			if (!plugin.accessToValidImageData()) {
				noValidImageDataError();
				return;
			}
			(new DynamicCmdRunner(WekaModelLoader.class, null)).run();
		});
		commandFinder.register(mi5, "Main tab", "Auto-tracing (II Layer)");
		GuiUtils.addSeparator(secLayerMenu, "Create:");
		secLayerMenu.add(mi1);
		GuiUtils.addSeparator(secLayerMenu, "Load Precomputed:");
		secLayerMenu.add(mi3);
		secLayerMenu.add(mi2);
		GuiUtils.addSeparator(secLayerMenu, "Load from Model:");
		secLayerMenu.add(mi5);
		GuiUtils.addSeparator(secLayerMenu, "Dispose/Disable:");
		secLayerMenu.add(mi4);
		secLayerMenu.addSeparator();
		final JMenuItem mi6 = GuiUtils.menuItemTriggeringHelpURL("Help on Secondary Layers",
				"https://imagej.net/plugins/snt/manual#tracing-on-secondary-image");
		commandFinder.register(mi6, "Main tab", "Auto-tracing (II Layer)");
		secLayerMenu.add(mi6);

		// Assemble panel
		JPanel secLayerPanel = new JPanel();
		secLayerPanel.setLayout(new GridBagLayout());
		final GridBagConstraints c = GuiUtils.defaultGbc();
		c.insets.top = MARGIN * 2; // separator
		c.ipadx = 0;

		// row 1
		c.insets.left = (int) new JCheckBox("").getPreferredSize().getWidth();
		final JPanel row1 = new JPanel(new BorderLayout(0,0));
		row1.add(secLayerActivateCheckbox, BorderLayout.CENTER);
		row1.add(secLayerActionButton, BorderLayout.EAST);
		secLayerPanel.add(row1, c);
		c.gridy++;

		// row 2
		c.insets.left *= 2;
		secLayerImgOverlayCSpinner = new CheckboxSpinner(new JCheckBox("Render in overlay at "),
				GuiUtils.integerSpinner(20, 10, 80, 1, true));
		registerInCommandFinder(secLayerImgOverlayCSpinner.getCheckBox(), "Toggle secondary layer overlay", "Main Tab");
		secLayerImgOverlayCSpinner.getSpinner().addChangeListener(e -> {
			secLayerImgOverlayCSpinner.setSelected(false);
		});
		secLayerImgOverlayCSpinner.getCheckBox().addActionListener(e -> {
			if (!noSecondaryDataAvailableError()) {
				plugin.showMIPOverlays(true,
						(secLayerImgOverlayCSpinner.isSelected())
								? (int) secLayerImgOverlayCSpinner.getValue() * 0.01
								: 0);
			}
		});
		secLayerImgOverlayCSpinner.appendLabel("% opacity");
		secLayerPanel.add(secLayerImgOverlayCSpinner, c);
		c.gridy++;
		return secLayerPanel;
	}

	private void loadSecondaryImage(final boolean fromAlreadyOpenImageOtherwiseFile) {
		if (!plugin.accessToValidImageData()) {
			noValidImageDataError();
			return;
		}
		if (!okToReplaceSecLayer()) 
			return;
		if (fromAlreadyOpenImageOtherwiseFile) {
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("secondaryLayer", true);
			(new DynamicCmdRunner(ChooseDatasetCmd.class, inputs, LOADING)).run();
		} else {
			final File proposedFile = (plugin.getFilteredImageFile() == null) ? plugin.getPrefs().getRecentDir()
					: plugin.getFilteredImageFile();
			final File file = guiUtils.getOpenFile("Choose Secondary Image", proposedFile);
			if (file != null)
				loadSecondaryImageFile(file);
		}
	}

	private void loadSecondaryImageFile(final File imgFile) {
		if (!SNTUtils.fileAvailable(imgFile)) {
			guiUtils.error("Current file path is not valid.");
			return;
		}
		plugin.secondaryImageFile = imgFile;
		loadCachedDataImage(true, plugin.secondaryImageFile);
		setFastMarchSearchEnabled(plugin.tubularGeodesicsTracingEnabled);
	}

	private JButton optionsButton(final IconFactory.GLYPH glyph, final JPopupMenu optionsMenu) {
		final JButton optionsButton =  IconFactory.getButton(glyph);
		optionsButton.addActionListener(e -> {
			optionsMenu.show(optionsButton, optionsButton.getWidth() / 2, optionsButton.getHeight() / 2);
		});
		return optionsButton;
	}

	void disableSecondaryLayerComponents() {
		assert SwingUtilities.isEventDispatchThread();

		setSecondaryLayerTracingSelected(false);
		if (plugin.tubularGeodesicsTracingEnabled) {
			setFastMarchSearchEnabled(false);
		}
		if (secLayerImgOverlayCSpinner.getCheckBox().isSelected()) {
			secLayerImgOverlayCSpinner.getCheckBox().setSelected(false);
			plugin.showMIPOverlays(true, 0);
		}
		updateSecLayerWidgets();
	}

	protected File openReconstructionFile(final String extension) {
		final String suggestFilename = (plugin.accessToValidImageData()) ? plugin.getImagePlus().getTitle() : "SNT_Data";
		final File suggestedFile = SNTUtils.findClosestPair(new File(plugin.getPrefs().getRecentDir(), suggestFilename), extension);
		return guiUtils.getReconstructionFile(suggestedFile, extension);
	}

	private File getProposedSavingFile(final String extensionWithoutDot) {
		String filename;
		if (plugin.accessToValidImageData())
			filename = SNTUtils.stripExtension(plugin.getImagePlus().getShortTitle());
		else
			filename = "SNT_Data";
		filename += "." + extensionWithoutDot;
		return new File(plugin.getPrefs().getRecentDir(), filename);
	}

	protected File saveFile(final String promptMsg, final String suggestedFileName, final String extensionWithoutDot) {
		final File fFile = (suggestedFileName == null) ? getProposedSavingFile(extensionWithoutDot)
				: new File(plugin.getPrefs().getRecentDir(), suggestedFileName);
		return guiUtils.getSaveFile(promptMsg, fFile, extensionWithoutDot);
	}

	private void loadCachedDataImage(final boolean warnUserOnMemory,
									 final File file) { // FIXME: THIS is likely all outdated now
		if (file == null) {
			throw new IllegalArgumentException("Secondary image File is null");
		}
		if (warnUserOnMemory && plugin.getImagePlus() != null) {
			final int byteDepth = 32 / 8;
			final ImagePlus tracingImp = plugin.getImagePlus();
			final long megaBytesExtra = (((long) tracingImp.getWidth()) * tracingImp.getHeight()
					* tracingImp.getNSlices() * byteDepth * 2) / (1024 * 1024);
			final long maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024);
			if (megaBytesExtra > 0.8 * maxMemory && !guiUtils.getConfirmation( //
					"Loading an extra image will likely require " + megaBytesExtra + "MiB of " //
							+ "RAM. Currently only " + maxMemory + " MiB are available. " //
							+ "Consider enabling real-time processing.",
					"Confirm Loading?")) {
				return;
			}
		}
		loadImageData(file);
	}

	private void loadImageData(final File file) {

		showStatus("Loading image. Please wait...", false);
		changeState(CACHING_DATA);
		activeWorker = new ActiveWorker() {

			@Override
			protected String doInBackground() {

				try {
					plugin.loadSecondaryImage(file);
				} catch (final IllegalArgumentException | NullPointerException e1) {
					return ("Could not load " + file.getAbsolutePath() + ":<br>"
							+ e1.getMessage());
				} catch (final IOException e2) {
					e2.printStackTrace();
					return ("Loading of image failed. See Console for details.");
				} catch (final OutOfMemoryError e3) {
					e3.printStackTrace();
					return ("It seems there is not enough memory to proceed. See Console for details.");
				} catch (final Exception e4) {
					e4.printStackTrace();
					return ("Un unknown error occurred. See Console for details.");
				}
				return null;
			}

			private void flushData() {
				plugin.flushSecondaryData();
			}

			@Override
			public boolean kill() {
				flushData();
				return cancel(true);
			}

			@Override
			protected void done() {
				try {
					final String errorMsg = (String) get();
					if (errorMsg != null) {
						guiUtils.error(errorMsg);
						flushData();
					}
				} catch (InterruptedException | ExecutionException e) {
					SNTUtils.error("ActiveWorker failure", e);
				}
				updateSecLayerWidgets();
				resetState();
				showStatus(null, false);
			}
		};
		activeWorker.run();
	}

	protected void updateSecLayerWidgets() {
		SwingUtilities.invokeLater(() -> {
			secLayerActivateCheckbox.setEnabled(plugin.isAstarEnabled() && plugin.isSecondaryDataAvailable());
			secLayerImgOverlayCSpinner.setEnabled(plugin.isAstarEnabled() && plugin.isTracingOnSecondaryImageAvailable());
		});
	}

	@SuppressWarnings("deprecation")
	private JMenuBar createMenuBar() {
		final JMenuBar menuBar = new JMenuBar();
		final JMenu fileMenu = new JMenu("File");
		menuBar.add(fileMenu);
		final JMenu importSubmenu = new JMenu("Load Tracings");
		importSubmenu.setToolTipText("Import reconstruction file(s");
		importSubmenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.IMPORT));
		final JMenu exportSubmenu = new JMenu("Save Tracings");
		exportSubmenu.setToolTipText("Save reconstruction(s)");
		exportSubmenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.EXPORT));
		final JMenu analysisMenu = new JMenu("Analysis");
		menuBar.add(analysisMenu);
		final JMenu utilitiesMenu = new JMenu("Utilities");
		menuBar.add(utilitiesMenu);
		final ScriptInstaller installer = new ScriptInstaller(plugin.getContext(), SNTUI.this);
		menuBar.add(installer.getScriptsMenu());
		final JMenu viewMenu = new JMenu("View");
		menuBar.add(viewMenu);
		menuBar.add(GuiUtils.helpMenu());

		// Options to replace image data
		final JMenu changeImpMenu = new JMenu("Choose Tracing Image");
		changeImpMenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.IMAGE));
		final JMenuItem fromList = GuiUtils.MenuItems.fromOpenImage();
		fromList.addActionListener(e -> {
			if (plugin.isSecondaryDataAvailable()) {
				flushSecondaryDataPrompt();
			}
			(new DynamicCmdRunner(ChooseDatasetCmd.class, null, LOADING)).run();
		});
		changeImpMenu.add(getImportActionMenuItem(ImportAction.IMAGE));
		changeImpMenu.add(fromList);
		fileMenu.add(changeImpMenu);
		fileMenu.addSeparator();
		final JMenuItem autoTrace = getImportActionMenuItem(ImportAction.AUTO_TRACE_IMAGE);
		autoTrace.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.ROBOT));
		autoTrace.setToolTipText("Runs automated tracing by specifying the path to a thresholded/binary image");
		fileMenu.add(autoTrace);
		fileMenu.addSeparator();
		fileMenu.add(importSubmenu);

		final JMenuItem fromDemo = getImportActionMenuItem(ImportAction.DEMO);
		fromDemo.setIcon(IconFactory.getMenuIcon(GLYPH.GRADUATION_CAP));
		fromDemo.setToolTipText("Load sample images and/or reconstructions");

		final JMenuItem loadLabelsMenuItem = new JMenuItem("Load Labels (AmiraMesh)...");
		loadLabelsMenuItem.setToolTipText("Load neuropil labels from an AmiraMesh file");
		loadLabelsMenuItem.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.TAG));
		loadLabelsMenuItem.addActionListener( e -> {
			final File openFile = openReconstructionFile("labels");
			if (openFile != null) { // null if user pressed cancel;
				plugin.loadLabelsFile(openFile.getAbsolutePath());
				return;
			}
		});
		fileMenu.add(loadLabelsMenuItem);
		fileMenu.add(fromDemo);
		fileMenu.addSeparator();

		fileMenu.add(exportSubmenu);
		final JMenuItem saveTable = GuiUtils.MenuItems.saveTablesAndPlots(GLYPH.TABLE);
		saveTable.addActionListener(e -> {
			(new DynamicCmdRunner(SaveMeasurementsCmd.class, null, getState())).run();
		});
		fileMenu.add(saveTable);
		final JMenuItem restoreMenuItem = new JMenuItem("Restore Snapshot Backup...");
		restoreMenuItem.setToolTipText("Restores data from existing timecoded backups stored on disk");
		restoreMenuItem.setIcon(IconFactory.getMenuIcon(GLYPH.CLOCK_ROTATE_LEFT));
		restoreMenuItem.addActionListener(e -> revertFromBackup());
		fileMenu.add(restoreMenuItem);

		fileMenu.addSeparator();
		sendToTrakEM2 = new JMenuItem("Send to TrakEM2");
		sendToTrakEM2.addActionListener(e -> plugin.notifyListeners(new SNTEvent(SNTEvent.SEND_TO_TRAKEM2)));
		fileMenu.add(sendToTrakEM2);
		fileMenu.add(showFolderMenu(installer));

		final JMenuItem importGuessingType = getImportActionMenuItem(ImportAction.ANY_RECONSTRUCTION);
		importGuessingType.setIcon(IconFactory.getMenuIcon(GLYPH.MAGIC));
		importSubmenu.add(importGuessingType);

		importSubmenu.add(getImportActionMenuItem(ImportAction.JSON));
		final JMenuItem importNDF = getImportActionMenuItem(ImportAction.NDF);
		importNDF.setToolTipText("Imports a  NeuronJ data file");
		importSubmenu.add(importNDF);
		importSubmenu.add(getImportActionMenuItem(ImportAction.SWC));
		importSubmenu.add(getImportActionMenuItem(ImportAction.TRACES));

		final JMenuItem importDirectory = getImportActionMenuItem(ImportAction.SWC_DIR);
		importDirectory.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.FOLDER));
		importSubmenu.add(importDirectory);
		importSubmenu.addSeparator();
		final JMenu remoteSubmenu = new JMenu("Remote Databases");
		remoteSubmenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.DATABASE));
		final JMenuItem importFlyCircuit = new JMenuItem("FlyCircuit...");
		remoteSubmenu.add(importFlyCircuit);
		importFlyCircuit.addActionListener(e -> {
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("loader", new FlyCircuitLoader());
			inputs.put("recViewer", null);
			(new DynamicCmdRunner(RemoteSWCImporterCmd.class, inputs, LOADING)).run();
		});
		final JMenuItem importInsectBrainDb = new JMenuItem("InsectBrain...");
		remoteSubmenu.add(importInsectBrainDb);
		importInsectBrainDb.addActionListener(e -> {
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("recViewer", null);
			(new DynamicCmdRunner(InsectBrainImporterCmd.class, inputs, LOADING)).run();
		});
		final JMenuItem importMouselight = new JMenuItem("MouseLight...");
		remoteSubmenu.add(importMouselight);
		importMouselight.addActionListener(e -> {
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("recViewer", null);
			(new DynamicCmdRunner(MLImporterCmd.class, inputs, LOADING)).run();
		});
		final JMenuItem importNeuroMorpho = new JMenuItem("NeuroMorpho...");
		remoteSubmenu.add(importNeuroMorpho);
		importNeuroMorpho.addActionListener(e -> {
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("loader", new NeuroMorphoLoader());
			inputs.put("recViewer", null);
			(new DynamicCmdRunner(RemoteSWCImporterCmd.class, inputs, LOADING)).run();
		});
		importSubmenu.add(remoteSubmenu);

		saveMenuItem = new JMenuItem("Save");
		saveMenuItem.setToolTipText("Saves tracings to a TRACES (XML) file.\n"
				+ "This file may be gzip compressed as per options in the Preferences dialog.");
		saveMenuItem.setAccelerator(
				KeyStroke.getKeyStroke(KeyEvent.VK_S, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
		saveMenuItem.addActionListener(e -> saveToXML(false));
		exportSubmenu.add(saveMenuItem);
		final JMenuItem saveCopyMenuItem = new JMenuItem("Save Snapshot Backup");
		saveCopyMenuItem.setToolTipText("Saves data to a timecoded backup TRACES file on main file's directory");
		saveCopyMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S,
				java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMask() | KeyEvent.SHIFT_DOWN_MASK));
		exportSubmenu.add(saveCopyMenuItem);
		saveCopyMenuItem.addActionListener(e -> saveToXML(true));
		final JMenuItem saveAsMenuItem = new JMenuItem("Save As...");
		exportSubmenu.add(saveAsMenuItem);
		saveAsMenuItem.addActionListener(e -> {
			if (!noPathsError()) {
				final File saveFile = saveFile("Save Traces As...", null, "traces");
				if (saveFile != null && saveToXML(saveFile))
					warnOnPossibleAnnotationLoss();
			}
		});
		final JMenuItem exportAllSWCMenuItem = new JMenuItem("Export As SWC...");
		exportAllSWCMenuItem.addActionListener( e -> {
			if (plugin.accessToValidImageData() && pathAndFillManager.usingNonPhysicalUnits() && !guiUtils.getConfirmation(
					"These tracings were obtained from a spatially uncalibrated "
							+ "image but the SWC specification assumes all coordinates to be " + "in "
							+ GuiUtils.micrometer() + ". Do you really want to proceed " + "with the SWC export?",
					"Warning"))
				return;

			final SWCExportDialog dialog = new SWCExportDialog(SNTUI.this, plugin.getImagePlus(), getProposedSavingFile("swc"));
			if (dialog.succeeded()) {
				saveAllPathsToSwc(dialog.getFile().getAbsolutePath(), dialog.getFileHeader());
				warnOnPossibleAnnotationLoss();
			}	
		});
		exportSubmenu.addSeparator();
		exportSubmenu.add(exportAllSWCMenuItem);

		final JMenuItem restartMenuItem = new JMenuItem("Reset and Restart...", IconFactory.getMenuIcon(IconFactory.GLYPH.RECYCLE));
		restartMenuItem.setToolTipText("Reset all preferences and restart SNT");
		restartMenuItem.addActionListener( e -> {
			CommandService cmdService = plugin.getContext().getService(CommandService.class);
			exitRequested();
			if (SNTUtils.getPluginInstance() == null) { // exit successful
				PrefsCmd.wipe();
				cmdService.run(SNTLoaderCmd.class, true);
			} else {
				cmdService = null;
			}
		});


		fileMenu.addSeparator();
		fileMenu.add(restartMenuItem);
		fileMenu.addSeparator();
		quitMenuItem = new JMenuItem("Quit", IconFactory.getMenuIcon(IconFactory.GLYPH.QUIT));
		quitMenuItem.addActionListener(listener);
		fileMenu.add(quitMenuItem);

		final JMenuItem measureMenuItem = GuiUtils.MenuItems.measureQuick();
		measureMenuItem.addActionListener(e -> {
			if (noPathsError()) return;
			final Tree tree = getPathManager().getSingleTree();
			if (tree == null) return;
			try {
				final TreeAnalyzer ta = new TreeAnalyzer(tree);
				ta.setContext(plugin.getContext());
				if (ta.getParsedTree().isEmpty()) {
					guiUtils.error("None of the selected paths could be measured.");
					return;
				}
				ta.setTable(pmUI.getTable(), PathManagerUI.TABLE_TITLE);
				ta.run();
			}
			catch (final IllegalArgumentException ignored) {
				getPathManager().quickMeasurementsCmdError(guiUtils);
			}
		});
		final JMenuItem plotMenuItem = new JMenuItem("Reconstruction Plotter...", IconFactory.getMenuIcon(IconFactory.GLYPH.DRAFT));
		plotMenuItem.setToolTipText("Renders traced paths as vector graphics (2D)");
		plotMenuItem.addActionListener( e -> {
			if (noPathsError()) return;
			final Tree tree = getPathManager().getSingleTree();
			if (tree == null) return;
			final Map<String, Object> input = new HashMap<>();
			input.put("tree", tree);
			final CommandService cmdService = plugin.getContext().getService(CommandService.class);
			cmdService.run(PlotterCmd.class, true, input);
		});

		final JMenuItem convexHullMenuItem = GuiUtils.MenuItems.convexHull();
		convexHullMenuItem.addActionListener(e -> {
			if (noPathsError()) return;
			final Collection<Tree> trees = getPathManager().getMultipleTrees();
			if (trees == null || trees.isEmpty()) return;
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("trees", trees);
			inputs.put("table", getPathManager().getTable());
			inputs.put("calledFromRecViewerInstance", false);
			(new CmdRunner(ConvexHullCmd.class, inputs, getState())).execute();
		});
		analysisMenu.add(convexHullMenuItem);
		analysisMenu.addSeparator();

		final JMenuItem pathOrderAnalysis = new JMenuItem("Path Order Analysis",
				IconFactory.getMenuIcon(IconFactory.GLYPH.BRANCH_CODE));
		pathOrderAnalysis.setToolTipText("Horton-Strahler-like analysis based on paths rather than branches");
		pathOrderAnalysis.addActionListener(e -> {
			if (noPathsError()) return;
			final Tree tree = getPathManager().getSingleTree();
			if (tree == null) return;
			final PathOrderAnalysisCmd pa = new PathOrderAnalysisCmd(tree);
			pa.setContext(plugin.getContext());
			pa.setTable(new SNTTable(), "SNT: Path Order Analysis");
			pa.run();
		});
		analysisMenu.add(pathOrderAnalysis);
		exportCSVMenuItem = new JMenuItem("Path Properties: Export CSV...", IconFactory.getMenuIcon(IconFactory.GLYPH.CSV));
		exportCSVMenuItem.setToolTipText("Export details (metrics, relationships, ...) of existing paths as tabular data");
		exportCSVMenuItem.addActionListener(listener);
		analysisMenu.add(exportCSVMenuItem);
		analysisMenu.addSeparator();
		final JMenuItem brainMenuItem = GuiUtils.MenuItems.brainAreaAnalysis();
		brainMenuItem.addActionListener(e -> {
			if (noPathsError()) return;
			final Tree tree = getPathManager().getSingleTree();
			if (tree == null) return;
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("tree", tree);
			new DynamicCmdRunner(BrainAnnotationCmd.class, inputs).run();
		});
		analysisMenu.add(brainMenuItem);
		final JMenuItem shollMenuItem = GuiUtils.MenuItems.shollAnalysis();
		shollMenuItem.addActionListener(e -> {
			if (noPathsError()) return;
			final Tree tree = getPathManager().getMultipleTreesInASingleContainer();
			if (tree == null) return;
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("tree", tree);
			inputs.put("snt", plugin);
			new DynamicCmdRunner(ShollAnalysisTreeCmd.class, inputs).run();
		});
		analysisMenu.add(shollMenuItem);
		analysisMenu.add(shollAnalysisHelpMenuItem());
		final JMenuItem strahlerMenuItem = GuiUtils.MenuItems.strahlerAnalysis();
		strahlerMenuItem.addActionListener(e -> {
			if (noPathsError()) return;
			final Collection<Tree> trees = getPathManager().getMultipleTrees();
			if (trees == null || trees.isEmpty()) return;
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("trees", trees);
			(new CmdRunner(StrahlerCmd.class, inputs, getState())).execute();
		});
		analysisMenu.add(strahlerMenuItem);
		analysisMenu.addSeparator();

		// Measuring options : All Paths
		final JMenuItem measureWithPrompt = GuiUtils.MenuItems.measureOptions();
		measureWithPrompt.addActionListener(e -> {
			if (noPathsError()) return;
			getPathManager().measureCells(((e.getModifiers() & ActionEvent.SHIFT_MASK) != 0)
					|| ((e.getModifiers() & ActionEvent.ALT_MASK) != 0));
		});
		analysisMenu.add(measureWithPrompt);
		analysisMenu.add(measureMenuItem);

		// Utilities
		utilitiesMenu.add(commandFinder.getMenuItem(menuBar, false));
		utilitiesMenu.addSeparator();
		utilitiesMenu.add(plotMenuItem);
		final JMenuItem compareFiles = new JMenuItem("Compare Reconstructions/Cell Groups...");
		compareFiles.setToolTipText("Statistical comparisons between cell groups or individual files");
		compareFiles.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.BINOCULARS));
		utilitiesMenu.add(compareFiles);
		compareFiles.addActionListener(e -> {
			final String[] choices = { "Compare two files", "Compare groups of cells (two or more)" };
			final String[] desc = { //
					"Opens the contents of two reconstruction files in Reconstruction Viewer. "//
							+ "A statistical summary of each file is displayed on a dedicated table. "//
							+ "Note that is also possible to compare two files in the legacy 3D Viewer "//
							+ "('Legacy viewer' widget in the '3D' tab).", //
					"Compares up to 6 groups of cells. Detailed measurements and comparison plots "//
							+ "are retrieved for selected metric(s) along statistical reports (two-sample "//
							+ "t-test/one-way ANOVA). Color-coded montages of analyzed groups can also be "//
							+ "generated."//
			};
			final String defChoice = plugin.getPrefs().getTemp("compare", choices[1]);
			final String choice = guiUtils.getChoice("Which kind of comparison would you like to perform?",
					"Single or Group Comparison?", choices, desc, defChoice);
			if (choice == null) return;
			plugin.getPrefs().setTemp("compare", choice);
			if (choices[0].equals(choice))
				(new CmdRunner(CompareFilesCmd.class)).execute();
			else {
				(new DynamicCmdRunner(GroupAnalyzerCmd.class, null)).run();
			}
		});
		utilitiesMenu.addSeparator();
		final JMenuItem graphGenerator = GuiUtils.MenuItems.createDendrogram();
		utilitiesMenu.add(graphGenerator);
		graphGenerator.addActionListener(e -> {
			if (noPathsError()) return;
			final Tree tree = getPathManager().getSingleTree();
			if (tree == null) return;
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("tree", tree);
			(new DynamicCmdRunner(GraphGeneratorCmd.class, inputs)).run();
		});
		final JMenuItem annotGenerator = GuiUtils.MenuItems.createAnnotionGraph();
		utilitiesMenu.add(annotGenerator);
		annotGenerator.addActionListener(e -> {
			if (noPathsError()) return;
			final Collection<Tree> trees = getPathManager().getMultipleTrees();
			if (trees == null) return;
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("trees", trees);
			(new DynamicCmdRunner(AnnotationGraphGeneratorCmd.class, inputs)).run();
		});
		utilitiesMenu.addSeparator();

		// similar to File>Autotrace Segmented Image File... but assuming current image as source,
		// which does not require file validations etc.
		final JMenuItem autotraceJMI = new JMenuItem("Autotrace Segmented Image...",
				IconFactory.getMenuIcon(IconFactory.GLYPH.ROBOT));
		autotraceJMI.setToolTipText("Runs automated tracing on a thresholded/binary image already open");
		utilitiesMenu.add(autotraceJMI);
		ScriptRecorder.setRecordingCall(autotraceJMI, "snt.getUI().runAutotracingWizard(false)");
		autotraceJMI.addActionListener(e -> runAutotracingOnImage(false));

		// View menu
		final JMenuItem arrangeDialogsMenuItem = new JMenuItem("Arrange Dialogs",
				IconFactory.getMenuIcon(IconFactory.GLYPH.WINDOWS2));
		arrangeDialogsMenuItem.addActionListener(e -> {
			final int w = Integer.valueOf(getPrefs().get("def-gui-width", "-1"));
			final int h = Integer.valueOf(getPrefs().get("def-gui-height", "-1"));
			if (w == -1 || h == -1) {
				error("Preferences may be corrupt. Please reset them using File>Reset and Restart...");
				return;
			}
			java.awt.Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
			setBounds(bounds.x, bounds.y, w, h);
			pmUI.setBounds(getLocation().x + w + MARGIN, getLocation().y, w, h);
			fmUI.setLocation(pmUI.getLocation().x + w + MARGIN, getLocation().y);
			final Window console = GuiUtils.getConsole();
			if (console != null)
				console.setBounds(getLocation().x, bounds.height - h / 3, w * 2, h / 3);
		});
		viewMenu.add(arrangeDialogsMenuItem);
		final JMenuItem arrangeWindowsMenuItem = new JMenuItem("Arrange Tracing Views");
		arrangeWindowsMenuItem.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.WINDOWS));
		arrangeWindowsMenuItem.addActionListener(e -> arrangeCanvases(true));
		viewMenu.add(arrangeWindowsMenuItem);
		final JMenu hideViewsMenu = new JMenu("Hide Tracing Canvas");
		hideViewsMenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.EYE_SLASH));
		final JCheckBoxMenuItem xyCanvasMenuItem = new JCheckBoxMenuItem("Hide XY View");
		xyCanvasMenuItem.addActionListener(e -> toggleWindowVisibility(MultiDThreePanes.XY_PLANE, xyCanvasMenuItem));
		hideViewsMenu.add(xyCanvasMenuItem);
		final JCheckBoxMenuItem zyCanvasMenuItem = new JCheckBoxMenuItem("Hide ZY View");
		zyCanvasMenuItem.addActionListener(e -> toggleWindowVisibility(MultiDThreePanes.ZY_PLANE, zyCanvasMenuItem));
		hideViewsMenu.add(zyCanvasMenuItem);
		final JCheckBoxMenuItem xzCanvasMenuItem = new JCheckBoxMenuItem("Hide XZ View");
		xzCanvasMenuItem.addActionListener(e -> toggleWindowVisibility(MultiDThreePanes.XZ_PLANE, xzCanvasMenuItem));
		hideViewsMenu.add(xzCanvasMenuItem);
		final JCheckBoxMenuItem threeDViewerMenuItem = new JCheckBoxMenuItem("Hide Legacy 3D View");
		threeDViewerMenuItem.addItemListener(e -> {
			if (plugin.get3DUniverse() == null || !plugin.use3DViewer) {
				guiUtils.error("Legacy 3D Viewer is not active.");
				return;
			}
			plugin.get3DUniverse().getWindow().setVisible(e.getStateChange() == ItemEvent.DESELECTED);
		});
		hideViewsMenu.add(threeDViewerMenuItem);
		viewMenu.add(hideViewsMenu);
		final JMenuItem showImpMenuItem = new JMenuItem("Display Secondary Image", IconFactory.getMenuIcon(GLYPH.LAYERS));
		showImpMenuItem.addActionListener(e -> {
			if (noSecondaryDataAvailableError())
				return;
			final ImagePlus imp = plugin.getSecondaryDataAsImp();
			if (imp == null) {
				guiUtils.error("Somehow image could not be created.", "Secondary Image Unavailable?");
			} else {
				imp.show();
			}
		});
		viewMenu.add(showImpMenuItem);
		viewMenu.addSeparator();

		final JMenuItem consoleJMI = new JMenuItem("Toggle Fiji Console", IconFactory.getMenuIcon(GLYPH.CODE));
		consoleJMI.addActionListener(e -> {
			try {
				final Window console = GuiUtils.getConsole();
				if (console == null)
					plugin.getContext().getService(UIService.class).getDefaultUI().getConsolePane().show();
				else
					console.setVisible(!console.isVisible());
			} catch (final Exception ex) {
				guiUtils.error(
						"Could not toggle Fiji's built-in Console. Please use Fiji's Window>Console command directly.");
				ex.printStackTrace();
			}
		});
		viewMenu.add(consoleJMI);
		//viewMenu.addSeparator();

		//viewMenu.add(guiUtils.combineChartsMenuItem());
		return menuBar;
	}

	private JMenu showFolderMenu(final ScriptInstaller installer) {
		final JMenu menu = new JMenu("Reveal Directory");
		menu.setIcon(IconFactory.getMenuIcon(GLYPH.OPEN_FOLDER));
		final String[] labels = { "Current TRACES file", "Image Being Traced", "Fiji Scripts", "Last Accessed Folder",
				"Secondary Layer Image", "Snapshot Backup(s)" };
		Arrays.stream(labels).forEach(label -> {
			final JMenuItem jmi = new JMenuItem(label);
			menu.add(jmi);
			jmi.addActionListener(e -> {
				File f = null;
				boolean proceed = true;
				switch (label) {
				case "Fiji Scripts":
					f = installer.getScriptsDir();
					break;
				case "Image Being Traced":
					if (!plugin.accessToValidImageData()) {
						noValidImageDataError();
						proceed = false;
					}
					try {
						f = new File(plugin.getImagePlus().getOriginalFileInfo().getFilePath());
					} catch (final Exception ignored) {
						// do nothing
					}
					break;
				case "Last Accessed Folder":
					f = plugin.getPrefs().getRecentDir();
					break;
				case "Snapshot Backup(s)":
					f = getAutosaveFile();
					if (SNTUtils.getBackupCopies(f).isEmpty()) {
						guiUtils.error("No Snapshot Backup(s) seem to exist for current tracings.");
						proceed = false;
					}
					break;
				case "Secondary Layer Image":
					f = plugin.getFilteredImageFile();
					proceed = !noSecondaryDataAvailableError();
					break;
				case "Current TRACES file":
					f = getAutosaveFile();
					if (f == null) {
						guiUtils.error("Current tracings do not seem to be associated with a TRACES file.");
						proceed = false;
					}
					break;
				default:
					break;
				}
				if (proceed)
					guiUtils.showDirectory(f);
			});
		});
		return menu;
	}

	private JPanel renderingPanel() {

		showPathsSelected = new JCheckBox(InternalUtils.hotKeyLabel("1. Only selected paths (hide deselected)", "1"),
				plugin.showOnlySelectedPaths);
		showPathsSelected.addItemListener(listener);

		final JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		row1.add(showPathsSelected);

		partsNearbyCSpinner = new CheckboxSpinner(new JCheckBox(InternalUtils.hotKeyLabel("2. Only nodes within ", "2")),
				GuiUtils.integerSpinner(1, 1, 80, 1, true));
		partsNearbyCSpinner.appendLabel("nearby Z-slices");
		partsNearbyCSpinner.setToolTipText("See Options pane for display settings of out-of-plane nodes");
		partsNearbyCSpinner.getCheckBox().addItemListener(e -> {
			plugin.justDisplayNearSlices(partsNearbyCSpinner.isSelected(),
					(int) partsNearbyCSpinner.getValue());
			if (recorder != null)
				recorder.recordCmd("snt.getUI().setVisibilityFilter(\"Z-slices\", "
						+ partsNearbyCSpinner.isSelected() + ")");
		});
		partsNearbyCSpinner.getSpinner().addChangeListener(e -> {
			plugin.justDisplayNearSlices(true, (int) partsNearbyCSpinner.getValue());
		});

		final JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		onlyActiveCTposition = new JCheckBox(InternalUtils.hotKeyLabel("3. Only paths from active channel/frame", "3"));
		row3.add(onlyActiveCTposition);
		onlyActiveCTposition.addItemListener(listener);

		final JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.add(row1);
		panel.add(partsNearbyCSpinner);
		panel.add(row3);
		return panel;
	}

	private JPanel colorOptionsPanel() {
		final JPanel colorOptionsPanel = new JPanel(new GridBagLayout());
		final GridBagConstraints cop_f = GuiUtils.defaultGbc();
		final JPanel colorButtonPanel = new JPanel(new GridLayout(1, 2));
		final ColorChooserButton colorChooser1 = new ColorChooserButton(plugin.selectedColor, "Selected");
		colorChooser1.setName("Color for Selected Paths");
		colorChooser1.addColorChangedListener(newColor -> plugin.setSelectedColor(newColor));
		final ColorChooserButton colorChooser2 = new ColorChooserButton(plugin.deselectedColor, "Deselected");
		colorChooser2.setName("Color for Deselected Paths");
		colorChooser2.addColorChangedListener(newColor -> plugin.setDeselectedColor(newColor));
		colorButtonPanel.add(colorChooser1);
		colorButtonPanel.add(colorChooser2);
		++cop_f.gridy;
		colorOptionsPanel.add(colorButtonPanel, cop_f);
		++cop_f.gridy;
		final JCheckBox jcheckbox = new JCheckBox("Enforce default colors (ignore color tags)");
		GuiUtils.addTooltip(jcheckbox,
				"Whether default colors above should be used even when color tags have been applied in the Path Manager");
		registerInCommandFinder(jcheckbox, "Enforce Default Colors", "Main Tab");
		jcheckbox.addActionListener(e -> {
			plugin.displayCustomPathColors = !jcheckbox.isSelected();
			// colorChooser1.setEnabled(!plugin.displayCustomPathColors);
			// colorChooser2.setEnabled(!plugin.displayCustomPathColors);
			plugin.updateTracingViewers(true);
		});
		colorOptionsPanel.add(jcheckbox, cop_f);

		return colorOptionsPanel;
	}

	private JPanel snappingPanel() {

		final JPanel tracingOptionsPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		useSnapWindow = new JCheckBox(InternalUtils.hotKeyLabel("Enable Snapping within XY", "S"), plugin.snapCursor);
		registerInCommandFinder(useSnapWindow, "Toggle Cursor Snapping", "Main Tab");
		useSnapWindow.addItemListener(listener);
		tracingOptionsPanel.add(useSnapWindow);

		snapWindowXYsizeSpinner = GuiUtils.integerSpinner(plugin.cursorSnapWindowXY * 2,
				SNT.MIN_SNAP_CURSOR_WINDOW_XY, SNT.MAX_SNAP_CURSOR_WINDOW_XY * 2, 2, false);
		snapWindowXYsizeSpinner
				.addChangeListener(e -> plugin.cursorSnapWindowXY = (int) snapWindowXYsizeSpinner.getValue() / 2);
		tracingOptionsPanel.add(snapWindowXYsizeSpinner);

		final JLabel z_spinner_label = GuiUtils.leftAlignedLabel("  Z ", true);
		z_spinner_label.setBorder(new EmptyBorder(0, 2, 0, 0));
		tracingOptionsPanel.add(z_spinner_label);
		snapWindowZsizeSpinner = GuiUtils.integerSpinner(plugin.cursorSnapWindowZ * 2,
				SNT.MIN_SNAP_CURSOR_WINDOW_Z, SNT.MAX_SNAP_CURSOR_WINDOW_Z * 2, 2, false);
		snapWindowZsizeSpinner.setEnabled(isStackAvailable());
		snapWindowZsizeSpinner
				.addChangeListener(e -> plugin.cursorSnapWindowZ = (int) snapWindowZsizeSpinner.getValue() / 2);
		tracingOptionsPanel.add(snapWindowZsizeSpinner);
		GuiUtils.addTooltip(tracingOptionsPanel, "Whether the mouse pointer should snap to the brightest voxel "
				+ "searched within the specified neighborhood (in pixels). When Z=0 snapping occurs in 2D.");
		// ensure same alignment of all other panels using defaultGbc
		final JPanel container = new JPanel(new GridBagLayout());
		container.add(tracingOptionsPanel, GuiUtils.defaultGbc());
		return container;
	}

	@SuppressWarnings("unchecked")
	private JPanel aStarPanel() {
		aStarCheckBox = new JCheckBox("Enable ", plugin.isAstarEnabled());
		registerInCommandFinder(aStarCheckBox, "Toggle Auto-tracing", "Main Tab");
		aStarCheckBox.addActionListener(e -> {
			boolean enable = aStarCheckBox.isSelected();
			if (!enable && askUserConfirmation
					&& !guiUtils.getConfirmation(
							"Disable computation of paths? All segmentation tasks will be disabled.",
							"Enable Manual Tracing?")) {
				aStarCheckBox.setSelected(true);
				return;
			}
			if (enable && !plugin.accessToValidImageData()) {
				aStarCheckBox.setSelected(false);
				noValidImageDataError();
				enable = false;
			} else if (enable && !plugin.inputImageLoaded()) {
				loadImagefromGUI(plugin.channel, plugin.frame);
			}
			plugin.enableAstar(enable);
		});

		searchAlgoChoice = new JComboBox<>();
		searchAlgoChoice.addItem("A* search");
		searchAlgoChoice.addItem("NBA* search");
		searchAlgoChoice.addItem("Fast marching");
		//TODO: ensure choice reflects the current state of plugin when assembling GUI
		searchAlgoChoice.addItemListener(new ItemListener() {

			Object previousSelection = null;

			@Override
			public void itemStateChanged(final ItemEvent e) {
				// This is called twice during a selection change, so handle each state accordingly
				if( e.getStateChange() == ItemEvent.DESELECTED ) {
					previousSelection = e.getItem();

				} else if ( e.getStateChange() == ItemEvent.SELECTED ) {
					final int idx = ((JComboBox<String>) e.getSource()).getSelectedIndex();
					if (idx == 0) {
						enableTracerThread();
						setFastMarchSearchEnabled(false);
					} else if (idx == 1) {
						enableNBAStar();
						setFastMarchSearchEnabled(false);
					} else if (idx == 2 && !setFastMarchSearchEnabled(true)) {
						searchAlgoChoice.setSelectedItem(previousSelection);
					}
					updateSettingsString();
				}
			}
		});

		final JPanel checkboxPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		checkboxPanel.add(aStarCheckBox);
		checkboxPanel.add(searchAlgoChoice);
		checkboxPanel.add(GuiUtils.leftAlignedLabel(" algorithm", true));

		final JPopupMenu optionsMenu = new JPopupMenu();
		final JButton optionsButton = optionsButton(IconFactory.GLYPH.MATH, optionsMenu);
		GuiUtils.addTooltip(optionsButton, "Algorithm settings");
		optionsMenu.add(GuiUtils.leftAlignedLabel("Data Structure:", false));
		final ButtonGroup dataStructureButtonGroup = new ButtonGroup();

		final Map<String, SNT.SearchImageType> searchMap = new LinkedHashMap<>();
		searchMap.put("Map (Lightweight)", SNT.SearchImageType.MAP);
		searchMap.put("Array (Fast)", SNT.SearchImageType.ARRAY);
		searchMap.forEach((lbl, type) -> {
			final JRadioButtonMenuItem rbmi = new JRadioButtonMenuItem(lbl);
			dataStructureButtonGroup.add(rbmi);
			optionsMenu.add(rbmi);
			rbmi.setSelected(plugin.searchImageType == type);
			rbmi.addActionListener(e -> {
				plugin.searchImageType = type;
				showStatus("Active data structure: " + lbl, true);
				updateSettingsString();
			});
		});
		optionsMenu.addSeparator();

		optionsMenu.add(GuiUtils.leftAlignedLabel("Cost Function:", false));
		final ButtonGroup costFunctionButtonGroup = new ButtonGroup();
		final Map<String, SNT.CostType> costMap = new  LinkedHashMap<>();
		costMap.put("Reciprocal of Intensity (Default)", SNT.CostType.RECIPROCAL);
		costMap.put("Difference of Intensity", SNT.CostType.DIFFERENCE);
		costMap.put("Difference of Intensity Squared", SNT.CostType.DIFFERENCE_SQUARED);
		costMap.put("Probability of Intensity", SNT.CostType.PROBABILITY);
		costMap.forEach((lbl, type) -> {
			final JRadioButtonMenuItem rbmi = new JRadioButtonMenuItem(lbl);
			rbmi.setToolTipText(type.getDescription());
			costFunctionButtonGroup.add(rbmi);
			optionsMenu.add(rbmi);
			rbmi.setActionCommand(String.valueOf(type));
			rbmi.setSelected(plugin.getCostType() == type);
			rbmi.addActionListener(e -> {
				plugin.setCostType(type);
				updateSettingsString();
				showStatus("Cost function: " + lbl, true);
				SNTUtils.log("Cost function: Now using " + plugin.getCostType());
				if (type == SNT.CostType.PROBABILITY) {
					plugin.setUseSubVolumeStats(true);
				}
			});
		});

		optionsMenu.addSeparator();
		optionsMenu.add(GuiUtils.leftAlignedLabel("Image Statistics:", false));
		autoRbmi = new JRadioButtonMenuItem("Compute Real-Time", plugin.getUseSubVolumeStats());
		final JRadioButtonMenuItem onceRbmi = new JRadioButtonMenuItem("Compute Now For Whole Image ", false);
		final JRadioButtonMenuItem manRbmi = new JRadioButtonMenuItem("Specify Manually...", !plugin.getUseSubVolumeStats());
		final ButtonGroup minMaxButtonGroup = new ButtonGroup();
		minMaxButtonGroup.add(autoRbmi);
		minMaxButtonGroup.add(onceRbmi);
		minMaxButtonGroup.add(manRbmi);
		optionsMenu.add(autoRbmi);
		optionsMenu.add(onceRbmi);
		optionsMenu.add(manRbmi);
		autoRbmi.addActionListener(e -> {
			plugin.setUseSubVolumeStats(autoRbmi.isSelected());
			onceRbmi.setEnabled(true);
			onceRbmi.setText("Compute Now for Whole Image");

		});
		onceRbmi.addActionListener(e -> {
			if (!plugin.accessToValidImageData()) {
				noValidImageDataError();
			} else {
				SNTUtils.log("Computing statistics for whole image...");
				showStatus("Computing statistics...", false);
				plugin.computeImgStats(plugin.getLoadedIterable(), plugin.getStats());
				showStatus(null, false);
				plugin.setUseSubVolumeStats(false);
				onceRbmi.setText("Compute for Whole Image");
				onceRbmi.setEnabled(false);
			}
		});
		manRbmi.addActionListener(e -> {
			final boolean successfullySet = setMinMaxFromUser();
			if (successfullySet) {
				plugin.setUseSubVolumeStats(false);
				onceRbmi.setText("Compute Now for Whole Image");
				onceRbmi.setEnabled(true);
			} else if (plugin.getUseSubVolumeStats()) {
				autoRbmi.setSelected(true);
			} else if (plugin.getStats().mean > 0 && plugin.getStats().stdDev > 0) {
				onceRbmi.setSelected(true);
			}
		});
		optionsMenu.addSeparator();
		optionsMenu.add(GuiUtils.menuItemTriggeringHelpURL("Help on Algorithm Settings",
				"https://imagej.net/plugins/snt/manual#auto-tracing"));
		aStarPanel = new JPanel(new BorderLayout());
		aStarPanel.add(checkboxPanel, BorderLayout.CENTER);
		aStarPanel.add(optionsButton, BorderLayout.EAST);
		return aStarPanel;
	}

	private void enableTracerThread() {
		plugin.setSearchType(SNT.SearchType.ASTAR);
	}

	private void enableNBAStar() {
		plugin.setSearchType(SNT.SearchType.NBASTAR);
	}

	private JPanel hideWindowsPanel() {
		showOrHidePathList = new JButton("Show Path Manager");
		showOrHidePathList.addActionListener(listener);
		registerInCommandFinder(showOrHidePathList, "Show/Hide Path Manager", "Main Tab");
		showOrHideFillList = new JButton();
		showOrHideFillList.addActionListener(listener);
		registerInCommandFinder(showOrHideFillList, "Show/Hide Fill Manager", "Main Tab");
		final JPanel hideWindowsPanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = new GridBagConstraints();
		gdb.fill = GridBagConstraints.HORIZONTAL;
		gdb.weightx = 0.5;
		hideWindowsPanel.add(showOrHidePathList, gdb);
		gdb.gridx = 1;
		hideWindowsPanel.add(showOrHideFillList, gdb);
		return hideWindowsPanel;
	}

	private JPanel statusBar() {
		final JPanel statusBar = new JPanel();
		statusBar.setLayout(new BoxLayout(statusBar, BoxLayout.X_AXIS));
		statusBarText = GuiUtils.leftAlignedLabel("Ready to trace...", true);
		statusBarText.setBorder(BorderFactory.createEmptyBorder(0, MARGIN, MARGIN / 2, 0));
		statusBar.add(statusBarText);
		refreshStatus();
		statusBar.addMouseListener( new MouseAdapter() {
			@Override
			public void mousePressed(final MouseEvent e) {
				if (e.getClickCount() == 2) {
					refreshStatus();
					if (recorder != null)
						recorder.recordCmd("snt.getUI().showStatus(\"\", true)");
				}
			}
		});
		return statusBar;
	}

	boolean setFastMarchSearchEnabled(final boolean enable) {
		if (enable && isFastMarchSearchAvailable()) {
			plugin.tubularGeodesicsTracingEnabled = true;
			searchAlgoChoice.setSelectedIndex(2);
			return true;
		} else {
			plugin.tubularGeodesicsTracingEnabled = false;
			if (plugin.tubularGeodesicsThread != null) {
				plugin.tubularGeodesicsThread.requestStop();
				plugin.tubularGeodesicsThread = null;
			}
			return false;
		}
	}

	private boolean isFastMarchSearchAvailable() {
		final boolean tgInstalled = Types.load("FijiITKInterface.TubularGeodesics") != null;
		final boolean tgAvailable = plugin.tubularGeodesicsTracingEnabled;
		if (!tgAvailable || !tgInstalled) {
			final StringBuilder msg = new StringBuilder(
					"Fast marching requires the <i>TubularGeodesics</i> plugin to be installed ")
							.append("and an <i>oof.tif</i> secondary image to be loaded. Currently, ");
			if (!tgInstalled && !tgAvailable) {
				msg.append("neither conditions are fulfilled.");
			} else if (!tgInstalled) {
				msg.append("the plugin is not installed.");
			} else {
				msg.append("the secondary image does not seem to be valid.");
			}
			guiUtils.error(msg.toString(), "Error", "https://imagej.net/plugins/snt/extending#tubular-geodesics");
		}
		return tgInstalled && tgAvailable;
	}

	private void refreshStatus() {
		showStatus(null, false);
	}

	/**
	 * Updates the status bar.
	 *
	 * @param msg       the text to displayed. Set it to null (or empty String) to
	 *                  reset the status bar.
	 * @param temporary if true and {@code msg} is valid, text is displayed
	 *                  transiently for a couple of seconds
	 */
	public void showStatus(final String msg, final boolean temporary) {
		SwingUtilities.invokeLater(() -> {
			if (plugin == null)
				return;

			final boolean validMsg = !(msg == null || msg.isEmpty());
			if (validMsg && !temporary) {
				statusBarText.setText(msg);
				return;
			}

			final String defaultText;
			if (!plugin.accessToValidImageData()) {
				defaultText = "Image data unavailable...";
			} else {
				defaultText = "Tracing " + StringUtils.abbreviate(plugin.getImagePlus().getShortTitle(), 25) + ", C=" + plugin.channel + ", T="
						+ plugin.frame;
			}

			if (!validMsg) {
				statusBarText.setText(defaultText);
				return;
			}

			final Timer timer = new Timer(3000, e -> statusBarText.setText(defaultText));
			timer.setRepeats(false);
			timer.start();
			statusBarText.setText(msg);
		});
	}

	public void setLookAndFeel(final String lookAndFeelName) {
		final ArrayList<Component> components = new ArrayList<>();
		components.add(SNTUI.this);
		components.add(getPathManager());
		components.add(getFillManager());
		if (plugin.getXYCanvas() != null)
			plugin.getXYCanvas().setLookAndFeel(lookAndFeelName);
		if (plugin.getXZCanvas() != null)
			plugin.getXZCanvas().setLookAndFeel(lookAndFeelName);
		if (plugin.getZYCanvas() != null)
			plugin.getZYCanvas().setLookAndFeel(lookAndFeelName);
		final Viewer3D recViewer = getReconstructionViewer(false);
		if (recViewer != null)
			recViewer.setLookAndFeel(lookAndFeelName);
		GuiUtils.setLookAndFeel(lookAndFeelName, false, components.toArray(new Component[0]));
	}

	protected void displayOnStarting() {
		SwingUtilities.invokeLater(() -> {
			if (plugin.getPrefs().isSaveWinLocations())
				arrangeDialogs();
			arrangeCanvases(false);
			resetState();
			updateSettingsString();
			pack();
			pmUI.setSize(getWidth(), pmUI.getHeight());
			pathAndFillManager.resetListeners(null, true); // update Path lists
			setPathListVisible(true, false);
			setFillListVisible(false);
			setVisible(true);
			SNTUtils.setIsLoading(false);
			if (plugin.getImagePlus()!=null) plugin.getImagePlus().getWindow().toFront();
			InternalUtils.ijmLogMessage();
			promptForAutoTracingAsAppropriate();
			guiUtils.notifyIfNewVersion(0);
			getPrefs().set("def-gui-width", ""+getWidth());
			getPrefs().set("def-gui-height", ""+getHeight());
		});
	}

	protected void promptForAutoTracingAsAppropriate() {
		if (plugin.getPrefs().getTemp("autotracing-prompt-armed", true)) {
			final boolean nag = plugin.getPrefs().getTemp("autotracing-nag", true);
			boolean run = plugin.getPrefs().getTemp("autotracing-run", true);
			if (plugin.accessToValidImageData() && plugin.getImagePlus().getProcessor().isBinary()) {
				if (nag) {
					final boolean[] options = guiUtils.getPersistentConfirmation(
							"Image is eligible for fully automated reconstruction. Would you like to attempt it now?",
							"Run Auto-tracing?");
					plugin.getPrefs().setTemp("autotracing-run", run = options[0]);
					plugin.getPrefs().setTemp("autotracing-nag", !options[1]);
				}
				if (run)
					runAutotracingOnImage(false);
			}
		}
		plugin.getPrefs().setTemp("autotracing-prompt-armed", true);
	}

	private void runAutotracingOnImage(final boolean simplifyPrompt) {
		final HashMap<String, Object> inputs = new HashMap<>();
		inputs.put("useFileChoosers", false);
		inputs.put("simplifyPrompt", simplifyPrompt);
		(new DynamicCmdRunner(SkeletonConverterCmd.class, inputs)).run();
	}

	@SuppressWarnings("unused")
	private Double getZFudgeFromUser() {
		final double defaultValue = new OneMinusErf(0,0,0).getZFudge();
		final String promptMsg = "Enter multiplier for intensity z-score. "//
				+ "Values < 1 make it easier to numerically distinguish bright voxels. "//
				+ "The current default is "//
				+ SNTUtils.formatDouble(defaultValue, 2) + ".";
		final Double fudge = guiUtils.getDouble(promptMsg, "Z-score fudge", defaultValue);
		if (fudge == null) {
			return null; // user pressed cancel
		}
		if (Double.isNaN(fudge) || fudge < 0) {
			guiUtils.error("Fudge must be a positive number.", "Invalid Input");
			return null;
		}
		return fudge;
	}

	/* returns true if min/max successfully set by user */
	private boolean setMinMaxFromUser() {
		final String choice = getPrimarySecondaryImgChoice("Adjust range for which image?");
		if (choice == null) return false;

		final boolean useSecondary = "Secondary".equalsIgnoreCase(choice);
		final float[] defaultValues = new float[2];
		defaultValues[0] = useSecondary ? (float)plugin.getStatsSecondary().min : (float)plugin.getStats().min;
		defaultValues[1] = useSecondary ? (float)plugin.getStatsSecondary().max : (float)plugin.getStats().max;
		String promptMsg = "Enter the min-max range for the A* search";
		if (useSecondary)
			promptMsg += " for secondary image";
		promptMsg +=  ". Values less than or equal to <i>Min</i> maximize the A* cost function. "
				+ "Values greater than or equal to <i>Max</i> minimize the A* cost function. "//
				+ "The current min-max range is " + defaultValues[0] + "-" + defaultValues[1];
		// FIXME: scientific notation is parsed incorrectly
		final float[] minMax = guiUtils.getRange(promptMsg, "Setting Min-Max Range",
				defaultValues);
		if (minMax == null) {
			return false; // user pressed cancel
		}
		if (Double.isNaN(minMax[0]) || Double.isNaN(minMax[1])) {
			guiUtils.error("Invalid range. Please specify two valid numbers separated by a single hyphen.");
			return false;
		}
		if (useSecondary) {
			plugin.getStatsSecondary().min = minMax[0];
			plugin.getStatsSecondary().max = minMax[1];
		} else {
			plugin.getStats().min = minMax[0];
			plugin.getStats().max = minMax[1];
		}
		updateSettingsString();
		return true;
	}

	private String getPrimarySecondaryImgChoice(final String promptMsg) {
		if (plugin.isTracingOnSecondaryImageAvailable()) {
			final String[] choices = new String[] { "Primary (Main)", "Secondary" };
			final String defChoice = plugin.getPrefs().getTemp("pschoice", choices[0]);
			final String choice = guiUtils.getChoice(promptMsg, "Which Image?", choices, defChoice);
			if (choice != null) {
				plugin.getPrefs().setTemp("pschoice", choice);
				secLayerActivateCheckbox.setSelected(choices[1].equals(choice));
			}
			return choice;
		}
		return "primary";
	}

	private void arrangeDialogs() {
		Point loc = plugin.getPrefs().getPathWindowLocation();
		if (loc != null)
			pmUI.setLocation(loc);
		loc = plugin.getPrefs().getFillWindowLocation();
		if (loc != null)
			fmUI.setLocation(loc);
		// final GraphicsDevice activeScreen =
		// getGraphicsConfiguration().getDevice();
		// final int screenWidth = activeScreen.getDisplayMode().getWidth();
		// final int screenHeight = activeScreen.getDisplayMode().getHeight();
		// final Rectangle bounds =
		// activeScreen.getDefaultConfiguration().getBounds();
		//
		// setLocation(bounds.x, bounds.y);
		// pw.setLocation(screenWidth - pw.getWidth(), bounds.y);
		// fw.setLocation(bounds.x + getWidth(), screenHeight - fw.getHeight());
	}

	private void arrangeCanvases(final boolean displayErrorOnFailure) {

		final ImageWindow xy_window = (plugin.getImagePlus()==null) ? null : plugin.getImagePlus().getWindow();
		if (xy_window == null) {
			if (displayErrorOnFailure)
				guiUtils.error("XY view is not available.");
			return;
		}

		// Adjust and uniformize zoom levels
		final boolean zoomSyncStatus = plugin.isZoomAllPanesDisabled();
		plugin.disableZoomAllPanes(false);
		double zoom = plugin.getImagePlus().getCanvas().getMagnification();
		if (plugin.getImagePlus().getWidth() < 500d && plugin.getImagePlus().getCanvas().getMagnification() == 1) {
			// if the image is rather small (typically a display canvas), zoom it to more manageable dimensions
			zoom = ImageCanvas.getLowerZoomLevel(500d/plugin.getImagePlus().getWidth() * Math.min(1.5, Prefs.getGuiScale()));
		}
		plugin.zoomAllPanes(zoom);
		plugin.disableZoomAllPanes(zoomSyncStatus);

		final GraphicsConfiguration xy_config = xy_window.getGraphicsConfiguration();
		final GraphicsDevice xy_screen = xy_config.getDevice();
		final Rectangle xy_screen_bounds = xy_screen.getDefaultConfiguration().getBounds();

		// Center the main tracing canvas on the screen it was found
		final int x = (xy_screen_bounds.width / 2) - (xy_window.getWidth() / 2) + xy_screen_bounds.x;
		final int y = (xy_screen_bounds.height / 2) - (xy_window.getHeight() / 2) + xy_screen_bounds.y;
		xy_window.setLocation(x, y);

		final ImagePlus zy = plugin.getImagePlus(MultiDThreePanes.ZY_PLANE);
		if (zy != null && zy.getWindow() != null) {
			zy.getWindow().setLocation(x + xy_window.getWidth() + 5, y);
			zy.getWindow().toFront();
		}
		final ImagePlus xz = plugin.getImagePlus(MultiDThreePanes.XZ_PLANE);
		if (xz != null && xz.getWindow() != null) {
			xz.getWindow().setLocation(x, y + xy_window.getHeight() + 2);
			xz.getWindow().toFront();
		}
		xy_window.toFront();
	}

	private void toggleWindowVisibility(final int pane, final JCheckBoxMenuItem mItem) {
		final ImagePlus imp = plugin.getImagePlus(pane);
		if (imp == null) {
			String msg;
			if (pane == MultiDThreePanes.XY_PLANE) {
				msg = "XY view is not available.";
			} else if (plugin.getSinglePane()) {
				msg = "View does not exist. To generate ZY/XZ " + "views run \"Display ZY/XZ views\".";
			} else {
				msg = "View is no longer accessible. " + "You can (re)build it using \"Rebuild ZY/XZ views\".";
			}
			guiUtils.error(msg);
			mItem.setSelected(false);
			return;
		}
		// NB: WindowManager list won't be notified
		imp.getWindow().setVisible(!mItem.isSelected());
	}

	protected boolean noPathsError() {
		final boolean noPaths = pathAndFillManager.size() == 0;
		if (noPaths)
			guiUtils.error("There are no traced paths.");
		return noPaths;
	}

	private boolean notReadyToSaveError() {
		final boolean notReady = !isReady();
		if (notReady)
			plugin.discreteMsg("Please finish current task before saving...");
		return notReady;
	}

	private void setPathListVisible(final boolean makeVisible, final boolean toFront) {
		assert SwingUtilities.isEventDispatchThread();
		if (makeVisible) {
			pmUI.setVisible(true);
			if (toFront)
				pmUI.toFront();
			if (showOrHidePathList != null)
				showOrHidePathList.setText("  Hide Path Manager");
		} else {
			if (showOrHidePathList != null)
				showOrHidePathList.setText("Show Path Manager");
			pmUI.setVisible(false);
		}
	}

	protected void setFillListVisible(final boolean makeVisible) {
		assert SwingUtilities.isEventDispatchThread();
		if (makeVisible) {
			fmUI.setVisible(true);
			if (showOrHideFillList != null)
				showOrHideFillList.setText("  Hide Fill Manager");
			fmUI.toFront();
		} else {
			if (showOrHideFillList != null)
				showOrHideFillList.setText("Show Fill Manager");
			fmUI.setVisible(false);
		}
	}

	protected boolean nearbySlices() {
		assert SwingUtilities.isEventDispatchThread();
		return partsNearbyCSpinner.isSelected();
	}

	private JMenuItem shollAnalysisHelpMenuItem() {
		final JMenuItem mi = new JMenuItem("Sholl Analysis (by Focal Point)...");
		mi.setToolTipText("Instructions on how to perform Sholl from a specific node");
		mi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.DOTCIRCLE));
		mi.addActionListener(e -> {
			final Thread newThread = new Thread(() -> {
				if (noPathsError())
					return;
				final String modKey = "Alt+Shift";
				final String url1 = ShollUtils.URL + "#Analysis_of_Traced_Cells";
				final String url2 = "https://imagej.net/plugins/snt/analysis#Sholl_Analysis";
				final StringBuilder sb = new StringBuilder();
				sb.append("<html>");
				sb.append("<div WIDTH=500>");
				sb.append("To initiate <a href='").append(ShollUtils.URL).append("'>Sholl Analysis</a>, ");
				sb.append("you must select a focal point. You can do it coarsely by ");
				sb.append("right-clicking near a node and choosing <i>Sholl Analysis at Nearest ");
				sb.append("Node</i> from the contextual menu (Shortcut: \"").append(modKey).append("+A\").");
				sb.append("<p>Alternatively, for precise positioning of the center of analysis:</p>");
				sb.append("<ol>");
				sb.append("<li>Mouse over the path of interest. Press \"G\" to activate it</li>");
				sb.append("<li>Press \"").append(modKey).append("\" to select a node along the path</li>");
				sb.append("<li>Press \"").append(modKey).append("+A\" to start analysis</li>");
				sb.append("</ol>");
				sb.append("A walk-through of this procedure is <a href='").append(url2)
						.append("'>available online</a>. ");
				sb.append("For batch processing, run <a href='").append(url1)
						.append("'>Analyze>Sholl>Sholl Analysis (From Tracings)...</a>. ");
				GuiUtils.showHTMLDialog(sb.toString(), "Sholl Analysis How-to");
			});
			newThread.start();
		});
		return mi;
	}

	public void setSigmaPaletteListener(final SigmaPaletteListener listener) {
		if (sigmaPalette != null && listener == null) {
			sigmaPalette.removeListener(sigmaPaletteListener);
		}
		sigmaPaletteListener = listener;
	}

	/**
	 * Gets the Path Manager dialog.
	 *
	 * @return the {@link PathManagerUI} associated with this UI
	 */
	public PathManagerUI getPathManager() {
		return pmUI;
	}

	/**
	 * Gets the Fill Manager dialog.
	 *
	 * @return the {@link FillManagerUI} associated with this UI
	 */
	public FillManagerUI getFillManager() {
		return fmUI;
	}

	/**
	 * Gets the Reconstruction Viewer.
	 *
	 * @param initializeIfNull it true, initializes the Viewer if it has not yet
	 *                         been initialized
	 * @return the reconstruction viewer
	 */
	public Viewer3D getReconstructionViewer(final boolean initializeIfNull) {
		if (initializeIfNull && recViewer == null) {
			recViewer = new SNTViewer3D();
			recViewer.show();
			setReconstructionViewer(recViewer);
		}
		return recViewer;
	}

	/**
	 * Gets the active getSciViewSNT (SciView<>SNT bridge) instance.
	 *
	 * @param initializeIfNull it true, initializes SciView if it has not yet
	 *                         been initialized
	 * @return the SciView viewer
	 */
	public SciViewSNT getSciViewSNT(final boolean initializeIfNull) {
		if (initializeIfNull && sciViewSNT == null) {
			openSciView.doClick();
		}
		return (sciViewSNT == null) ? null : sciViewSNT;
	}

	public JPopupMenu getTracingCanvasPopupMenu() {
		return plugin.getTracingCanvas().getComponentPopupMenu();
	}

	protected void setReconstructionViewer(final Viewer3D recViewer) {
		this.recViewer = recViewer;
		openRecViewer.setEnabled(recViewer == null);
	}

	protected void setSciViewSNT(final SciViewSNT sciViewSNT) {
		this.sciViewSNT = sciViewSNT;
		SwingUtilities.invokeLater(() -> {
			openingSciView = openingSciView && this.sciViewSNT != null;
			openSciView.setEnabled(this.sciViewSNT == null);
			svSyncPathManager.setEnabled(this.sciViewSNT != null);
		});
	}

	protected void reset() {
		abortCurrentOperation();
		resetState();
		showStatus("Resetting", true);
	}

	protected void inputImageChanged() {
		final ImagePlus imp = plugin.getImagePlus();
		partsNearbyCSpinner.setSpinnerMinMax(1, plugin.getDepth());
		partsNearbyCSpinner.setEnabled(imp != null && !plugin.is2D());
		plugin.justDisplayNearSlices(partsNearbyCSpinner.isSelected(),
				(int) partsNearbyCSpinner.getValue(), false);
		final JPanel newSourcePanel = sourcePanel(imp);
		final GridBagLayout layout = (GridBagLayout) newSourcePanel.getLayout();
		for (int i = 0; i < sourcePanel.getComponentCount(); i++) {
			sourcePanel.remove(i);
			final Component component = newSourcePanel.getComponent(i);
			sourcePanel.add(component, layout.getConstraints(component));
		}
		revalidate();
		repaint();
		if (autoRbmi != null)
			autoRbmi.setSelected(plugin.getUseSubVolumeStats());
		final boolean validImage = plugin.accessToValidImageData();
		plugin.enableAstar(validImage);
		plugin.enableSnapCursor(validImage);
		resetState();
		arrangeCanvases(false);
		promptForAutoTracingAsAppropriate();
	}

	protected void abortCurrentOperation() {// FIXME: MOVE TO SNT?
		if (commandFinder != null)
			commandFinder.setVisible(false);
		switch (currentState) {
		case (SEARCHING):
			updateStatusText("Cancelling path search...", true);
			plugin.cancelSearch(false);
			break;
		case (CACHING_DATA):
			updateStatusText("Unloading cached data", true);
			break;
		case (RUNNING_CMD):
			updateStatusText("Requesting command cancellation", true);
			break;
		case (CALCULATING_HESSIAN_I):
		case (CALCULATING_HESSIAN_II):
			updateStatusText("Cancelling Hessian generation...", true);
			break;
		case (WAITING_FOR_SIGMA_POINT_I):
			if (sigmaPalette != null) sigmaPalette.dismiss();
			showStatus("Sigma adjustment cancelled...", true);
			break;
		case (PARTIAL_PATH):
			showStatus("Last temporary path cancelled...", true);
			plugin.cancelTemporary();
			if (plugin.currentPath != null)
				plugin.cancelPath();
			break;
		case (QUERY_KEEP):
			showStatus("Last segment cancelled...", true);
			if (plugin.temporaryPath != null)
				plugin.cancelTemporary();
			plugin.cancelPath();
			break;
		case (FILLING_PATHS):
			showStatus("Filling out cancelled...", true);
			plugin.stopFilling(); // will change UI state
			plugin.discardFill();
			return;
		case (FITTING_PATHS):
			showStatus("Fitting cancelled...", true);
			pmUI.cancelFit(true); // will change UI state
			return;
		case (SNT_PAUSED):
			showStatus("SNT is now active...", true);
			if (plugin.getImagePlus() != null)
				plugin.getImagePlus().unlock();
			plugin.pause(false, false); // will change UI state
			return;
		case (TRACING_PAUSED):
			if (!plugin.accessToValidImageData()) {
				showStatus("All tasks terminated", true);
				return;
			}
			showStatus("Tracing is now active...", true);
			plugin.pauseTracing(false, false); // will change UI state
			return;
		case (EDITING):
			showStatus("Exited from 'Edit Mode'...", true);
			plugin.enableEditMode(false); // will change UI state
			return;
		case (WAITING_FOR_SIGMA_CHOICE):
			showStatus("Close the sigma palette to abort sigma input...", true);
			return; // do nothing: Currently we have no control over the sigma
					// palette window
		case (WAITING_TO_START_PATH):
			// If user is aborting something in this state, something
			// went awry!?. Try to abort all possible lingering tasks
			pmUI.cancelFit(true);
			plugin.cancelSearch(true);
			if (plugin.currentPath != null)
				plugin.cancelPath();
			if (plugin.temporaryPath != null)
				plugin.cancelTemporary();
			showStatus("All tasks terminated", true);
			return;
		default:
			break;
		}
		if (activeWorker != null && !activeWorker.isDone()) activeWorker.kill();
		changeState(WAITING_TO_START_PATH);
	}

	protected void launchSigmaPaletteAround(final int x, final int y) {

		final int either_side_xy = 40;
		final int either_side_z = 15;
		final int z = plugin.getImagePlus().getZ();
		int x_min = x - either_side_xy;
		int x_max = x + either_side_xy;
		int y_min = y - either_side_xy;
		int y_max = y + either_side_xy;
		int z_min = z - either_side_z; // 1-based index
		int z_max = z + either_side_z; // 1-based index

		final int originalWidth = plugin.getImagePlus().getWidth();
		final int originalHeight = plugin.getImagePlus().getHeight();
		final int originalDepth = plugin.getImagePlus().getNSlices();

		if (x_min < 0) x_min = 0;
		if (y_min < 0) y_min = 0;
		if (z_min < 1) z_min = 1;
		if (x_max >= originalWidth) x_max = originalWidth - 1;
		if (y_max >= originalHeight) y_max = originalHeight - 1;
		if (z_max > originalDepth) z_max = originalDepth;

		final double[] sigmas = new double[16];
		for (int i = 0; i < sigmas.length; ++i) {
			sigmas[i] = ((i + 1) * plugin.getMinimumSeparation()) / 2;
		}

		sigmaPalette = new SigmaPalette(plugin);
		sigmaPalette.makePalette(x_min, x_max, y_min, y_max, z_min, z_max, sigmas, 4, 4, z);
		sigmaPalette.addListener(sigmaPaletteListener);
		if (sigmaPaletteListener != null) sigmaPalette.setParent(sigmaPaletteListener.getParent());
		updateStatusText("Adjusting \u03C3 and max visually...");
	}

	private String getState(final int state) {
		switch (state) {
		case READY:
			return "READY";
		case PARTIAL_PATH:
			return "PARTIAL_PATH";
		case SEARCHING:
			return "SEARCHING";
		case QUERY_KEEP:
			return "QUERY_KEEP";
		case CACHING_DATA:
			return "CACHING_DATA";
		case RUNNING_CMD:
			return "RUNNING_CMD";
		case FILLING_PATHS:
			return "FILLING_PATHS";
		case CALCULATING_HESSIAN_I:
			return "CALCULATING_HESSIAN_I";
		case CALCULATING_HESSIAN_II:
			return "CALCULATING_HESSIAN_II";
		case WAITING_FOR_SIGMA_POINT_I:
			return "WAITING_FOR_SIGMA_POINT_I";
		case WAITING_FOR_SIGMA_CHOICE:
			return "WAITING_FOR_SIGMA_CHOICE";
		case SAVING:
			return "SAVING";
		case LOADING:
			return "LOADING";
		case FITTING_PATHS:
			return "FITTING_PATHS";
		case EDITING:
			return "EDITING_MODE";
		case SNT_PAUSED:
			return "PAUSED";
		case TRACING_PAUSED:
			return "ANALYSIS_MODE";
		default:
			return "UNKNOWN";
		}
	}

	protected void togglePathsChoice() {
		assert SwingUtilities.isEventDispatchThread();
		showPathsSelected.setSelected(!showPathsSelected.isSelected());
	}

	protected void setSecondaryLayerTracingSelected(final boolean enable) {
		assert SwingUtilities.isEventDispatchThread();
		secLayerActivateCheckbox.setSelected(enable);
		updateSettingsString();
		showStatus("Tracing on scondary layer enabled", true);
	}

	boolean noSecondaryDataAvailableError() {
		if (!plugin.isSecondaryDataAvailable()) {
			guiUtils.error("No secondary image has been defined. Please create or load one first.", "Secondary Image Unavailable");
			setSecondaryLayerTracingSelected(false);
			return true;
		}
		return false;
	}

	protected void toggleSecondaryLayerTracing() {
		assert SwingUtilities.isEventDispatchThread();
		if (secLayerActivateCheckbox.isEnabled()) plugin.enableSecondaryLayerTracing(!secLayerActivateCheckbox.isSelected());
	}

	/** Should only be called by {@link SNT#enableAstar(boolean)} */
	protected void enableAStarGUI(final boolean enable) {
		SwingUtilities.invokeLater(() -> {
			aStarCheckBox.setSelected(enable);
			setEnableAutoTracingComponents(enable, true);
			showStatus("A* " + ((enable) ? "enabled" : "disabled"), true);
		});
	}

	protected void togglePartsChoice() {
		assert SwingUtilities.isEventDispatchThread();
		partsNearbyCSpinner.getCheckBox().setSelected(!partsNearbyCSpinner.getCheckBox().isSelected());
	}

	protected void toggleChannelAndFrameChoice() {
		assert SwingUtilities.isEventDispatchThread();
		onlyActiveCTposition.setSelected(!onlyActiveCTposition.isSelected());
	}

	protected void noValidImageDataError() {
		guiUtils.error("This option requires valid image data to be loaded.");
	}

	private boolean okToReplaceSecLayer() {
		return !plugin.isSecondaryDataAvailable() || guiUtils
				.getConfirmation("A secondary layer image is already loaded. Unload it?", "Discard Existing Layer?");
	}

	@SuppressWarnings("unused")
	private Boolean userPreferstoRunWizard(final String noButtonLabel) {
		if (askUserConfirmation && sigmaPalette == null) {
			final Boolean decision = guiUtils.getConfirmation2(//
					"You have not yet previewed filtering parameters. It is recommended that you do so "
							+ "at least once to ensure auto-tracing is properly tuned. Would you like to "
							+ "preview paramaters now by clicking on a representative region of the image?",
					"Adjust Parameters Visually?", "Yes. Adjust Visually...", noButtonLabel);
				if (decision != null && decision)
					changeState(WAITING_FOR_SIGMA_POINT_I);
			return decision;
		}
		return false;
	}

	private class GuiListener
			implements ActionListener, ItemListener, ImageListener {

		public GuiListener() {
			ImagePlus.addImageListener(this);
		}

		/* ImageListener */
		@Override
		public void imageClosed(final ImagePlus imp) {
			if (imp != plugin.getMainImagePlusWithoutChecks())
				return;
			if (!plugin.isDisplayCanvas(imp)) {
				// the image being closed contained valid data 
				plugin.pauseTracing(true, false);
			}
			SwingUtilities.invokeLater(() -> updateRebuildCanvasButton());
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see ij.ImageListener#imageOpened(ij.ImagePlus)
		 */
		@Override
		public void imageOpened(final ImagePlus imp) {
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see ij.ImageListener#imageUpdated(ij.ImagePlus)
		 */
		@Override
		public void imageUpdated(final ImagePlus imp) {
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.awt.event.ItemListener#itemStateChanged(java.awt.event.ItemEvent)
		 */
		@Override
		public void itemStateChanged(final ItemEvent e) {
			assert SwingUtilities.isEventDispatchThread();

			final Object source = e.getSource();

			if (source == useSnapWindow) {
				plugin.enableSnapCursor(useSnapWindow.isSelected());
			} else if (source == showPathsSelected) {
				plugin.setShowOnlySelectedPaths(showPathsSelected.isSelected());
				if (recorder != null)
					recorder.recordCmd("snt.getUI().setVisibilityFilter(\"selected\", "
						+ showPathsSelected.isSelected() + ")");
			} else if (source == onlyActiveCTposition) {
				plugin.setShowOnlyActiveCTposPaths(onlyActiveCTposition.isSelected(), true);
				if (recorder != null)
					recorder.recordCmd("snt.getUI().setVisibilityFilter(\"channel/frame\", "
							+ onlyActiveCTposition.isSelected() + ")");
			}
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see
		 * java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
		 */
		@Override
		public void actionPerformed(final ActionEvent e) {
			assert SwingUtilities.isEventDispatchThread();

			final Object source = e.getSource();

			if (source == secLayerActivateCheckbox) {

				if (secLayerActivateCheckbox.isSelected()) {
					if (!plugin.accessToValidImageData()) {
						plugin.enableSecondaryLayerTracing(false);
						noValidImageDataError();
						return;
					}
					plugin.enableSecondaryLayerTracing(true);

				} else {
					plugin.enableSecondaryLayerTracing(false);
				}

			} else if (source == exportCSVMenuItem && !noPathsError()) {

				final File saveFile = saveFile("Export All Paths as CSV...", "CSV_Properties.csv", "csv");
				if (saveFile == null)
					return; // user pressed cancel
				if (saveFile.exists() && !guiUtils.getConfirmation(
						"The file " + saveFile.getAbsolutePath() + " already exists. " + "Do you want to replace it?",
						"Override CSV file?")) {
					return;
				}
				final String savePath = saveFile.getAbsolutePath();
				showStatus("Exporting as CSV to " + savePath, false);

				final int preExportingState = currentState;
				changeState(SAVING);
				// Export here...
				try {
					pathAndFillManager.exportToCSV(saveFile);
				} catch (final IOException ioe) {
					showStatus("Exporting failed.", true);
					guiUtils.error("Writing traces to '" + savePath + "' failed. See Console for details.");
					changeState(preExportingState);
					ioe.printStackTrace();
					return;
				}
				showStatus("Export complete.", true);
				changeState(preExportingState);

			} else if (source == keepSegment) {

				plugin.confirmTemporary(true);

			} else if (source == junkSegment) {

				plugin.cancelTemporary();

			} else if (source == completePath) {

				plugin.finishedPath();

			} else if (source == quitMenuItem) {

				exitRequested();

			} else if (source == showOrHidePathList) {

				togglePathListVisibility();

			} else if (source == showOrHideFillList) {

				toggleFillListVisibility();

			}

		}

		private void toggleFillListVisibility() {
			assert SwingUtilities.isEventDispatchThread();
			if (!plugin.accessToValidImageData()) {
				guiUtils.error("Paths can only be filled when valid image data is available.");
			} else {
				synchronized (fmUI) {
					setFillListVisible(!fmUI.isVisible());
				}
			}
		}

		private void togglePathListVisibility() {
			assert SwingUtilities.isEventDispatchThread();
			synchronized (pmUI) {
				setPathListVisible(!pmUI.isVisible(), true);
			}
		}
	}

	/** Dynamic commands don't work well with CmdRunner. Use this class instead to run them */
	class DynamicCmdRunner {

		private final Class<? extends Command> cmd;
		private final int preRunState;
		private final boolean run;
		private final HashMap<String, Object> inputs;

		public DynamicCmdRunner(final Class<? extends Command> cmd, final HashMap<String, Object> inputs) {
			this(cmd, inputs, getState());
		}

		public DynamicCmdRunner(final Class<? extends Command> cmd, final HashMap<String, Object> inputs,
				final int uiStateDuringRun) {
			assert SwingUtilities.isEventDispatchThread();
			this.cmd = cmd;
			this.preRunState = getState();
			this.inputs = inputs;
			run = initialize();
			if (run && preRunState != uiStateDuringRun)
				changeState(uiStateDuringRun);
		}
	
		private boolean initialize() {
			if (preRunState == SNTUI.EDITING && plugin.getEditingPath() != null) {
				guiUtils.error(
						"Please finish editing " + plugin.getEditingPath().getName() + " before running this command.");
				return false;
			}
			return true;
		}

		public void run() {
			if (!run) return;
			try {
				SNTUtils.log("Running "+ cmd.getName());
				final CommandService cmdService = plugin.getContext().getService(CommandService.class);
				cmdService.run(cmd, true, inputs);
			} catch (final OutOfMemoryError e) {
				e.printStackTrace();
				guiUtils.error("There is not enough memory to complete command. See Console for details.");
			} finally {
				if (preRunState != getState())
					changeState(preRunState);
			}
		}

	}

	private class CmdRunner extends ActiveWorker {

		private final Class<? extends Command> cmd;
		private final int preRunState;
		private final boolean run;
		private final HashMap<String, Object> inputs;

		// Cmd that does not require rebuilding canvas(es) nor changing UI state
		public CmdRunner(final Class<? extends Command> cmd) {
			this(cmd, null, SNTUI.this.getState());
		}

		public CmdRunner(final Class<? extends Command> cmd, final HashMap<String, Object> inputs,
				final int uiStateduringRun) {
			assert SwingUtilities.isEventDispatchThread();
			this.cmd = cmd;
			this.preRunState = SNTUI.this.getState();
			this.inputs = inputs;
			run = initialize();
			if (run && preRunState != uiStateduringRun)
				changeState(uiStateduringRun);
			activeWorker = this;
		}

		private boolean initialize() {
			if (preRunState == SNTUI.EDITING && plugin.getEditingPath() != null) {
				guiUtils.error(
						"Please finish editing " + plugin.getEditingPath().getName() + " before running this command.");
				return false;
			}
			return true;
		}

		@Override
		public String doInBackground() {
			if (!run) {
				publish("Please finish ongoing task...");
				return "";
			}
			try {
				SNTUtils.log("Running "+ cmd.getName());
				final CommandService cmdService = plugin.getContext().getService(CommandService.class);
				final CommandModule cmdModule = cmdService.run(cmd, true, inputs).get();
				return (cmdModule.isCanceled()) ? cmdModule.getCancelReason() : "Command completed";
			} catch (final NullPointerException | IllegalArgumentException | CancellationException | InterruptedException | ExecutionException e2) {
				// NB: A NPE seems to happen if command is DynamicCommand
				e2.printStackTrace();
				return "Unfortunately an error occurred. See console for details.";
			}
		}

		@Override
		protected void process(final List<Object> chunks) {
			final String msg = (String) chunks.get(0);
			guiUtils.error(msg);
		}
	
		@Override
		protected void done() {
			showStatus("Command terminated...", false);
			if (run && preRunState != SNTUI.this.getState())
				changeState(preRunState);
		}
	}

	private static class InitViewer3DSystemProperties extends Viewer3D {
		static void init() {
			workaroundIntelGraphicsBug();
		}
	}

	private static class InternalUtils {

		static String getImportActionName(final int type) {
			switch (type) {
			case ImportAction.AUTO_TRACE_IMAGE:
				return "Autotrace Segmented Image File...";
			case ImportAction.SWC_DIR:
				return "Directory of SWCs...";
			case ImportAction.SWC:
				return "SWC...";
			case ImportAction.IMAGE:
				return "From File...";
			case ImportAction.ANY_RECONSTRUCTION:
				return "Guess File Type...";
			case ImportAction.JSON:
				return "JSON...";
			case ImportAction.DEMO:
				return "Load Demo Dataset...";
			case ImportAction.NDF:
				return "NDF...";
			case ImportAction.TRACES:
				return "TRACES...";
			default:
				throw new IllegalArgumentException("Unknown type '" + type + "'");
			}
		}

		static int getImportActionType(final String name) {
			switch (name) {
			case "Autotrace Segmented Image File...":
				return ImportAction.AUTO_TRACE_IMAGE;
			case "Directory of SWCs...":
				return ImportAction.SWC_DIR;
			case "e(SWC)...": // backwards compatibility
			case "SWC...":
				return ImportAction.SWC;
			case "From File...":
				return ImportAction.IMAGE;
			case "Guess File Type...":
				return ImportAction.ANY_RECONSTRUCTION;
			case "JSON...":
				return ImportAction.JSON;
			case "Load Demo Dataset...":
				return ImportAction.DEMO;
			case "NDF...":
				return ImportAction.NDF;
			case "TRACES...":
				return ImportAction.TRACES;
			default:
				return -1;
			}
		}

		static int getImportActionType(final File file) {
			if (file.isDirectory())
				return ImportAction.SWC_DIR;
			final String filename = file.getName().toLowerCase();
			if (filename.endsWith(".traces"))
				return ImportAction.TRACES;
			if (filename.endsWith("swc"))
				return ImportAction.SWC;
			if (filename.endsWith(".json"))
				return ImportAction.JSON;
			if (filename.endsWith(".ndf"))
				return ImportAction.NDF;
			if (filename.endsWith(".tif") || filename.endsWith(".tiff"))
				return ImportAction.IMAGE;
			return -1;
		}

		static void addSeparatorWithURL(final JComponent component, final String label, final boolean vgap,
				final GridBagConstraints c) {
			final String anchor = label.toLowerCase().replace(" ", "-").replace(":", "");
			final String uri = "https://imagej.net/plugins/snt/manual#" + anchor;
			final JLabel jLabel = GuiUtils.leftAlignedLabel(label, uri, true);
			GuiUtils.addSeparator(component, jLabel, vgap, c);
		}

		static String hotKeyLabel(final String text, final String key) {
			final String label = text.replaceFirst(key, "<u><b>" + key + "</b></u>");
			return (text.startsWith("<HTML>")) ? label : "<HTML>" + label;
		}

		static JPanel getTab() {
			final JPanel tab = new JPanel();
			tab.setBorder(BorderFactory.createEmptyBorder(MARGIN, MARGIN / 2, MARGIN / 2, MARGIN));
			tab.setLayout(new GridBagLayout());
			return tab;
		}

		static void ijmLogMessage() {
			if (Recorder.record) {
				final String recordString = "// NB: This recorder may not capture SNT's internal commands. Those are\n"
						+ "// better captured using SNT's own Script Recorder (Scripts -> New -> Record...)\n";
				Recorder.recordString(recordString);
			}
		}
	}

	private class SNTViewer3D extends Viewer3D {
		SNTViewer3D() {
			super(SNTUI.this.plugin);
			super.setDefaultColor(new ColorRGB(plugin.deselectedColor.getRed(),
					plugin.deselectedColor.getGreen(), plugin.deselectedColor.getBlue()));
		}

		@Override
		public Frame show() {
			final Frame frame = super.show();
			frame.addWindowListener(new WindowAdapter() {

				@Override
				public void windowClosing(final WindowEvent e) {
					openRecViewer.setEnabled(true);
					recViewer = null;
					recViewerFrame = null;
				}
			});
			return frame;
		}
	}

	private static class ActiveWorker extends SwingWorker<Object, Object> {

		@Override
		protected Object doInBackground() throws Exception {
			return null;
		}

		public boolean kill() {
			return cancel(true);
		}
	}

	private void addFileDrop(final Component component, final GuiUtils guiUtils) {
		new FileDrop(component, files -> {
			if (files.length == 0) { // Is this even possible?
				guiUtils.error("Dropped file(s) not recognized.");
				return;
			}
			if (files.length > 1) {
				guiUtils.error("Ony a single file (or directory) can be imported using drag-and-drop.");
				return;
			}
			final int type = InternalUtils.getImportActionType(files[0]);
			if (type == -1) {
				guiUtils.error(files[0].getName() + " cannot be imported using drag-and-drop.");
				return;
			}
			new ImportAction(type, files[0]).run();
		});
	}

	private File getAutosaveFile() {
		final String autosavePath = plugin.getPrefs().getTemp(SNTPrefs.AUTOSAVE_KEY, null);
		return (autosavePath == null) ? null : new File(autosavePath);
	}

	private void setAutosaveFile(final File file) {
		plugin.getPrefs().setTemp(SNTPrefs.AUTOSAVE_KEY,
				(file != null && SNTUtils.fileAvailable(file)) ? file.getAbsolutePath() : null);
	}

	private void revertFromBackup() {
		final File autosaveFile = getAutosaveFile();
		if (autosaveFile == null) {
			error("Current tracings have not been loaded from disk or the location of the data in the file system is not known."
					+ " Please load backup tracings manually.");
			return;
		}
		final List<File> copies = SNTUtils.getBackupCopies(autosaveFile); // list never null
		if (copies == null || copies.isEmpty()) {
			error("No time-stamped backups seem to exist for current data. Please create one first.");
			return;
		}
		final HashMap<String, File> map = new HashMap<>(copies.size());
		copies.forEach(cp -> map.put(SNTUtils.extractReadableTimeStamp(cp), cp));
		final String[] choices = map.keySet().toArray(new String[0]);
		final String choice = guiUtils.getChoice("Select timepoint for restore (current unsaved changes will be lost):",
				"Revert to Saved State...", choices, choices[0]);
		if (choice == null)
			return; // user pressed cancel
		plugin.loadTracesFile(map.get(choice));
		if (guiUtils.getConfirmation(
				"Data restored from snapshot backup. Shall this state now be saved to the main file, overriding its contents? "
						+ "Alternatively, you can dismiss this prompt and save data manually later on.",
				"Data Restored. Override Main File?", "Yes. Override main file.", "No. Do nothing.")) {
			saveToXML(autosaveFile);
		}
	}

	protected void saveToXML(final boolean timeStampedCopy) {
		if (noPathsError() || notReadyToSaveError()) return; // do not create empty files
		boolean successfull = false;
		File targetFile = getAutosaveFile();
		if (timeStampedCopy) {
			if (targetFile == null) {
				if (guiUtils.getConfirmation(
						"Traces must be saved at least once before a time-stamped backup is made. Save traces to main file now?",
						"No Main File Exists")) {
					saveToXML(false);
				}
				return;
			}
			final String suffix = SNTUtils.getTimeStamp();
			final String fName = SNTUtils.stripExtension(targetFile.getName());
			targetFile = new File(targetFile.getParentFile(), fName + suffix + ".traces");
		}
		try {
			if (!timeStampedCopy && (targetFile == null || !targetFile.exists() || !targetFile.canWrite())) {
				targetFile = saveFile("Save Traces As...", null, "traces");
			}
			if (targetFile != null)
				successfull = saveToXML(targetFile);
		} catch (final SecurityException ignored) {
			// do nothing
		}
		if (successfull) {
			SNTUtils.log(String.format("Saved to %s...", targetFile.getName()));
			plugin.discreteMsg(String.format("Saved to %s...", targetFile.getName()));
			if (!timeStampedCopy)
				plugin.getPrefs().setTemp(SNTPrefs.AUTOSAVE_KEY, targetFile.getAbsolutePath());
		} else {
			plugin.discreteMsg("File could not be saved! Please use File> menu instead.");
		}
	}

	protected boolean saveToXML(final File file) {
		if (noPathsError()) return false; // do not create empty files
		showStatus("Saving traces to " + file.getAbsolutePath(), false);

		final int preSavingState = currentState;
		changeState(SAVING);
		try {
			pathAndFillManager.writeXML(file.getAbsolutePath(), plugin.getPrefs().isSaveCompressedTraces());
		} catch (final IOException ioe) {
			showStatus("Saving failed.", true);
			guiUtils.error(
					"Writing traces to '" + file.getAbsolutePath() + "' failed. See Console for details.");
			changeState(preSavingState);
			ioe.printStackTrace();
			return false;
		}
		changeState(preSavingState);
		showStatus("Saving completed.", true);
		return true;
	}

	private void warnOnPossibleAnnotationLoss() {
		final boolean nag = plugin.accessToValidImageData() && plugin.getPrefs().getTemp("markerLoss-nag", true);
		if (nag && pathAndFillManager.getPaths().stream().anyMatch(p -> p.getSpineOrVaricosityCount() > 1)) {
			final Boolean prompt = guiUtils.getPersistentWarning(
					"Reminder: Multipoint ROIs marking spines or varicosities along neurites are not "
							+ "saved by SNT. Those should be saved independently, either by:<ul>"
							+ "<li>Storing the ROIs in the image overlay and saving the image as TIFF (NB: the active "
							+ "selection is always saved in the image header when the image is saved as TIFF)</li>"
							+ "<li>Using ROI Manager's <i>Save</i> command</li></ul>",
					"Possible Loss of ROI Markers");
			if (prompt != null) // do nothing if user dismissed the dialog
				plugin.getPrefs().setTemp("markerLoss-nag", !prompt.booleanValue());
		}
	}

	protected boolean saveAllPathsToSwc(final String filePath) {
		return saveAllPathsToSwc(filePath, null);
	}
	
	protected boolean saveAllPathsToSwc(final String filePath, final String commonFileHeader) {
		final Path[] primaryPaths = pathAndFillManager.getPathsStructured();
		final int n = primaryPaths.length;
		final String prefix = SNTUtils.stripExtension(filePath);
		final StringBuilder errorMessage = new StringBuilder();
		for (int i = 0; i < n; ++i) {
			final File swcFile = pathAndFillManager.getSWCFileForIndex(prefix, i);
			if (swcFile.exists())
				errorMessage.append(swcFile.getAbsolutePath()).append("<br>");
		}
		if (errorMessage.length() > 0) {
			errorMessage.insert(0, "The following file(s) would be overwritten:<br>");
			errorMessage.append("<b>Overwrite?</b>");
			if (!guiUtils.getConfirmation(errorMessage.toString(), "Overwrite SWC files?"))
				return false;
		}
		SNTUtils.log("Exporting paths... " + prefix);
		return pathAndFillManager.exportAllPathsAsSWC(primaryPaths, filePath, commonFileHeader);
	}

	private class ImportAction {

		private static final int TRACES = 0;
		private static final int SWC = 1;
		private static final int SWC_DIR = 2;
		private static final int JSON = 3;
		private static final int IMAGE = 4;
		private static final int ANY_RECONSTRUCTION = 5;
		private static final int DEMO = 6;
		private static final int AUTO_TRACE_IMAGE = 7;
		private static final int NDF = 8;

		private final int type;
		private File file;

		private ImportAction(final int type, final File file) {
			this.type = type;
			this.file = file;
		}

		private void run() {
			if (getState() != READY && getState() != TRACING_PAUSED) {
				guiUtils.blinkingError(statusText, "Please exit current state before importing data.");
				return;
			}
			if (!proceed()) return;
			final HashMap<String, Object> inputs = new HashMap<>();
			final int priorState = currentState;
			switch (type) {
			case AUTO_TRACE_IMAGE:
				if (plugin.isSecondaryDataAvailable()) {
					flushSecondaryDataPrompt();
				}
				inputs.put("useFileChoosers", true);
				(new DynamicCmdRunner(SkeletonConverterCmd.class, inputs, RUNNING_CMD)).run();
				break;
			case DEMO:
				if (plugin.isSecondaryDataAvailable()) {
					flushSecondaryDataPrompt();
				}
				final DemoRunner demoRunner = new DemoRunner(SNTUI.this, plugin);
				if (file != null) { // recorded command
					try (final Scanner scanner = new Scanner(file.getName())) {
						demoRunner.load(scanner.useDelimiter("\\D+").nextInt());
						return;
					} catch (final NoSuchElementException | IllegalStateException | IllegalArgumentException ex) {
						throw new IllegalArgumentException ("Invalid recorded option " + ex.getMessage());
					}
				}
				final Demo choice = demoRunner.getChoice();
				if (choice == null) {
					changeState(priorState);
					showStatus(null, true);
					return;
				}
				// Suppress the 'auto-tracing' prompt for this image. This
				// will be reset once SNT initializes with the new data
				plugin.getPrefs().setTemp("autotracing-prompt-armed", false);
				choice.load(); // will reset UI
				break;
			case IMAGE:
				if (plugin.isSecondaryDataAvailable()) {
					flushSecondaryDataPrompt();
				}
				inputs.put("file", file);
				(new DynamicCmdRunner(OpenDatasetCmd.class, inputs, LOADING)).run();
				break;
			case JSON:
				if (file != null) inputs.put("file", file);
				(new DynamicCmdRunner(JSONImporterCmd.class, inputs, LOADING)).run();
				break;
			case NDF:
				if (file != null) inputs.put("file", file);
				(new DynamicCmdRunner(NDFImporterCmd.class, inputs, LOADING)).run();
				break;
			case SWC_DIR:
				if (file != null) inputs.put("dir", file);
				(new DynamicCmdRunner(MultiSWCImporterCmd.class, inputs, LOADING)).run();
				break;
			case TRACES:
			case SWC:
			case ANY_RECONSTRUCTION:
				boolean succeed = false;
				changeState(LOADING);
				if (type == SWC) {
					succeed = loadSWCFile(file);
				} else if (type == TRACES){
					succeed = plugin.loadTracesFile(file);
					setAutosaveFile(file);
				} else if (type == ANY_RECONSTRUCTION){
					if (file == null)
						file = openReconstructionFile(null);
					if (file != null)
						succeed = plugin.loadTracings(file);
				}
				if (succeed)
					validateImgDimensions();
				changeState(priorState);
				break;
			default:
				throw new IllegalArgumentException("Unknown action");
			}
			if (file != null && recorder != null)
				recorder.recordComment("Detected option: \"" + file.getAbsolutePath() + "\"");

		}

		private boolean proceed() {
			return !plugin.isUnsavedChanges() || (plugin.isUnsavedChanges() && guiUtils.getConfirmation(
					"There are unsaved paths. Do you really want to load new data?", "Proceed with Import?"));
		}
	}

	private boolean loadSWCFile(final File file) {
		final SWCImportDialog importDialog = new SWCImportDialog(this, file);
		boolean success = false;
		if (importDialog.succeeded()) {
			final File f = importDialog.getFile();
			final double[] offsets = importDialog.getOffsets();
			final double[] scales = importDialog.getScalingFactors();
			success = pathAndFillManager.importSWC(f.getAbsolutePath(), importDialog.isAssumePixelCoordinates(),
					offsets[0], offsets[1], offsets[2], scales[0], scales[1], scales[2], scales[3],
					importDialog.isReplacePaths());
			if (!success)
				guiUtils.error(f.getAbsolutePath() + " does not seem to contain valid SWC data.");
		}

		return false;
	}

	private void flushSecondaryDataPrompt() {
		final String[] choices = new String[] { "Flush. I'll load new data manually", "Do nothing. Leave as is" };
		final String choice = guiUtils.getChoice("What should be done with the secondary image currently cached?",
				"Flush Filtered Data?", choices, choices[0]);
		if (choice != null && choice.startsWith("Flush"))
			plugin.flushSecondaryData();
	}

	private JMenuItem getImportActionMenuItem(final int type) {
		final JMenuItem jmi = (type == ImportAction.IMAGE) ? GuiUtils.MenuItems.fromFileImage()
				: new JMenuItem(InternalUtils.getImportActionName(type));
		jmi.addActionListener(e -> {
			new ImportAction(type, null).run();
		});
		return jmi;
	}

	public ScriptRecorder getRecorder(final boolean createIfNeeded) {
		if (recorder == null && createIfNeeded) {
			recorder = new ScriptRecorder();
			commandFinder.setRecorder(recorder);
			recorder.addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosed(final WindowEvent e) {
					recorder = null;
					commandFinder.setRecorder(null);
				}
			});
		}
		return recorder;
	}

}
