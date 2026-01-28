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

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

import javax.swing.*;

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.ImageStatistics;
import net.imagej.ImageJ;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.tracing.FillerThread;
import sc.fiji.snt.tracing.SearchInterface;
import sc.fiji.snt.tracing.SearchThread;

/**
 * Implements the <i>Fill Manager</i> dialog.
 *
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
public class FillManagerUI extends JDialog implements PathAndFillListener,
	ActionListener, FillerProgressCallback
{

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private static final long serialVersionUID = 1L;
	protected static final String FILLING_URI = "https://imagej.net/plugins/snt/walkthroughs#filling";
	private static final int MARGIN = 10;

	public enum State {READY, STARTED, ENDED, LOADED, STOPPED}

	private final SNT plugin;
	private final PathAndFillManager pathAndFillManager;
	private final JList<Fill> fillList;
	private final DefaultListModel<Fill> listModel;
	private final GuiUtils gUtils;
	private double maxThresholdValue = 0;
	private State currentState;

	private final JTextField manualThresholdInputField;
	private JLabel maxThresholdLabel;
	private JLabel currentThresholdLabel;
	private JLabel cursorPositionLabel;
	private JLabel fillTypeLabel;
	private JLabel statusText;
	private final JButton manualThresholdApplyButton;
	private final JButton exploredThresholdApplyButton;
	private JButton startFill;
	private JButton saveFill;
	private JButton stopFill;
	private final JButton reloadFill;
	private final JRadioButton cursorThresholdChoice;
	private final JRadioButton manualThresholdChoice;
	private final JRadioButton exploredThresholdChoice;
	private JPopupMenu exportFillsMenu;
	private final JCheckBox transparentCheckbox;
	private final JCheckBox storeExtraNodesCheckbox;


	/**
	 * Instantiates a new Fill Manager Dialog
	 *
	 * @param plugin the {@link SNT} instance to be associated
	 *               with this FillManager. It is assumed that its {@link SNTUI} is
	 *               available.
	 */
	public FillManagerUI(final SNT plugin) {
		super(plugin.getUI(), "Fill Manager");
		getRootPane().putClientProperty("JRootPane.menuBarEmbedded", false);

		this.plugin = plugin;
		pathAndFillManager = plugin.getPathAndFillManager();
		pathAndFillManager.addPathAndFillListener(this);
		listModel = new DefaultListModel<>();
		fillList = new JList<>(listModel);
		fillList.setCellRenderer(new FMCellRenderer());
		fillList.setVisibleRowCount(5);
		fillList.setPrototypeCellValue(PrototypeFill.instance);
		gUtils = new GuiUtils(this);
		setPlaceholderStatusLabels();
		initializeActions();

		assert SwingUtilities.isEventDispatchThread();

		setLayout(new GridBagLayout());
		final GridBagConstraints c = GuiUtils.defaultGbc();

		add(statusPanel(), c);
		c.gridy++;
		addSeparator(" Search Type:", c);
		final int storedPady = c.ipady;
		final Insets storedInsets = c.insets;
		c.ipady = 0;
		c.insets = new Insets(0, MARGIN, 0, MARGIN);
		add(fillTypeLabel, c);
		++c.gridy;
		c.ipady = storedPady;
		c.insets = storedInsets;
		addSeparator(" Search Status:", c);
		c.insets = new Insets(0, MARGIN, 0, MARGIN);
		add(currentThresholdLabel, c);
		++c.gridy;
		add(maxThresholdLabel, c);
		++c.gridy;
		add(cursorPositionLabel, c);
		++c.gridy;
		c.ipady = storedPady;
		c.insets = storedInsets;

		addSeparator(" Distance Threshold for Fill Search:", c);

		final JPanel distancePanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = GuiUtils.defaultGbc();
		cursorThresholdChoice = new JRadioButton("Set by clicking on traced structure (preferred)"); // placeholder default

		final JPanel t1Panel = leftAlignedPanel();
		t1Panel.add(cursorThresholdChoice);
		distancePanel.add(t1Panel, gdb);
		++gdb.gridy;

		manualThresholdChoice = new JRadioButton("Specify manually:");
		manualThresholdInputField = new JTextField("", 6);
		manualThresholdApplyButton = GuiUtils.Buttons.smallButton("Apply");
		manualThresholdApplyButton.addActionListener(this);
		final JPanel t2Panel = leftAlignedPanel();
		t2Panel.add(manualThresholdChoice);
		t2Panel.add(manualThresholdInputField);
		t2Panel.add(manualThresholdApplyButton);
		distancePanel.add(t2Panel, gdb);
		++gdb.gridy;

		exploredThresholdChoice = new JRadioButton("Use explored maximum");
		exploredThresholdApplyButton = GuiUtils.Buttons.smallButton("Apply");
		exploredThresholdApplyButton.addActionListener(this);
		final JPanel t3Panel = leftAlignedPanel();
		t3Panel.add(exploredThresholdChoice);
		t3Panel.add(exploredThresholdApplyButton);
		distancePanel.add(t3Panel, gdb);
		++gdb.gridy;

		final JButton defaults = GuiUtils.Buttons.smallButton("Defaults");
		defaults.addActionListener( e -> {
			plugin.setFillThreshold(-1);
			cursorThresholdChoice.setSelected(true);
		});
		final JPanel defaultsPanel = leftAlignedPanel();
		defaultsPanel.add(defaults);
		distancePanel.add(defaultsPanel, gdb);
		add(distancePanel, c);
		++c.gridy;

		final ButtonGroup group = new ButtonGroup();
		group.add(cursorThresholdChoice);
		group.add(exploredThresholdChoice);
		group.add(manualThresholdChoice);
		final RadioGroupListener listener = new RadioGroupListener();
		cursorThresholdChoice.addActionListener(listener);
		manualThresholdChoice.addActionListener(listener);
		exploredThresholdChoice.addActionListener(listener);
		cursorThresholdChoice.setSelected(true);
		manualThresholdApplyButton.setEnabled(manualThresholdChoice.isSelected());
		exploredThresholdApplyButton.setEnabled(exploredThresholdChoice.isSelected());

		addSeparator(" Performance Impacting Options:", c);

		transparentCheckbox = new JCheckBox("Transparent overlay");
		transparentCheckbox.setToolTipText("Enabling this option allows you better inspect fills,\nbut may slow down filling");
		transparentCheckbox.addActionListener(e -> plugin.setFillTransparent(transparentCheckbox.isSelected()));
		final JPanel transparencyPanel = leftAlignedPanel();
		transparencyPanel.add(transparentCheckbox);
		add(transparencyPanel, c);
		c.gridy++;
		storeExtraNodesCheckbox = new JCheckBox(" Store above-threshold nodes");
		storeExtraNodesCheckbox.addActionListener(e -> plugin.setStoreExtraFillNodes(storeExtraNodesCheckbox.isSelected()));
		storeExtraNodesCheckbox.setToolTipText("Enabling this option lets you resume progress with the same fill,\nbut may impact performance");
		final JPanel storeExtraNodesPanel = leftAlignedPanel();
		storeExtraNodesPanel.add(storeExtraNodesCheckbox);
		add(storeExtraNodesPanel, c);
		c.gridy++;

		GuiUtils.addSeparator((JComponent) getContentPane(), " Stored Fill(s):", true, c);
		++c.gridy;

		final JScrollPane scrollPane = new JScrollPane();
		scrollPane.getViewport().add(fillList);
		final JPanel listPanel = new JPanel(new BorderLayout());
		listPanel.add(scrollPane, BorderLayout.CENTER);
		add(listPanel, c);
		++c.gridy;
		final JButton deleteFills = new JButton("Delete");
		deleteFills.addActionListener(e -> {
			if (noFillsError())
				return;
			final int[] selectedIndices = fillList.getSelectedIndices();
			if (selectedIndices.length < 1
					&& gUtils.getConfirmation("No fill was select for deletion. Delete All?", "Delete All?")) {
				pathAndFillManager.deleteFills(IntStream.range(0, fillList.getModel().getSize()).toArray());
			}
			pathAndFillManager.deleteFills(selectedIndices);
			plugin.updateTracingViewers(false);

		});
		reloadFill = new JButton("Reload");
		reloadFill.addActionListener(e -> {
			if (!noFillsError()) reload("Reload");
		});

		assembleExportFillsMenu();
		final JButton exportFills = new JButton("Export As...");
		exportFills.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(final MouseEvent e) {
				if (exportFills.isEnabled())
					exportFillsMenu.show(e.getComponent(), e.getX(), e.getY());
			}
		});

		add(SNTUI.InternalUtils.buttonPanel(deleteFills, reloadFill, exportFills), c);
		++c.gridy;

		pack();
		adjustListPlaceholder();
		changeState(State.READY);
		setLocationRelativeTo(plugin.getUI());
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(final WindowEvent ignored) {
				setVisible(false);
			}
		});


	}

	private int[] getSelectedIndices(final String msg) {
		int[] selectedIndices = (fillList.getModel().getSize() == 1 ) ? new int[] {0} : fillList.getSelectedIndices();
		if (selectedIndices.length < 1 && gUtils.getConfirmation(
				"No fill was select for " + msg.toLowerCase() + ". " + msg + " all?", msg + " All?"))
		{
			selectedIndices = IntStream.range(0, fillList.getModel().getSize()).toArray();
		}
		return selectedIndices;
	}

    private List<FillerThread> getSelectedFills(final String msg) {
        int[] selectedIndices = getSelectedIndices(msg);
        final List<FillerThread> fills = new ArrayList<>(selectedIndices.length);
        final boolean useSecondary = plugin.isTracingOnSecondaryImageActive();
        final RandomAccessibleInterval<? extends RealType<?>> scope = useSecondary ? plugin.getSecondaryData()
                : plugin.getLoadedData();
        final Calibration calibration = plugin.getImagePlus().getCalibration();
        final ImageStatistics stats = plugin.getStats();
        final List<Fill> allFills = pathAndFillManager.getAllFills();
        for (int i : selectedIndices) {
            FillerThread filler = FillerThread.fromFill(scope, calibration, stats, allFills.get(i));
            fills.add(filler);
        }
        return fills;
    }

	private void reload(final String msg) {
		int[] selectedIndices = getSelectedIndices(msg);
		if (selectedIndices.length == 0)
			return;
		pathAndFillManager.reloadFills(selectedIndices);
		fillList.setSelectedIndices(selectedIndices);
		changeState(State.LOADED);
	}

	private JPanel statusPanel() {
		statusText = new JLabel("Loading Fill Manager...");
		startFill = GuiUtils.Buttons.smallButton("Start");
		startFill.addActionListener(this);
		stopFill = GuiUtils.Buttons.smallButton("Stop");
		stopFill.addActionListener(this);
		saveFill = GuiUtils.Buttons.smallButton("Store");
		saveFill.addActionListener(this);
		final JButton discardFill = GuiUtils.Buttons.smallButton("Cancel/Discard");
		discardFill.addActionListener( e -> {
			plugin.stopFilling();
			plugin.discardFill(); // will change state
		});
		return SNTUI.InternalUtils.statusPanel(statusText, startFill, stopFill, saveFill, discardFill);
	}

	private void setPlaceholderStatusLabels() {
		fillTypeLabel = GuiUtils.leftAlignedLabel("Target image: primary. Cost-function f(xxx)...", false);
		fillTypeLabel.setToolTipText("Modifiable in the Auto-tracing panel of SNT's main dialog");
		currentThresholdLabel = GuiUtils.leftAlignedLabel("No Pahs are currently being filled...", false);
		maxThresholdLabel = GuiUtils.leftAlignedLabel("Max. explored distance: N/A", false);
		cursorPositionLabel = GuiUtils.leftAlignedLabel("Cursor position: N/A", false);
		updateSettingsString();
	}

	protected void updateSettingsString() {
		final StringBuilder sb = new StringBuilder();
		sb.append("Image: ").append( (plugin.isTracingOnSecondaryImageActive()) ? "Secondary" : "Main");
		sb.append("; Cost func.: ").append(plugin.getCostType());
		fillTypeLabel.setText(sb.toString());
	}

    private class FMCellRenderer extends DefaultListCellRenderer {

        private static final long serialVersionUID = 1L;
        private Font boldFont;  // Cached bold font

        @Override
        public Component getListCellRendererComponent(final JList<?> list, final Object value, final int index,
                                                      final boolean isSelected, final boolean cellHasFocus)
        {
            final Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

            final Fill fill = (Fill) value;
            if (isFillLoaded(fill)) {
                if (boldFont == null || !boldFont.getFamily().equals(getFont().getFamily())) {
                    boldFont = getFont().deriveFont(Font.BOLD);
                }
                c.setFont(boldFont);
            } else if (fill instanceof PrototypeFill) {
                c.setEnabled(false);
            }
            return c;
        }
    }

	private static class PrototypeFill extends Fill {
		private static final PrototypeFill instance = new PrototypeFill();
		@Override
		public String toString() {
			return "No fillings currently exist";
		}
	}

    private boolean isFillLoaded(Fill fill) {
        return pathAndFillManager.getLoadedFills().containsKey(fill);
    }

	protected void adjustListPlaceholder() {
		if (listModel.isEmpty()) {
			listModel.addElement(PrototypeFill.instance);
		} else {
			listModel.removeElement(PrototypeFill.instance);
		}
	}

	private void addSeparator(final String label, final GridBagConstraints c) {
		final JLabel jLabel = GuiUtils.leftAlignedLabel(label, FILLING_URI, true);
		GuiUtils.addSeparator((JComponent) getContentPane(), jLabel, true, c);
		++c.gridy;
	}

	private JPanel leftAlignedPanel() {
		final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEADING));
		panel.setBorder(BorderFactory.createEmptyBorder(0, MARGIN, 0, MARGIN));
		return panel;
	}

	protected void setEnabledWhileFilling() {
		assert SwingUtilities.isEventDispatchThread();
		fillTypeLabel.setEnabled(true);
		cursorPositionLabel.setEnabled(true);
		maxThresholdLabel.setEnabled(exploredThresholdChoice.isSelected());
		currentThresholdLabel.setEnabled(true);
		cursorPositionLabel.setEnabled(true);
	}

	protected void setEnabledWhileNotFilling() {
		assert SwingUtilities.isEventDispatchThread();
		fillTypeLabel.setEnabled(true);
		cursorPositionLabel.setEnabled(true);
		maxThresholdLabel.setEnabled(false);
		currentThresholdLabel.setEnabled(false);
		cursorPositionLabel.setEnabled(false);
	}

	protected void setEnabledNone() {
		assert SwingUtilities.isEventDispatchThread();
		fillTypeLabel.setEnabled(false);
		cursorPositionLabel.setEnabled(false);
		maxThresholdLabel.setEnabled(false);
		currentThresholdLabel.setEnabled(false);
	}

	/**
	 * Sets the transparency state of fills.
	 *
	 * @param transparent true to make fills transparent, false for opaque
	 */
	public void setFillTransparent(final boolean transparent) {
		if (this.transparentCheckbox != null) {
			SwingUtilities.invokeLater(() -> this.transparentCheckbox.setSelected(transparent));
		}
		plugin.setFillTransparent(transparent);
	}

	/* (non-Javadoc)
	 * @see PathAndFillListener#setPathList(java.lang.String[], Path, boolean)
	 */
	@Override
	public void setPathList(final List<Path> pathList, final Path justAdded,
		final boolean expandAll) // ignored
	{}

	/* (non-Javadoc)
	 * @see PathAndFillListener#setFillList(java.lang.String[])
	 */
	@Override
	public void setFillList(final List<Fill> fillList) {
		SwingUtilities.invokeLater(() -> {
			listModel.removeAllElements();
			fillList.forEach(listModel::addElement);
			adjustListPlaceholder();
		});
	}

	/* (non-Javadoc)
	 * @see PathAndFillListener#setSelectedPaths(java.util.HashSet, java.lang.Object)
	 */
	@Override
	public void setSelectedPaths(final Collection<Path> selectedPathSet,
		final Object source)
	{
		// This dialog doesn't deal with paths, so ignore this.
	}

	/* (non-Javadoc)
	 * @see java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
	 */
	// Action command interface
	private interface FillAction {
		boolean canHandle(Object source);
		void execute();
	}

	// Action registry
	private final List<FillAction> actions = new ArrayList<>();

	// Initialize actions
	private void initializeActions() {
		actions.add(new ExploredThresholdApplyAction());
		actions.add(new ManualThresholdApplyAction());
		actions.add(new StopFillAction());
		actions.add(new SaveFillAction());
		actions.add(new StartFillAction());
	}

	@Override
	public void actionPerformed(final ActionEvent ae) {
		assert SwingUtilities.isEventDispatchThread();

		final Object source = ae.getSource();

		if (noPathsError() || noValidImgError()) {
			return;
		}

		// Process actions in order
		for (FillAction action : actions) {
			if (action.canHandle(source)) {
				action.execute();
				return;
			}
		}

		SNTUtils.error("BUG: FillWindow received an event from an unknown source.");
	}

	// Action implementations
	private class ExploredThresholdApplyAction implements FillAction {
		@Override
		public boolean canHandle(Object source) {
			return source == exploredThresholdApplyButton;
		}

		@Override
		public void execute() {
			try {
				plugin.setFillThreshold(maxThresholdValue);
			} catch (final IllegalArgumentException ignored) {
				gUtils.error("No explored maximum exists yet.");
				cursorThresholdChoice.setSelected(true);
			}
		}
	}

	private class ManualThresholdApplyAction implements FillAction {
		@Override
		public boolean canHandle(Object source) {
			return source == manualThresholdApplyButton || source == manualThresholdInputField;
		}

		@Override
		public void execute() {
			try {
				plugin.setFillThreshold(Double.parseDouble(manualThresholdInputField.getText()));
			} catch (final IllegalArgumentException ignored) { // includes NumberFormatException
				gUtils.error("The threshold '" + manualThresholdInputField.getText() +
					"' is not a valid option. Only positive values accepted.");
				cursorThresholdChoice.setSelected(true);
			}
		}
	}

	private class StopFillAction implements FillAction {
		@Override
		public boolean canHandle(Object source) {
			return source == stopFill;
		}

		@Override
		public void execute() {
			try {
				plugin.stopFilling(); // will change state
			} catch (final IllegalArgumentException ex) {
				gUtils.error(ex.getMessage());
			}
		}
	}

	private class SaveFillAction implements FillAction {
		@Override
		public boolean canHandle(Object source) {
			return source == saveFill;
		}

		@Override
		public void execute() {
			try {
				plugin.saveFill(); // will change state
			} catch (final IllegalArgumentException ex) {
				gUtils.error(ex.getMessage());
			}
		}
	}

	private class StartFillAction implements FillAction {
		@Override
		public boolean canHandle(Object source) {
			return source == startFill;
		}

		@Override
		public void execute() {
			if (plugin.fillerThreadPool != null) {
				gUtils.error("A filling operation is already running.");
				return;
			}
			if (plugin.fillerSet.isEmpty()) {
				if (plugin.getUI().getPathManager().selectionExists()) {
					plugin.initPathsToFill(new HashSet<>(plugin.getUI().getPathManager().getSelectedPaths(false)));
					applyCheckboxSelections();
					plugin.startFilling();
				} else {
					final int ans = gUtils.yesNoDialog("There are no paths selected in Path Manager. Would you like to "
							+ "fill all paths? Alternatively, you can dismiss this prompt, select a subset in the Path "
							+ "Manager list, and rerun. ", "Fill All Paths?", "Yes. Fill All", "No. I'll Select A Subset");
					if (ans == JOptionPane.YES_OPTION) {
						plugin.initPathsToFill(new HashSet<>(plugin.getUI().getPathManager().getSelectedPaths(true)));
						applyCheckboxSelections();
						plugin.startFilling();
					}
				}
			} else {
				try {
					applyCheckboxSelections();
					plugin.startFilling();
				} catch (final IllegalArgumentException ex) {
					gUtils.error(ex.getMessage());
				}
			}
		}
	}

	private void applyCheckboxSelections() {
		plugin.setStoreExtraFillNodes(storeExtraNodesCheckbox.isSelected());
		plugin.setStopFillAtThreshold(manualThresholdChoice.isSelected());
		plugin.setFillTransparent(transparentCheckbox.isSelected());
	}

	private boolean noFillsError() {
		final boolean noFills = listModel.getSize() == 0 || listModel.get(0) instanceof PrototypeFill;
		if (noFills) gUtils.error("There are no fills stored.");
		return noFills;
	}

	private boolean noValidImgError() {
		final boolean noValidImg = !plugin.accessToValidImageData();
		if (noValidImg)
			gUtils.error("Filling requires valid image data to be loaded.");
		return noValidImg;
	}

	private boolean noPathsError() {
		final boolean noPaths = pathAndFillManager.size() == 0;
		if (noPaths)
			gUtils.error("There are no traced paths.");
		return noPaths;
	}

	// Export menu action interface
	private interface ExportAction {
		String getMenuText();
		void execute();
	}

	// Export action implementations
	private class AnnotatedDistanceMapExportAction implements ExportAction {
		@Override
		public String getMenuText() {
			return "Annotated Distance Map";
		}

		@Override
		public void execute() {
			ImagePlus imp = exportAsImp(FillConverter.ResultType.DISTANCE);
			if (imp != null) {
				ij.IJ.run(imp,
					   "Calibration Bar...",
					   "location=[Upper Right] fill=White label=Black number=10 decimal=3 zoom=1 overlay");
				imp.show();
			}
		}
	}

	private class BinaryMaskExportAction implements ExportAction {
		@Override
		public String getMenuText() {
			return "Binary Mask";
		}

		@Override
		public void execute() {
			ImagePlus imp = exportAsImp(FillConverter.ResultType.BINARY_MASK);
			if (imp != null)
				imp.show();
		}
	}

	private class DistanceImageExportAction implements ExportAction {
		@Override
		public String getMenuText() {
			return "Distance Image";
		}

		@Override
		public void execute() {
			ImagePlus imp = exportAsImp(FillConverter.ResultType.DISTANCE);
			if (imp != null)
				imp.show();
		}
	}

	private class GrayscaleImageExportAction implements ExportAction {
		@Override
		public String getMenuText() {
			return "Grayscale Image";
		}

		@Override
		public void execute() {
			ImagePlus imp = exportAsImp(FillConverter.ResultType.SAME);
			if (imp != null)
				imp.show();
		}
	}

	private class LabelImageExportAction implements ExportAction {
		@Override
		public String getMenuText() {
			return "Label Image";
		}

		@Override
		public void execute() {
			ImagePlus imp = exportAsImp(FillConverter.ResultType.LABEL);
			if (imp != null)
				imp.show();
		}
	}

	private class CsvSummaryExportAction implements ExportAction {
		@Override
		public String getMenuText() {
			return "CSV Summary...";
		}

		@Override
		public void execute() {
			saveFills();
		}
	}

	private void assembleExportFillsMenu() {
		exportFillsMenu = new JPopupMenu();

		// Create export actions
		List<ExportAction> exportActions = Arrays.asList(
			new AnnotatedDistanceMapExportAction(),
			new BinaryMaskExportAction(),
			new DistanceImageExportAction(),
			new GrayscaleImageExportAction(),
			new LabelImageExportAction()
		);

		// Add image export actions
		for (ExportAction action : exportActions) {
			JMenuItem jmi = new JMenuItem(action.getMenuText());
			jmi.addActionListener(e -> action.execute());
			exportFillsMenu.add(jmi);
		}

		// Add separator and CSV export
		exportFillsMenu.addSeparator();
		CsvSummaryExportAction csvAction = new CsvSummaryExportAction();
		JMenuItem csvItem = new JMenuItem(csvAction.getMenuText());
		csvItem.addActionListener(e -> csvAction.execute());
		exportFillsMenu.add(csvItem);
	}

	private ImagePlus exportAsImp(final FillConverter.ResultType resultType) {
		if (noFillsError())
			return null;
		if (plugin.fillerSet.isEmpty()) {
			gUtils.error("All stored Fills are currently unloaded. Currently, only loaded fills "
					+ "(those highlighted in the <i>Stored Fill(s)</i> list) can be exported into an "
					+ "image. Please reload the Fill(s) you are attempting to export and re-try.");
			return null;
		}
		final List<FillerThread> fillers = getSelectedFills("export");
		if (fillers.isEmpty()) {
			gUtils.error("You must select at least one Fill for export.");
			return null;
		}
		final ImagePlus imp;
        switch (resultType) {
            case SAME -> imp = plugin.getFilledImp();
            case BINARY_MASK -> imp = plugin.getFilledBinaryImp();
            case DISTANCE -> imp = plugin.getFilledDistanceImp();
            case LABEL -> imp = plugin.getFilledLabelImp();
            default -> throw new IllegalArgumentException("Unknown result type: " + resultType);
        }
		if (imp != null) imp.setTitle("Fill_" + resultType);
		return imp;
	}

	private void saveFills() {
		if (noFillsError()) return;

		if (pathAndFillManager.getAllFills().isEmpty()) {
			gUtils.error("There are currently no fills. CSV file would be empty.");
			return;
		}

		final File saveFile = plugin.getUI().saveFile("Export CSV Summary...", "Fills.csv", "csv");
		if (saveFile == null) return; // user pressed cancel;
		plugin.getUI().showStatus("Exporting CSV data to " + saveFile
			.getAbsolutePath(), false);
		try {
			pathAndFillManager.exportFillsAsCSV(saveFile);
			plugin.getUI().showStatus("Done... ", true);
		}
		catch (final IOException ioe) {
			gUtils.error("Saving to " + saveFile.getAbsolutePath() +
				" failed. See console for details");
			SNTUtils.error("IO Error", ioe);
		}
	}

	/* (non-Javadoc)
	 * @see FillerProgressCallback#maximumDistanceCompletelyExplored(SearchThread, double)
	 */
	@Override
	public void maximumDistanceCompletelyExplored(final FillerThread source,
		final double f)
	{
		SwingUtilities.invokeLater(() -> {
			maxThresholdLabel.setText("Max. explored distance: " + SNTUtils.formatDouble(f, 3));
			maxThresholdValue = f;
		});
	}

	/* (non-Javadoc)
	 * @see SearchProgressCallback#pointsInSearch(SearchInterface, int, int)
	 */
	@Override
	public void pointsInSearch(final SearchInterface source, final long inOpen,
							   final long inClosed)
	{
		// Do nothing...
	}

	/* (non-Javadoc)
	 * @see SearchProgressCallback#finished(SearchInterface, boolean)
	 */
	@Override
	public void finished(final SearchInterface source, final boolean success) {
		if (!success) {
			final int exitReason = ((SearchThread) source).getExitReason();
			if (exitReason != SearchThread.CANCELLED) {
				final String reason = SearchThread.EXIT_REASONS_STRINGS[((SearchThread) source).getExitReason()];
				new GuiUtils(this).error("Filling thread exited prematurely (Error code: '" + reason + "'). "
						+ "With debug mode on, see Console for details.", "Filling Error");
			} else {
				changeState(State.STOPPED);
			}
		}
	}

	/* (non-Javadoc)
	 * @see SearchProgressCallback#threadStatus(SearchInterface, int)
	 */
	@Override
	public void threadStatus(final SearchInterface source, final int currentStatus) {
		// do nothing
	}

	protected void showMouseThreshold(final double t) {
		SwingUtilities.invokeLater(() -> {
			String newStatus;
			if (t < 0) {
				newStatus = "Cursor position: Not reached by search yet";
			}
			else {
				newStatus = "Cursor position: Distance from path is " + SNTUtils
					.formatDouble(t, 3);
			}
			cursorPositionLabel.setText(newStatus);
		});
	}


	/**
	 * Changes this UI to a new state. Does nothing if {@code newState} is the
	 * current UI state
	 *
	 * @param newState the new state, e.g., {@link State#READY},
	 *                 {@link State#STARTED}, etc.
	 */
	protected void changeState(final State newState) {

		if (newState == currentState)
			return;
		currentState = newState;
		SwingUtilities.invokeLater(() -> {
            switch (newState) {
                case READY -> {
                    updateStatusText("Press <i>Start</i> to initiate filling...");
                    startFill.setEnabled(true);
                    stopFill.setEnabled(false);
                    saveFill.setEnabled(false);
                    reloadFill.setEnabled(true);
                }
                case STARTED -> {
                    updateStatusText("Filling started...");
                    startFill.setEnabled(false);
                    stopFill.setEnabled(true);
                    saveFill.setEnabled(false);
                    reloadFill.setEnabled(false);
                }
                case LOADED -> {
                    updateStatusText("Press <i>Start</i> to initiate filling...");
                    startFill.setEnabled(true);
                    stopFill.setEnabled(false);
                    saveFill.setEnabled(true);
                    reloadFill.setEnabled(false);
                }
                case STOPPED -> {
                    updateStatusText("Filling stopped...");
                    startFill.setEnabled(true);
                    stopFill.setEnabled(false);
                    saveFill.setEnabled(true);
                    reloadFill.setEnabled(false);
                }
                case ENDED -> {
                    updateStatusText("Filling concluded... Store result?");
                    startFill.setEnabled(true);
                    stopFill.setEnabled(false);
                    saveFill.setEnabled(true);
                    reloadFill.setEnabled(false);
                }
                default -> SNTUtils.error("BUG: switching to an unknown state");
            }
		});
	}

	private void updateStatusText(final String newStatus) {
		statusText.setText("<html><strong>" + newStatus + "</strong></html>");
	}

	/* IDE debug method */
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final ImagePlus imp = new ImagePlus();
		final SNT snt = new SNT(ij.context(), imp);
		final FillManagerUI fm = new FillManagerUI(snt);
		fm.setVisible(true);
	}

	protected void updateThresholdWidget(final double newThreshold) {
		SwingUtilities.invokeLater(() -> {
			final String value = SNTUtils.formatDouble(newThreshold, 3);
			manualThresholdInputField.setText(value);
			currentThresholdLabel.setText("Current threshold distance: " + value);
		});
	}

	private class RadioGroupListener implements ActionListener {

		@Override
		public void actionPerformed(final ActionEvent e) {
			manualThresholdApplyButton.setEnabled(manualThresholdChoice.isSelected());
			exploredThresholdApplyButton.setEnabled(exploredThresholdChoice.isSelected());
		}
	}
}
