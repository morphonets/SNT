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

import org.scijava.Context;
import org.scijava.ui.swing.script.InterpreterWindow;
import sc.fiji.snt.SNTUtils;

import javax.script.ScriptException;
import java.awt.*;

/**
 * SNT's (basic) Groovy REPL.
 *
 * @author Tiago Ferreira
 */
public class SNTREPL extends InterpreterWindow {

    public SNTREPL(final Context context) {
        super(context, ".groovy");
        init();
        setTitle("SNT Scripting REPL");
        final FontMetrics fm = getContentPane().getFontMetrics(getContentPane().getFont());
        final int w = fm.stringWidth("type a statement to evaluate it with the active language.");
        final int h = fm.getHeight() * 20;
        setMinimumSize(new Dimension(w * 2, h));
        pack();
    }

    private void init() {
        try {
            getInterpreter().eval(apiFunction());
            getInterpreter().eval(initCmds());
            print("SNT REPL: The following variables/functions have been loaded:");
            print("  instance               | sc.fiji.snt.SNT");
            print("  pafm                   | sc.fiji.snt.PathAndFillManager");
            print("  ui                     | sc.fiji.snt.SNTUI");
            print("  snt                    | sc.fiji.snt.SNTService"); // loaded by scijava
            print("  api(obj, <'keyword'>)  | inspects the API of an object");
            print("");
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

    @SuppressWarnings("unused")
    private String apiFunctionNotebook() {
        return """
                def void api(obj, keyword="") {
                    def list = notebook.methods(obj)
                    list = list.findAll { it["name"].toLowerCase().contains(keyword) }
                    println("${obj.class.getName()} methods:")
                    list.forEach( m -> {
                        def name = m["name"]
                        def arg = prettify(m["arguments"].replace("java.lang.", ""))
                        def res = prettify(m["returns"])
                        println("  $name($arg) ${""==res?"":"-> $res"}")
                    })
                    println()
                }
                def prettify(arg) {
                    if ("<none>"==arg || "void"==arg ) return ""
                    if (arg.contains(";") || arg.contains("[") ) {
                        arg = arg.replace("L", "").replace(";", "")
                        if (arg.contains("[")) arg = arg.replace("[", "") + "[]"
                        arg = arg.replaceAll("\\\\bI\\\\b", "int")
                        arg = arg.replaceAll("\\\\bD\\\\b", "double")
                    }
                    return arg
                }
                """;
    }
}
