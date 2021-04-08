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
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.stream.IntStream;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.BevelBorder;

import ij.ImagePlus;
import net.imagej.ImageJ;
import sc.fiji.snt.gui.GuiUtils;

/**
 * Implements the <i>Fill Manager</i> dialog.
 *
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
public class FillManagerUI extends JDialog implements PathAndFillListener,
	ActionListener, FillerProgressCallback
{

	private static final long serialVersionUID = 1L;
	protected static final String FILLING_URI = "https://imagej.net/SNT:_Step-By-Step_Instructions#Filling";
	private static final int MARGIN = 10;

	protected static final int READY = 0;
	protected static final int STARTED = 1;
	protected static final int ABORTED = 2;
	protected static final int ENDED = 3;

	private final SNT plugin;
	private final PathAndFillManager pathAndFillManager;
	private final JList<String> fillList;
	private final DefaultListModel<String> listModel;
	private final GuiUtils gUtils;
	private float maxThresholdValue = 0;
	private int currentState = -1;

	private JTextField manualThresholdInputField;
	private JLabel maxThresholdLabel;
	private JLabel currentThresholdLabel;
	private JLabel cursorPositionLabel;
	private JLabel statusText;
	private JButton manualThresholdApplyButton;
	private JButton exploredThresholdApplyButton;
	private JButton startFill;
	private JButton saveFill;
	private JButton stopFill;
	private JRadioButton cursorThresholdChoice;
	private JRadioButton manualThresholdChoice;
	private JRadioButton exploredThresholdChoice;
	private JPopupMenu exportFillsMenu;
	private JCheckBox transparentCheckbox;


	/**
	 * Instantiates a new Fill Manager Dialog
	 *
	 * @param plugin the the {@link SNT} instance to be associated
	 *               with this FillManager. It is assumed that its {@link SNTUI} is
	 *               available.
	 */
	public FillManagerUI(final SNT plugin) {
		super(plugin.getUI(), "Fill Manager");

		this.plugin = plugin;
		pathAndFillManager = plugin.getPathAndFillManager();
		pathAndFillManager.addPathAndFillListener(this);
		listModel = new DefaultListModel<>();
		fillList = new JList<>(listModel);
		fillList.setCellRenderer(new FMCellRenderer());
		fillList.setVisibleRowCount(5);
		fillList.setPrototypeCellValue(FMCellRenderer.LIST_PLACEHOLDER);
		gUtils = new GuiUtils(this);

		assert SwingUtilities.isEventDispatchThread();

		setLayout(new GridBagLayout());
		final GridBagConstraints c = GuiUtils.defaultGbc();

		add(statusPanel(), c);
		c.gridy++;

		addSeparator(" Search Status:", c);
		setPlaceholderStatusLabels();
		final int storedPady = c.ipady;
		final Insets storedInsets = c.insets;
		c.ipady = 0;
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
		cursorThresholdChoice = new JRadioButton("Set by clicking on traced strucure (preferred)"); // dummy. the default

		final JPanel t1Panel = leftAlignedPanel();
		t1Panel.add(cursorThresholdChoice);
		distancePanel.add(t1Panel, gdb);
		++gdb.gridy;

		manualThresholdChoice = new JRadioButton("Specify manually:");
		manualThresholdInputField = new JTextField("", 6);
		manualThresholdApplyButton = GuiUtils.smallButton("Apply");
		manualThresholdApplyButton.addActionListener(this);
		final JPanel t2Panel = leftAlignedPanel();
		t2Panel.add(manualThresholdChoice);
		t2Panel.add(manualThresholdInputField);
		t2Panel.add(manualThresholdApplyButton);
		distancePanel.add(t2Panel, gdb);
		++gdb.gridy;

		exploredThresholdChoice = new JRadioButton("Use explored maximum");
		exploredThresholdApplyButton = GuiUtils.smallButton("Apply");
		exploredThresholdApplyButton.addActionListener(this);
		final JPanel t3Panel = leftAlignedPanel();
		t3Panel.add(exploredThresholdChoice);
		t3Panel.add(exploredThresholdApplyButton);
		distancePanel.add(t3Panel, gdb);
		++gdb.gridy;

		final JButton defaults = GuiUtils.smallButton("Defaults");
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

		addSeparator(" Rendering Options:", c);

		transparentCheckbox = new JCheckBox(" Transparent overlay (may slow down filling)");
		transparentCheckbox.addActionListener(e -> plugin.setFillTransparent(transparentCheckbox.isSelected()));
		final JPanel transparencyPanel = leftAlignedPanel();
		transparencyPanel.add(transparentCheckbox);
		add(transparencyPanel, c);
		c.gridy++;

		GuiUtils.addSeparator((JComponent) getContentPane(), " Stored Fill(s):", true, c);
		++c.gridy;

		final JScrollPane scrollPane = new JScrollPane();
		scrollPane.getViewport().add(fillList);
		final JPanel listPanel = new JPanel(new BorderLayout());
		listPanel.add(scrollPane, BorderLayout.CENTER);
		add(listPanel, c);
		++c.gridy;
		final JButton deleteFills = GuiUtils.smallButton("Delete");
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
		final JButton reloadFill = GuiUtils.smallButton("Reload");
		reloadFill.addActionListener(e -> {
			if (noFillsError())
				return;
			final int[] selectedIndices = fillList.getSelectedIndices();
			if (selectedIndices.length < 1
					&& gUtils.getConfirmation("No fill was select for reload. Reload All?", "Reload All?")) {
				pathAndFillManager.reloadFills(IntStream.range(0, fillList.getModel().getSize()).toArray());
			} else {
				pathAndFillManager.reloadFills(selectedIndices);
			}
		});
		add(SNTUI.buttonPanel(deleteFills, reloadFill), c);
		++c.gridy;

		GuiUtils.addSeparator((JComponent) getContentPane(), " Export:", true, c);
		++c.gridy;

		final JButton exportAsCSV = new JButton("CSV Summary...");
		exportAsCSV.addActionListener(e -> saveFills());
		assembleViewFillsMenu();
		final JButton exportAsImp = new JButton("Image...");
		exportAsImp.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(final MouseEvent e) {
				if (exportAsImp.isEnabled() && !noFillsError())
					exportFillsMenu.show(e.getComponent(), e.getX(), e.getY());
			}
		});
		add(SNTUI.buttonPanel(exportAsCSV, exportAsImp), c);
		c.gridy++;

		pack();
		adjustListPlaceholder();
		changeState(READY);
		setLocationRelativeTo(plugin.getUI());
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(final WindowEvent ignored) {
				setVisible(false);
			}
		});


	}

	private JPanel statusPanel() {
		final JPanel statusPanel = new JPanel();
		statusPanel.setLayout(new BorderLayout());
		statusPanel.setBorder(BorderFactory.createEmptyBorder(MARGIN, MARGIN, MARGIN, MARGIN));
		statusText = new JLabel("Loading Fill Manager...");
		statusText.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createBevelBorder(BevelBorder.LOWERED),
				BorderFactory.createEmptyBorder(MARGIN, MARGIN, MARGIN, MARGIN)));
		statusPanel.add(statusText, BorderLayout.CENTER);
		startFill = GuiUtils.smallButton("Start");
		startFill.addActionListener(this);
		stopFill = GuiUtils.smallButton("Stop");
		stopFill.addActionListener(this);
		saveFill = GuiUtils.smallButton("Store");
		saveFill.addActionListener(this);
		final JButton discardFill = GuiUtils.smallButton("Cancel/Esc");
		discardFill.addActionListener( e -> {
			plugin.discardFill(true); // will change state
		});
		final JPanel fillControlPanel = SNTUI.buttonPanel(startFill, stopFill, saveFill, discardFill);
		statusPanel.add(fillControlPanel, BorderLayout.SOUTH);
		fillControlPanel.doLayout(); // otherwise dialog becomes too wide
		return statusPanel;
	}

	private void setPlaceholderStatusLabels() {
		currentThresholdLabel = GuiUtils.leftAlignedLabel("No Pahs are currently being filled...", false);
		maxThresholdLabel = GuiUtils.leftAlignedLabel("Max. explored distance: N/A", false);
		cursorPositionLabel = GuiUtils.leftAlignedLabel("Cursor position: N/A", false);
	}

	private class FMCellRenderer extends DefaultListCellRenderer {

		private static final long serialVersionUID = 1L;
		static final String LIST_PLACEHOLDER = "No fillings currently exist";

		@Override
		public Component getListCellRendererComponent(final JList<?> list, final Object value, final int index, final boolean isSelected,
				final boolean cellHasFocus) {
			if (LIST_PLACEHOLDER.equals(value.toString())) {
				return GuiUtils.leftAlignedLabel(LIST_PLACEHOLDER, false);
			} else {
				return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			}
		}
	}

	protected void adjustListPlaceholder() {
		if (listModel.isEmpty()) {
				listModel.addElement(FMCellRenderer.LIST_PLACEHOLDER);
		} else if (listModel.getSize() > 1 ){
			listModel.removeElement(FMCellRenderer.LIST_PLACEHOLDER);
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
		cursorPositionLabel.setEnabled(true);
		maxThresholdLabel.setEnabled(exploredThresholdChoice.isSelected());
		currentThresholdLabel.setEnabled(true);
		cursorPositionLabel.setEnabled(true);
	}

	protected void setEnabledWhileNotFilling() {
		assert SwingUtilities.isEventDispatchThread();
		cursorPositionLabel.setEnabled(true);
		maxThresholdLabel.setEnabled(false);
		currentThresholdLabel.setEnabled(false);
		cursorPositionLabel.setEnabled(false);
	}

	protected void setEnabledNone() {
		assert SwingUtilities.isEventDispatchThread();
		cursorPositionLabel.setEnabled(false);
		maxThresholdLabel.setEnabled(false);
		currentThresholdLabel.setEnabled(false);
	}

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
	public void setPathList(final String[] pathList, final Path justAdded,
		final boolean expandAll)
	{}

	/* (non-Javadoc)
	 * @see PathAndFillListener#setFillList(java.lang.String[])
	 */
	@Override
	public void setFillList(final String[] newList) {
		SwingUtilities.invokeLater(() -> {
			listModel.removeAllElements();
			if (newList != null && newList.length > 0)
				for (final String s : newList) listModel.addElement(s);
			adjustListPlaceholder();
		});
	}

	/* (non-Javadoc)
	 * @see PathAndFillListener#setSelectedPaths(java.util.HashSet, java.lang.Object)
	 */
	@Override
	public void setSelectedPaths(final HashSet<Path> selectedPathSet,
		final Object source)
	{
		// This dialog doesn't deal with paths, so ignore this.
	}

	/* (non-Javadoc)
	 * @see java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
	 */
	@Override
	public void actionPerformed(final ActionEvent ae) {
		assert SwingUtilities.isEventDispatchThread();

		final Object source = ae.getSource();

		if (noPathsError() || noValidImgError()) {
			return;
		}

		if (source == exploredThresholdApplyButton) {
			try {
				plugin.setFillThreshold(maxThresholdValue);
			} catch (final IllegalArgumentException ignored) {
				gUtils.error("No explored maximum exists yet.");
				cursorThresholdChoice.setSelected(true);
			}
		}
		else if (source == manualThresholdApplyButton || source == manualThresholdInputField) {
			try {
				plugin.setFillThreshold(Double.parseDouble(manualThresholdInputField.getText())); // will call #setThreshold()
			}
			catch (final IllegalArgumentException ignored) { // includes NumberFormatException
				gUtils.error("The threshold '" + manualThresholdInputField.getText() +
					"' is not a valid option. Only positive values accepted.");
				cursorThresholdChoice.setSelected(true);
			}

		}
		else if (source == stopFill) {

			try {
				plugin.stopFilling(); // will change state
			} catch (final IllegalArgumentException ex) {
				gUtils.error(ex.getMessage());
			}
		}
		else if (source == saveFill) {
			try {
				plugin.saveFill(); // will change state
			} catch (final IllegalArgumentException ex) {
				gUtils.error(ex.getMessage());
			}
		}
		else if (source == startFill) {
			if (plugin.fillerThreadPool != null) {
				gUtils.error ("A filling operation is already running.");
				return;
			}
			if (plugin.fillerSet.isEmpty()) {
				if (plugin.getUI().getPathManager().selectionExists()) {
					plugin.initPathsToFill(new HashSet<>(plugin.getUI().getPathManager().getSelectedPaths(false)));
					plugin.startFilling();
				} else  {
					final int ans = gUtils.yesNoDialog("There are no paths selected in Path Manager. Would you like to "
							+ "fill all paths? Alternatively, you can dismiss this prompt, select subsets in the Path "
							+ "Manager list, and re-run. ", "Fill All Paths?", "Yes. Fill all.", "No. Let me select subsets.");
					if (ans == JOptionPane.YES_OPTION) {
						plugin.initPathsToFill(new HashSet<>(plugin.getUI().getPathManager().getSelectedPaths(true)));
						plugin.startFilling();
					}
				}
			} else {
				try {
					plugin.startFilling(); //TODO: Check if this is the only thing left to do.
				} catch (final IllegalArgumentException ex) {
					gUtils.error(ex.getMessage());
				}
			}
		}

		else {
			SNTUtils.error("BUG: FillWindow received an event from an unknown source.");
		}

	}

	private boolean noFillsError() {
		final boolean noFills = listModel.getSize() == 0 || FMCellRenderer.LIST_PLACEHOLDER.equals(
				listModel.getElementAt(0));
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

	private void assembleViewFillsMenu() {
		exportFillsMenu = new JPopupMenu();
		JMenuItem jmi = new JMenuItem("As Grayscale Image");
		jmi.addActionListener(e-> showFilledVolumeImage(false));
		exportFillsMenu.add(jmi);
		jmi = new JMenuItem("As Binary Mask");
		jmi.addActionListener(e-> showFilledVolumeImage(true));
		exportFillsMenu.add(jmi);
	}

	private void showFilledVolumeImage(final boolean binary) {
		final ImagePlus imp = plugin.getFilledVolume(binary);
		if (imp == null && !noFillsError()) {
			// there are fills stashed but SNT#fillerSet is empty
			gUtils.error("Image could not be displayed. Please reload desired fill(s) and try again.");
			return;
		}
		else {
			imp.show();
		}
	}

	private void saveFills() {
		if (noFillsError()) return;

		if (pathAndFillManager.getAllFills().isEmpty()) {
			gUtils.error("There are currently no fills. CSV file would be empty.");
			return;
		}

		final File saveFile = plugin.getUI().saveFile("Export CSV Summary...", "Fills.csv", ".csv");
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
	 * @see FillerProgressCallback#maximumDistanceCompletelyExplored(SearchThread, float)
	 */
	@Override
	public void maximumDistanceCompletelyExplored(final SearchThread source,
		final float f)
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
			final int exitReason = ((SearchThread) source).exitReason;
			if (exitReason != SearchThread.CANCELLED) {
				final String reason = SearchThread.EXIT_REASONS_STRINGS[((SearchThread) source).exitReason];
				new GuiUtils(this).error("Filling thread exited prematurely (Error code: '" + reason + "'). "
						+ "With debug mode on, see Console for details.", "Filling Error");
			}
		}
	}

	/* (non-Javadoc)
	 * @see SearchProgressCallback#threadStatus(SearchInterface, int)
	 */
	@Override
	public void threadStatus(final SearchInterface source, final int currentStatus) {
		switch (currentStatus) {
		case SearchThread.STOPPING:
			changeState(ABORTED);
			break;
		case SearchThread.RUNNING:
			changeState(STARTED);
			break;
		default:
			throw new IllegalArgumentException("Unrecognized status");
		}
	}

	@Override
	public void allFillsFinished(final boolean allSucceeded) {
		changeState(ENDED);
	}

	protected void showMouseThreshold(final float t) {
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
	 * @param newState the new state, e.g., {@link FillManagerUI#READY},
	 *                 {@link FillManagerUI#STOPPED}, etc.
	 */
	protected void changeState(final int newState) {

		if (newState == currentState)
			return;
		currentState = newState;
		SwingUtilities.invokeLater(() -> {
			switch (newState) {

			case READY:
				updateStatusText("Press <i>Start</i> to initiate filling...");
				startFill.setEnabled(true);
				stopFill.setEnabled(false);
				saveFill.setEnabled(false);
				break;

			case STARTED:
				updateStatusText("Filling started...");
				startFill.setEnabled(false);
				stopFill.setEnabled(true);
				saveFill.setEnabled(false);
				break;

			case ABORTED:
				updateStatusText("Filling stopped...");
				startFill.setEnabled(true);
				stopFill.setEnabled(false);
				saveFill.setEnabled(true);
				break;

			case ENDED:
				updateStatusText("Filling concluded... Store result?");
				startFill.setEnabled(false);
				stopFill.setEnabled(false);
				saveFill.setEnabled(true);
				break;

			default:
				SNTUtils.error("BUG: switching to an unknown state");
				return;
			}
		});
	}

	private void updateStatusText(final String newStatus) {
		statusText.setText("<html><strong>" + newStatus + "</strong></html>");
	}

	/* IDE debug method */
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
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
