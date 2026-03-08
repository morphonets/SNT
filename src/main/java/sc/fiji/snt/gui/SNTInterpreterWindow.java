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
import org.scijava.script.ScriptInterpreter;

import javax.swing.*;

/**
 * SNT's REPL window. A targeted fork of
 * {@code org.scijava.ui.swing.script.InterpreterWindow} with a single change:
 * the {@code InterpreterPane} field is replaced by {@link SNTInterpreterPane}
 * to wire in the syntax-highlighted {@link SNTPromptPane}.
 * <p>
 * NOTE: if the upstream {@code InterpreterWindow} source changes (e.g. new
 * fields or methods are added), this fork must be updated accordingly.
 *
 * @author Tiago Ferreira
 * @see SNTInterpreterPane
 * @see SNTREPL
 */
public class SNTInterpreterWindow extends JFrame {

    private final SNTInterpreterPane pane;

    public SNTInterpreterWindow(final Context context, final String languagePreference) {
        super("SNT Scripting REPL");
        pane = new SNTInterpreterPane(context, languagePreference);
        setContentPane(pane.getComponent());
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        pack();
    }

    public SNTInterpreterPane getInterpreterPane() {
        return pane;
    }

    /** Returns the underlying {@link ScriptInterpreter} for direct evaluation. */
    public ScriptInterpreter getInterpreter() {
        return pane.getREPL().getInterpreter();
    }

    /** Prints a message to the output panel. */
    public void print(final String string) {
        pane.print(string);
    }
}
