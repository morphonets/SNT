/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
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
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;

import org.apache.commons.lang3.StringUtils;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.scijava.ui.swing.script.EditorPane;

import com.formdev.flatlaf.FlatLaf;

public class ScriptRecorder extends JDialog {

	private static final long serialVersionUID = -5275540638446494067L;
	private static final LANG DEF_LANG = LANG.PYTHON;
	private EditorPane editor;
	private JComboBox<LANG> combo;
	private LANG currentLang;

	enum LANG {
		BEANSHELL(".bsh", "//", true), GROOVY(".groovy", "//", false), PYTHON(".py", "#", false);

		private final String ext;
		private final String commentSeq;
		private boolean semiColon;

		LANG(final String ext, final String commentSeq, final boolean semiColon) {
			this.ext = ext;
			this.commentSeq = commentSeq;
			this.semiColon = semiColon;
		}

		@Override
		public String toString() {
			return StringUtils.capitalize(super.toString().toLowerCase() + " (" + ext + ")");
		}

		public static LANG[] list() {
			return new LANG[] { BEANSHELL, GROOVY, PYTHON };
		}

	}

	public ScriptRecorder() {
		setTitle("SNT Script Recorder (Experimental)");
		setAlwaysOnTop(true);
		editor = getEditor();
		combo = getComboBox();
		setLanguage(DEF_LANG);
		final RTextScrollPane sp = new RTextScrollPane(editor);
		sp.setLineNumbersEnabled(true);
		sp.setAutoscrolls(true);
		setLayout(new BorderLayout());
		add(getToolbar(), BorderLayout.NORTH);
		add(sp, BorderLayout.CENTER);
		prepareForDisplay();
	}

	private EditorPane getEditor() {
		final EditorPane editor = new EditorPane();
		editor.setColumns(70);
		editor.setRows(7);
		editor.requestFocusInWindow();
		editor.setMarkOccurrences(true);
		editor.setClearWhitespaceLinesEnabled(false);
		//editor.setEditable(false);
		editor.setAntiAliasingEnabled(true);
		editor.setLineWrap(false);
		editor.setMarginLineEnabled(false);
		editor.setCodeFoldingEnabled(true);
		editor.setFont(editor.getFont().deriveFont(GuiUtils.uiFontSize()));
		((DefaultCaret) editor.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE); // somehow this does not work!?
		try {
			editor.applyTheme((FlatLaf.isLafDark()) ? "dark" : "default");
		} catch (IllegalArgumentException ignored) {
			// do nothing
		}
		return editor;
	}

	private JComboBox<LANG> getComboBox() {
		final JComboBox<LANG> cb = new JComboBox<>(LANG.list());
		cb.addActionListener(e -> setLanguage((LANG) cb.getSelectedItem()));
		return cb;
	}

	private JButton createButton() {
		final JButton button = new JButton("Create");
		button.addActionListener(e -> {
			final boolean close = new GuiUtils(ScriptRecorder.this).getConfirmation("Close Script Recorder?",
					"Close Recorder?", "Close", "Leave Open");
			ScriptInstaller.newScript(editor.getText(), "SNT_Recorded_Script" + currentLang.ext);
			if (close)
				dispose();
		});
		return button;
	}

	private JPanel getToolbar() {
		final JPanel p = new JPanel(new GridBagLayout());
		final GridBagConstraints c = GuiUtils.defaultGbc();
		c.gridx = 0;
		c.gridy = 0;
		c.ipadx = 0;
		c.gridwidth = 3;
		c.fill = GridBagConstraints.NONE;
		p.add(GuiUtils.leftAlignedLabel("Language: ", true));
		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		p.add(combo, c);
		c.gridx = 2;
		c.fill = GridBagConstraints.NONE;
		p.add(createButton());
		return p;
	}

	private void setLanguage(final LANG lang) {
		final LANG oldLang = currentLang;
		currentLang = lang;
		switch (currentLang) {
		case BEANSHELL:
			editor.setSyntaxEditingStyle(RSyntaxTextArea.SYNTAX_STYLE_JAVA);
			break;
		case GROOVY:
			editor.setSyntaxEditingStyle(RSyntaxTextArea.SYNTAX_STYLE_GROOVY);
			break;
		case PYTHON:
			editor.setSyntaxEditingStyle(RSyntaxTextArea.SYNTAX_STYLE_PYTHON);
			break;
		default:
			throw new IllegalArgumentException("Unknown language");
		}
		combo.setSelectedItem(currentLang);
		if (oldLang != null)
			replaceHeaderAndCommentSeq(oldLang, currentLang);
	}

	private void replaceHeaderAndCommentSeq(final LANG oldLang, final LANG newLang) {
		final String oldHeader = ScriptInstaller.getBoilerplateScript(oldLang.ext);
		final String newHeader = ScriptInstaller.getBoilerplateScript(newLang.ext);
		editor.setText(editor.getText().replace(oldHeader, newHeader));
		try {
			for (int i = 0; i < editor.getLineCount(); i++) {
				final int start = editor.getLineStartOffset(i);
				final int end = editor.getLineEndOffset(i);
				final String line = editor.getText(start, end - start);
				if (line.trim().startsWith(oldLang.commentSeq))
					editor.replaceRange(line.replace(oldLang.commentSeq + " ", newLang.commentSeq + " "), start, end);
			}
		} catch (final BadLocationException e) {
			// ignored
		}
	}

	private void prepareForDisplay() {
		editor.setText(ScriptInstaller.getBoilerplateScript(currentLang.ext) + "\n\n");
		pack();
		final Rectangle bounds = getGraphicsConfiguration().getDevice().getDefaultConfiguration().getBounds();
		// final int x = (int) bounds.getMaxX() - getWidth() - getInsets().right;
		final int x = (int) bounds.getMinX() + getInsets().left;
		// we don't know the height of the title bar (OS specific): we'll assume height
		// of JComboBox instead. NB: this does not take into account position of native
		// dock/taskbar
		final int y = (int) bounds.getMaxY() - getHeight() - getInsets().top - getInsets().bottom - combo.getHeight();
		setLocation(x, y);
		setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		setFocusableWindowState(false);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(final WindowEvent e) {
				dispose();
			}

			@Override
			public void windowOpened(final WindowEvent e) {
				// https://forums.oracle.com/ords/apexds/post/jdialog-that-does-not-have-focus-when-it-is-shown-how-0854
				final Timer timer = new Timer(500, ae -> {
					setFocusableWindowState(true);
					// new RSyntaxTextAreaEditorKit.CollapseAllFoldsAction().actionPerformedImpl(null, textArea);
				});
				timer.setRepeats(false);
				timer.start();
			}
		});
	}

	public void recordCmd(final String str) {
		SwingUtilities.invokeLater(() -> {
			editor.append(str + ((currentLang.semiColon) ? ";" : "") + "\n");
		});
	}

	public void recordComment(final String str) {
		for (final String line : str.split("\n"))
			recordCmd(currentLang.commentSeq + " " + line);
		editor.setCaretPosition(editor.getText().length()); // caret ALWAYS_UPDATE not working!?
	}

	public void reset() {
		editor.setText("");
	}

	/**
	 * Sets the recording language.
	 *
	 * @param nameOrExtension the recording language. Either ".bsh", ".groovy", or
	 *                        ".py".
	 */
	public void setLanguage(final String nameOrExtension) {
		final String query = nameOrExtension.toLowerCase();
		if (query.contains("bsh") || query.contains("beanshell")) {
			setLanguage(LANG.BEANSHELL);
		} else if (query.contains("gvy") || query.contains("groovy")) {
			setLanguage(LANG.GROOVY);
		} else if (query.contains("py")) {
			setLanguage(LANG.PYTHON);
		} else {
			throw new IllegalArgumentException("Unsupported language: " + nameOrExtension);
		}
	}

	@Override
	public void dispose() {
		super.dispose();
		editor = null;
		combo = null;
		currentLang = null;
	}

	public static void main(final String[] args) {
		final ScriptRecorder rec = new ScriptRecorder();
		rec.setLanguage("bsh");
		rec.recordCmd("public static void main(final String[] args) {\n" //
				+ "	GuiUtils.setLookAndFeel();\n" //
				+ "	final ScriptRecorder rec = new ScriptRecorder();\n" //
				+ "	rec.setLanguage(\"bsh\");\n" //
				+ "	rec.setVisible(true);\n" //
				+ "}");
		rec.setVisible(true);
	}

}
