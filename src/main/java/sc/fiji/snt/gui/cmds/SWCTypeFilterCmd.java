/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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

package sc.fiji.snt.gui.cmds;

import net.imagej.ImageJ;
import org.scijava.Context;
import org.scijava.command.Command;
import org.scijava.command.CommandModule;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import sc.fiji.snt.Path;
import sc.fiji.snt.PathManagerUI;
import sc.fiji.snt.gui.GuiUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Implements {@link PathManagerUI}'s SWC-type filtering Command.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "SWC-type Filtering")
public class SWCTypeFilterCmd extends ContextCommand {

	private static final String CHOSEN_TYPES = "chosenTypes";

	@Parameter
	private PrefService prefService;

	@Parameter(label = Path.SWC_DENDRITE_LABEL)
	private boolean basalDendrite;

	@Parameter(label = Path.SWC_APICAL_DENDRITE_LABEL)
	private boolean apicalDendrite;

	@Parameter(label = Path.SWC_AXON_LABEL)
	private boolean axon;

	@Parameter(label = Path.SWC_CUSTOM_LABEL)
	private boolean custom;

	@Parameter(label = Path.SWC_CUSTOM2_LABEL)
	private boolean custom2;

	@Parameter(label = Path.SWC_GLIA_PROCESS_LABEL)
	private boolean glia;

	@Parameter(label = Path.SWC_SOMA_LABEL)
	private boolean soma;

	@Parameter(label = Path.SWC_UNDEFINED_LABEL)
	private boolean undefined;

	@Parameter(label = Path.SWC_UNSPECIFIED_LABEL)
	private boolean unspecified;

	@Parameter(label = "Select", choices = {"Choose", "Select All", "Select None", "Reverse Selection"},
			callback="actionChoiceSelected")
	private String actionChoice;

	@SuppressWarnings("unused")
	private void actionChoiceSelected() {
		if (actionChoice.contains("All")) {
			setAllCheckboxesEnabled(true);
		} else if (actionChoice.contains("None")) {
			setAllCheckboxesEnabled(false);
		} else if (actionChoice.contains("Reverse")) {
			toggleCheckboxes();
		}
	}

	private void setAllCheckboxesEnabled(final boolean enable) {
		axon = basalDendrite = apicalDendrite = custom = custom2 = glia = soma = undefined = unspecified = enable;
		actionChoice = "Choose";
	}

	private void toggleCheckboxes() {
		axon = !axon;
		basalDendrite = !basalDendrite;
		apicalDendrite = !apicalDendrite;
		custom= !custom;
		custom2 = !custom2;
		glia = !glia;
		soma = !soma;
		undefined = !undefined;
		unspecified = !unspecified;
		actionChoice = "Choose";
	}

	/* (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		final StringBuilder sb = new StringBuilder();
		if (axon) sb.append(Path.SWC_AXON);
		if (basalDendrite) sb.append(Path.SWC_DENDRITE);
		if (apicalDendrite) sb.append(Path.SWC_APICAL_DENDRITE);
		if (custom) sb.append(Path.SWC_CUSTOM);
		if (custom2) sb.append(Path.SWC_CUSTOM2);
		if (glia) sb.append(Path.SWC_GLIA_PROCESS);
		if (soma) sb.append(Path.SWC_SOMA);
		if (undefined) sb.append(Path.SWC_UNDEFINED);
		if (unspecified) sb.append(Path.SWC_UNSPECIFIED);
		prefService.put(SWCTypeFilterCmd.class, CHOSEN_TYPES, sb.toString());
	}

	public static Set<Integer> getChosenTypes(final Context context) {
		final String stringTypes = context.getService(PrefService.class).get(
			SWCTypeFilterCmd.class, CHOSEN_TYPES, "");
		if (stringTypes.isEmpty()) return null;
		final Set<Integer> set = new HashSet<>();
		for (final char c : stringTypes.toCharArray()) {
			set.add(Character.getNumericValue(c));
		}
		return set;
	}

	/* IDE debug method **/
	public static void main(final String[] args) throws InterruptedException,
		ExecutionException
	{
		GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final CommandModule cm = ij.command().run(SWCTypeFilterCmd.class, true)
			.get();
		if (cm.isCanceled()) {
			System.out.println("Command canceled ");
			return;
		}
		final Set<Integer> types = SWCTypeFilterCmd.getChosenTypes(ij.context());
		System.out.println("Chosen types were: ");
		for (final int type : types) {
			System.out.println(Path.getSWCtypeName(type, true));
		}
	}

}
