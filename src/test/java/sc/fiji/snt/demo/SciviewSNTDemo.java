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

package sc.fiji.snt.demo;

import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.SciViewSNT;

public class SciviewSNTDemo {

	/**
	 * This demo requires Java21 and the following VM arguments to be specified:
	 * <code>
	 * --add-opens java.base/java.lang=ALL-UNNAMED
	 * </code>
	 * <p>
	 * In Eclipse: Run -> Run Configurations..., Arguments tab<br>
	 * In IntelliJ: Run -> Edit Configurations..., Add VM Options (Alt+V)
	 * </p>
	 */
	@SuppressWarnings("unused")
	public static void main(final String[] args) {

		// 1. Start SNT's GUI
		final SNT snt = SNTUtils.startApp();
		// 2. Load a demo dataset (File> Load Demo Dataset...> Demo 03)
		snt.getUI().runCommand("Load Demo Dataset...", "3");
		// 3. Make sciview widget visible
		snt.getUI().selectTab("3D");
		// 4. Display paths in sciview
		final SciViewSNT scivewSNT = snt.getUI().getSciViewSNT(true);
		// scivewSNT.syncPathManagerList(); // FIXME: This causes sciview to stall without response!?
											// As a workaround, wait until sciview is fully initialized
											// and click on "Sync Changes" in SNT's 3D tab
	}
}
