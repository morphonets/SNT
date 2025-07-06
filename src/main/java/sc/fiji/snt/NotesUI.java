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

package sc.fiji.snt;

import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.gui.SNTEditorPane;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Implements the <i>Notepad</i> pane.
 *
 * @author Tiago Ferreira
 */
public class NotesUI {

    private final SNTUI sntui;
    private final SNTEditorPane editor;


    /**
     * Constructs a <i>Notepad</i> instance.
     * @see SNTUI
     */
    public NotesUI(final SNTUI sntui) {
        this.sntui = sntui;
        editor = new SNTEditorPane(false);
        editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
    }

    /**
     * Returns the assembled <i>Notepad</i> panel.
     */
    protected JPanel getPanel() {
        final JPanel container = SNTUI.InternalUtils.getTab();
        final GridBagConstraints gbc = GuiUtils.defaultGbc();
        gbc.fill = GridBagConstraints.BOTH;
        SNTUI.InternalUtils.addSeparatorWithURL(container, "Notepad:", true, gbc);
        gbc.gridy++;
        final String msg = """
                This pane allows you to jot down notes during a tracing session.
                Similarly to Bookmarks, notes are not autosaved and must be \
                exported manually. Markdown syntax is supported.
                """;
        gbc.weighty = 0.0;
        container.add(GuiUtils.longSmallMsg(msg, container), gbc);
        gbc.gridy++;
        container.add(getToolBar(), gbc);
        gbc.gridy++;
        gbc.weighty = 0.95;
        container.add(editor.getScrollPane(), gbc);
        return container;
    }


    private JComponent getToolBar() {
        final JButton open = new JButton(IconFactory.buttonIcon(IconFactory.GLYPH.IMPORT, 1f));
        open.setToolTipText("Import from file");
        open.addActionListener(e -> {
            if (!editor.getText().isBlank() && !sntui.guiUtils.getConfirmation("Importing a new file will clear" + " existing notes. Proceed?", "Replace Notepad Contents?"))
                return;
            final File openFile = sntui.openFile("md");
            if (openFile == null) return;
            if (!SNTUtils.fileAvailable(openFile)) {
                sntui.guiUtils.error(String.format("%s does not exist or it cannot be open.", openFile.getName()));
            } else {
                loadNotesFromFile(openFile);
                noRecordComment();
            }
        });
        final JButton export = new JButton(IconFactory.buttonIcon(IconFactory.GLYPH.EXPORT, 1f));
        export.setToolTipText("Save as...");
        export.addActionListener(e -> {
            if (noNotesError()) return;
            final File file = sntui.saveFile("Export Notes...", "SNT_notes.md", "md");
            if (file != null) exportNotes(file);
        });
        final JButton save = new JButton(IconFactory.buttonIcon(IconFactory.GLYPH.SAVE, 1f));
        save.setToolTipText("Save to last saved path");
        save.addActionListener(e -> {
            if (noNotesError()) return;
            if (editor.getFile() != null && !editor.fileChanged())
                sntui.guiUtils.error("Notepad unchanged. No changes to save.");
            else if (editor.getFile() != null) exportNotes(editor.getFile());
            else export.doClick();
        });
        final JButton syntax = new JButton(IconFactory.buttonIcon('\uf1c9', false, IconFactory.defaultColor()));
        syntax.setToolTipText("Toggle cheatsheet for Markdown syntax");
        syntax.addActionListener(e -> {
            final String cheatsheet = markDownOverview();
            if (editor.getText().contains(cheatsheet)) {
                editor.setText(editor.getText().replace(cheatsheet, ""));
            } else {
                editor.append(cheatsheet);
                editor.setCaretPosition(editor.getDocument().getLength()-cheatsheet.length());
            }
            editor.requestFocusInWindow();
        });
        final JToolBar toolbar = new JToolBar();
        toolbar.add(open);
        toolbar.addSeparator();
        toolbar.add(save);
        toolbar.add(export);
        toolbar.addSeparator();
        toolbar.add(Box.createHorizontalGlue());
        toolbar.addSeparator();
        toolbar.add(editor.timeStampButton(e -> {
            editor.appendTimeStamp("*", "*\n");
            editor.requestFocusInWindow();
        }));
        final JButton settingsStamp = new JButton(IconFactory.buttonIcon('\uf2db', true, IconFactory.defaultColor()));
        settingsStamp.setToolTipText("Insert computation settings");
        settingsStamp.addActionListener(e -> {
            editor.append("**Computation Settings:**");
            editor.append("\n```\n" + sntui.geSettingsString() +"\n```\n");
            editor.requestFocusInWindow();
        });
        toolbar.add(settingsStamp);
        final JButton imgTitleStamp = new JButton(IconFactory.buttonIcon('\uf03e', true, IconFactory.defaultColor()));
        imgTitleStamp.setToolTipText("Insert name of image being traced");
        imgTitleStamp.addActionListener(e -> {
            if (!sntui.plugin.accessToValidImageData()) {
                sntui.noValidImageDataError();
            } else {
                editor.append("`" + sntui.plugin.getImagePlus().getTitle() + "`\n");
                editor.requestFocusInWindow();
            }
        });
        toolbar.add(imgTitleStamp);
        final JButton filenameStamp = new JButton(IconFactory.buttonIcon('\uf15c', true, IconFactory.defaultColor()));
        filenameStamp.setToolTipText("Insert filename of .TRACES file");
        filenameStamp.addActionListener(e -> {
            final File file = sntui.getAutosaveFile();
            if (file == null) {
                sntui.error("Current tracings do not seem to be associated with a TRACES file.");
            } else {
                editor.append("`" + file.getName() + "`\n");
                editor.requestFocusInWindow();
            }
        });
        toolbar.add(filenameStamp);
        toolbar.addSeparator();
        toolbar.add(Box.createHorizontalGlue());
        toolbar.addSeparator();
        toolbar.add(editor.lightDarkToggleButton());
        toolbar.add(syntax);
        return toolbar;
    }

    private void exportNotes(final File file) {
        try {
            saveNotesToFile(file);
            sntui.showStatus("Notes saved to " + file.getName(), true);
            try {
                editor.setFileName(file); // set editor.getFile();
            } catch (final NullPointerException ignored) {
                // do nothing. ScriptService has not been initialized
            }
        } catch (final IOException ex) {
            sntui.showStatus("I/O error. Notes not saved.", true);
            sntui.guiUtils.error("Notes could not be saved. See Console for details.");
            ex.printStackTrace();
        } finally {
            noRecordComment();
        }
    }

    private void loadNotesFromFile(final File file) {
        try {
            editor.open(file);
            editor.setCaretPosition(editor.getDocument().getLength());
            editor.requestFocusInWindow();
            sntui.showStatus("Notes loaded from " + file.getName(), true);
        } catch (final Exception ex) {
            sntui.guiUtils.error(ex.getMessage());
            SNTUtils.error("loadNotesFromFile() failure", ex);
        }
    }

    private void saveNotesToFile(final File file) throws IOException {
        try (final FileWriter writer = new FileWriter(file)) {
            editor.write(writer);
        }
    }

    private boolean noNotesError() {
        final boolean blank = editor.getText().isBlank();
        if (blank) sntui.guiUtils.error("Notepad is empty.");
        return blank;
    }

    private void noRecordComment() {
        if (null != sntui.getRecorder(false))
            sntui.getRecorder(false).recordComment("Notepad actions are currently not recorded");
    }

    private String markDownOverview() {
        return """
                
                ------ Markdown Basics
                # Headings
                Headings are lines starting with `# `
                (i.e., `# h1`, `## h2`, `### h3`, etc.)
                
                # Emphasis
                *italic* (same as _italic_)
                **bold** (same as __bold__)
                ~~strikethrough~~
                
                # Code
                `inline code` uses a single backtick
                ```
                a code block uses 3 backticks
                ```
                
                # Lists
                - Unordered can start with `- `
                - (or `+`, `*`)
                
                1. Ordered lists start with `1.`, `2.`, etc.
                2. In either case sub-lists are
                  * defined by 2-space indentation
                
                # Links
                [SNT link](https://imagej.net/plugins/snt/)
                [local link](file:///Path/to/local.file)
                ------
                """;
    }
}