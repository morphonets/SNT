/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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

import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.Interactive;
import org.scijava.display.DisplayService;
import org.scijava.io.IOService;
import org.scijava.module.MutableModuleItem;
import org.scijava.platform.PlatformService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;

import ij.measure.Calibration;
import net.imagej.ImageJ;
import net.imagej.legacy.LegacyService;
import net.imagej.ops.OpService;
import net.imagej.util.Images;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.filter.Frangi;
import sc.fiji.snt.filter.Lazy;
import sc.fiji.snt.filter.Tubeness;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.SigmaPaletteListener;

/**
 * Implements the "Generate Secondary Layer" command.
 *
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
@Plugin(type = Command.class, visible = false, initializer = "init")
public class ComputeSecondaryImg<T extends RealType<T> & NativeType<T>> extends CommonDynamicCmd implements Interactive, SigmaPaletteListener
{

	private static final String PROMPT_TITLE = "Compute Secondary Layer...    "; // THIS MUST BE UNIQUE for getPrompt() to work
	private static final String REFRESH_BUTTON_TITLE = "Refresh";

	private static final String LAZY_LOADING_FALSE = "Save computations (Compute once and store result in RAM)";
	private static final String LAZY_LOADING_TRUE = "Save memory (Dilute computation time across searches)";

	private static final int PALETTE_CLOSED = 0;
	private static final int PALETTE_WAITING = 1;
	private static final int PALETTE_IS_RUNNING = 2;

	private static final String NONE = "None. Duplicate primary image";
	private static final String FRANGI = "Frangi Vesselness";
	private static final String TUBENESS = "Tubeness";
	private static final String GAUSS = "Gaussian Blur";
	private static final String MEDIAN = "Median (Must be computed once)";

	@Parameter
	private DisplayService displayService;

	@Parameter
	private LegacyService legacyService;

	@Parameter
	private PlatformService platformService;

	@Parameter
	private OpService ops;

	@Parameter
	private IOService io;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Filtering:")
	private String HEADER1;

	@Parameter(label = "Filter", choices = { FRANGI, TUBENESS, GAUSS, MEDIAN, NONE }, callback = "filterChanged")
	private String filter;

	@Parameter(label = "Size of traced structures", required = false, //
			description = "<HTML>Aprox. thickness (radius) of structures being traced (comma separated list).<br>There are two ways "
					+ "of setting this field: Using <i>Select visually...</i> for an<br>interactive prompt, or manually, if you "
					+ "know a priori such thicknesses.<br>Note that the 'gear menu' also hosts commands for estimation of radii.")
	private String sizeOfStructuresString;

	@Parameter(label = "Select visually...", callback="triggerSigmaPalette")
	private Button triggerSigmaPalette;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Performance:")
	private String HEADER2;

	@Parameter(label = "Strategy", choices = { LAZY_LOADING_FALSE, LAZY_LOADING_TRUE }, //
			description = "<HTML><b>Save computations</b>: Allows for faster tracing when images are small.<br>"
					+ "<b>Save Computations</b>: Allows for tracing of large images when available RAM is limited.")
	private String useLazyChoice;

	@Parameter(label = "No. of threads", min = "1", stepSize = "1")
	private int numThreads;

	@Parameter(label = "Defaults", callback = "defaults")
	private Button defaults;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Options:")
	private String HEADER3;

	@Parameter(label = "Display image")
	private boolean show;

	@Parameter(label = "Save to file")
	private boolean save;

	@Parameter(label = REFRESH_BUTTON_TITLE, callback = "updatePrompt", //
			description="Updates this prompt, ensuring fields are up-to-date.")
	private Button refresh;

	@Parameter(label = "   Run   ", callback = "runCommand", //
			description="Generates the filtered image")
	private Button run;

	@SuppressWarnings("rawtypes")
	private RandomAccessibleInterval filteredImg;
	private boolean useLazy;
	private List<Double> sigmas;
	private int paletteStatus = PALETTE_CLOSED;

	//HACKS:
	private JDialog prompt;
	private AbstractButton refreshButtonAsSwingComponent;

	protected void init() {
		super.init(true);
		if (!snt.accessToValidImageData()) {
			error("Valid image data is required for computation.");
			return;
		}
		getInfo().setLabel(PROMPT_TITLE);
	}

	@SuppressWarnings("unused")
	private void filterChanged() {
		if (MEDIAN.equals(filter))
			useLazyChoice = LAZY_LOADING_FALSE;
	}

	@SuppressWarnings("unused")
	private void triggerSigmaPalette() {
		if (NONE.equals(filter)) {
			msg("Current filter choice does not require size parameters.", "Unnecessary Operation");
			return;
		}
		switch (paletteStatus) {
		case PALETTE_CLOSED:
			ui.setSigmaPaletteListener(this);
			ui.changeState(SNTUI.WAITING_FOR_SIGMA_POINT_I);
			paletteStatus = PALETTE_WAITING;
			break;
		default:
			break; // do nothing;
		}
		updatePrompt();
	}

	private void updatePrompt() {
		final MutableModuleItem<Button> mmi = getInfo().getMutableInput("triggerSigmaPalette", Button.class);
		switch (paletteStatus) {
		case PALETTE_IS_RUNNING:
			mmi.setLabel("Adjusting settings visually. Press refresh when done.");
			break;
		case PALETTE_WAITING:
			mmi.setLabel("Now click on a representative structure...");
			break;
		default:
			mmi.setLabel("Select visually...");
			break;
		}
		updateSigmasField();
		// getInfo().update(eventService); // DOES NOTHING!?
	}

	@SuppressWarnings("unused")
	private void defaults() {
		numThreads = SNTPrefs.getThreads();
		useLazyChoice = LAZY_LOADING_FALSE;
	}

	@SuppressWarnings("unused")
	private void help() {
		final String url = "https://imagej.net/SNT:_Manual#Tracing_on_Secondary_Image";
		try {
			platformService.open(new URL(url));
		} catch (final IOException e) {
			error("Web page could not be open. " + "Please visit " + url + " using your web browser.");
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {} // Do nothing

	@SuppressWarnings({ "unchecked", "unused" })
	private void runCommand() {
		if (isCanceled() || !snt.accessToValidImageData())
			return;
		if (numThreads > SNTPrefs.getThreads()) {
			numThreads = SNTPrefs.getThreads();
		}
		useLazy = LAZY_LOADING_TRUE.equals(useLazyChoice);

		final int cellDim = 30; // side length for cell
		if (NONE.equals(filter)) {
			final RandomAccessibleInterval<T> loadedData = sntService.getPlugin().getLoadedData();
			final RandomAccessibleInterval<T> copy = ops.create().img(loadedData);
			Images.copy(loadedData, copy);
			filteredImg = copy;
			apply();
			return;
		}

		// validate inputs
		if (MEDIAN.equals(filter) && useLazy) {
			error("Current filter is not compatible with the current performance strategy.");
			return;
		}
		final double[] sigmas = getSigmas();
		if (!NONE.equals(filter) && (sigmas == null || sigmas.length == 0)) {
			error("No valid sizes have been specified.");
			return;
		}

		final RandomAccessibleInterval<T> data = sntService.getPlugin().getLoadedData();
		final RandomAccessibleInterval<T> in = Views.dropSingletonDimensions(data);
		final Calibration cal = sntService.getPlugin().getImagePlus().getCalibration();
		final double[] spacing = new double[]{cal.pixelWidth, cal.pixelHeight, cal.pixelDepth};
		switch (filter) {
			case FRANGI: {
				Frangi<T, FloatType> op = new Frangi<>(
						sigmas,
						spacing,
						sntService.getPlugin().getStats().max,
						numThreads);

				if (useLazy) {
					filteredImg = Lazy.process(
							in,
							in,
							new int[]{cellDim, cellDim, cellDim},
							new FloatType(),
							op);
				} else {
					filteredImg = ops.create().img(in, new FloatType(), new CellImgFactory<>(new FloatType()));;
					op.compute(in, filteredImg);
				}

				break;
			}
			case TUBENESS: {
				Tubeness<T, FloatType> op = new Tubeness<>(sigmas, spacing, numThreads);
				if (useLazy) {
					filteredImg = Lazy.process(
							in,
							in,
							new int[]{cellDim, cellDim, cellDim},
							new FloatType(),
							op);
				} else {
					filteredImg = ops.create().img(in, new FloatType(), new CellImgFactory<>(new FloatType()));
					op.compute(in, filteredImg);
				}

				break;
			}
			case GAUSS: {
				final double sig = sigmas[0]; // just pick the first sigma I guess...
				if (useLazy) {
					filteredImg = Lazy.process(
							in,
							in,
							new int[]{cellDim, cellDim, cellDim},
							new FloatType(),
							ops,
							net.imagej.ops.filter.gauss.DefaultGaussRAI.class,
							(Object) new double[]{sig / spacing[0], sig / spacing[1], sig / spacing[2]});
				} else {
					filteredImg = ops.create().img(in, new FloatType(), new CellImgFactory<>(new FloatType()));
					ops.filter().gauss(
							filteredImg,
							in,
							sig / spacing[0], sig / spacing[1], sig / spacing[2]);
				}

				break;
			}
			case MEDIAN: {
				// FIXME: TO BE IMPLEMENTED
			}
			default:
				throw new IllegalArgumentException("Unrecognized filter " + filter);
		}

		apply();
	}

	@SuppressWarnings("unchecked")
	private void apply() {
		snt.loadSecondaryImage(filteredImg, !useLazy);
		if (show) {
			displayService.createDisplay(getImageName(), filteredImg); // virtual stack!?
//			ImageJFunctions.show(filteredImg, getImageName());
		}
		if (save) {
			final File file = getSaveFile();
			if (file != null) { // user did not abort file prompt
				try {
					io.save(filteredImg, getSaveFile().getAbsolutePath());
				} catch (final IOException e) {
					error("An error occurred when trying to save image. See console for details");
					e.printStackTrace();
				}
			}
		}
		if (getPrompt() != null) getPrompt().dispose();
		if (ui != null) ui.changeState(SNTUI.READY);
		prompt = null;
	}

	private String getImageName() {
		final String basename = SNTUtils.stripExtension(sntService.getPlugin().getImagePlus().getTitle());
		final String sfx = (NONE.equals(filter)) ? "DUP" : filter;
		return basename + " Sec Img [" + sfx + "].tif";
	}

	private File getSaveFile() {
		File file = new File(sntService.getPlugin().getPrefs().getRecentDir(), getImageName());
		file = SNTUtils.getUniquelySuffixedTifFile(file);
		return legacyService.getIJ1Helper().saveDialog("Save \"Filtered Image\"", file, ".tif");
	}

	private double[] getSigmas() {
		if (sigmas != null) {
			// values have been set from the sigma palette
			return sigmas.stream().mapToDouble(Double::doubleValue).toArray();
		}
		if (sizeOfStructuresString != null && !sizeOfStructuresString.trim().isEmpty()) {
			// read from prompt in case user entered values
			try {
				final Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(sizeOfStructuresString);
				sigmas = new ArrayList<>();
				while (matcher.find()) {
					sigmas.add(Double.valueOf(matcher.group()));
				}
				return sigmas.stream().mapToDouble(Double::doubleValue).toArray();
			} catch (final NumberFormatException e) {
				return null;
			}
		}
		// no values exist. Some logic to read values from elsewwhere could go here
		return snt.getSigmas(true); //FIXME:getHessianSigma() is in limbo. Better to return null?
	}

	private void updateSigmasField() {
		if (sigmas == null) {
			sizeOfStructuresString = "";
		} else {
			final DecimalFormat df = new DecimalFormat("0.00");
			final StringBuilder sb = new StringBuilder();
			sigmas.forEach(s -> sb.append(df.format(s)).append(", "));
			sb.setLength(sb.length() - 2);
			sizeOfStructuresString = sb.toString();
		}
	}

	@Override
	protected void resetUI(final boolean validateDimensions) {
		if (ui != null) {
			ui.changeState((getPrompt() == null) ? SNTUI.READY : SNTUI.RUNNING_CMD);
			if (validateDimensions && !isCanceled())
				ui.runCommand("validateImgDimensions");
		}
		statusService.clearStatus();
	}

	private JDialog getPrompt() {
		// HACK: There is no guarantee that any of this will work. In the future prompt may not be a Swing
		// dialog and Button may become something else but an AbstractButton. The only reason we are doing
		// this is because there seems to be no way to refresh the prompt without the user interacting with
		// it. getInfo().update(eventService) does not work and we have no way to access the prompt itself!
		if (prompt == null) {
			for (final Window w : JDialog.getWindows()) {
				if (w instanceof JDialog) {
					if (PROMPT_TITLE.equals(((JDialog) w).getTitle())) {
						prompt = ((JDialog) w);
						refreshButtonAsSwingComponent = GuiUtils.getButton(prompt, REFRESH_BUTTON_TITLE);
						prompt.addWindowListener(new WindowAdapter() {
							@Override
							public void windowClosing(final WindowEvent ignored) {
								if (ui != null) ui.changeState(SNTUI.READY);
							}
						});
						break;
					}
				}
			}
		}
		return prompt;
	}

	private void setPromptVisible(final boolean visible) {
		if (getPrompt() == null) return;
		SwingUtilities.invokeLater(() ->  {
			if (getPrompt().isVisible() != visible) getPrompt().setVisible(visible);
			if (visible) ui.changeState(SNTUI.RUNNING_CMD); // ensure state for as long this prompt is being displayed
		});
	}

	@Override
	public void paletteDisplayed() {
		paletteStatus = PALETTE_IS_RUNNING;
		updatePrompt();
		setPromptVisible(false);
	}

	@Override
	public void paletteDismissed() {
		setPromptVisible(true);
		paletteStatus = PALETTE_CLOSED;
		if (refreshButtonAsSwingComponent != null) {
			refreshButtonAsSwingComponent.doClick();
		} else {
			updatePrompt();
		}
		ui.setSigmaPaletteListener(null);
	}

	@Override
	public Window getParent() {
		return getPrompt();
	}

	@Override
	public void setSigmas(final List<Double> list) {
		this.sigmas = list;
		updateSigmasField();
	}


	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(ComputeSecondaryImg.class, true);
	}

	//FIXME:
		// - SigmaPalette assumes always 'Tubeness' independently of filter choice
				// - the SigmaPaletterListener could be used to set the proper filter
		// the built-in filter 'gear menu should be cleansed of redundant options now implemented here?
		// - The Median filter was just introduced for testing callbacks. If we are not going to use it, it should be removed
		// - the getPrompt() hack only works _AFTER_ interacting with certain widgets in the prompt. If the prompt is closed before such interactions, UI may remain in some unexpected state

}
