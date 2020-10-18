/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2020 Fiji developers.
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

package sc.fiji.snt.plugin.ij1;

import net.imagej.ImageJ;
import net.imagej.legacy.LegacyService;

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

/**
 * Command to invoke the legacy "Sholl Analysis (Existing Profile)..." IJ1 plugin
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class,
	menuPath = "Analyze>Sholl>Deprecated>Sholl Analysis (Existing Profile)...")
public class CallLegacyShollAnalysisTable implements Command {

	@Parameter
	private LegacyService legacyService;

	@SuppressWarnings("deprecation")
	@Override
	public void run() {
		legacyService.runLegacyCommand(Sholl_Analysis.class.getName(), "csv");
	}

	/* IDE debugging method */
	public static void main(final String... args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(CallLegacyShollAnalysisTable.class, true);
	}
}
