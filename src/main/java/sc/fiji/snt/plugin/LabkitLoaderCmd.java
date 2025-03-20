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

import net.imagej.Dataset;
import net.imagej.axis.Axes;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.roi.labeling.LabelingType;
import net.imglib2.type.numeric.ARGBType;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.ColorRGB;
import sc.fiji.labkit.ui.InitialLabeling;
import sc.fiji.labkit.ui.LabkitFrame;
import sc.fiji.labkit.ui.inputimage.DatasetInputImage;
import sc.fiji.labkit.ui.labeling.Label;
import sc.fiji.labkit.ui.labeling.Labeling;
import sc.fiji.labkit.ui.models.DefaultSegmentationModel;
import sc.fiji.labkit.ui.models.SegmentationModel;
import sc.fiji.labkit.ui.utils.DimensionUtils;
import sc.fiji.snt.Path;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.util.PointInCanvas;
import sc.fiji.snt.util.SNTColor;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Command for sending Path-converted ROIs to a new Labkit instance.
 *
 * @author Tiago Ferreira
 * @author Matthias Arzt
 * 
 */
@Plugin(type = Command.class, initializer = "init")
public class LabkitLoaderCmd extends CommonDynamicCmd {

	private static final String FOREGROUND_LABEL = "SNT paths ch"; // should this be "Neurites"!?
	private static final String BACKGROUND_LABEL = "background";
	private static final String BACKGROUND_COLOR = "#666666"; // dark gray

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

		if (isCanceled()) return;
		try {

			// Retrieve training image and training image properties
			final Dataset dataset = snt.getDataset();
			final long cLen = dataset.dimension(dataset.dimensionIndex(Axes.CHANNEL));
			final long zLen = dataset.dimension(dataset.dimensionIndex(Axes.Z));
			final long tLen = dataset.dimension(dataset.dimensionIndex(Axes.TIME));

			// Initialize model
			final DatasetInputImage inputImage = new DatasetInputImage(dataset);
			final SegmentationModel model = new DefaultSegmentationModel(getContext(), inputImage);
			model.imageLabelingModel().labeling().set(InitialLabeling.initialLabeling(getContext(), inputImage));

			// Initialize an empty labeling of correct size. We need to discard the channel
			// dimension because Labkit does so internally. NB: This likely assumes image axes
			// are ordered as XYZT. See https://github.com/juglab/labkit-ui/issues/112
			Interval labkitImgSize;
			if (cLen > 1) {
				final int channelDimension = dataset.dimensionIndex(Axes.CHANNEL);
				labkitImgSize = DimensionUtils.intervalRemoveDimension(dataset, channelDimension);
			} else {
				labkitImgSize = dataset;
			}
					
			// Prepare the labeling set: 1 label per channel + background class
			final String[] labels = new String[(int) cLen + 1];
			for (int i = 0; i < cLen; i++)
				labels[i] = FOREGROUND_LABEL + (i + 1);
			labels[(int) cLen] = BACKGROUND_LABEL;
			final Labeling labeling = Labeling.createEmpty(Arrays.asList(labels), labkitImgSize);

			// Adjust colors (the default 'blue/red' has poor contrast
			final ColorRGB[] colors = SNTColor.getDistinctColors(labels.length);
			for (int i = 0; i < cLen; i++)
				labeling.getLabel(labels[i]).setColor(new ARGBType(colors[i].getARGB()));
			labeling.getLabel(BACKGROUND_LABEL).setColor(new ARGBType(Color.decode(BACKGROUND_COLOR).getRGB()));

			// Add path positions to foreground class(es). NB: Axes order in Labeling is
			// fixed to XYZT(!?) without channel axis. For details:
			// https://github.com/juglab/labkit-ui/issues/112
			final RandomAccess<LabelingType<Label>> randomAccess = labeling.randomAccess();
			final LabelingType<Label> lt = randomAccess.get().createVariable();
			final Set<Integer> labeledChannels = new HashSet<>();
			paths.forEach(path -> {
				lt.add(labeling.getLabel(FOREGROUND_LABEL + path.getChannel()));
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
				labeledChannels.add(path.getChannel());
			});

			// Apply model and display
			model.imageLabelingModel().labeling().set(labeling);
			SwingUtilities.invokeLater(() -> {
				displayReport(LabkitFrame.show(model, "SNT - " + dataset.getName()), labeledChannels);
			});

		} catch (final Throwable t) {
			error("An error occurred. See Console for details");
			t.printStackTrace();
		} finally {
			resetUI();
		}

	}

	private void displayReport(final LabkitFrame lkf, final Set<Integer> labeledChannels) {
		final StringBuilder sb = new StringBuilder();
		sb.append("Labels from ").append(paths.size()).append(" path(s) were added to labeling(s) ").append(" <i>")
				.append(FOREGROUND_LABEL).append(labeledChannels.toString().replace("[", "").replace("]", ""))
				.append("</i>. You can now add <i>").append(BACKGROUND_LABEL)
				.append("</i> labels to properly train the classifier.");
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
