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

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTextField;

import ij.Prefs; //TODO: Use SNT.Prefs

/**
 * Implements a dialog for importing SWC files into SNT with advanced options.
 */
@SuppressWarnings("serial")
public class SWCImportOptionsDialog extends JDialog implements ActionListener,
	ItemListener
{

	private boolean succeeded = false;

	private final JCheckBox replaceExistingPathsCheckbox = new JCheckBox(
		"Replace existing paths?");

	private final JCheckBox ignoreCalibrationCheckbox = new JCheckBox(
		"File uses pixel coordinates");

	private final JCheckBox applyOffsetCheckbox = new JCheckBox(
		"Apply offset to file coordinates");
	private final JCheckBox applyScaleCheckbox = new JCheckBox(
		"Apply scale to file coordinates");

	private final String offsetDefault = "0.0";
	private final String scaleDefault = "1.0";

	private final JTextField xOffsetTextField = new JTextField(offsetDefault);
	private final JTextField yOffsetTextField = new JTextField(offsetDefault);
	private final JTextField zOffsetTextField = new JTextField(offsetDefault);
	private final JTextField xScaleTextField = new JTextField(scaleDefault);
	private final JTextField yScaleTextField = new JTextField(scaleDefault);
	private final JTextField zScaleTextField = new JTextField(scaleDefault);
	private final JButton okButton = new JButton("Load");
	private final JButton cancelButton = new JButton("Cancel");
	private final JButton restoreToDefaultsButton = new JButton(
		"Restore default options");

	private void setFieldsFromPrefs() {

		xOffsetTextField.setText(Prefs.get("tracing.SWCImportOptionsDialog.xOffset",
			offsetDefault));
		yOffsetTextField.setText(Prefs.get("tracing.SWCImportOptionsDialog.yOffset",
			offsetDefault));
		zOffsetTextField.setText(Prefs.get("tracing.SWCImportOptionsDialog.zOffset",
			offsetDefault));

		xScaleTextField.setText(Prefs.get("tracing.SWCImportOptionsDialog.xScale",
			scaleDefault));
		yScaleTextField.setText(Prefs.get("tracing.SWCImportOptionsDialog.yScale",
			scaleDefault));
		zScaleTextField.setText(Prefs.get("tracing.SWCImportOptionsDialog.zScale",
			scaleDefault));

		if (Prefs.get("tracing.SWCImportOptionsDialog.applyOffset", "false").equals(
			"true")) applyOffsetCheckbox.setSelected(true);
		else {
			applyOffsetCheckbox.setSelected(false);
			xOffsetTextField.setText(offsetDefault);
			yOffsetTextField.setText(offsetDefault);
			zOffsetTextField.setText(offsetDefault);
		}

		if (Prefs.get("tracing.SWCImportOptionsDialog.applyScale", "false").equals(
			"true")) applyScaleCheckbox.setSelected(true);
		else {
			applyScaleCheckbox.setSelected(false);
			xScaleTextField.setText(scaleDefault);
			yScaleTextField.setText(scaleDefault);
			zScaleTextField.setText(scaleDefault);
		}

		ignoreCalibrationCheckbox.setSelected(Prefs.get(
			"tracing.SWCImportOptionsDialog.ignoreCalibration", "false").equals(
				"true"));

		replaceExistingPathsCheckbox.setSelected(Prefs.get(
			"tracing.SWCImportOptionsDialog.replaceExistingPaths", "true").equals(
				"true"));

		updateEnabled();
	}

	@Override
	public void actionPerformed(final ActionEvent e) {

		final Object source = e.getSource();
		if (source == okButton) {
			try {
				Double.parseDouble(xOffsetTextField.getText());
				Double.parseDouble(yOffsetTextField.getText());
				Double.parseDouble(zOffsetTextField.getText());
				Double.parseDouble(xScaleTextField.getText());
				Double.parseDouble(yScaleTextField.getText());
				Double.parseDouble(zScaleTextField.getText());
			}
			catch (final NumberFormatException nfe) {
				GuiUtils.errorPrompt("Couldn't parse an offset or scale as a number: " +
					nfe);
				return;
			}
			succeeded = true;
			saveFieldsToPrefs();
			dispose();
		}
		else if (source == cancelButton) {
			dispose();
		}
		else if (source == restoreToDefaultsButton) {
			restoreToDefaults();
		}
	}

	@Override
	public void itemStateChanged(final ItemEvent e) {
		final Object source = e.getSource();
		if (source == applyScaleCheckbox || source == applyOffsetCheckbox)
			updateEnabled();
	}

	private void enableTextField(final JTextField tf, final boolean enabled,
		final String defaultValue)
	{
		tf.setEnabled(enabled);
		tf.setVisible(enabled);
		if (!enabled) tf.setText(defaultValue);
	}

	private void updateEnabled() {
		final boolean manualScale = applyScaleCheckbox.isSelected();
		enableTextField(xScaleTextField, manualScale, scaleDefault);
		enableTextField(yScaleTextField, manualScale, scaleDefault);
		enableTextField(zScaleTextField, manualScale, scaleDefault);
		final boolean manualOffset = applyOffsetCheckbox.isSelected();
		enableTextField(xOffsetTextField, manualOffset, offsetDefault);
		enableTextField(yOffsetTextField, manualOffset, offsetDefault);
		enableTextField(zOffsetTextField, manualOffset, offsetDefault);
		pack();
	}

	private void saveFieldsToPrefs() {

		Prefs.set("tracing.SWCImportOptionsDialog.xOffset", xOffsetTextField
			.getText());
		Prefs.set("tracing.SWCImportOptionsDialog.yOffset", yOffsetTextField
			.getText());
		Prefs.set("tracing.SWCImportOptionsDialog.zOffset", zOffsetTextField
			.getText());
		Prefs.set("tracing.SWCImportOptionsDialog.xScale", xScaleTextField
			.getText());
		Prefs.set("tracing.SWCImportOptionsDialog.yScale", yScaleTextField
			.getText());
		Prefs.set("tracing.SWCImportOptionsDialog.zScale", zScaleTextField
			.getText());

		Prefs.set("tracing.SWCImportOptionsDialog.applyOffset", applyOffsetCheckbox
			.isSelected());

		Prefs.set("tracing.SWCImportOptionsDialog.applyScale", applyScaleCheckbox
			.isSelected());

		Prefs.set("tracing.SWCImportOptionsDialog.ignoreCalibration",
			ignoreCalibrationCheckbox.isSelected());

		Prefs.set("tracing.SWCImportOptionsDialog.replaceExistingPaths",
			replaceExistingPathsCheckbox.isSelected());

		Prefs.savePreferences();
	}

	private void restoreToDefaults() {
		ignoreCalibrationCheckbox.setSelected(false);
		applyScaleCheckbox.setSelected(false);
		applyOffsetCheckbox.setSelected(false);
		updateEnabled();
		saveFieldsToPrefs();
	}

	public SWCImportOptionsDialog(final JDialog parent, final String title) {

		super((JFrame)null, title, true);

		okButton.addActionListener(this);
		cancelButton.addActionListener(this);
		restoreToDefaultsButton.addActionListener(this);

		applyOffsetCheckbox.addItemListener(this);
		applyScaleCheckbox.addItemListener(this);

		final JPanel offsetPanel = new JPanel();
		offsetPanel.setLayout(new BorderLayout());
		offsetPanel.add(xOffsetTextField, BorderLayout.NORTH);
		offsetPanel.add(yOffsetTextField, BorderLayout.CENTER);
		offsetPanel.add(zOffsetTextField, BorderLayout.SOUTH);

		final JPanel scalePanel = new JPanel();
		scalePanel.setLayout(new BorderLayout());
		scalePanel.add(xScaleTextField, BorderLayout.NORTH);
		scalePanel.add(yScaleTextField, BorderLayout.CENTER);
		scalePanel.add(zScaleTextField, BorderLayout.SOUTH);

		final JPanel okCancelPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0,
			0));
		okCancelPanel.add(okButton);
		okCancelPanel.add(cancelButton);

		final JPanel buttonsPanel = new JPanel();
		buttonsPanel.setLayout(new BorderLayout());
		buttonsPanel.add(okCancelPanel, BorderLayout.WEST);
		buttonsPanel.add(restoreToDefaultsButton, BorderLayout.EAST);

		setLayout(new GridBagLayout());
		final GridBagConstraints c = new GridBagConstraints();

		c.gridx = 0;
		c.gridy = 0;
		c.anchor = GridBagConstraints.LINE_START;
		add(replaceExistingPathsCheckbox, c);

		c.gridy++;
		add(ignoreCalibrationCheckbox, c);
		ignoreCalibrationCheckbox.setToolTipText("<HTML><div WIDTH=500>"
				+ "Select this checkbox if the imported data uses pixel "
				+ "coordinates (N.B.: The SWC specification details that "
				+ "data must be stored in real world coordinates)");
		c.gridy++;
		add(applyOffsetCheckbox, c);

		c.gridy++;
		c.anchor = GridBagConstraints.CENTER;
		add(offsetPanel, c);

		c.gridy++;
		c.anchor = GridBagConstraints.LINE_START;
		add(applyScaleCheckbox, c);

		c.gridy++;
		c.anchor = GridBagConstraints.CENTER;
		add(scalePanel, c);

		c.gridy++;
		c.anchor = GridBagConstraints.LINE_START;
		c.fill = GridBagConstraints.BOTH;
		add(buttonsPanel, c);

		setFieldsFromPrefs();
		pack();
		addWindowListener(new WindowAdapter() {

			@Override
			public void windowClosing(final WindowEvent e) {
				dispose();
			}
		});
		setLocationRelativeTo(parent);
		setVisible(true);
	}

	public boolean getIgnoreCalibration() {
		return ignoreCalibrationCheckbox.isSelected();
	}

	public boolean getReplaceExistingPaths() {
		return replaceExistingPathsCheckbox.isSelected();
	}

	public double getXOffset() {
		return Double.parseDouble(xOffsetTextField.getText());
	}

	public double getYOffset() {
		return Double.parseDouble(yOffsetTextField.getText());
	}

	public double getZOffset() {
		return Double.parseDouble(zOffsetTextField.getText());
	}

	public double getXScale() {
		return Double.parseDouble(xScaleTextField.getText());
	}

	public double getYScale() {
		return Double.parseDouble(yScaleTextField.getText());
	}

	public double getZScale() {
		return Double.parseDouble(zScaleTextField.getText());
	}

	public boolean succeeded() {
		return succeeded;
	}

}
