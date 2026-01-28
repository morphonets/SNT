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

package sc.fiji.snt.gui;

import com.formdev.flatlaf.FlatClientProperties;
import sc.fiji.snt.SNTPrefs;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.util.Arrays;

/**
 * A FilePicker panel similar to Scijava's File widget but adopting SNT's FileChooser.
 */
public class FilePicker extends JPanel {

    private static final long serialVersionUID = 1L;
    public static final int OPEN_DIALOG = FileChooser.OPEN_DIALOG;
    public static final int SAVE_DIALOG = FileChooser.SAVE_DIALOG;
    private final JTextField textField;
	private final FileChooser fileChooser;
    private final int fileChooserType;
    private File fallbackFile;
    private String label;
    private String title;

    /**
     * Default FilePicker constructor.
     *
     * @param fileChooserType   Either {@link #OPEN_DIALOG} or {@link #SAVE_DIALOG}
     * @param initialFile       File path to be displayed when panel is shown. Optional.
     *                          {@code null} allowed.
     * @param allowedExtensions Allowed file extensions in FileChooser. If "/" is included then
     *                          FileChooser allows selection of both files and directories. Optional.
     *                          {@code null} allowed.
     */
    public FilePicker(final int fileChooserType, final File initialFile, final String... allowedExtensions) {
        if (fileChooserType != OPEN_DIALOG && fileChooserType != SAVE_DIALOG) {
            throw new IllegalArgumentException("Unsupported file chooser type");
        }
        this.fileChooserType = fileChooserType;
        textField = new JTextFieldFile(allowedExtensions);
		fileChooser = initFileChooser(allowedExtensions);
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
            fileChooser.setSelectedFile(getFile());
            if (fileChooser.showOpenDialog(SwingUtilities.windowForComponent(this)) == FileChooser.APPROVE_OPTION) {
                final File chosenFile = fileChooser.getSelectedFile();
                textField.setText(chosenFile.getAbsolutePath());
                SNTPrefs.setLastKnownDir(chosenFile);
            }
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

    private FileChooser initFileChooser(final String... allowedExtensions) {
        final int selectionMode = (isFilesOnly()) ? FileChooser.FILES_ONLY : FileChooser.FILES_AND_DIRECTORIES;
        final FileNameExtensionFilter extensionFilter = getExtensionFilter(allowedExtensions);
		final FileChooser fc = (FileChooser) GuiUtils.fileChooser(getTitle(), null, fileChooserType, selectionMode);
        if (extensionFilter != null)
            fc.setFileFilter(extensionFilter);
        else
            fc.setAcceptAllFileFilterUsed(true);
        fc.setDialogType(fileChooserType);
        fc.setMultiSelectionEnabled(false);
        return fc;
    }

    private boolean isFilesOnly(final String... allowedExtensions) {
        return allowedExtensions != null && !Arrays.asList(allowedExtensions).contains("/");
    }

    private FileNameExtensionFilter getExtensionFilter(final String... allowedExtensions) {
        if (allowedExtensions == null || allowedExtensions.length == 0)
            return null;
        final String[] filteredExtensions = Arrays.stream(allowedExtensions).filter(ext -> !ext.contains("/")).toArray(String[]::new);
        if (filteredExtensions.length == 0)
            return null;
        final String prefix = (isFilesOnly(allowedExtensions)) ? "Files with extension(s) " : "Folders, and files with extension(s) ";
        return new FileNameExtensionFilter(prefix + String.join(",", allowedExtensions) + ")", allowedExtensions);
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
        return (textField.getText() == null || textField.getText().isBlank()) ? fallbackFile : new File(textField.getText().trim());
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
     * Sets the FileChooser title
     *
     * @param title the FileChooser title
     */
    @SuppressWarnings("unused")
    public void setFileChooserTitle(final String title) {
        this.title = title;
    }

    public void addListener(final DocumentListener dl) {
        textField.getDocument().addDocumentListener(dl);
    }

    boolean invalidFileChoiceError() {
        final File file = getFile();
        if (!valid(file)) {
            final String msg = (fileChooserType == OPEN_DIALOG)
                    ? "Make sure file remains available and that you have permission to read it."
                    : "Make sure parent folder exists and that you have appropriate write permissions.";
			fileChooser.error("Invalid file path: " + msg);
            return true;
        }
        return false;
    }

    private boolean valid(final File f) {
        try {
            if (fileChooserType == OPEN_DIALOG)
                return f != null && f.exists() && f.canRead();
            return f != null && f.getParentFile().exists() && f.getParentFile().canWrite();
        } catch (final SecurityException | NullPointerException ignored) {
            return false;
        }
    }

    private class JTextFieldFile extends JTextField {

        private static final long serialVersionUID = 6943445407475634685L;
        private final String[] allowedExtensions;

        JTextFieldFile(final String... allowedExtensions) {
            super(30);
            this.allowedExtensions = allowedExtensions;
            GuiUtils.addClearButton(this);
            if (allowedExtensions != null && allowedExtensions.length > 0)
                GuiUtils.addPlaceholder(this, "Supported extensions: " + String.join(", ", allowedExtensions));
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
            assert lowercasePath != null;
            return Arrays.stream(allowedExtensions).anyMatch(lowercasePath::endsWith);
        }

        private void updateField() {
            final String text = getText();
            if (text == null || text.isEmpty() || (valid(new File(text)) && validExtension(text.toLowerCase()))) {
                putClientProperty(FlatClientProperties.OUTLINE, null);
                setToolTipText(null);
            } else {
                putClientProperty(FlatClientProperties.OUTLINE, FlatClientProperties.OUTLINE_WARNING);
                setToolTipText("Invalid path");
            }
        }
    }
}
