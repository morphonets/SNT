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
import sc.fiji.snt.SNTUtils;

import javax.script.ScriptException;

/**
 * SNT's Groovy REPL.
 *
 * @author Tiago Ferreira
 */
public class SNTREPL extends SNTInterpreterWindow {

    public SNTREPL(final Context context) {
        super(context, ".groovy");
        setTitle("SNT Scripting REPL");
        init();
        pack();
        getInterpreterPane().getPromptEditor().setSyntaxStyle("groovy");
        getInterpreterPane().getPromptEditor().requestFocusInWindow();
    }

    private void init() {
        try {
            getInterpreter().eval(apiFunction());
            getInterpreter().eval(initCmds());
            getInterpreterPane().getOutput().setText(""); // wipe SciJava's welcome before printing ours
            getInterpreterPane().getOutput().setText("Welcome to the SNT SciJava Scripting REPL!\n\n");
            getInterpreterPane().getPrompt().help();
        } catch (final ScriptException ex) {
            SNTUtils.error("initialization failed", ex);
        }
    }

    private String initCmds() {
        return """
                instance = snt.getInstance()
                pafm = snt.getPathAndFillManager()
                ui = snt.getUI()
                """;
    }

    private String apiFunction() {
        return """
                def api(obj, keyword="") {
                	if (obj == null) { println("api: obj is null"); return }
                	def list = obj.class.declaredMethods.findAll { !it.name.contains("\\$") }
                    if ("" != keyword) list = list.findAll{ it.name.toLowerCase().contains(keyword.toLowerCase()) }
                	println("${list.size()} method(s) available in ${obj.class.getName()}:")
                	list.sort{ it.name }.each {
                		def params = it.parameters.collect { it.toString().split("\\\\.").last() }
                		def name = it.name + "(" + params.join(", ") + ")"
                		def returnType = it.returnType.toString()
                		def array = returnType.contains("[")
                		returnType = returnType.split("\\s|\\\\.").last().replace(";","")
                		if (array) returnType += "[]"
                    	println("  ${name.padRight(45)} -> ${returnType}")
                	}
                	return ""
                }
                """;
    }
}
