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


import com.formdev.flatlaf.FlatLaf;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.scijava.ui.swing.script.EditorPane;
import sc.fiji.snt.util.SNTColor;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.ActionListener;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Implements minor customizations to {@link EditorPane} for usage by SNT.
 */
public class SNTEditorPane extends EditorPane {

    private final RTextScrollPane scrollPane;

    public SNTEditorPane(final boolean enableCodingOptions) {
        setFont(getFont().deriveFont(GuiUtils.uiFontSize()));
        setFractionalFontMetricsEnabled(true);
        setMarginLineEnabled(false);
        setAutoIndentEnabled(true);
        setMarkOccurrences(true);
        setLineWrap(false);
        scrollPane = new RTextScrollPane(this);
        scrollPane.setAutoscrolls(true);
//        scrollPane.setIconRowHeaderEnabled(false);
//        scrollPane.getGutter().setIconRowHeaderInheritsGutterBackground(true);
        setCodeFoldingEnabled(enableCodingOptions);
        setHighlightCurrentLine(enableCodingOptions);
        scrollPane.setFoldIndicatorEnabled(enableCodingOptions);
        scrollPane.setLineNumbersEnabled(enableCodingOptions);
        updateUI();
    }

    public RTextScrollPane getScrollPane() {
        return scrollPane;
    }

    public void appendTimeStamp(final String prefix, final String suffix) {
        try {
            final String lastCharAsString = getDocument().getText(getDocument().getLength() - 1, 1);
            if (!lastCharAsString.isBlank()) append(" ");
        } catch (BadLocationException ignored) {
            ///
        }
        if (prefix != null) append(prefix);
        append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEE dd MMM yyyy, HH:mm:ss")));
        if (suffix != null) append(suffix);
        setCaretPosition(getDocument().getLength());
    }

    public void toggleDarkLightTheme() {
        if (Color.WHITE.equals(SNTColor.contrastColor(getBackground()))) { // the current theme is dark
            applyTheme("default");
        } else {
            applyTheme("dark");
        }
    }

    private Theme getTheme(final String theme) throws IllegalArgumentException {
        try {
            return Theme.load(SNTEditorPane.class.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/" + theme + ".xml"));
        } catch (final Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    public AbstractButton lightDarkToggleButton() {
        final Icon[] icons = {IconFactory.buttonIcon('\uf186', true, IconFactory.defaultColor()), IconFactory.buttonIcon('\uf185', true, IconFactory.defaultColor())};
        final JButton lightDark = new JButton(icons[0]);
        lightDark.setToolTipText("Toggle light/dark theme");
        lightDark.addActionListener(e -> {
            lightDark.setIcon((lightDark.getIcon() == icons[0]) ? icons[1] : icons[0]);
            toggleDarkLightTheme();
        });
        return lightDark;
    }

    public AbstractButton timeStampButton(final ActionListener al) {
        final JButton time = new JButton(IconFactory.buttonIcon('\uf2f2', true, IconFactory.defaultColor()));
        time.setToolTipText("Insert timestamp");
        time.addActionListener(al);
        return time;
    }

    public AbstractButton optionsButton(final JPopupMenu optionsMenu) {
        final JButton options = new JButton(IconFactory.buttonIcon('\uf7d9', true, IconFactory.defaultColor()));
        options.setToolTipText("Options");
        options.addActionListener(e -> optionsMenu.show(options, options.getWidth() / 2, options.getHeight() / 2));
        return options;
    }

    @Override
    public void applyTheme(final String theme) throws IllegalArgumentException {
        try {
            final Theme th = getTheme(theme);
            th.apply(this);
            GuiUtils.recolorTracks(scrollPane, getBackground());
        } catch (final Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    @Override
    public void updateUI() {
        try {
            applyTheme((FlatLaf.isLafDark()) ? "dark" : "default");
        } catch (final IllegalArgumentException ignored) {
            // do nothing
        }
    }

}