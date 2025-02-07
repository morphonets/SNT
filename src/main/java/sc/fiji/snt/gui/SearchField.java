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

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.icons.FlatSearchIcon;
import com.formdev.flatlaf.icons.FlatSearchWithHistoryIcon;

import javax.swing.*;
import java.awt.*;

public class SearchField extends JTextField {

    public static final int OPTIONS_MENU = 0x1;
    public static final int CASE_BUTTON = 0x2;
    public static final int WORD_BUTTON = 0x4;
    public static final int REGEX_BUTTON = 0x8;
    private static final Color COLOR = defColor();

    private JButton optionsButton;
    private JToggleButton caseButton;
    private JToggleButton wordButton;
    private JToggleButton regexButton;

    public SearchField(final String placeholder, final int visibleButtons) {
        super();
        if (placeholder != null)
            putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
        putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        if ((visibleButtons & OPTIONS_MENU) != 0) {
            initOptionsButton();
        } else {
            putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_COMPONENT, new JButton(new FlatSearchIcon(true)));
        }
        final JToolBar buttons = new JToolBar();
        if ((visibleButtons & CASE_BUTTON) != 0) {
            initCaseButton();
            buttons.add(caseButton);
        }
        if ((visibleButtons & WORD_BUTTON) != 0) {
            initWordButton();
            buttons.add(wordButton);
        }
        if ((visibleButtons & REGEX_BUTTON) != 0) {
            initRegexButton();
            buttons.addSeparator();
            buttons.add(regexButton);
        }
        if (buttons.getComponentCount() > 0)
            putClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, buttons);
    }

    public void enlarge() {
        final int PADDING = 4;
        setMargin(new Insets((int) (PADDING * 1.5), PADDING, (int) (PADDING * 1.5), PADDING));
        setFont(getFont().deriveFont(getFont().getSize() * 1.1f));
    }

    public void setWarningOutlineEnabled(final boolean b) {
        putClientProperty(FlatClientProperties.OUTLINE, (b) ? FlatClientProperties.OUTLINE_WARNING : null);
    }

    private void initOptionsButton() {
        optionsButton = new JButton(new FlatSearchWithHistoryIcon(true));
        putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_COMPONENT, optionsButton);
    }

    private void initCaseButton() {
        caseButton = new JToggleButton("Cc");
        adjustButton(caseButton);
        caseButton.setToolTipText("Match Case");
    }

    private void initWordButton() {
        wordButton = new JToggleButton("W");
        adjustButton(wordButton);
        wordButton.setToolTipText("Match Words");
    }

    private void initRegexButton() {
        regexButton = new JToggleButton(" .* ");
        adjustButton(regexButton);
        regexButton.setToolTipText("Enable Wildcards");
    }

    private void adjustButton(final AbstractButton button) {
        button.setFont(button.getFont().deriveFont((float) (button.getFont().getSize() * .85)));
        button.setForeground(COLOR);
        //button.setFont(button.getFont().deriveFont(Font.BOLD));
    }

    private static Color defColor() {
        Color c  = UIManager.getColor("SearchField.searchIconColor");
        if (c == null)  c = Color.DARK_GRAY;
        return c;
    }

    public JPopupMenu getOptionsMenu() {
        if (optionsButton == null)
            throw new IllegalArgumentException("OPTIONS_MENU flag not specified in SearchField constructor.");
        final JPopupMenu popupMenu = new JPopupMenu();
        optionsButton.addActionListener(e -> popupMenu.show(optionsButton, 0, optionsButton.getHeight()));
        return popupMenu;
    }

    public JButton optionsButton() {
        return optionsButton;
    }

    public JToggleButton caseButton() {
        return caseButton;
    }

    public JToggleButton wordButton() {
        return wordButton;
    }

    public JToggleButton regexButton() {
        return regexButton;
    }
}
