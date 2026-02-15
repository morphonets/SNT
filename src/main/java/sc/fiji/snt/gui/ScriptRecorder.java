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

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.Objects;

public class ScriptRecorder extends JDialog {

	public static final String IGNORED_CMD = "rec-off";

	private static final long serialVersionUID = -5275540638446494067L;
	private static final String REC_PROPERTY_KEY = "rec-key";
	private static final String REC_BOOL_PROPERTY_KEY = "rec-bool-key";
	private static final LANG DEF_LANG = LANG.PYTHON;
	private SNTEditorPane editor;
	private JComboBox<LANG> combo;
	private LANG currentLang;
	private static final boolean[] createOptions = {true, true};
	private boolean paused = false;
	private JToggleButton pauseButton;

	enum LANG {
		BEANSHELL(".bsh", "//", true), GROOVY(".groovy", "//", false), PYTHON(".py", "#", false);

		private final String ext;
		private final String commentSeq;
		private final boolean semiColon;

		LANG(final String ext, final String commentSeq, final boolean semiColon) {
			this.ext = ext;
			this.commentSeq = commentSeq;
			this.semiColon = semiColon;
		}

		@Override
		public String toString() {
			return switch (this) {
				case BEANSHELL -> "BeanShell [.bsh]";
				case GROOVY -> "Groovy [.groovy]";
				case PYTHON -> "Python (Jython) [.py]";
			};
		}

		public static LANG[] list() {
			return new LANG[] { BEANSHELL, GROOVY, PYTHON };
		}

	}

	public ScriptRecorder() {
		setTitle("SNT Script Recorder (Experimental)");
		editor = getEditor();
		combo = getComboBox();
		setLanguage(DEF_LANG);
		add(getToolbar(), BorderLayout.NORTH);
		add(editor.getScrollPane(), BorderLayout.CENTER);
		prepareForDisplay();
	}

	private SNTEditorPane getEditor() {
		final SNTEditorPane editor = new SNTEditorPane(true);
		editor.setColumns(70);
		editor.setRows(7);
		editor.setClearWhitespaceLinesEnabled(false);
		editor.requestFocusInWindow();
		editor.getScrollPane().setBorder(null);
		//editor.setEditable(false);
		((DefaultCaret) editor.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE); // somehow this does not work!?
		return editor;
	}

	private JComboBox<LANG> getComboBox() {
		final JComboBox<LANG> cb = new JComboBox<>(LANG.list());
		cb.addActionListener(e -> setLanguage((LANG) cb.getSelectedItem()));
		cb.setToolTipText("Scripting language of recording");
		return cb;
	}

	private JButton createButton() {
		final JButton button = new JButton(IconFactory.buttonIcon('\uf0fe', false, IconFactory.defaultColor()));
		button.setToolTipText("Create script");
		button.addActionListener(e -> {
			if (createOptions[1]) {
				final boolean[] closeAndNag = new GuiUtils(ScriptRecorder.this)
						.getPersistentConfirmation("Close Recorder after creating script?", "Create and Close?");
				createOptions[0] = closeAndNag[0];
				createOptions[1] = !closeAndNag[1];
			}
			final String title = (LANG.PYTHON.ext.equals(currentLang.ext)) ? "snt_recorded_script" : "SNTRecordedScript";
			ScriptInstaller.newScript(editor.getText(), title + currentLang.ext);
			if (createOptions[0]) dispose();
		});
		return button;
	}

	private AbstractButton optionsButton() {
		final JPopupMenu menu = new JPopupMenu();
		final JCheckBoxMenuItem mi2 = new JCheckBoxMenuItem("Float Above All Windows", isAlwaysOnTop());
		mi2.addItemListener(e -> setAlwaysOnTop(mi2.isSelected()));
		menu.add(mi2);
		final JMenuItem mi3 = new JMenuItem("Reset Script Creation Prompts");
		mi3.addActionListener(e -> Arrays.fill(createOptions, true));
		menu.add(mi3);
		return editor.optionsButton(menu);
	}

	private JToolBar getToolbar() {
		final JToolBar tb = new JToolBar();
		tb.setFloatable(false);
		tb.add(optionsButton());
		tb.addSeparator();
		tb.add(editor.lightDarkToggleButton());
		tb.add(Box.createHorizontalStrut(10));
		tb.add(combo);
		tb.add(Box.createHorizontalStrut(10));
		tb.add(editor.timeStampButton((e -> {
			editor.appendTimeStamp(currentLang.commentSeq + " ", "\n");
			requestFocusInWindow();
		})));
		tb.addSeparator();
		tb.add(pauseButton());
		tb.addSeparator();
		tb.add(createButton());
		return tb;
	}

	private JToggleButton pauseButton() {
		pauseButton = new JToggleButton();
		updatePauseButtonState();
		pauseButton.addActionListener(e -> {
			paused = pauseButton.isSelected();
			recordCommentInternal("Recording " + ((paused) ? "paused..." : "resumed"));
			updatePauseButtonState();
		});
		return pauseButton;
	}

	private void updatePauseButtonState() {
		if (paused) {
			pauseButton.setIcon(IconFactory.buttonIcon('\uf144', false));
			pauseButton.setToolTipText("Resume recording");
		} else {
			pauseButton.setIcon(IconFactory.buttonIcon('\uf28b', false));
			pauseButton.setToolTipText("Pause recording");
		}
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
		} finally {
			replaceBooleans(oldLang, newLang);
		}
	}

	private void replaceBooleans(final LANG oldLang, final LANG newLang) {
		if (oldLang != LANG.PYTHON && newLang != LANG.PYTHON || oldLang == newLang) {
			return;
		}
		if (newLang == LANG.PYTHON) {
			editor.setText(editor.getText().replaceAll("\\btrue", "True").replaceAll("\\bfalse", "False"));
		} else {
			editor.setText(editor.getText().replaceAll("\\bTrue", "true").replaceAll("\\bFalse", "false"));
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

	@SuppressWarnings("unused")
	private void deleteLine(final int lineNumber) {
		try {
			final int start = editor.getLineStartOffset(lineNumber);
			final int end = editor.getLineEndOffset(lineNumber);
			editor.replaceRange("", start, end);
		} catch (final BadLocationException e) {
			// ignored
		}
	}

	public void recordCmd(final String str) {
		recordCmd(str, false);
	}

	public void recordCmd(final String str, final boolean suppressNextLine) {
		if (!paused) recordCmdInternal(str, suppressNextLine);
	}

	private void recordCmdInternal(final String str, final boolean suppressNextLine) {
		final String sfx = ((currentLang.semiColon) ? ";\n" : "\n");
		SwingUtilities.invokeLater(() -> {
			if (currentLang == LANG.PYTHON) {
				editor.append(str.replaceAll("\\btrue", "True").replaceAll("\\bfalse", "False") + sfx);
			} else {
				editor.append(str + sfx);
			}
			if (suppressNextLine)
				editor.append(currentLang.commentSeq );
		});
	}

	private void recordCommentInternal(final String str) {
		for (final String line : str.split("\n"))
			recordCmdInternal(currentLang.commentSeq + " " + line, false);
		editor.setCaretPosition(editor.getText().length()); // caret ALWAYS_UPDATE not working!?
	}

	public void recordComment(final String str) {
		if (!paused) recordCommentInternal(str);
	}

	public void reset() {
		editor.setText("");
	}

	/**
	 * Returns whether recording is currently paused.
	 *
	 * @return true if recording is paused, false otherwise
	 */
	public boolean isPaused() {
		return paused;
	}

	/**
	 * Sets the pause state of the recorder.
	 *
	 * @param paused true to pause recording, false to resume
	 */
	public void setPaused(final boolean paused) {
		this.paused = paused;
		if (pauseButton != null) {
			pauseButton.setSelected(paused);
			updatePauseButtonState();
		}
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

	public static void setRecordingCall(final AbstractButton button, final String recordingString) {
		if (button instanceof JCheckBox)
			button.putClientProperty(REC_BOOL_PROPERTY_KEY, Objects.requireNonNullElse(recordingString, IGNORED_CMD));
		else
			button.putClientProperty(REC_PROPERTY_KEY, Objects.requireNonNullElse(recordingString, IGNORED_CMD));
	}

	public static String getRecordingCall(final AbstractButton button) {
		if (button instanceof JCheckBox) {
			final Object rec = button.getClientProperty(REC_BOOL_PROPERTY_KEY);
			return (rec == null) ? null : rec.toString().replace("{STATE}", String.valueOf(button.isSelected()));
		}
		final Object rec = button.getClientProperty(REC_PROPERTY_KEY);
		return (rec == null) ? null : rec.toString();
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
