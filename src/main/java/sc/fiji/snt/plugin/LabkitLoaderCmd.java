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

import java.awt.Color;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.scijava.command.Command;
import org.scijava.convert.ConvertService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import net.imagej.Dataset;
import net.imagej.axis.Axes;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccess;
import net.imglib2.roi.labeling.LabelingType;
import net.imglib2.type.numeric.ARGBType;
import sc.fiji.labkit.ui.InitialLabeling;
import sc.fiji.labkit.ui.LabkitFrame;
import sc.fiji.labkit.ui.inputimage.DatasetInputImage;
import sc.fiji.labkit.ui.labeling.Label;
import sc.fiji.labkit.ui.labeling.Labeling;
import sc.fiji.labkit.ui.models.DefaultSegmentationModel;
import sc.fiji.labkit.ui.models.SegmentationModel;
import sc.fiji.snt.Path;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.util.PointInCanvas;

/**
 * Command for sending Path-converted ROIs to a new Labkit instance.
 *
 * @author Tiago Ferreira
 * @author Matthias Arzt
 * 
 */
@Plugin(type = Command.class, initializer = "init")
public class LabkitLoaderCmd extends CommonDynamicCmd {

	private static final String FOREGROUND_LABEL = "SNT paths"; // should this be "Neurites"!?
	private static final String FOREGROUND_COLOR = "#FF00FF"; // magenta
	private static final String BACKGROUND_LABEL = "background";
	private static final String BACKGROUND_COLOR = "#666666"; // dark gray

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

			// Retrieve training image and training image properties
			final Dataset dataset = (snt.getDataset() == null)
					? convertService.convert(snt.getImagePlus(), Dataset.class)
					: snt.getDataset();
			final long zLen = dataset.dimension(dataset.dimensionIndex(Axes.Z));
			final long tLen = dataset.dimension(dataset.dimensionIndex(Axes.TIME));

			// Initialize model
			final DatasetInputImage inputImage = new DatasetInputImage(dataset);
			final SegmentationModel model = new DefaultSegmentationModel(getContext(), inputImage);
			model.imageLabelingModel().labeling().set(InitialLabeling.initialLabeling(getContext(), inputImage));

			// Initialize an empty labeling of correct size. We need to discard the channel
			// dimension because Labkit does so internally!?
			FinalInterval labkitImgSize;
			if (zLen == 1 && tLen == 1) {
				labkitImgSize = new FinalInterval(dataset.dimension(dataset.dimensionIndex(Axes.X)),
						dataset.dimension(dataset.dimensionIndex(Axes.Y)));
			} else if (zLen > 1 && tLen == 1) {
				labkitImgSize = new FinalInterval(dataset.dimension(dataset.dimensionIndex(Axes.X)),
						dataset.dimension(dataset.dimensionIndex(Axes.Y)),
						dataset.dimension(dataset.dimensionIndex(Axes.Z)));
			} else if (zLen == 1 && tLen > 1) {
				labkitImgSize = new FinalInterval(dataset.dimension(dataset.dimensionIndex(Axes.X)),
						dataset.dimension(dataset.dimensionIndex(Axes.Y)),
						dataset.dimension(dataset.dimensionIndex(Axes.TIME)));
			} else {
				labkitImgSize = new FinalInterval(dataset.dimension(dataset.dimensionIndex(Axes.X)),
						dataset.dimension(dataset.dimensionIndex(Axes.Y)),
						dataset.dimension(dataset.dimensionIndex(Axes.Z)),
						dataset.dimension(dataset.dimensionIndex(Axes.TIME)));
			}
			final Labeling labeling = Labeling.createEmpty(List.of(FOREGROUND_LABEL, BACKGROUND_LABEL), labkitImgSize);
			final RandomAccess<LabelingType<Label>> randomAccess = labeling.randomAccess();
			final LabelingType<Label> lt = randomAccess.get().createVariable();
			final Label fLabel = labeling.getLabel(FOREGROUND_LABEL);

			// Minor tweaks to labels (the default 'blue/red' has poor contrast for
			// skeletonized lines)
			fLabel.setColor(new ARGBType(Color.decode(FOREGROUND_COLOR).getRGB()));
			labeling.getLabel(BACKGROUND_LABEL).setColor(new ARGBType(Color.decode(BACKGROUND_COLOR).getRGB()));

			// Add path positions to foreground classifier. NB: Axes order in Labeling is
			// fixed to XYZT(!?) and no channel axis exist. Every channel has the same
			// "label". For details: https://github.com/juglab/labkit-ui/issues/112
			lt.add(fLabel);
			paths.forEach(path -> {
				for (int i = 0; i < path.size(); i++) {
					final PointInCanvas pic = path.getPointInCanvas(i);
					randomAccess.setPosition((int) pic.x, 0); // set X
					randomAccess.setPosition((int) pic.y, 1); // set Y
					if (zLen > 1)
						randomAccess.setPosition((int) pic.z, 2); // set Z
					if (tLen > 1)
						randomAccess.setPosition(path.getFrame() - 1, (zLen > 1) ? 3 : 2); // set T
					randomAccess.get().set(lt);
				}
			});

			// Apply model and display
			model.imageLabelingModel().labeling().set(labeling);
			SwingUtilities.invokeLater(() -> {
				displayReport(LabkitFrame.show(model, "SNT - " + dataset.getName()));
			});

		} catch (final Throwable t) {
			error("An error occured. See Console for details");
			t.printStackTrace();
		} finally {
			resetUI();
		}

	}

	private void displayReport(final LabkitFrame lkf) {
		final StringBuilder sb = new StringBuilder();
		sb.append(paths.size()).append(" labels from ").append(paths.size()).append(" path(s) were added to the <i>")
				.append(FOREGROUND_LABEL).append("</i> class. You can now add labels to the <i>")
				.append(BACKGROUND_LABEL).append("</i> class to properly train the classifier.");
		new GuiUtils(getFrame(lkf)).centeredMsg(sb.toString(), "Labeling Successfully Adedd");
	}

	private JFrame getFrame(final LabkitFrame instance) {
		try {
			final Field f = instance.getClass().getDeclaredField("frame");
			f.setAccessible(true);
			return (JFrame) f.get(instance);
		} catch (final NoSuchFieldException | SecurityException | IllegalArgumentException
				| IllegalAccessException ignored) {
			// do nothing
		}
		return null;
	}
}
