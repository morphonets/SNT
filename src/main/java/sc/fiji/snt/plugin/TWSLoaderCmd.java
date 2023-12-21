/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
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

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.gui.StackWindow;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.analysis.RoiConverter;
import sc.fiji.snt.gui.GuiUtils;
import trainableSegmentation.Weka_Segmentation;

/**
 * Command for sending Path-converted ROIs to a new TWS instance.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, initializer = "init")
public class TWSLoaderCmd extends Weka_Segmentation implements Command {

	@Parameter
	private SNTService sntService;

	@Parameter
	private Collection<Path> paths;

	@Parameter(required = false)
	private ImagePlus imp;

	private StackWindow twsWin;
	private boolean redirectErrorMsgsState;

	@SuppressWarnings("unused")
	private void init() {
		redirectErrorMsgsState = ij.IJ.redirectingErrorMessages();
		ij.IJ.redirectErrorMessages(true); // required to handle TWS errors
	}

	private void exit() {
		ij.IJ.redirectErrorMessages(redirectErrorMsgsState);
	}

	private void error(final String reason) {
		new GuiUtils(sntService.getUI().getPathManager()).error(reason);
		exit();
	}

	private void warn(final String msg, final String id) {
		final SNT snt = sntService.getInstance();
		if (!snt.getPrefs().getBoolean(id, true))
			return;
		final boolean[] options = new GuiUtils(sntService.getUI().getPathManager()).getConfirmationAndOption(msg, "Warning",
				"Do not remind me again about this", false, new String[] { "Understood", "Dismiss" });
		snt.getPrefs().set(id, options != null && !options[1]);
	}

	private void initializeTWS(final ImagePlus trainingImage) {
		// Weka_Segmentation#run("") loads frontmost image
		if (!trainingImage.isVisible())
			trainingImage.show();
		trainingImage.getWindow().toFront();
		if (trainingImage.isComposite()) {
			// trainingImage.setProp("CompositeProjection", "null");
			imp.setDisplayMode(ij.IJ.GRAYSCALE);
			ij.IJ.run("Channels Tool...");
		}
		run((trainingImage.getNSlices() == 1) ? "" : "3D");
		twsWin = getTWSgui();
		ij.IJ.wait(500); // ensure GUI is fully displayed
	}

	private ImagePlus getTWSdisplayImage() {
		try {
			final Field f = getClass().getSuperclass().getDeclaredField("displayImage");
			f.setAccessible(true);
			return (ImagePlus) f.get(this);
		} catch (final NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
			e.printStackTrace();
		}
		return null;
	}

	private StackWindow getTWSgui() {
		try {
			final Field f = getClass().getSuperclass().getDeclaredField("win");
			f.setAccessible(true);
			return (StackWindow) f.get(this);
		} catch (final NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
			e.printStackTrace();
		}
		return null;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {

		if (!sntService.isActive()) {
			error("SNT is not running.");
			return;
		}
		if (paths == null || paths.isEmpty()) {
			error("At least a path is required but none was found.");
			return;
		}
		if (imp == null) {
			error("This option requires valid image data to be loaded.");
			return;
		}
		if (!PLUGIN_VERSION.equals("v3.3.4")) {
			warn("This command has only been tested with TWS v3.3.4 (you are running " + PLUGIN_VERSION
					+ "). Unexpected issues may occur.", "tws-version");
		}

		// TWS does not really support hyperstacks so we need to account for it
		final boolean multidImg = imp.isComposite() || imp.isHyperStack();
		if (imp.getNFrames() > 1) {
			warn("TWS does not have formal support for timelapse images, and parsing "
					+ "of large time-sequences requires significant resources."
					+ "You may need to handle frames individually.", "tws-time");
		}
		try {
			initializeTWS(imp);
			// GUI initializes with 2 classes by default. For consistency,
			// we'll set the last class in the GUI for background classification
			changeClassName("" + 0, "SNTch1");
			if (imp.getNChannels() > 1) {
				changeClassName("" + 1, "SNTch2");
				IntStream.rangeClosed(3, imp.getNChannels()).forEach(ch -> {
					// add a class per channel so that RO C positions sync with class indices
					createNewClass("SNTch" + ch);
				});
				createNewClass("Bckgrnd");
			} else {
				changeClassName("" + 1, "Bckgrnd");
			}
			final ImagePlus twsImp = getTWSdisplayImage();
			final Overlay overlay = new Overlay();
			final RoiConverter converter = new RoiConverter(paths, imp);
			converter.convertPaths(overlay);
			final Map<Integer, Integer> counter = new HashMap<>();
			IntStream.rangeClosed(1, imp.getNSlices()).forEach(z -> {
				final List<Roi> planeRois = RoiConverter.getZplaneROIs(overlay, z);
				planeRois.forEach(roi -> {
					int stackSlice;
					int classNum;
					if (multidImg) {
						stackSlice = imp.getStackIndex(roi.getCPosition(), z, roi.getTPosition());
						classNum = roi.getCPosition() - 1; // 0-based index
					} else {
						stackSlice = z;
						classNum = 0;
					}
					twsImp.setPosition(stackSlice);
					twsImp.killRoi();
					twsImp.setRoi(roi);
					addTrace("" + classNum, "" + stackSlice);
					final int count = (counter.get(classNum) == null) ? 0 : counter.get(classNum);
					counter.put(classNum, count + 1);
				});
			});
			twsImp.killRoi();
			updateResultOverlay();
			displayReport(counter);
		} catch (final Throwable t) {
			error(t.getClass().getSimpleName() + ": An error occured. See Console/Log window for details.");
			t.printStackTrace();
		} finally {
			exit();
		}
	}

	private void displayReport(final Map<Integer, Integer> result) {
		final int sum = result.values().stream().mapToInt(d -> d).sum();
		final String mapAsString = (result
				.size() < 2)
						? "."
						: result.entrySet().stream().map(entry -> "<li><i>SNTCh" + (entry.getKey() + 1) + "</i>: "
								+ entry.getValue() + " segment(s)</li>")
								.collect(Collectors.joining(":", "<ul>", "</ul>"));
		new GuiUtils(twsWin).centeredMsg("" + sum + " label(s) from " + paths.size() + " path(s) were loaded across "
				+ result.size() + " class(es)" + mapAsString + " You should now add labels for the background "
				+ "class to ensure the classifier is properly trained.", "Examples Adedd");
	}
}
