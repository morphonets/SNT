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
import com.formdev.flatlaf.icons.FlatSearchIcon;
import com.formdev.flatlaf.icons.FlatSearchWithHistoryIcon;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class SearchField extends JTextField {

    public static final int OPTIONS_MENU = 0x1;
    public static final int CASE_BUTTON = 0x2;
    public static final int WORD_BUTTON = 0x4;
    public static final int REGEX_BUTTON = 0x8;
    private static Color iconColor;

    private JButton optionsButton;
    private JToggleButton caseButton;
    private JToggleButton wordButton;
    private JToggleButton regexButton;

    /**
     * Constructs a new SearchField with the specified placeholder text and visible buttons.
     *
     * @param placeholder the placeholder text to display when the field is empty
     * @param visibleButtons the buttons to make visible (use bitwise OR of button constants)
     */
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
        final JToolBar rightToolbar = new JToolBar();
        rightToolbar.setMargin(new Insets(0, 0, 0, 2));
        if ((visibleButtons & CASE_BUTTON) != 0) {
            initCaseButton();
            rightToolbar.add(caseButton);
        }
        if ((visibleButtons & WORD_BUTTON) != 0) {
            initWordButton();
            rightToolbar.add(wordButton);
        }
        if ((visibleButtons & REGEX_BUTTON) != 0) {
            initRegexButton();
            if (rightToolbar.getComponentCount() > 1) rightToolbar.addSeparator();
            rightToolbar.add(regexButton);
        }
        if (rightToolbar.getComponentCount() > 0)
            putClientProperty(FlatClientProperties.TEXT_FIELD_TRAILING_COMPONENT, rightToolbar);
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
        regexButton = new JToggleButton(" .* "); // too narrow on macOS only!?
        regexButton.setFont(regexButton.getFont().deriveFont(Font.BOLD));
        adjustButton(regexButton);
        regexButton.setToolTipText("Enable Wildcards");
    }

    private void adjustButton(final AbstractButton button) {
        button.setFont(button.getFont().deriveFont((float) (button.getFont().getSize() * .85)));
        button.setForeground(iconColor());
        //button.setFont(button.getFont().deriveFont(Font.BOLD));
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

    public void enlarge(final float enlargeFactor) {
        setFont(getFont().deriveFont(getFont().getSize() * enlargeFactor));
        List.of(optionsButton, caseButton, regexButton).forEach(c -> c.setFont(c.getFont().deriveFont(c.getFont().getSize() * enlargeFactor)));
        final int PADDING = (int) (getFontMetrics(getFont()).getHeight() / 2f);
        //setMargin(new Insets(PADDING, PADDING, PADDING, PADDING));
        setBorder(BorderFactory.createEmptyBorder(PADDING, PADDING, PADDING, PADDING));
    }

    public static Color iconColor() {
         if (iconColor == null) {
             iconColor = UIManager.getColor("SearchField.searchIconColor");
             if (iconColor == null) {
                 iconColor = Color.DARK_GRAY;
             } else {
                 // somehow transparency is not being set(!?), so we'll get it here
                 iconColor = new Color(iconColor.getRed(), iconColor.getGreen(), iconColor.getBlue(), iconColor.getAlpha());
             }
         }
        return iconColor;
    }

}
