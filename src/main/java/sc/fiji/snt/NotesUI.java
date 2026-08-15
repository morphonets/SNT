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

import ij.ImagePlus;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.gui.SNTEditorPane;
import sc.fiji.snt.viewer.AbstractBigViewer;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;

/**
 * Implements the <i>Notepad</i> pane.
 *
 * @author Tiago Ferreira
 */
public class NotesUI {

    static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

    private final SNTUI sntui;
    private final SNTEditorPane editor;


    /**
     * Constructs a <i>Notepad</i> instance.
     * @see SNTUI
     */
    public NotesUI(final SNTUI sntui) {
        this.sntui = sntui;
        editor = new SNTEditorPane(false);
        editor.setSyntaxStyle("markdown");
    }

    /**
     * Returns a reference to the underlying editor pane.
     */
    public SNTEditorPane getEditor() {
        return editor;
    }

    /**
     * Returns the assembled <i>Notepad</i> panel.
     */
    protected JPanel getPanel() {
        final JPanel container = SNTUI.InternalUtils.initTab();
        final GridBagConstraints gbc = GuiUtils.defaultGbc();
        gbc.fill = GridBagConstraints.BOTH;
        SNTUI.InternalUtils.addSeparatorWithURL(container, "Notepad:", true, gbc);
        gbc.gridy++;
        final String msg = """
                This pane allows you to jot down notes during tracing. \
                Notes can be saved to the workspace directory using the toolbar button \
                or via File › Save Session. Markdown syntax is supported.
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
        save.setToolTipText("Save to workspace");
        save.addActionListener(e -> {
            if (noNotesError()) return;
            final File workspaceDir = sntui.getOrPromptForWorkspace();
            if (workspaceDir == null) return;
            final String prefix = sntui.getImageFilenamePrefix();
            exportNotes(new File(sntui.getPrefs().getWorkspaceDir(), prefix + "_notes.md"));
        });
        final JButton syntax = new JButton(IconFactory.buttonIcon('\uf1c9', false, IconFactory.defaultColor()));
        syntax.setToolTipText("Toggle cheatsheet for Markdown syntax");
        syntax.addActionListener(e -> {
            final String cheatsheet = markDownOverview();
            if (editor.getText().contains(cheatsheet)) {
                editor.setText(editor.getText().replace(cheatsheet, ""));
                editor.requestFocusInWindow();
            } else {
                final int cheatsheetStart = editor.getDocument().getLength() + 1;
                editor.append(cheatsheet);
                editor.setCaretPosition(cheatsheetStart);
                scrollToOffsetIfNotVisible(cheatsheetStart + cheatsheet.length()/2); // scroll to middle
            }
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
            scrollToOffsetIfNotVisible(editor.getDocument().getLength());
        }));
        final JButton settingsStamp = new JButton(IconFactory.buttonIcon('\uf2db', true, IconFactory.defaultColor()));
        settingsStamp.setToolTipText("Insert computation settings");
        settingsStamp.addActionListener(e -> {
            editor.append("**Computation Settings:**");
            editor.append("\n```\n" + computationSettings() +"\n```\n");
            scrollToOffsetIfNotVisible(editor.getDocument().getLength());
        });
        toolbar.add(settingsStamp);
        final JButton imgTitleStamp = new JButton(IconFactory.buttonIcon('\uf03e', true, IconFactory.defaultColor()));
        imgTitleStamp.setToolTipText("Insert details on image being traced");
        imgTitleStamp.addActionListener(e -> {
            if (!sntui.plugin.accessToValidImageData()) {
                sntui.noValidImageDataError();
            } else {
                if (sntui.plugin.getImagePlus() != null)
                    editor.append("`" + standardModeImageDetails(sntui.plugin.getImagePlus()) + "`\n");
                else if (sntui.plugin.isStreamMode())
                    editor.append("`" + streamModeImageDetails() + "`\n");
                else
                    editor.append("`unknown image title`\n");
                scrollToOffsetIfNotVisible(editor.getDocument().getLength());
            }
        });
        toolbar.add(imgTitleStamp);
        final JButton filenameStamp = new JButton(IconFactory.buttonIcon('\uf15c', true, IconFactory.defaultColor()));
        filenameStamp.setToolTipText("Insert filename of .TRACES file");
        filenameStamp.addActionListener(e -> {
            final File file = sntui.getPrefs().getAutosaveFile();
            if (file == null) {
                sntui.error("Current tracings do not seem to be associated with a TRACES file.");
            } else {
                editor.append("`" + file.getName() + "`\n");
                scrollToOffsetIfNotVisible(editor.getDocument().getLength());
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

    public void load(final File file) {
        loadNotesFromFile(file);
    }

    public void save(final File file) {
        exportNotes(file);
    }

    public String computationSettings() {
        return sntui.geSettingsString();
    }

    private String streamModeImageDetails() {
        final SNT p = sntui.plugin;
        final StringBuilder sb = new StringBuilder("Streamed data");
        if (p.getWidth() > 0 && p.getHeight() > 0 && p.getDepth() > 0) {
            sb.append(String.format(" (%dx%dx%d px", p.getWidth(), p.getHeight(), p.getDepth()));
            if (p.getPixelWidth() > 0 && p.getPixelHeight() > 0 && p.getPixelDepth() > 0) {
                sb.append(String.format("; %.3fx%.3fx%.3f %s/px", p.getPixelWidth(), p.getPixelHeight(),
                        p.getPixelDepth(), p.getSpacingUnits()));
            }
            sb.append(")");
        }
        if (p.isMaterializedCrop()) sb.append(" [materialized crop]");
        final AbstractBigViewer viewer = sntui.getActiveBigViewer();
        if (viewer != null) {
            final String sourcePath = viewer.getPrimarySourcePath();
            if (sourcePath != null && !sourcePath.isBlank()) sb.append(", source: ").append(sourcePath);
            if (!viewer.getRenderedTrees().isEmpty())
                sb.append(", ").append(viewer.getRenderedTrees().size()).append(" tree(s)");
            if (viewer.hasMarkerManager() && viewer.getMarkerManager().hasBookmarks())
                sb.append(", ").append(viewer.getMarkerManager().getCount()).append(" marker(s)");
        }
        return sb.toString();
    }

    private String standardModeImageDetails(final ImagePlus imp) {
        assert imp != null;
        final StringBuilder sb = new StringBuilder(imp.getTitle());
        sb.append(String.format(" (%dx%dx%d px", imp.getWidth(), imp.getHeight(), imp.getNSlices()));
        final ij.measure.Calibration cal = imp.getCalibration();
        if (cal != null && cal.pixelWidth > 0 && cal.pixelHeight > 0) {
            sb.append(String.format("; %.3fx%.3fx%.3f %s/px", cal.pixelWidth, cal.pixelHeight,
                    (cal.pixelDepth > 0) ? cal.pixelDepth : 1d, cal.getUnit()));
        }
        sb.append(")");
        if (imp.getNChannels() > 1) sb.append(", ").append(imp.getNChannels()).append(" channels");
        if (imp.getNFrames() > 1) sb.append(", ").append(imp.getNFrames()).append(" frames");
        final String sourcePath = standardModeSourcePath(imp);
        if (sourcePath != null) sb.append(", source: ").append(sourcePath);
        final Collection<Tree> trees = sntui.plugin.getPathAndFillManager().getTrees();
        if (trees != null && !trees.isEmpty()) sb.append(", ").append(trees.size()).append(" tree(s)");
        if (sntui.getBookmarkManager().hasBookmarks())
            sb.append(", ").append(sntui.getBookmarkManager().getCount()).append(" marker(s)");
        return sb.toString();
    }

    /** Best-effort original file path/URL for {@code imp}, or null if unknown. */
    private String standardModeSourcePath(final ImagePlus imp) {
        final ij.io.FileInfo fi = imp.getOriginalFileInfo();
        if (fi == null) return null;
        if (fi.url != null && !fi.url.isBlank()) return fi.url;
        if (fi.directory != null && fi.fileName != null && !fi.fileName.isBlank())
            return fi.directory + fi.fileName;
        return null;
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

    private void scrollToOffsetIfNotVisible(final int offset) {
        SwingUtilities.invokeLater(() -> {
            try {
                final Rectangle2D r2d = editor.modelToView2D(offset);
                if (r2d == null) return;
                final Rectangle rect = r2d.getBounds();
                if (!editor.getVisibleRect().contains(rect)) {
                    editor.scrollRectToVisible(rect);
                }
            } catch (final javax.swing.text.BadLocationException ignored) {
                // offset no longer valid (e.g., editor content changed in the meantime): nothing to scroll to
            } finally {
                editor.requestFocusInWindow();
            }
        });
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
