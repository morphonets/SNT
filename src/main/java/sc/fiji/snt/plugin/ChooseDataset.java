/*
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
package sc.fiji.snt.plugin;

import net.imagej.Dataset;
import net.imagej.DatasetService;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.DynamicCommand;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * @author Tiago Ferreira
 */
@Plugin(initializer = "init", type = Command.class, visible = false, label = "Choose New Dataset")
public class ChooseDataset extends DynamicCommand {

	@Parameter
	private DatasetService datasetService;

	@Parameter
	private PrefService prefService;

	@Parameter(label = "New dataset", persist= false, visibility = ItemVisibility.TRANSIENT)
	private String choice;

	@Parameter(required=false, persist= false, visibility = ItemVisibility.TRANSIENT)
	private Dataset datasetToIgnore;

//	@Parameter(type = ItemIO.OUTPUT)
//	private ImageDisplay chosen;

//	private HashMap<String, ImageDisplay> map;

	@Override
	public void run() {
		prefService.put(ChooseDataset.class, "choice", choice);
//		chosen = map.get(choice);
	}

	protected void init() {
//		map = new HashMap<>();
//		final List<ImageDisplay> list = imgDisplayService.getImageDisplays();
//		if (list == null || list.size() < 2) {
//			cancel("No other images are open.");
//		}
//		for (final ImageDisplay imgDisplay : list) {
//			map.put(imgDisplay.getName(), imgDisplay);
//		}
		final List<String> choices = new ArrayList<>();
		prefService.put(ChooseDataset.class, "choice", ""); // reset pref
		final List<Dataset> list = datasetService.getDatasets();
		if (list == null || list.size() < 2) {
			cancel("No other images are open.");
			return;
		}
		for (final Dataset dataset : list) {
			if (dataset.equals(datasetToIgnore))
				continue;
			choices.add(dataset.getName());
		}
		Collections.sort(choices);
		final MutableModuleItem<String> mItem = getInfo().getMutableInput("choice",
			String.class);
		mItem.setChoices(choices);
		// mItem.setValue(this, choices.get(0));
	}

}
