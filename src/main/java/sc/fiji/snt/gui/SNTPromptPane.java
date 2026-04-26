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

import org.fife.ui.rtextarea.RTextScrollPane;
import org.scijava.script.ScriptREPL;
import org.scijava.thread.ThreadService;
import org.scijava.ui.swing.script.OutputPane;
import org.scijava.ui.swing.script.VarsPane;
import org.scijava.widget.UIComponent;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.util.SNTColor;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

import static java.awt.event.KeyEvent.*;
import static sc.fiji.snt.SNTUtils.isDebugMode;

/**
 * Syntax-highlighted prompt pane for the SNT scripting REPL. Replaces the
 * vanilla {@link JTextArea} used by the upstream SciJava {@code PromptPane}
 * with an {@link SNTEditorPane} ({@code RSyntaxTextArea}) so that input
 * benefits from the same highlighting used elsewhere in SNT.
 * <p>
 * This is a targeted fork of {@code org.scijava.ui.swing.script.PromptPane}.
 * The only structural changes are:
 * <ol>
 *   <li>The inner {@code TextArea extends JTextArea} class is replaced by
 *       {@link SNTEditorPane} ({@code RSyntaxTextArea} already exposes
 *       {@code getRowHeight()} publicly, so the subclass is unnecessary).</li>
 *   <li>A {@link RTextScrollPane} wraps the editor rather than a plain
 *       {@link JScrollPane}, enabling RSyntaxTextArea-specific rendering.</li>
 * </ol>
 *
 * @author Tiago Ferreira
 * @see SNTInterpreterPane
 */
public abstract class SNTPromptPane implements UIComponent<JTextArea> {

    private final ScriptREPL repl;
    private final VarsPane vars;
    private final SNTEditorPane textArea;
    private final OutputPane output;

    private boolean executing;
    private boolean replDebugMode = false;

    public SNTPromptPane(final ScriptREPL repl, final VarsPane vars, final OutputPane output) {
        textArea = new SNTEditorPane(false);
        adjustTextArea();
        this.repl = repl;
        this.vars = vars;
        this.output = output;
        textArea.addKeyListener(new KeyAdapter() {

            @Override
            public void keyPressed(final KeyEvent event) {
                final int code = event.getKeyCode();
                switch (code) {
                    case VK_ENTER:
                        if (executing) {
                            event.consume();
                            return;
                        }
                        if (event.isShiftDown()) {
                            // multi-line input
                            textArea.insert("\n", textArea.getCaretPosition());
                        } else {
                            execute();
                            event.consume();
                        }
                        break;
                    case VK_UP:
                        if (isOnFirstLine()) {
                            up();
                            event.consume();
                        }
                        break;
                    case VK_DOWN:
                        if (isOnLastLine()) {
                            down();
                            event.consume();
                        }
                        break;
                }
            }
        });
    }

    private boolean isOnFirstLine() {
        final int firstNewline = textArea.getText().indexOf('\n');
        return firstNewline == -1 || textArea.getCaretPosition() <= firstNewline;
    }

    private boolean isOnLastLine() {
        final int lastNewline = textArea.getText().lastIndexOf('\n');
        return lastNewline == -1 || textArea.getCaretPosition() > lastNewline;
    }

    private void adjustTextArea() {
        textArea.setRows(3);
        textArea.setFractionalFontMetricsEnabled(true);
        textArea.setAutoIndentEnabled(true);
        textArea.setLineWrap(true);
        textArea.setAnimateBracketMatching(true);
        textArea.setPaintMatchedBracketPair(true);
        textArea.setTabsEmulated(true);
        textArea.setTabSize(4);
    }

    /**
     * A callback method which is invoked when the REPL quits.
     */
    public abstract void quit();

    /**
     * Returns the {@link RTextScrollPane} wrapping this prompt. Used by
     * {@link SNTInterpreterPane} instead of creating a new {@code JScrollPane},
     * so that RSyntaxTextArea-specific rendering (e.g. gutter) is preserved.
     */
    public RTextScrollPane getScrollPane() {
        return textArea.getScrollPane();
    }

    @Override
    public JTextArea getComponent() {
        return textArea; // safe cast: RSyntaxTextArea extends JTextArea
    }

    @Override
    public Class<JTextArea> getComponentType() {
        return JTextArea.class;
    }


    private void up() {
        walk(false);
    }

    private void down() {
        walk(true);
    }

    private void walk(final boolean forward) {
        textArea.setText(repl.getInterpreter().walkHistory(textArea.getText(), forward));
    }

    void help() {
        output.append("""
        Session variables (bound to the active SNT instance):
          instance  →  SNT                 (core plugin)
          pafm      →  PathAndFillManager  (paths and fills)
          ui        →  SNTUI               (user interface)
          snt       →  SNTService          (SNT's scijava service)
        
        Built-in functions (evaluated as Groovy):
          api(obj, <'keyword'>)  Lists methods of an object
        
        Commands:
          :clear                 Clears this output pane
          :debug                 Toggles full stack traces
          :help                  Displays this message
          :lang <name>           Switches the active language
          :langs                 Lists available languages
          :quit                  Exits the REPL
          :theme                 Toggles light/dark theme
          :vars                  Lists current variables
        
        Utilities:
          ↑ / ↓                  Walk command history
          Shift+Enter            Insert new line without evaluating
        
        """);
    }

    private void execute() {
        final String text = textArea.getText().trim();
        textArea.setText("");
        if (":clear".equalsIgnoreCase(text)) {
            output.setText("");
            return;
        }
        if (":help".equalsIgnoreCase(text)) {
            help();
            return;
        }
        if (":theme".equalsIgnoreCase(text)) {
            textArea.toggleTheme();
            output.setBackground(textArea.getBackground());
            output.setForeground(SNTColor.contrastColor(textArea.getBackground()));
            return;
        }
        if (":debug".equalsIgnoreCase(text)) {
            replDebugMode = !replDebugMode;
            if (SNTUtils.getInstance() != null && SNTUtils.getInstance().getUI() != null)
                SNTUtils.getInstance().getUI().setEnableDebugMode(replDebugMode); // handle SNTUI checkbox
            else
                SNTUtils.setDebugMode(replDebugMode);
            // don't return: "let SciJava handle its own :debug toggle too
        }
        output.append(">>> " + text + "\n");
        executing = true;
        threadService().run(() -> {
            final boolean result = repl.evaluate(text);
            threadService().queue(() -> {
                executing = false;
                if (!result) quit();
                vars.update();
            });
        });
    }

    private ThreadService threadService() {
        return SNTUtils.getContext().service(ThreadService.class);
    }

}
