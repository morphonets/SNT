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

import org.scijava.Context;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.script.ScriptLanguage;
import org.scijava.script.ScriptREPL;
import org.scijava.ui.swing.script.DefaultAutoImporters;
import org.scijava.ui.swing.script.OutputPane;
import org.scijava.ui.swing.script.VarsPane;
import org.scijava.widget.UIComponent;
import sc.fiji.snt.util.SNTColor;

import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;

/**
 * SNT's interpreter UI pane. A targeted fork of
 * {@code org.scijava.ui.swing.script.InterpreterPane} with a single change:
 * the vanilla {@code PromptPane} is replaced by {@link SNTPromptPane}, giving
 * the input area Groovy syntax highlighting via {@link SNTEditorPane}. The
 * corresponding scroll pane is sourced from {@link SNTPromptPane#getScrollPane()}
 * to preserve RSyntaxTextArea-specific rendering rather than wrapping in a
 * plain {@link JScrollPane}.
 *
 * @author Tiago Ferreira
 * @see SNTPromptPane
 * @see SNTREPL
 */
public class SNTInterpreterPane implements UIComponent<JComponent> {

    private final ScriptREPL repl;
    private final JSplitPane mainPane;
    private final OutputPane output;
    private final SNTPromptPane prompt;

    @Parameter(required = false)
    private LogService log;

    public SNTInterpreterPane(final Context context, final String languagePreference) {
        context.inject(this);
        output = new OutputPane(log);
        output.setRows(26); // tall/wide enough for welcome message
        output.setColumns(50);
        final JScrollPane outputScroll = new JScrollPane(output);
        repl = new ScriptREPL(context, languagePreference, output.getOutputStream());
        repl.initialize();

        final Writer writer = output.getOutputWriter();
        final ScriptContext ctx = repl.getInterpreter().getEngine().getContext();
        ctx.setErrorWriter(writer);
        ctx.setWriter(writer);

        final VarsPane vars = new VarsPane(context, repl);
        prompt = new SNTPromptPane(repl, vars, output) {
            @Override
            public void quit() {
                dispose();
            }
        };
        hookLanguageSwitch(vars, getPromptEditor());

        final JPanel bottomPane = new JPanel(new BorderLayout());
        // Use the RTextScrollPane from SNTPromptPane rather than wrapping in a
        // plain JScrollPane, so RSyntaxTextArea rendering is handled correctly.
        bottomPane.add(prompt.getScrollPane(), BorderLayout.CENTER);

        final Object importGenerator = DefaultAutoImporters.getImportGenerator(
                log.getContext(), repl.getInterpreter().getLanguage());
        if (importGenerator != null) {
            final JButton autoImportButton = new JButton("Auto-Import");
            autoImportButton.setToolTipText("Auto-imports common classes.");
            autoImportButton.addActionListener(e -> {
                try {
                    repl.getInterpreter().getEngine().eval(importGenerator.toString());
                } catch (final ScriptException e1) {
                    e1.printStackTrace(new PrintWriter(output.getOutputWriter()));
                }
                autoImportButton.setEnabled(false);
                prompt.getComponent().requestFocus();
            });
            final JPanel buttonPane = new JPanel(new BorderLayout());
            buttonPane.add(autoImportButton, BorderLayout.PAGE_START);
            bottomPane.add(buttonPane, BorderLayout.EAST);
        }

        final JSplitPane outputAndPromptPane =
                new JSplitPane(JSplitPane.VERTICAL_SPLIT, outputScroll, bottomPane);
        outputAndPromptPane.setResizeWeight(1);

        mainPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, vars, outputAndPromptPane);
        mainPane.setDividerLocation(300);
        syncOutputBackground();
    }

    private void syncOutputBackground() {
        output.setBackground(prompt.getComponent().getBackground());
        output.setForeground(SNTColor.contrastColor(prompt.getComponent().getBackground()));
        output.setFont(getPromptEditor().getFont());
    }

    private static void hookLanguageSwitch(final VarsPane vars, final SNTEditorPane prompt) {
        for (final Component c : vars.getComponents()) {
            if (c instanceof JComboBox<?> box) {
                box.addActionListener(e -> {
                    final Object selected = box.getSelectedItem();
                    if (selected instanceof ScriptLanguage lang) {
                        prompt.setSyntaxStyle(lang.getLanguageName());
                    }
                });
                return;
            }
        }
    }

    /** Gets the associated prompt pane. */
    public SNTPromptPane getPrompt() {
        return prompt;
    }

    /** Gets the associated prompt pane. */
    public SNTEditorPane getPromptEditor() {
        return (SNTEditorPane) prompt.getComponent();
    }

    /** Gets the associated output pane. */
    public OutputPane getOutput() {
        return output;
    }

    /** Gets the associated script REPL. */
    public ScriptREPL getREPL() {
        return repl;
    }

    /** Prints a message to the output panel. */
    public void print(final String string) {
        final Writer writer = output.getOutputWriter();
        try {
            writer.write(string + "\n");
        } catch (final IOException e) {
            e.printStackTrace(new PrintWriter(writer));
        }
    }

    public void dispose() {
        output.close();
    }

    @Override
    public JComponent getComponent() {
        return mainPane;
    }

    @Override
    public Class<JComponent> getComponentType() {
        return JComponent.class;
    }
}
