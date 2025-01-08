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

import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.File;

/**
 * A FilePicker panel similar to Scijava's File widget but adopting SNT's
 * JFileChooser.
 */
public class FilePicker extends JPanel {

	private static final long serialVersionUID = 1L;
	public static final int OPEN_DIALOG = JFileChooser.OPEN_DIALOG;
	public static final int SAVE_DIALOG = JFileChooser.SAVE_DIALOG;
	private final int fileChooserType;
	final JTextField textField;
	private File fallbackFile;
	private String label;
	private String title;

	/**
	 * Default FilePicker constructor.
	 * 
	 * @param fileChooserType   Either {@link #OPEN_DIALOG} or {@link #SAVE_DIALOG}
	 * @param initialFile       File path to be displayed when panel is shown. Optional.
	 *                          {@code null} allowed.
	 * @param allowedExtensions Allowed file extensions in JFileChooser. Optional.
	 *                          {@code null} allowed.
	 */
	public FilePicker(final int fileChooserType, final File initialFile, final String... allowedExtensions) {
		if (fileChooserType != OPEN_DIALOG && fileChooserType != SAVE_DIALOG) {
			throw new IllegalArgumentException("Unsupported file chooser type");
		}
		this.fileChooserType = fileChooserType;
		textField = new JTextFieldFile(fileChooserType, allowedExtensions);
		fallbackFile = initialFile;
		if (initialFile == null) {
			if (allowedExtensions == null || allowedExtensions.length == 0)
				fallbackFile = new File(SNTPrefs.lastknownDir(), "SNT_data");
			else
				fallbackFile = new File(SNTPrefs.lastknownDir(), "SNT_data." + allowedExtensions[0]);
		}		
		textField.setText(fallbackFile.getAbsolutePath());
		final JButton button = new JButton("Browse...");
		button.addActionListener(e -> {
			File existingFile = new File(textField.getText());
			File chosenFile;
			if (existingFile == null || !SNTUtils.fileAvailable(existingFile.getParentFile()))
				existingFile = fallbackFile;
			if (fileChooserType == OPEN_DIALOG) {
				chosenFile = new GuiUtils(SwingUtilities.windowForComponent(this)).getOpenFile(getTitle(), existingFile,
						allowedExtensions);
			} else {
				chosenFile = new GuiUtils(SwingUtilities.windowForComponent(this)).getSaveFile(getTitle(), existingFile,
						allowedExtensions);
			}
			// set field to path of chosen file. Default to existing file if user dismissed JFileChooser
			textField.setText( (chosenFile == null) ? existingFile.getAbsolutePath() : chosenFile.getAbsolutePath());
		});
		setLayout(new GridBagLayout());
		final GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = 0;
		c.anchor = GridBagConstraints.LINE_START;
		c.insets.right = 3;
		add(new JLabel(getLabel()), c);
		c.gridx++;
		c.fill = GridBagConstraints.BOTH;
		c.weightx = 1;
		c.weighty = 1;
		add(textField, c);
		c.gridx++;
		c.weightx = 0;
		c.weighty = 0;
		c.insets.right = 0;
		add(button, c);
	}

	private String getLabel() {
		if (label != null)
			return label;
		return (fileChooserType == OPEN_DIALOG) ? "File path: " : "Save to: ";
	}

	private String getTitle() {
		if (title != null)
			return title;
		return (fileChooserType == OPEN_DIALOG) ? "Open... " : "Save To...";
	}

	public File getFile() {
		// the 'final' file choice is always defined by the textfield path, unless it has never been set
		return (textField.getText() == null || textField.getText().isBlank()) ? fallbackFile : new File(textField.getText());
	}

	/**
	 * Sets the File Picker label
	 * 
	 * @param label the TextField label
	 */
	public void setLabel(final String label) {
		this.label = label;
	}

	/**
	 * Sets the JFileChooser title
	 * 
	 * @param title the JFileChooser title
	 */
	public void setFileChooserTitle(final String title) {
		this.title = title;
	}

	public void addListener(final DocumentListener dl) {
		textField.getDocument().addDocumentListener(dl);
	}

	public void removeListener(final DocumentListener dl) {
		textField.getDocument().removeDocumentListener(dl);
	}

	boolean invalidFileChoiceError() {
		final File file = getFile();
		if (!valid(file)) {
			final String msg = (fileChooserType == OPEN_DIALOG)
					? "Make sure file remains available and that you have permission to read it."
					: "Make sure parent folder exists and that you have appropriate write permissions.";
			new GuiUtils(SwingUtilities.windowForComponent(this)).error("Invalid file path: " + msg);
			return true;
		}
		return false;
	}

	private boolean valid(final File f) {
		try {
			if (fileChooserType == OPEN_DIALOG)
				return f != null && f.exists() && f.canRead();
			else
				return f != null && f.getParentFile().exists() && f.getParentFile().canWrite();
		} catch (final SecurityException | NullPointerException ignored) {
			return false;
		}
	}

	private class JTextFieldFile extends JTextField {

		private static final long serialVersionUID = 6943445407475634685L;
		private final Color defaultColor;
		private final String[] allowedExtensions;

		JTextFieldFile(final int type, final String... allowedExtensions) {
			super(30);
			this.allowedExtensions = allowedExtensions;
			defaultColor = super.getForeground();
			getDocument().addDocumentListener(new DocumentListener() {

				@Override
				public void changedUpdate(final DocumentEvent e) {
					updateField();
				}

				@Override
				public void removeUpdate(final DocumentEvent e) {
					updateField();
				}

				@Override
				public void insertUpdate(final DocumentEvent e) {
					updateField();
				}

			});
		}

		private boolean validExtension(final String lowercasePath) {
			for (final String extension : allowedExtensions) {
				if (lowercasePath.endsWith(extension))
					return true;
			}
			return false;
		}

		private void updateField() {
			if (valid(new File(getText())) && validExtension(getText().toLowerCase())) {
				setForeground(defaultColor);
				setToolTipText(null);
			} else {
				setForeground(Color.RED);
				setToolTipText("Invalid path");
			}
		}
	}
}
