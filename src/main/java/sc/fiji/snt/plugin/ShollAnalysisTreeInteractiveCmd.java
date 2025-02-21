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

package sc.fiji.snt.plugin;

import ij.plugin.frame.Recorder;
import org.scijava.command.Command;
import org.scijava.command.Interactive;
import org.scijava.menu.MenuConstants;
import org.scijava.plugin.Menu;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;
import sc.fiji.snt.Tree;

/**
 * Implements SNT's commands for Sholl Analysis of {@link Tree}s.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, menu = {
		@Menu(label = MenuConstants.PLUGINS_LABEL, weight = MenuConstants.PLUGINS_WEIGHT, mnemonic = MenuConstants.PLUGINS_MNEMONIC), //
		@Menu(label = "Neuroanatomy"), @Menu(label = "Sholl"), // default weights
		@Menu(label = "Sholl Analysis (From Tracings) [Interactive]...") }, // correct sorting in menu
		initializer = "init")
public class ShollAnalysisTreeInteractiveCmd extends ShollAnalysisTreeCmd implements Interactive {

	@Parameter(label = "  Run Analysis...  ", callback = "runAnalysis")
	private Button analysisButton;


	@Override
	protected void runAnalysis() throws InterruptedException {
		if (Recorder.record && !ij.IJ.macroRunning()) {
			Recorder.recordString("// Sholl Analysis (From Tracings) [Interactive] is not amenable to macro recording\n" +
					"// Please use the macro recordable version of the same prompt for reliable macros.\n");
		}
		super.runAnalysis();
	}

	@Override
	public void run() {
		// There is no "OK" button in the prompt, so we don't need anything here. All functionality is run from callbacks
		// The code here gets called once the prompt is displayed and when CommandService.run() is called, so we'll only
		// run analysis if called from a (legacy!?) pre-recorded macro
		if (ij.IJ.macroRunning()) {
			try {
				runAnalysis();
			} catch (final InterruptedException e) {
				throw new RuntimeException(e);
			}
		} else if (Recorder.record) {
			Recorder.disableCommandRecording();
		}
	}

}
