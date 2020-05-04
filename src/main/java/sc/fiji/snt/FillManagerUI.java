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

import javax.swing.AbstractAction;
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
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import ij.ImagePlus;
import net.imagej.ImageJ;
import sc.fiji.snt.gui.GuiUtils;

/**
 * Implements the <i>Fill Manager</i> dialog.
 *
 * @author Tiago Ferreira
 */
public class FillManagerUI extends JDialog implements PathAndFillListener,
	ActionListener, FillerProgressCallback
{

	private static final long serialVersionUID = 1L;
	private final SNT plugin;
	private final PathAndFillManager pathAndFillManager;
	private JScrollPane scrollPane;
	private final JList<String> fillList;
	private final DefaultListModel<String> listModel;
	private JButton deleteFills;
	private JButton reloadFill;
	private JPanel fillControlPanel;
	protected JLabel fillStatus;
	private float maxThresholdValue = 0;
	private JTextField thresholdField;
	private JLabel maxThreshold;
	private JLabel currentThreshold;
	private JButton setThreshold;
	private JButton setMaxThreshold;
	private JButton view3D;
	private JPopupMenu viewFillsMenu;
	private JCheckBox transparent;
	protected JButton pauseOrRestartFilling;
	private JButton saveFill;
	private JButton discardFill;
	private JButton exportAsCSV;
	private JRadioButton manualRButton;
	private JRadioButton maxRButton;

	private final GuiUtils gUtils;

	private final int MARGIN = 10;
	protected static final String FILLING_URI = "https://imagej.net/SNT:_Step-By-Step_Instructions#Filling";

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

		gUtils = new GuiUtils(this);
		listModel = new DefaultListModel<>();
		fillList = new JList<>(listModel);
		fillList.setCellRenderer(new FMCellRenderer());

		assert SwingUtilities.isEventDispatchThread();

		fillList.setVisibleRowCount(5);
		fillList.setPrototypeCellValue(FMCellRenderer.LIST_PLACEHOLDER);
		setLocationRelativeTo(plugin.getUI());
		setLayout(new GridBagLayout());
		final GridBagConstraints c = GuiUtils.defaultGbc();

		{
			scrollPane = new JScrollPane();
			scrollPane.getViewport().add(fillList);
			final JPanel listPanel = new JPanel(new BorderLayout());
			listPanel.add(scrollPane, BorderLayout.CENTER);
			add(listPanel, c);
			++c.gridy;
		}

		{
			deleteFills = new JButton("Delete Fill(s)");
			deleteFills.addActionListener(this);
			reloadFill = new JButton("Reload Fill");
			reloadFill.addActionListener(this);
			final JPanel fillListCommandsPanel = new JPanel(new BorderLayout());
			fillListCommandsPanel.add(deleteFills, BorderLayout.WEST);
			fillListCommandsPanel.add(reloadFill, BorderLayout.EAST);
			add(fillListCommandsPanel, c);
			++c.gridy;
		}

		addSeparator(" Distance Threshold for Fill Search:", c);

		{
			final JPanel distancePanel = new JPanel(new GridBagLayout());
			final GridBagConstraints gdb = GuiUtils.defaultGbc();
			final JRadioButton cursorRButton = new JRadioButton(
				"Set by clicking on traced strucure (preferred)");

			final JPanel t1Panel = leftAlignedPanel();
			t1Panel.add(cursorRButton);
			distancePanel.add(t1Panel, gdb);
			++gdb.gridy;

			manualRButton = new JRadioButton("Specify manually:");
			thresholdField = new JTextField("", 6);
			setThreshold = GuiUtils.smallButton("Apply");
			setThreshold.addActionListener(this);
			final JPanel t2Panel = leftAlignedPanel();
			t2Panel.add(manualRButton);
			t2Panel.add(thresholdField);
			t2Panel.add(setThreshold);
			distancePanel.add(t2Panel, gdb);
			++gdb.gridy;

			maxRButton = new JRadioButton("Use explored maximum");
			setMaxThreshold = GuiUtils.smallButton("Apply");
			setMaxThreshold.setEnabled(false);
			setMaxThreshold.addActionListener(this);
			final JPanel t3Panel = leftAlignedPanel();
			t3Panel.add(maxRButton);
			t3Panel.add(setMaxThreshold);
			distancePanel.add(t3Panel, gdb);
			add(distancePanel, c);
			++c.gridy;

			final ButtonGroup group = new ButtonGroup();
			group.add(maxRButton);
			group.add(manualRButton);
			group.add(cursorRButton);
			final RadioGroupListener listener = new RadioGroupListener();
			cursorRButton.addActionListener(listener);
			manualRButton.addActionListener(listener);
			maxRButton.addActionListener(listener);
			cursorRButton.setSelected(true);
			thresholdField.setEnabled(false);
			setThreshold.setEnabled(false);
			setMaxThreshold.setEnabled(false);
		}

		addSeparator(" Search Status:", c);

		{
			currentThreshold = GuiUtils.leftAlignedLabel(
				"No Pahs are currently being filled...", false);
			maxThreshold = GuiUtils.leftAlignedLabel(
				"This message will be updated once a Fill-out", false);
			fillStatus = GuiUtils.leftAlignedLabel(
				"command is run from the Path Manager...", false);
			final int storedPady = c.ipady;
			final Insets storedInsets = c.insets;
			c.ipady = 0;
			c.insets = new Insets(0, MARGIN, 0, MARGIN);
			add(currentThreshold, c);
			++c.gridy;
			add(maxThreshold, c);
			++c.gridy;
			add(fillStatus, c);
			++c.gridy;
			c.ipady = storedPady;
			c.insets = storedInsets;
		}

		addSeparator(" Rendering Options:", c);

		{
			transparent = new JCheckBox(
				" Transparent overlay (may slow down filling)");
			transparent.addActionListener(e-> {
				plugin.setFillTransparent(transparent.isSelected());
			});
			final JPanel transparencyPanel = leftAlignedPanel();
			transparencyPanel.add(transparent);
			add(transparencyPanel, c);
			c.gridy++;
		}

		GuiUtils.addSeparator((JComponent) getContentPane(),
			" Filler Thread Controls:", true, c);
		++c.gridy;

		{
			pauseOrRestartFilling = new JButton("Pause");
			pauseOrRestartFilling.addActionListener(this);
			discardFill = new JButton("Stop");
			discardFill.addActionListener(this);
			saveFill = new JButton("Stash Progress");
			saveFill.addActionListener(this);
			fillControlPanel = SNTUI.buttonPanel(pauseOrRestartFilling, discardFill, saveFill);
			add(fillControlPanel, c);
			++c.gridy;
		}

		GuiUtils.addSeparator((JComponent) getContentPane(), " Export Fill(s):",
			true, c);
		++c.gridy;

		{
			exportAsCSV = new JButton("CSV Summary...");
			exportAsCSV.addActionListener(this);
			assembleViewFillsMenu();
			view3D = new JButton("Image Stack...");
			view3D.addMouseListener(new MouseAdapter() {

				@Override
				public void mousePressed(final MouseEvent e) {
					viewFillsMenu.show(e.getComponent(), e.getX(), e.getY());
				}
			});
			add(SNTUI.buttonPanel(exportAsCSV, view3D), c);
			c.gridy++;
		}
		adjustListPlaceholder();
		pack();
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(final WindowEvent ignored) {
				setVisible(false);
			}
		});
	}

	private class FMCellRenderer extends DefaultListCellRenderer {

		private static final long serialVersionUID = 1L;
		static final String LIST_PLACEHOLDER = "To start filling, run \"Fill Out\" from Path Manager";

		public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
				boolean cellHasFocus) {
			if (LIST_PLACEHOLDER.equals(value.toString())) {
				isSelected = false;
				return GuiUtils.leftAlignedLabel(LIST_PLACEHOLDER, false);
			} else {
				return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			}
		}
	}

	private boolean fillerHasNotRun() {
		return (plugin.filler == null || !plugin.filler.isAlive());
	}

	protected void adjustListPlaceholder() {
		if (fillerHasNotRun()) {
			if (!listModel.contains(FMCellRenderer.LIST_PLACEHOLDER))
				listModel.addElement(FMCellRenderer.LIST_PLACEHOLDER);
		} else {
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
		fillList.setEnabled(false);
		deleteFills.setEnabled(false);
		reloadFill.setEnabled(false);
		fillStatus.setEnabled(true);
		manualRButton.setEnabled(true);
		maxThreshold.setEnabled(maxRButton.isSelected());
		currentThreshold.setEnabled(true);
		fillStatus.setEnabled(true);
		maxRButton.setEnabled(maxRButton.isSelected());
		view3D.setEnabled(true);
		exportAsCSV.setEnabled(true);
		transparent.setEnabled(true);
		pauseOrRestartFilling.setEnabled(true);
		saveFill.setEnabled(false);
		discardFill.setEnabled(true);
	}

	protected void setEnabledWhileNotFilling() {
		assert SwingUtilities.isEventDispatchThread();
		fillList.setEnabled(true);
		deleteFills.setEnabled(true);
		reloadFill.setEnabled(true);
		fillStatus.setEnabled(true);
		manualRButton.setEnabled(false);
		maxThreshold.setEnabled(false);
		currentThreshold.setEnabled(false);
		fillStatus.setEnabled(false);
		maxRButton.setEnabled(false);
		view3D.setEnabled(false);
		exportAsCSV.setEnabled(false);
		transparent.setEnabled(false);
		pauseOrRestartFilling.setEnabled(false);
		saveFill.setEnabled(false);
		discardFill.setEnabled(false);
	}

	protected void setEnabledNone() {
		assert SwingUtilities.isEventDispatchThread();
		fillList.setEnabled(false);
		deleteFills.setEnabled(false);
		reloadFill.setEnabled(false);
		fillStatus.setEnabled(false);
		manualRButton.setEnabled(false);
		maxThreshold.setEnabled(false);
		currentThreshold.setEnabled(false);
		fillStatus.setEnabled(false);
		maxRButton.setEnabled(false);
		view3D.setEnabled(false);
		exportAsCSV.setEnabled(false);
		transparent.setEnabled(false);
		pauseOrRestartFilling.setEnabled(false);
		saveFill.setEnabled(false);
		discardFill.setEnabled(false);
	}

	public void setFillTransparent(final boolean transparent) {
		if (this.transparent != null) {
			SwingUtilities.invokeLater(() -> this.transparent.setSelected(transparent));
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
				for (String s : newList) listModel.addElement(s);
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

		if (source == deleteFills) {

			final int[] selectedIndices = fillList.getSelectedIndices();
			if (selectedIndices.length < 1 || fillerHasNotRun()) {
				gUtils.error("No fill was selected for deletion.");
				return;
			}
			pathAndFillManager.deleteFills(selectedIndices);
			plugin.updateTracingViewers(false);

		}
		else if (source == reloadFill) {

			final int[] selectedIndices = fillList.getSelectedIndices();
			if (selectedIndices.length != 1 || fillerHasNotRun()) {
				gUtils.error(
					"You must have a single fill selected in order to reload.");
				return;
			}
			pathAndFillManager.reloadFill(selectedIndices[0]);

		}
		else if (source == setMaxThreshold) {

			plugin.setFillThreshold(maxThresholdValue);

		}
		else if (source == setThreshold || source == thresholdField) {

			try {
				final double t = Double.parseDouble(thresholdField.getText());
				if (t < 0) {
					gUtils.error("The fill threshold cannot be negative.");
					return;
				}
				plugin.setFillThreshold(t);
			}
			catch (final NumberFormatException nfe) {
				gUtils.error("The threshold '" + thresholdField.getText() +
					"' wasn't a valid number.");
				return;
			}

		}
		else if (source == discardFill) {

			plugin.discardFill();

		}
		else if (source == saveFill) {

			plugin.saveFill();

		}
		else if (source == pauseOrRestartFilling) {

			plugin.pauseOrRestartFilling();

		}
		else if (source == exportAsCSV) {

			final File saveFile = plugin.getUI().saveFile("Export CSV Summary...", "Fills.csv", ".csv");
			if (saveFile == null) return; // user pressed cancel;
			plugin.getUI().showStatus("Exporting CSV data to " + saveFile
				.getAbsolutePath(), false);
			try {
				pathAndFillManager.exportFillsAsCSV(saveFile);
			}
			catch (final IOException ioe) {
				gUtils.error("Saving to " + saveFile.getAbsolutePath() +
					" failed. See console for details");
				SNTUtils.error("IO Error", ioe);
				return;
			}
			plugin.getUI().showStatus("Done... ", true);
		}
		else {
			SNTUtils.error("BUG: FillWindow received an event from an unknown source.");
		}

	}

	private void assembleViewFillsMenu() {
		viewFillsMenu = new JPopupMenu();
		viewFillsMenu.add(new JMenuItem(new AbstractAction(
			"As Grayscale Image...")
		{

			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(final ActionEvent e) {
				final ImagePlus imp = plugin.getFilledVolume(false);
				if (imp != null) imp.show();
			}
		}));
		viewFillsMenu.add(new JMenuItem(new AbstractAction("As Binary Mask...") {

			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(final ActionEvent e) {
				final ImagePlus imp = plugin.getFilledVolume(true);
				if (imp != null) imp.show();
			}
		}));
	}

	protected void thresholdChanged(final double f) {
		SwingUtilities.invokeLater(() -> {
			assert SwingUtilities.isEventDispatchThread();
			final String value = SNTUtils.formatDouble(f, 3);
			thresholdField.setText(SNTUtils.formatDouble(f, 3));
			currentThreshold.setText("Current threshold distance: " + value);
		});
	}

	/* (non-Javadoc)
	 * @see FillerProgressCallback#maximumDistanceCompletelyExplored(SearchThread, float)
	 */
	@Override
	public void maximumDistanceCompletelyExplored(final SearchThread source,
		final float f)
	{
		SwingUtilities.invokeLater(() -> {
			maxThreshold.setText("Max. explored distance: " + SNTUtils.formatDouble(f, 3));
			maxThresholdValue = f;
		});
	}

	/* (non-Javadoc)
	 * @see SearchProgressCallback#pointsInSearch(SearchInterface, int, int)
	 */
	@Override
	public void pointsInSearch(final SearchInterface source, final int inOpen,
		final int inClosed)
	{
		// Do nothing...
	}

	/* (non-Javadoc)
	 * @see SearchProgressCallback#finished(SearchInterface, boolean)
	 */
	@Override
	public void finished(final SearchInterface source, final boolean success) {
		// Do nothing...
	}

	/* (non-Javadoc)
	 * @see SearchProgressCallback#threadStatus(SearchInterface, int)
	 */
	@Override
	public void threadStatus(final SearchInterface source,
		final int currentStatus)
	{
		SwingUtilities.invokeLater(() -> {
			switch (currentStatus) {
				case SearchThread.STOPPING:
					pauseOrRestartFilling.setText("Stopped");
					pauseOrRestartFilling.setEnabled(false);
					saveFill.setEnabled(false);

					break;
				case SearchThread.PAUSED:
					pauseOrRestartFilling.setText("Continue");
					saveFill.setEnabled(true);
					break;
				case SearchThread.RUNNING:
					pauseOrRestartFilling.setText("Pause");
					saveFill.setEnabled(false);
					break;
			}
			fillControlPanel.doLayout();
		});
	}

	protected void showMouseThreshold(final float t) {
		SwingUtilities.invokeLater(() -> {
			String newStatus = null;
			if (t < 0) {
				newStatus = "Cursor position: Not reached by search yet";
			}
			else {
				newStatus = "Cursor position: Distance from path is " + SNTUtils
					.formatDouble(t, 3);
			}
			fillStatus.setText(newStatus);
		});
	}

	/* IDE debug method */
	public static void main(final String[] args) {
		GuiUtils.setSystemLookAndFeel();
		final ImageJ ij = new ImageJ();
		final ImagePlus imp = new ImagePlus();
		final SNT snt = new SNT(ij.context(), imp);
		final FillManagerUI fm = new FillManagerUI(snt);
		fm.setVisible(true);
	}

	private class RadioGroupListener implements ActionListener {

		@Override
		public void actionPerformed(final ActionEvent e) {
			setThreshold.setEnabled(manualRButton.isSelected());
			thresholdField.setEnabled(manualRButton.isSelected());
			setMaxThreshold.setEnabled(maxRButton.isSelected());
		}
	}
}
