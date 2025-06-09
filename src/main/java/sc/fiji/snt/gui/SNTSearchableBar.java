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

import com.jidesoft.swing.Searchable;
import com.jidesoft.swing.SearchableBar;
import com.jidesoft.swing.SearchableBarIconsFactory;
import com.jidesoft.swing.WholeWordsSupport;
import com.jidesoft.swing.event.SearchableEvent;
import com.jidesoft.swing.event.SearchableListener;
import org.scijava.util.PlatformUtils;
import sc.fiji.snt.SNTUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Implements a SearchableBar following SNT's UI.
 *
 * @author Tiago Ferreira
 */
public class SNTSearchableBar extends SearchableBar {

	@Serial
	private static final long serialVersionUID = 1L;
	private static final int DELAY_MS = 100;
	protected List<AbstractButton> _extraButtons;

	private String statusLabelPlaceholder;
	private String objectDescription;
	private GuiUtils guiUtils;
	private JPopupMenu optionsMenu;
	private JMenu historyMenu;
	private Icon subFilteringStatusIcon;
	private final List<String> builtinSearchHistory;
	protected boolean subFilteringEnabled;

	public SNTSearchableBar(final Searchable searchable) {
		this(searchable, "Search");
	}

	public SNTSearchableBar(final Searchable searchable, final String placeholder) {
		super(searchable, true);
		builtinSearchHistory = new ArrayList<>(10);
		getSearchable().setCaseSensitive(false);
		getSearchable().setWildcardEnabled(false);
		getSearchable().setFromStart(false);
		getSearchable().setRepeats(true);
		getSearchable().setSearchingDelay(DELAY_MS);
		setShowMatchCount(true); // false improves performance
		setMismatchForeground(GuiUtils.warningColor());
		setMaxHistoryLength(0); // disable default history. We'll use builtinSearchHistory
		setHighlightAll(true);
		init(placeholder); // should be the last call in the constructor
	}

	@Override
	public int getMaxHistoryLength() {
		return 0; // disabled. We'll use builtinSearchHistory
	}

	private void init(final String placeholder) {
		_textField = getModifiedTextField(placeholder);
		_textField.setVisible(true);
		if (_findNextButton != null)
			_textField.registerKeyboardAction(_findNextButton.getActionListeners()[0], KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), JComponent.WHEN_FOCUSED);
		if (_findPrevButton != null)
			_textField.registerKeyboardAction(_findPrevButton.getActionListeners()[0], KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), JComponent.WHEN_FOCUSED);

		setStatusLabelPlaceholder((placeholder==null)?SNTUtils.getReadableVersion():placeholder);
	}

	public SearchField getSearchField() {
		return (SearchField)_textField;
	}

	private void updateHistoryMenu() {
		if (builtinSearchHistory == null || builtinSearchHistory.isEmpty()) {
			return;
		}
		historyMenu.removeAll();
		historyMenu.setEnabled(true);
		historyMenu.setToolTipText(null);
		for (int i = builtinSearchHistory.size() - 1; i >= 0; i--) {
			final String h = builtinSearchHistory.get(i);
			final JMenuItem mi = new JMenuItem(h);
			mi.addActionListener(e -> {
				getSearchField().setText(h);
				searchOnDemandAsNeeded();
			});
			historyMenu.add(mi);
		}
		historyMenu.addSeparator();
		final JMenuItem mi = new JMenuItem("Clear History");
		mi.addActionListener(e -> {
			builtinSearchHistory.clear();
			historyMenu.removeAll();
			historyMenu.setEnabled(false);
		});
		historyMenu.add(mi);
	}

	JToggleButton createSubFilteringButton() {
		final JToggleButton button = new JToggleButton();
		formatButton(button, IconFactory.GLYPH.FILTER);
		button.setToolTipText("Restricts filtering to selected " + objectDescription +".\nCombines filters to restrict matches");
		button.setRequestFocusEnabled(false);
		button.setFocusable(false);
		button.addActionListener(e -> {
			setSubFilteringEnabled(button.isSelected());
			setStatusLabelPlaceholder(statusLabelPlaceholder); // update label
		});
		getSearchField().getDocument().addDocumentListener(new DocumentListener() {
					@Override
					public void changedUpdate(final DocumentEvent e) {
						disable();
					}
					@Override
					public void removeUpdate(final DocumentEvent e) {
						disable();
					}
					@Override
					public void insertUpdate(final DocumentEvent e) {
						disable();
					}
					void disable() { // text searches disable subFiltering
						setSubFilteringEnabled(false);
						button.setSelected(false);
						button.setEnabled(getSearchingText().isBlank());
					}
				});
		return button;
	}

	public void setStatusLabelPlaceholder(final String placeholder) {
		statusLabelPlaceholder = placeholder;
		if (_statusLabel != null) {
			_statusLabel.setText(statusLabelPlaceholder);
			_statusLabel.setIcon((subFilteringEnabled) ? getSubFilteringStatusIcon() : null);
		}
	}

	private Icon getSubFilteringStatusIcon() {
		if (subFilteringStatusIcon == null) {
            getSearchField();
            subFilteringStatusIcon = IconFactory.get(IconFactory.GLYPH.FILTER, _statusLabel.getFont().getSize(),
					SearchField.iconColor()); // SearchField is initialized by the time this is called
		}
		return subFilteringStatusIcon;
	}

	@Override
	protected void installComponents() {

		final JToolBar tb = new JToolBar();
		//tb.setFloatable(true);
		final GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.anchor = GridBagConstraints.WEST;

		// close button (if any)
		if ((getVisibleButtons() & SHOW_CLOSE) != 0) {
			tb.add(_closeButton, gbc);
			gbc.gridx++;
		}
		// search field
		gbc.weightx = 1.0;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		tb.add(_textField, gbc);
		gbc.gridx++;
		gbc.weightx = 0;
		gbc.fill = GridBagConstraints.NONE;

		// buttons
		if ((getVisibleButtons() & SHOW_NAVIGATION) != 0) {
			tb.add(_findNextButton, gbc);
			gbc.gridx++;
			tb.add(_findPrevButton, gbc);
			gbc.gridx++;
		}
		if ((getVisibleButtons() & SHOW_HIGHLIGHTS) != 0) {
			tb.add(_highlightsButton, gbc);
			gbc.gridx++;
		}
		if ((getVisibleButtons() & SHOW_REPEATS) != 0) {
			tb.add(_repeatCheckBox, gbc);
			gbc.gridx++;
		}
		if (_extraButtons != null) {
			tb.add(new JToolBar.Separator(), gbc);
			gbc.gridx++;
			_extraButtons.forEach(b -> {
				tb.add(b, gbc);
				gbc.gridx++;
			});
		}

		setLayout(new BorderLayout());
		setBorder(null);
		add(tb, BorderLayout.NORTH);
		// status label
		if ((getVisibleButtons() & SHOW_STATUS) != 0) {
			add(statusLabel(), BorderLayout.CENTER);
		}
	}

	private void addSearchingTextToHistory(String searchingText) {
		if (searchingText == null || searchingText.isEmpty()) {
			return;
		}
		if (builtinSearchHistory.isEmpty()) {
			builtinSearchHistory.add(searchingText);
			return;
		}
		if (searchingText.equals(builtinSearchHistory.getLast())) {
			return;
		}
		builtinSearchHistory.add(searchingText);
		if (builtinSearchHistory.size() > 10) {
			builtinSearchHistory.removeFirst();
		}
	}

	private void searchOnDemandAsNeeded() {
		if (getSearchable().getSearchingDelay()==-1) {
			updateSearch();
		}
	}

	private SearchField getModifiedTextField(final String placeholder) {
		final boolean wholeWordsSupport = getSearchable() instanceof WholeWordsSupport;
		int options = SearchField.OPTIONS_MENU + SearchField.CASE_BUTTON + SearchField.REGEX_BUTTON;
		if (wholeWordsSupport) options += SearchField.WORD_BUTTON;
		final SearchField sf = new SearchField(placeholder, options);
		final Color optionsButtonOriginalColor = sf.optionsButton().getBackground();
		final Timer blinkingTimer = new Timer(200, evt -> sf.optionsButton().setBackground(optionsButtonOriginalColor));
		blinkingTimer.setRepeats(false);
		sf.addActionListener(e -> {  // triggered by pressing Enter
			if (sf.getText().isBlank()) {
				updateSearch();
				return;
			}
			searchOnDemandAsNeeded();
			addSearchingTextToHistory(sf.getText());
			if (!blinkingTimer.isRunning()) {
				sf.optionsButton().setBackground(GuiUtils.getSelectionColor());
				blinkingTimer.start();
			}
		});
		sf.enlarge(PlatformUtils.isMac() ? 1.05f : 1.1f);
		// assign search functionalities of original text field
		sf.setAction(_textField.getAction());
		sf.setDocument(_textField.getDocument());
		// case button
		sf.caseButton().setSelected(getSearchable().isCaseSensitive());
		sf.caseButton().addItemListener(e -> {
			getSearchable().setCaseSensitive(sf.caseButton().isSelected());
			updateSearch();
		});
		// word button
		if (wholeWordsSupport) {
			sf.wordButton().setSelected(((WholeWordsSupport) getSearchable()).isWholeWords());
			sf.wordButton().addItemListener(e -> {
				((WholeWordsSupport) getSearchable()).setWholeWords(sf.wordButton().isSelected());
				updateSearch();
			});
		}
		// regex button
		sf.regexButton().setSelected(getSearchable().isWildcardEnabled());
		sf.regexButton().setToolTipText("<HTML>Enable Wildcards<br>" +
				"<b>?</b> (any character) and <b>*</b> (any string) supported");
		sf.regexButton().addItemListener(e -> {
			getSearchable().setWildcardEnabled(sf.regexButton().isSelected());
			updateSearch();
		});
		// options button
		optionsMenu = createOptionsMenu();
		sf.optionsButton().addActionListener(e -> {
			updateHistoryMenu();
			optionsMenu.show(sf.optionsButton(), 0, sf.optionsButton().getHeight());
		});
		return sf;
	}

	private JPopupMenu createOptionsMenu() {
		final JPopupMenu popup = new JPopupMenu();
		historyMenu = new JMenu("Search History");
		historyMenu.setEnabled(false); // will be enabled once history is updated
		historyMenu.setToolTipText("Press enter on a typed term to store it in this menu");
		popup.add(historyMenu);
		popup.addSeparator();
		if ((getVisibleButtons() & SHOW_STATUS) != 0) {
			final JMenuItem jcbmi = new JCheckBoxMenuItem("Display No. of Matches", getSearchable().isCountMatch());
			jcbmi.setToolTipText("May adversely affect performance if selected");
			jcbmi.addItemListener(e -> {
				setShowMatchCount(jcbmi.isSelected());
				updateSearch();
			});
			popup.add(jcbmi);
		}
		final JMenuItem jcbmi3 = new JCheckBoxMenuItem("Loop After First/Last Hit", getSearchable().isRepeats());
		jcbmi3.addItemListener(e -> getSearchable().setRepeats(jcbmi3.isSelected()));
		jcbmi3.setToolTipText("Return to first hit after last is selected?");
		popup.add(jcbmi3);
		final JMenuItem jcbmi4 = new JCheckBoxMenuItem("Non-interactive Search", getSearchable().getSearchingDelay()==-1);
		jcbmi4.addItemListener(e -> getSearchable().setSearchingDelay((jcbmi4.isSelected())?-1:DELAY_MS));
		jcbmi4.setToolTipText("Search only after Enter is pressed");
		popup.add(jcbmi4);
		popup.addSeparator();
		popup.add(getTipsAndShortcutsMenuItem());
		return popup;
	}

	protected void setSearchableObjectDescription(final String objectDescription) {
		this.objectDescription = objectDescription;
	}

	public void setGuiUtils(final GuiUtils guiUtils) {
		this.guiUtils = guiUtils;
	}

	private GuiUtils getGuiUtils() {
		if (guiUtils == null) guiUtils = new GuiUtils(getRootPane());
		return guiUtils;
	}

	protected void appendToOptionsMenu(final Collection<JMenuItem> menuItems) {
		if (menuItems != null && !menuItems.isEmpty()) {
			optionsMenu.remove(optionsMenu.getComponentCount() - 1); // remove tipsAndShortcutsMenuItem
			menuItems.forEach(optionsMenu::add);
			optionsMenu.addSeparator();
			optionsMenu.add(getTipsAndShortcutsMenuItem()); // re-add tipsAndShortcutsMenuItem
		}
	}

	private JMenuItem getTipsAndShortcutsMenuItem() {
		final JMenuItem mi2 = new JMenuItem("Search Tips...");
		mi2.addActionListener(e -> {
			if (objectDescription == null) objectDescription = "items";
			String msg = "<HTML><body><div><ul>"
					+ "<li>Press the ↓/↑ keys to find the next/previous occurrence of the search term</li>"
					+ "<li>Press Enter to store terms in the <i>Search History</i> menu</li>";
			if ((getVisibleButtons() & SHOW_HIGHLIGHTS) != 0) {
				msg += "<li>Press the <i>Highlight All</i> button to select all the "
						+ objectDescription + " filtered by the search term</li>";
			}
			msg += "<li>To improve search performance:</li><ul>" +
					"<li>Disable <i>Display No. of Matches</i></li>" +
					"<li>Enable <i>Non-interactive Search</i> (search starts only after pressing Enter)</li></ul>" +
					"</ul></div></html>";
			getGuiUtils().centeredMsg(msg, "Text-based Filtering");
		});
		return mi2;
	}

	private void updateSearch() {
		for (final SearchableListener l : getSearchable().getSearchableListeners())
			l.searchableEventFired(new SearchableEvent(getSearchable(), SearchableEvent.SEARCHABLE_MODEL_CHANGE));
	}

	private JLabel statusLabel() {
		_statusLabel = new JLabel(statusLabelPlaceholder);
		_statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 0));
		_statusLabel.addPropertyChangeListener("text", evt -> {
			final String text = _statusLabel.getText();
			if (text == null || text.isEmpty()) _statusLabel.setText(statusLabelPlaceholder);
		});
		return _statusLabel;
	}

	public void setSubFilteringEnabled(final boolean enable) {
		this.subFilteringEnabled = enable;
	}

	public void setStatus(final String text) {
		super._statusLabel.setText(text);
	}

	public void setBorderless() {
		GuiUtils.Buttons.makeBorderless(_closeButton, _findPrevButton, _findNextButton, _highlightsButton);
		if (_extraButtons != null)
			GuiUtils.Buttons.makeBorderless(_extraButtons.toArray(new AbstractButton[0]));
	}

	@Override
	protected AbstractButton createFindPrevButton(final AbstractAction findPrevAction) {
		final AbstractButton button = super.createFindPrevButton(findPrevAction);
		formatButton(button, IconFactory.GLYPH.PREVIOUS);
		button.setToolTipText("Find the previous hit (or press ↑ in search field)");
		return button;
	}

	@Override
	protected AbstractButton createFindNextButton(final AbstractAction findNextAction) {
		final AbstractButton button = super.createFindNextButton(findNextAction);
		formatButton(button, IconFactory.GLYPH.NEXT);
		button.setToolTipText("Find the next hit (or press ↓ in search field)");
		return button;
	}

	@Override
	protected ImageIcon getImageIcon(final String name) {
		if (_statusLabel == null)
			return super.getImageIcon(name);
        return switch (name) {
            case SearchableBarIconsFactory.Buttons.REPEAT ->
                    IconFactory.getAsImage(IconFactory.GLYPH.REPEAT, _statusLabel.getFont().getSize2D(),
                            _statusLabel.getForeground());
            case SearchableBarIconsFactory.Buttons.ERROR ->
                    IconFactory.getAsImage(IconFactory.GLYPH.DANGER, _statusLabel.getFont().getSize2D(),
                            _statusLabel.getForeground());
            default -> SearchableBarIconsFactory.getImageIcon(name);
        };
    }

	@Override
	protected AbstractButton createCloseButton(final AbstractAction closeAction) {
		final AbstractButton button = new JButton();
		button.addActionListener(closeAction);
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
		// wipe all jide customizations
		button.setText(null);
		button.setDisabledIcon(null);
		button.setIcon(null);
		button.setPressedIcon(null);
		button.setRolloverIcon(null);
		button.setRolloverSelectedIcon(null);
		button.setSelectedIcon(null);
		IconFactory.assignIcon(button, glyph, 1.2f);
		GuiUtils.Buttons.makeBorderless(button);
		button.setRequestFocusEnabled(false);
		button.setFocusable(false);
	}
}
