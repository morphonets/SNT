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

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicComboBoxEditor;

import com.jidesoft.swing.Searchable;
import com.jidesoft.swing.SearchableBar;
import com.jidesoft.swing.WholeWordsSupport;
import com.jidesoft.swing.event.SearchableEvent;
import com.jidesoft.swing.event.SearchableListener;

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils.TextFieldWithPlaceholder;

/**
 * Implements a SearchableBar following SNT's UI.
 *
 * @author Tiago Ferreira
 */
public class SNTSearchableBar extends SearchableBar {

	private static final long serialVersionUID = 1L;
	public static final int SHOW_SEARCH_OPTIONS = 0x80;
	protected List<AbstractButton> _extraButtons;
	protected boolean containsCheckboxes;
	private int buttonHeight;
	private float iconHeight;
	private int buttonCount;

	private String statusLabelPlaceholder;
	private String objectDescription;
	private GuiUtils guiUtils;
	private JMenuItem findAndReplaceMenuItem;

	public SNTSearchableBar(final Searchable searchable) {
		this(searchable, "Find:");
	}

	public SNTSearchableBar(final Searchable searchable, final String placeholder) {
		super(searchable, true); // will create _comboBox
		init(placeholder);
		searchable.setCaseSensitive(false);
		searchable.setWildcardEnabled(false);
		searchable.setFromStart(false);
		searchable.setRepeats(true);
		setShowMatchCount(false); // for performance reasons
		setBorderPainted(false);
		setBorder(BorderFactory.createEmptyBorder());
		setMismatchForeground(Color.RED);
		setMaxHistoryLength(10);
		setHighlightAll(true);
		updatPlaceholderText();
	}

	private void init(final String placeholder) {
		_leadingLabel = new JLabel();
		if (getMaxHistoryLength() == 0) {
			_leadingLabel.setLabelFor(_textField);
			_textField.setVisible(true);
			_comboBox.setVisible(false);
		}
		else {
			_leadingLabel.setLabelFor(_comboBox);
			_comboBox.setVisible(true);
			_textField.setVisible(false);
		}
		// _comboBox has been initialized by the parent constructor. Now adjust its placeholder text
		final TextFieldWithPlaceholder editorField = ((GuiUtils.TextFieldWithPlaceholder)_comboBox.getEditor().getEditorComponent());
		editorField.changePlaceholder(placeholder, true);
		setStatusLabelPlaceholder(SNTUtils.getReadableVersion());
	}

	@SuppressWarnings("rawtypes")
	@Override
	protected JComboBox createComboBox() {
		final JComboBox comboBox = super.createComboBox();
		comboBox.setEditor(new BoxEditorWithPrompt("Find:"));
		return comboBox;
	}

	private class BoxEditorWithPrompt extends BasicComboBoxEditor {
		BoxEditorWithPrompt(String prompt) {
			super();
			final int cols = editor.getColumns();
			editor = new GuiUtils.TextFieldWithPlaceholder(prompt);
			editor.setColumns(cols);
		}
	}

	private void updatPlaceholderText() {
		final TextFieldWithPlaceholder editorField = ((GuiUtils.TextFieldWithPlaceholder)_comboBox.getEditor().getEditorComponent());
		if (getSearchable().isWildcardEnabled() && getSearchable().isCaseSensitive())
			editorField.changePlaceholder("Active filters: [Aa]  [?*]", false);
		else if (getSearchable().isWildcardEnabled())
			editorField.changePlaceholder("Active filter: [?*]", false);
		else if (getSearchable().isCaseSensitive())
			editorField.changePlaceholder("Active filter: [Aa]", false);
		else
			editorField.resetPlaceholder();
	}

	public void setStatusLabelPlaceholder(final String placeholder) {
		statusLabelPlaceholder = placeholder;
		if (_statusLabel != null) _statusLabel.setText(statusLabelPlaceholder);
	}

	@Override
	protected void installComponents() {

		setLayout(new GridBagLayout());
		final GridBagConstraints gbc = new GridBagConstraints();

		// close button (if any)
		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		if ((getVisibleButtons() & SHOW_CLOSE) != 0) {
			add(_closeButton, gbc);
		}

		// search field
		gbc.gridx = 1;
		gbc.gridy = 1;
		gbc.weightx = 1.0;
		gbc.anchor = GridBagConstraints.WEST;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		add((getMaxHistoryLength() == 0) ? _textField : _comboBox, gbc);

		// button panel
		final JPanel buttonPanel = new JPanel();
		if ((getVisibleButtons() & SHOW_SEARCH_OPTIONS) != 0) {
			addButton(buttonPanel, createSearchOptionsButton());
		}
		if ((getVisibleButtons() & SHOW_HIGHLIGHTS) != 0) {
			addButton(buttonPanel, _highlightsButton);
		}
		if ((getVisibleButtons() & SHOW_NAVIGATION) != 0) {
			addButton(buttonPanel, _findNextButton);
			addButton(buttonPanel, _findPrevButton);
		}
		if ((getVisibleButtons() & SHOW_MATCHCASE) != 0) {
			addButton(buttonPanel, _matchCaseCheckBox);
		}
		if ((getVisibleButtons() & SHOW_WHOLE_WORDS) != 0 && getSearchable() instanceof WholeWordsSupport) {
			addButton(buttonPanel, _wholeWordsCheckBox);
		}
		if ((getVisibleButtons() & SHOW_REPEATS) != 0) {
			addButton(buttonPanel, _repeatCheckBox);
		}
		if (_extraButtons != null) {
			// buttonPanel.add(Box.createHorizontalGlue());
			_extraButtons.forEach(b -> addButton(buttonPanel, b));
		}
		if (buttonCount > 0) {
			normalizeButtons(buttonPanel);
			buttonPanel.setLayout(new GridLayout(1, buttonCount));
			gbc.gridx = 2;
			gbc.gridy = 1;
			gbc.fill = GridBagConstraints.BOTH;
			gbc.weightx = 0.0;
			add(buttonPanel, gbc);
		}

		// status label
		if ((getVisibleButtons() & SHOW_STATUS) != 0) {
			gbc.gridx = 0;
			gbc.gridy = 2;
			gbc.gridwidth = 3;
			gbc.weightx = 1;
			gbc.anchor = GridBagConstraints.WEST;
			add(statusLabel(), gbc);
		}

	}

	protected void setSearcheableObjectDescription(final String objectDescription) {
		this.objectDescription = objectDescription;
	}

	public void setGuiUtils(final GuiUtils guiUtils) {
		this.guiUtils = guiUtils;
	}

	private GuiUtils getGuiUtils() {
		if (guiUtils == null) guiUtils = new GuiUtils(getRootPane());
		return guiUtils;
	}

	protected void setFindAndReplaceMenuItem(final JMenuItem findAndReplaceMenuItem) {
		this.findAndReplaceMenuItem = findAndReplaceMenuItem;
	}

	private JButton createSearchOptionsButton() {
		final JPopupMenu popup = new JPopupMenu();
		final JButton button = new JButton();
		button.setToolTipText("Options for text-based filtering");
		button.addActionListener( e -> {
			popup.show(button, button.getWidth() / 2, button.getHeight() / 2);
		});
		formatButton(button, IconFactory.GLYPH.LIST_ALT);

		final JMenuItem jcbmi1 = new JCheckBoxMenuItem("Case Sensitive Matching", getSearchable().isCaseSensitive());
		jcbmi1.addItemListener(e -> {
			_comboBox.getEditor().getEditorComponent().requestFocus();
			getSearchable().setCaseSensitive(jcbmi1.isSelected());
			updatPlaceholderText();
			_comboBox.getEditor().getEditorComponent().transferFocusBackward();
			updateSearch();
		});
		popup.add(jcbmi1);
		if ((getVisibleButtons() & SHOW_STATUS) != 0) {
			final JMenuItem jcbmi4 = new JCheckBoxMenuItem("Display No. of Matches", getSearchable().isCountMatch());
			jcbmi4.setToolTipText("May adversely affect performance if selected");
			jcbmi4.addItemListener(e -> {
				setShowMatchCount(jcbmi4.isSelected());
				updateSearch();
			});
			popup.add(jcbmi4);
		}
		final JMenuItem jcbmi2 = new JCheckBoxMenuItem("Enable Wildcards (?*)", getSearchable().isWildcardEnabled());
		jcbmi2.setToolTipText("<HTML><b>?</b> (any character) and <b>*</b> (any string) supported");
		jcbmi2.addItemListener(e -> {
			_comboBox.getEditor().getEditorComponent().requestFocus();
			getSearchable().setWildcardEnabled(jcbmi2.isSelected());
			updatPlaceholderText();
			_comboBox.getEditor().getEditorComponent().transferFocusBackward();
			updateSearch();
		});
		popup.add(jcbmi2);
		final JMenuItem jcbmi3 = new JCheckBoxMenuItem("Loop After First/Last Hit", getSearchable().isRepeats());
		jcbmi3.addItemListener(e -> getSearchable().setRepeats(jcbmi3.isSelected()));
		jcbmi3.setToolTipText("Affects selection of previous/next hit using arrow keys");
		popup.add(jcbmi3);
		popup.addSeparator();
		if (findAndReplaceMenuItem != null) popup.add(findAndReplaceMenuItem);
		final JMenuItem mi = new JMenuItem("Clear History");
		mi.addActionListener(e -> setSearchHistory(null));
		popup.add(mi);
		popup.addSeparator();
		final JMenuItem mi2 = new JMenuItem("Tips & Shortcuts...");
		mi2.addActionListener(e -> {
			if (objectDescription == null) objectDescription = "items";
			final String key = GuiUtils.ctrlKey();
			String msg = "<HTML><body><div style='width:500;'><ol>"
					+ "<li>Press the up/down keys to find the next/previous occurrence of the filtering string</li>"
					+ "<li>Hold " + key + " while pressing the up/down keys to select multiple filtered "
					+ objectDescription + "</li>";
			if ((getVisibleButtons() & SHOW_HIGHLIGHTS) != 0) {
					msg += "<li>Press the <i>Highlight All</i> button to select all the "
					+ objectDescription + " filtered by the search string</li>";
			}
			if (isShowMatchCount()) {
					msg += "<li>Uncheck <i>Display No. of Matches</i> to improve search performance</li>";
			}
					msg += "</ol></div></html>";
			getGuiUtils().centeredMsg(msg, "Text-based Filtering");
		});
		popup.add(mi2);
		return button;
	}

	private void updateSearch() {
		final SearchableListener[] listeners = getSearchable()
			.getSearchableListeners();
		for (final SearchableListener l : listeners)
			l.searchableEventFired(new SearchableEvent(getSearchable(),
				SearchableEvent.SEARCHABLE_MODEL_CHANGE));
	}

	private void addButton(final JPanel panel, final AbstractButton button) {
		containsCheckboxes = button instanceof JCheckBox;
		panel.add(button);
		buttonCount++;
	}

	private JLabel statusLabel() {
		_statusLabel = new JLabel(statusLabelPlaceholder);
		_statusLabel.addPropertyChangeListener("text", evt -> {
			final String text = _statusLabel.getText();
			if (text == null || text.isEmpty()) _statusLabel.setText(
				statusLabelPlaceholder);
		});
		_statusLabel.setBackground(Color.CYAN);
		return _statusLabel;
	}

	public void setStatus(final String text) {
		super._statusLabel.setText(text);
	}

	@Override
	protected AbstractButton createFindPrevButton(
		final AbstractAction findPrevAction)
	{
		final AbstractButton button = super.createFindPrevButton(findPrevAction);
		formatButton(button, IconFactory.GLYPH.PREVIOUS);
		return button;
	}

	@Override
	protected AbstractButton createMatchCaseButton() {
		final AbstractButton button = super.createMatchCaseButton();
		button.setText("Aa");
		button.setMnemonic('a');
		button.setToolTipText("Match case");
		return button;
	}

	@Override
	protected AbstractButton createFindNextButton(
		final AbstractAction findNextAction)
	{
		final AbstractButton button = super.createFindNextButton(findNextAction);
		formatButton(button, IconFactory.GLYPH.NEXT);
		return button;
	}

	@Override
	protected AbstractButton createCloseButton(final AbstractAction closeAction) {
	    final AbstractButton button = new JButton();
	    button.addActionListener(closeAction);
	    //button.setBorder(BorderFactory.createEmptyBorder());
	    //button.setOpaque(false);
	    button.setRequestFocusEnabled(false);
	    button.setFocusable(false);
		formatButton(button, IconFactory.GLYPH.TIMES);
		return button;
	}

	@Override
	protected AbstractButton createHighlightButton() {
		final AbstractButton button = super.createHighlightButton();
		formatButton(button, IconFactory.GLYPH.BULB);
		return button;
	}

	protected void formatButton(final AbstractButton button, final IconFactory.GLYPH glyph) {
		if (buttonHeight == 0)
			buttonHeight = (getMaxHistoryLength() == 0) ? new JTextField().getHeight() : new JComboBox<String>().getHeight();
		if (iconHeight == 0)
			iconHeight = UIManager.getFont("Label.font").getSize();
		button.setSize(new Dimension(buttonHeight, buttonHeight));
		IconFactory.applyIcon(button, iconHeight, glyph);
		button.setRequestFocusEnabled(false);
		button.setFocusable(false);
		if (button instanceof JToggleButton) {
			Color selectionColor = UIManager.getColor("Tree.selectionBackground");
			if (selectionColor == null) selectionColor = Color.RED;
			final Icon selectIcon = IconFactory.getIcon(glyph, iconHeight, selectionColor);
			button.setSelectedIcon(selectIcon);
			button.setRolloverSelectedIcon(selectIcon);
		}
	}

	private void normalizeButtons(final Container container) {
		// Some L&Fs/JDK versions render buttons inconsistently!? Attempt to fix it
		final JToggleButton template = firstToggleButton(container);
		if (template != null) {
			for (final Component component : container.getComponents()) {
				if (component instanceof AbstractButton) {
					((AbstractButton) component).setBackground(template.getBackground());
					((AbstractButton) component).setBorder(template.getBorder());
					((AbstractButton) component).setBorderPainted(template.isBorderPainted());
					((AbstractButton) component).setContentAreaFilled(template.isContentAreaFilled());
					((AbstractButton) component).setMargin(template.getMargin());
					((AbstractButton) component).setOpaque(template.isOpaque());
				}
			}
		}
	}

	private JToggleButton firstToggleButton(final Container container) {
		for (final Component component : container.getComponents()) {
			if (component instanceof JToggleButton)
				return (JToggleButton) component;
		}
		return null;
	}
}
