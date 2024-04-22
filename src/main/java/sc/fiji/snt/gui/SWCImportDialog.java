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

package sc.fiji.snt.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.Arrays;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.formdev.flatlaf.icons.FlatOptionPaneWarningIcon;

import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.SNTUtils;

/**
 * Implements a dialog for importing SWC files.
 */
@SuppressWarnings("serial")
public class SWCImportDialog extends JDialog {

	private boolean succeeded;

	private final FilePicker filePicker;
	private final PreviewArea previewArea;
	private final BoxPanel offsetPanel;
	private final BoxPanel scalingPanel;
	private final JButton okButton;
	private final JButton cancelButton;
	private final JCheckBox replaceExistingPathsCheckbox;
	private boolean assumePixelCoordinates;
	private boolean replaceExistingPaths;
	private File lastPreviewedFile;

	public SWCImportDialog(final SNTUI ui, final File suggestedFile) {

		super(ui, "Import SWC...", true);
		loadFieldPrefs(ui.getPrefs());
		replaceExistingPathsCheckbox = new JCheckBox("Replace existing paths?", replaceExistingPaths);
		replaceExistingPathsCheckbox
				.addItemListener(e -> replaceExistingPaths = replaceExistingPathsCheckbox.isSelected());
		offsetPanel = new BoxPanel("X axis ", "Y axis", "Z axis");
		scalingPanel = new BoxPanel("X axis", "Y axis", "Z axis", "Radius");
		loadBoxPanelPrefs(ui.getPrefs());
		previewArea = new PreviewArea();
		filePicker = new FilePicker(FilePicker.OPEN_DIALOG,
				(suggestedFile == null) ? getLastLoadedFile(ui.getPrefs()) : suggestedFile, "swc", "eswc");
		filePicker.addListener(previewArea);

		okButton = new JButton("Import");
		okButton.addActionListener( e-> {
			if (filePicker.invalidFileChoiceError())
				return;
			succeeded = true;
			savePrefs(ui.getPrefs());
			dispose();
		});
		cancelButton = new JButton("Cancel");
		cancelButton.addActionListener( e-> {
			succeeded = false;
			dispose();
		});
		assembleDialog();
		setLocationRelativeTo(ui);
		setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		pack();
		setVisible(true);
	}

	private void assembleDialog() {
		final JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 3));
		buttonsPanel.add(okButton);
		buttonsPanel.add(cancelButton);

		setLayout(new GridBagLayout());
		final GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(10, 10, 10, 10);
		c.gridx = 0;
		c.gridy = 0;
		c.anchor = GridBagConstraints.LINE_START;
		c.fill = GridBagConstraints.BOTH;
		c.weightx = 0;
		c.weighty = 0;
		c.insets.bottom += 20;
		add(filePicker, c);
		c.insets.bottom = 10;
		c.gridy++;
		add(replaceExistingPathsCheckbox, c);
		c.gridy++;
		add(ignoreCalibrationPanel(), c);
		c.gridy++;
		c.weighty = 1;
		add(previewArea.getCollapsiblePanel(), c);
		c.gridy++;
		c.weightx = 0;
		c.weighty = 0;
		add(new CollapsiblePanel("Apply offset", offsetPanel, true), c);
		c.gridy++;
		add(new CollapsiblePanel("Apply scaling factor", scalingPanel, true), c);
		c.gridy++;
		c.insets.top += 10;
		c.weightx = 1;
		add(buttonsPanel, c);
	}

	private void loadBoxPanelPrefs(final SNTPrefs prefs) {
		final JFormattedTextField[] offsetFields = offsetPanel.fields;
		offsetFields[0].setText(prefs.get("swci.xoff", "0"));
		offsetFields[1].setText(prefs.get("swci.yoff", "0"));
		offsetFields[2].setText(prefs.get("swci.zoff", "0"));
		final JFormattedTextField[] scalingFields = scalingPanel.fields;
		scalingFields[0].setText(prefs.get("swci.xscl", "1"));
		scalingFields[1].setText(prefs.get("swci.yscl", "1"));
		scalingFields[2].setText(prefs.get("swci.zscl", "1"));
		scalingFields[3].setText(prefs.get("swci.rscl", "1"));
	}
	
	private void loadFieldPrefs(final SNTPrefs prefs) {
		replaceExistingPaths = prefs.getBoolean("swci.replace", false);
		assumePixelCoordinates = prefs.getBoolean("swci.pixelcc", false);
	}

	private File getLastLoadedFile(final SNTPrefs prefs) {
		final String pref = prefs.get("swci.lastpath", "");
		return (pref.isEmpty()) ? new File(SNTPrefs.lastknownDir(), "SNT.swc") : new File(pref);
	}

	private void savePrefs(final SNTPrefs prefs) {
		prefs.set("swci.replace",replaceExistingPaths);
		prefs.set("swci.pixelcc",assumePixelCoordinates);
		if (lastPreviewedFile != null)
			prefs.get("swci.lastfile", lastPreviewedFile.getAbsolutePath());
		final double[] offsets = getOffsets();
		prefs.set("swci.xoff", "" + offsets[0]);
		prefs.set("swci.yoff", "" + offsets[1]);
		prefs.set("swci.zoff", "" + offsets[2]);
		final double[] scalingFactors = getScalingFactors();
		prefs.set("swci.xscl", "" + scalingFactors[0]);
		prefs.set("swci.yscl", "" + scalingFactors[1]);
		prefs.set("swci.zscl", "" + scalingFactors[2]);
		prefs.set("swci.rscl", "" + scalingFactors[3]);
		prefs.set("swci.lastpath", getFile().getAbsolutePath());
	}

	private CollapsiblePanel ignoreCalibrationPanel() {
		final JLabel label = new JLabel("<HTML>This is not predicted by the SWC specification");
		label.setIcon(new FlatOptionPaneWarningIcon());
		class CCPanel extends CollapsiblePanel {

			CCPanel() {
				super("File uses pixel coordinates", label, !assumePixelCoordinates);
			}

			@Override
			public void setCollapsed(final boolean collapse) {
				super.setCollapsed(collapse);
				assumePixelCoordinates = !collapse;
			}

		}
		return new CCPanel();
	}

	public boolean succeeded() {
		return succeeded;
	}

	public File getFile() {
		return filePicker.getFile();
	}

	public double[] getOffsets() {
		return new double[] { offsetPanel.getValue(0, 0d), offsetPanel.getValue(1, 0d), offsetPanel.getValue(2, 0d) };
	}

	public double[] getScalingFactors() {
		return new double[] { scalingPanel.getValue(0, 1d), scalingPanel.getValue(1, 1d),
				scalingPanel.getValue(2, 1d), scalingPanel.getValue(3, 1d) };
	}

	public boolean isReplacePaths() {
		return replaceExistingPaths;
	}

	public boolean isAssumePixelCoordinates() {
		return assumePixelCoordinates;
	}

	private static class BoxPanel extends JPanel {

		final JFormattedTextField[] fields;

		BoxPanel(final String... labels) {
			fields = new JFormattedTextField[labels.length];
			setLayout(new GridBagLayout());
			final GridBagConstraints c = new GridBagConstraints();
			c.gridx = 0;
			c.gridy = 0;
			c.weighty = 0;
			final int[] pos = { 0 };
			Arrays.stream(labels).forEach(label -> {
				if (pos[0] > 2) {
					c.gridx = 0;
					c.gridy++;
					c.insets.top = 10;
				}
				c.gridx++;
				c.weightx = 0;
				add(new JLabel(label + " "), c);
				c.gridx++;
				c.weightx = 0.5;
				fields[pos[0]] = getField();
				add(fields[pos[0]], c);
				pos[0]++;
			});
		}

		JFormattedTextField getField() {
			final JFormattedTextField field = new JFormattedTextField(NumberFormat.getNumberInstance());
			field.setColumns(8);
			return field;
		}

		final double getValue(final int field, final double defaultValue) {
			return (fields[field].getText() == null || fields[field].getText().isBlank()) ? defaultValue
					: Double.parseDouble(fields[field].getText());
		}
	}

	private class PreviewArea extends JTextArea implements DocumentListener {

		PreviewArea() {
			super(10, 60);
			setFont(new Font(Font.MONOSPACED, Font.PLAIN, getFont().getSize()));
			setEditable(false);
		}

		void previewFileHeader(final File file) {
			if (isVisible() && lastPreviewedFile == file && getText().startsWith("#"))
				return;
			if (file == null || !file.getAbsolutePath().toLowerCase().endsWith("swc")) {
				setForeground(Color.RED);
				setText("File path does not contain a valid SWC extension...");
				return;
			}
			try {
				setText("");
				setForeground(SWCImportDialog.this.getForeground());
				try (BufferedReader in = new BufferedReader(new FileReader(file))) {
					String line = in.readLine();
					while (line != null && line.startsWith("#")) {
						String  text = previewArea.getText();
						if (!text.isEmpty())
							text += "\n";
						setText(text + line);
						line = in.readLine();
					}
				}
				lastPreviewedFile = file;
				if (getText().isBlank())
					setText("File does not appear to have metadata header...");
				else
					setCaretPosition(0); // scroll to top
			} catch (final NullPointerException | IOException ex) {
				setForeground(Color.RED);
				if (SNTUtils.fileAvailable(file))
					setText(ex.getMessage());
				else
					setText("Path does not exist or cannot be read...");
			}
		}

		CollapsiblePanel getCollapsiblePanel() {
			class CPPlus extends CollapsiblePanel {

				public CPPlus(final String header, final Component contents, final boolean collapsedState) {
					super(header, contents, collapsedState);
				}

				@Override
				public void setCollapsed(final boolean collapse) {
					if (!collapse)
						previewFileHeader(filePicker.getFile());
					super.setCollapsed(collapse);
				}
			}
			return new CPPlus("Preview file header", new JScrollPane(this), true);
		}

		@Override
		public void changedUpdate(final DocumentEvent de) {
			previewFileHeader(filePicker.getFile());
		}

		@Override
		public void insertUpdate(final DocumentEvent de) {
			previewFileHeader(filePicker.getFile());
		}

		@Override
		public void removeUpdate(final DocumentEvent de) {
			previewFileHeader(filePicker.getFile());
		}
	}

}
