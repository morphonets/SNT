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

package sc.fiji.snt.plugin;

import java.util.Collection;

import javax.swing.SwingUtilities;

import org.scijava.command.Command;
import org.scijava.convert.ConvertService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import net.imagej.Dataset;
import sc.fiji.labkit.ui.InitialLabeling;
import sc.fiji.labkit.ui.LabkitFrame;
import sc.fiji.labkit.ui.inputimage.DatasetInputImage;
import sc.fiji.labkit.ui.models.DefaultSegmentationModel;
import sc.fiji.labkit.ui.models.SegmentationModel;
import sc.fiji.snt.Path;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;

/**
 * Command for sending Path-converted ROIs to a new Labkit instance.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, initializer = "init")
public class LabkitLoaderCmd extends CommonDynamicCmd {

	@Parameter
	private ConvertService convertService;

	@Parameter
	private Collection<Path> paths;

	@SuppressWarnings("unused")
	private void init() {
		init(true);
		if (!snt.accessToValidImageData()) {
			error("This option requires valid image data to be loaded.");
		} else if (paths == null || paths.isEmpty()) {
			error("At least a path is required but none was found.");
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {

		try {
			final Dataset dataset = (snt.getDataset() == null)
					? convertService.convert(snt.getImagePlus(), Dataset.class)
					: snt.getDataset();
			final DatasetInputImage inputImage = new DatasetInputImage(dataset);
			final SegmentationModel model = new DefaultSegmentationModel(getContext(), inputImage);
			model.imageLabelingModel().labeling().set(InitialLabeling.initialLabeling(getContext(), inputImage));
			SwingUtilities.invokeLater(() -> LabkitFrame.show(model, "SNT - " + dataset.getName()));
			msg("Path loading has not been implemented yet. Please add labels manually.", "Work In Progress");
			// TODO: Load paths as labels
		} catch (final Throwable t) {
			error("An error occured. See Console for details");
		} finally {
			resetUI();
		}

	}

}
