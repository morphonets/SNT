/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2026 Fiji developers.
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

import ij.ImagePlus;
import ij.measure.Calibration;
import net.imagej.ImageJ;
import net.imagej.ops.OpService;
import net.imagej.util.Images;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.neighborhood.DiamondShape;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.command.Interactive;
import org.scijava.display.DisplayService;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.widget.Button;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.filter.Frangi;
import sc.fiji.snt.filter.Lazy;
import sc.fiji.snt.filter.Tubeness;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.SigmaPaletteListener;
import sc.fiji.snt.plugin.LocalThicknessCmd;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.SigmaUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.DecimalFormat;
import java.util.List;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

/**
 * Implements the "Generate Secondary Layer" command.
 *
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
@Plugin(type = Command.class, initializer = "init")
public class ComputeSecondaryImg<T extends RealType<T> & NativeType<T>, U extends RealType<U> & NativeType<U>>
		extends CommonDynamicCmd implements Interactive, SigmaPaletteListener
{

	private static final String PROMPT_TITLE = "Compute Secondary Layer...    "; // THIS MUST BE UNIQUE for getPrompt() to work

	private static final String LAZY_LOADING_FALSE = "Preprocess (Compute full image and store in RAM)";
	private static final String LAZY_LOADING_TRUE = "Compute while tracing (Filter locally and cache in RAM)";

	private static final int PALETTE_CLOSED = 0;
	private static final int PALETTE_WAITING = 1;
	private static final int PALETTE_IS_RUNNING = 2;

	private static final String NONE = "None. Duplicate primary image";
	private static final String FRANGI = "Frangi Vesselness (Multi-scale filter)";
	private static final String FRANGI_ALT = "Frangi Vesselness";
	private static final String TUBENESS = "Tubeness (Multi-scale filter)";
	private static final String TUBENESS_ALT = "Tubeness";
	private static final String GAUSS = "Gaussian Blur (Single-scale filter)";
	private static final String GAUSS_ALT = "Gaussian Blur";
	private static final String MEDIAN = "Median (Single-scale filter, must be computed for full image)";
	private static final String MEDIAN_ALT = "Median";
	private static final String FLOAT = "32-bit";
	private static final String DOUBLE = "64-bit";

	@Parameter
	private DisplayService displayService;

	@Parameter
	private OpService ops;

	@Parameter
	private PrefService prefService;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Filtering:")
	private String HEADER1;

	@Parameter(label = "Filter", choices = {TUBENESS, FRANGI, GAUSS, MEDIAN, NONE }, callback = "filterChanged")
	private String filter = TUBENESS;

	@Parameter(label = "Scale(s)", required = false, //
			description = "<HTML>Aprox. thickness (radius) of structures being traced (comma separated list).<br>There are two ways "
					+ "of setting this field: Using <i>Select visually...</i> for an<br>interactive prompt, or manually, if you "
					+ "know a priori such thicknesses.<br>Use <i>Estimate Programmatically</i> for a prediction/estimation of radii.")
	private String sizeOfStructuresString;

	@Parameter(label = "Select visually...", callback="triggerSigmaPalette")
	private Button triggerSigmaPalette;
	
	@Parameter(label = "Estimate Programmatically...", description="<HTML>Predict radii of neurites using <i>Local Thickness</i> analysis", callback="triggerThicknessCmd")
	private Button triggerThicknessCmd;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Computation:")
	private String HEADER2;

	@Parameter(label = "Strategy", choices = { LAZY_LOADING_FALSE, LAZY_LOADING_TRUE }, //
			description = "<HTML><b>Preprocess</b>: Cache entire image in RAM for fast searches<br>"
					+ "<b>Compute while tracing</b>: Allows for tracing of large images when available RAM is limited",
			callback = "useLazyChoiceChanged")
	private String useLazyChoice;

	@Parameter(label = "Precision", choices = { FLOAT, DOUBLE })
	private String outputType = FLOAT;

	@Parameter(label = "No. of threads", min = "1", stepSize = "1")
	private int numThreads;

	@Parameter(label = "Display image", callback = "showChoiceChanged",
			description="<HTML>Requires strategy to be <i>Preprocess</i>")
	private boolean show;

	@Parameter(label = "Defaults", callback = "defaults")
	private Button defaults;

	@Parameter(label = "   Run   ", callback = "runCommand", //
			description="<HTML>Dismiss this prompt and generate/load filtered image")
	private Button run;

	// Used by the scripting API
	@Parameter(required=false, persist=false)
	private boolean calledFromScript;
	@Parameter(required=false, persist=false)
	private Object syncObject;


	private Img<U> filteredImg;
	private boolean useLazy;
	private List<Double> sigmas;
	private int paletteStatus = PALETTE_CLOSED;

	//HACKS:
	private JDialog prompt;
	private AbstractButton triggerSigmaPaletteAsSwingButton;
	private JTextField sizeOfStructuresStringAsSwingField;


	protected void init() {
		getInfo().setLabel(PROMPT_TITLE); // needs to be set before any getPrompt() calls
		super.init(true);
		if (!snt.accessToValidImageData()) {
			error("Valid image data is required for computation.");
			return;
		}
		if (calledFromScript) {
			numThreads = SNTPrefs.getThreads();
			resolveInput("numThreads");
			useLazyChoice = LAZY_LOADING_FALSE;
			resolveInput("useLazyChoice");
			outputType = FLOAT;
			resolveInput("outputType");
			show = false;
			resolveInput("show");
			resolveInput("HEADER1");
			resolveInput("HEADER2");
			resolveInput("HEADER3");
			resolveInput("triggerSigmaPalette");
			resolveInput("triggerThicknessCmd");
			resolveInput("defaults");
			resolveInput("run");
			snt.setCanvasLabelAllPanes("Running " + filter + "....");
		}
		resolveInput("calledFromScript");
		resolveInput("syncObject");
		loadPreferences();
	}

	@SuppressWarnings("unused")
	private void showChoiceChanged() {
		if (show && LAZY_LOADING_TRUE.equals(useLazyChoice)) {
			msg("This option is only available when strategy is '" + LAZY_LOADING_FALSE +"'.", "Invalid Option");
			show = false;
		}
	}

	@SuppressWarnings("unused")
	private void useLazyChoiceChanged() {
		if (LAZY_LOADING_TRUE.equals(useLazyChoice)) {
			show = false;
		}
	}

	@SuppressWarnings("unused")
	private void filterChanged() {
		if (!NONE.equals(filter) && paletteStatus == PALETTE_IS_RUNNING) {
			msg("'Pick Sigmas' won't capture new choice unless closed and reopened.", "New Choice Not Previewed");
		}
		switch (filter) {
			case TUBENESS:
			case TUBENESS_ALT:
				snt.setFilterType(SNT.FilterType.TUBENESS);
				break;
			case FRANGI:
			case FRANGI_ALT:
				snt.setFilterType(SNT.FilterType.FRANGI);
				break;
			case GAUSS:
			case GAUSS_ALT:
				snt.setFilterType(SNT.FilterType.GAUSS);
				break;
			case MEDIAN:
			case MEDIAN_ALT:
				snt.setFilterType(SNT.FilterType.MEDIAN);
				useLazyChoice = LAZY_LOADING_FALSE;
				break;
			default:
				// do nothing
		}
	}

	@SuppressWarnings("unused")
	private void triggerSigmaPalette() {
		getPrompt(); // attach WindowAdapter listeners if not attached by now
		if (NONE.equals(filter)) {
			msg("Current filter does not require size parameters.", "Unnecessary Operation");
			return;
		}
		switch (paletteStatus) {
		case PALETTE_CLOSED:
			ui.setSigmaPaletteListener(this);
			ui.changeState(SNTUI.WAITING_FOR_SIGMA_POINT_I);
			paletteStatus = PALETTE_WAITING;
			break;
		case PALETTE_WAITING:
			msg("Click on a representative structure (e.g., branch point or neurite) on the image being "
					+ "traced. Once you have done so, a preview grid with several kernel sizes will be "
					+ "displayed, allowing you to better select the size(s) for the filtering operation.<br><br>"
					+ "If you have never used the preview grid before, you can press 'H' (<u>H</u>elp) "
					+ "once it opens to access its built-in documentation.",//
					"Click on a Representative Structure: How-To");
			return;
		default:
			break; // do nothing;
		}
		updatePrompt();
	}

	@SuppressWarnings("unused")
	private void triggerThicknessCmd() {
		try {
			final CommandService cmdService = getContext().getService(CommandService.class);
			cmdService.run(LocalThicknessCmd.class, true, (Map<String, Object>)null);
		} catch (final Exception e) {
			e.printStackTrace();
			new GuiUtils(getPrompt()).error(e.getMessage()+". See Console for details.");
		}
	}

	private void updatePrompt() {
		final MutableModuleItem<Button> mmi = getInfo().getMutableInput("triggerSigmaPalette", Button.class);
		switch (paletteStatus) {
		case PALETTE_IS_RUNNING:
			mmi.setLabel("Adjusting scale(s) visually...");
			//mmi.setDescription("Scale(s) are being chosen in palette");
			break;
		case PALETTE_WAITING:
			mmi.setLabel("Now click on a representative structure...");
			//mmi.setDescription("Once you click on the image, a preview of clicked neighborhood will open");
			break;
		default:
			mmi.setLabel("Select visually...");
			//mmi.setDescription("Initialize preview palette");
			break;
		}
		sizeOfStructuresString = getSigmasAsString();

		// The label of the mmi button only changes when the user _actually_ interacts
		// with the prompt. We need it to update it consistently to avoid ill-states.
		// 'typing' into the textfield seems to work:
		if (triggerSigmaPaletteAsSwingButton != null
				&& !mmi.getLabel().equals(triggerSigmaPaletteAsSwingButton.getText())) {
			triggerSigmaPaletteAsSwingButton.setText(mmi.getLabel());
		}
		if (sizeOfStructuresStringAsSwingField != null
				&& !sizeOfStructuresStringAsSwingField.getText().equals(sizeOfStructuresString)) {
			SwingUtilities.invokeLater(() -> {
				sizeOfStructuresStringAsSwingField.requestFocusInWindow();
				sizeOfStructuresStringAsSwingField.setText(sizeOfStructuresString);
				sizeOfStructuresStringAsSwingField
						.setCaretPosition(sizeOfStructuresStringAsSwingField.getText().length());
				final KeyEvent ke = new KeyEvent(sizeOfStructuresStringAsSwingField, KeyEvent.KEY_TYPED,
						System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, ' ');
				sizeOfStructuresStringAsSwingField.dispatchEvent(ke);
			});
		}

	}

	@SuppressWarnings("unused")
	private void defaults() {
		numThreads = SNTPrefs.getThreads();
		outputType = FLOAT;
		useLazyChoice = LAZY_LOADING_FALSE;
		setDefaultSigmas();
		sizeOfStructuresString = getSigmasAsString();
		show = false;
	}

	private void setDefaultSigmas() {
		sigmas = DoubleStream.of(SigmaUtils.getDefaultSigma(snt)).boxed().collect(Collectors.toList());
	}

	private void exit(final boolean savePrefs) {
		if (getPrompt() != null) getPrompt().dispose();
		if (calledFromScript)
			snt.setCanvasLabelAllPanes(null);
		if (savePrefs)
			savePreferences();
		if (ui != null) ui.changeState(SNTUI.READY);
		prompt = null;
		if (syncObject != null) {
			synchronized (syncObject) {
				syncObject.notifyAll();
			}
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		if (calledFromScript) runCommand();
	}

	@SuppressWarnings({ "unchecked" })
	private void runCommand() {
		if (isCanceled() || !snt.accessToValidImageData()) {
			exit(false);
			return;
		}
		getPrompt(); // otherwise it may be only called when user interacts with _some_ input widgets
		final double[] sigmas = getSigmasAsArray();
		if (!NONE.equals(filter) && (sigmas == null || sigmas.length == 0)) {
			error("No scales have been specified.");
			return;
		}
		if (numThreads > SNTPrefs.getThreads())
			numThreads = SNTPrefs.getThreads();
		useLazy = LAZY_LOADING_TRUE.equals(useLazyChoice);
		if (NONE.equals(filter)) {
			final RandomAccessibleInterval<T> loadedData = getInputData();
			final Img<T> copy = ops.create().img(loadedData);
			Images.copy(loadedData, copy);
			filteredImg = (Img<U>) copy;
			apply();
			return;
		}
		// validate inputs
		if (MEDIAN.equals(filter) && useLazy) {
			error("Current filter is not compatible with the current performance strategy.");
			return;
		}
		if (sigmas.length > 1 && (MEDIAN.equals(filter) || GAUSS.equals(filter))) {
			msg("Only the first scale ('" + SNTUtils.formatDouble(sigmas[0], 2) + "') will be considered.",
					"Single-scale Filter Chosen");
		}
		final RandomAccessibleInterval<T> in = getInputData();
		final Calibration cal = sntService.getInstance().getCalibration();
		final double[] spacing = new double[]{cal.pixelWidth, cal.pixelHeight, cal.pixelDepth};
		final U type = switch (outputType) {
            case FLOAT -> (U) new FloatType();
            case DOUBLE -> (U) new DoubleType();
            default -> throw new IllegalArgumentException("Unknown output type");
        };
        final int cellDim = 32; // side length for cell
		final Img<U> out;
		switch (filter) {
			case FRANGI:
			case FRANGI_ALT: {
				final double stackMax = sntService.getInstance().getStats().max;
				if (stackMax == 0) {
					new GuiUtils().error("Statistics for the main image have not been computed yet. "
							+ "Please trace a small path over a relevant feature to compute them. "
							+ "This will allow SNT to better understand the dynamic range of the image.");
					return;
				}
				final Frangi<T, U> op = new Frangi<>(
						sigmas,
						spacing,
						sntService.getInstance().getStats().max,
						numThreads);

				if (useLazy) {
					out = Lazy.process(
							in,
							in,
							new int[]{cellDim, cellDim, cellDim},
							type,
							op);
				} else {
					out = ops.create().img(in, type, new CellImgFactory<>(type));
					op.compute(in, out);
				}

				break;
			}
			case TUBENESS:
			case TUBENESS_ALT: {
				final Tubeness<T, U> op = new Tubeness<>(sigmas, spacing, numThreads);
				if (useLazy) {
					out = Lazy.process(
							in,
							in,
							new int[]{cellDim, cellDim, cellDim},
							type,
							op);
				} else {
					out = ops.create().img(in, type, new CellImgFactory<>(type));
					op.compute(in, out);
				}

				break;
			}
			case GAUSS:
			case GAUSS_ALT: {
				final double sig = sigmas[0]; // just pick the first sigma I guess...
				if (useLazy) {
					out = Lazy.process(
							in,
							in,
							new int[]{cellDim, cellDim, cellDim},
							type,
							ops,
							net.imagej.ops.filter.gauss.DefaultGaussRAI.class,
							(Object) new double[]{sig / spacing[0], sig / spacing[1], sig / spacing[2]});
				} else {
					out = ops.create().img(in, type, new CellImgFactory<>(type));
					ops.filter().gauss(
							out,
							in,
							sig / spacing[0], sig / spacing[1], sig / spacing[2]);
				}

				break;
			}
			case MEDIAN:
			case MEDIAN_ALT: {
				final double sig = sigmas[0]; // just pick the first sigma
				int radius;
				if (in.numDimensions() == 2) {
					radius = (int) Math.round((sig/spacing[0] + sig/spacing[1]) / 2.0);
				} else {
					radius = (int) Math.round((sig/spacing[0] + sig/spacing[1] + sig/spacing[2]) / 3.0);
				}
				Img<T> tmp = ops.create().img(
						in,
						in.getType(),
						new ArrayImgFactory<>(in.getType()));
				ops.filter().median(tmp, in, new DiamondShape(radius));
				out = (Img<U>) tmp;
				break;
			}
			default:
				throw new IllegalArgumentException("Unrecognized filter " + filter);
		}

		filteredImg = out;
		if (ui != null && ui.getRecorder(false) != null) {
			ui.getRecorder(false).recordCmd(
					"snt.getUI().runSecondaryLayerWizard(\"" + filter + "\", " + Arrays.toString(sigmas) + ")");
		}
		apply();
	}

	private void apply() {
		// flush any rogue Img
		snt.flushSecondaryData();
		snt.loadSecondaryImage(filteredImg, false);
		snt.setUseSubVolumeStats(true);
		if (show) {
			final ImagePlus imp = ImgUtils.raiToImp(filteredImg, getImageName());
			imp.copyScale(sntService.getInstance().getImagePlus());
			imp.resetDisplayRange();
			imp.show();
		}
		exit(true);
	}

	private RandomAccessibleInterval<T> getInputData() {
		// could be modified to accept the original image with all channels, frames
		return sntService.getInstance().getLoadedData();
	}

	private String getImageName() {
		final String basename = SNTUtils.stripExtension(sntService.getInstance().getImagePlus().getTitle());
		final String sfx = (NONE.equals(filter)) ? "DUP" : filter.substring(0, filter.indexOf(" ("));
		return basename + " [" + sfx + "].tif";
	}

	private double[] getSigmasAsArray() {
		if (sizeOfStructuresString != null && !sizeOfStructuresString.trim().isEmpty()) {
			// interactive prompt: values may have been set by the user, the sigma palette, or both.
			// The easiest is to simply read the values in the field, even if we may be loosing
			// precision from the ping-pong between  double>String>double conversion
			try {
				final Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(sizeOfStructuresString.trim());
				sigmas = new ArrayList<>();
				while (matcher.find()) {
					sigmas.add(Double.valueOf(matcher.group()));
				}
				return sigmas.stream().mapToDouble(Double::doubleValue).toArray();
			} catch (final NumberFormatException e) {
				return null;
			}
		} else if (sigmas != null) { // values have been set from the sigma palette only!?
			return sigmas.stream().mapToDouble(Double::doubleValue).toArray();
		}
		// else no values exist.
		return null;
	}

	private String getSigmasAsString() {
		if (sigmas == null)
			setDefaultSigmas();
		final DecimalFormat df = new DecimalFormat("0.000");
		final StringBuilder sb = new StringBuilder();
		sigmas.forEach(s -> sb.append(df.format(s)).append(", "));
		sb.setLength(sb.length() - 2);
		return sb.toString();
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
		// HACK: There is no guarantee that this will work. In the future prompt may not
		// even be a Swing dialog but there is no other way to access the prompt itself!?
		if (prompt == null) {
			for (final Window w : JDialog.getWindows()) {
				if (w instanceof JDialog && PROMPT_TITLE.equals(((JDialog) w).getTitle())) {
					prompt = ((JDialog) w);
					triggerSigmaPaletteAsSwingButton = getFirstComponent(prompt, AbstractButton.class);
					sizeOfStructuresStringAsSwingField = getFirstComponent(prompt, JTextField.class);
					prompt.addWindowListener(new WindowAdapter() {
						@Override
						public void windowClosing(final WindowEvent ignored) {
							if (ui != null) {
								ui.setSigmaPaletteListener(null);
								ui.changeState(SNTUI.READY);
							}
						}

						@Override
						public void windowLostFocus(final WindowEvent e) {
							updatePrompt();
						}

						@Override
						public void windowGainedFocus(final WindowEvent e) {
							updatePrompt();
						}

						@Override
						public void windowDeactivated(final WindowEvent e) {
							updatePrompt();
						}
					});
					break;
				}
			}
		}
		return prompt;
	}

	
	@SuppressWarnings("unchecked")
	private static <J extends JComponent> J getFirstComponent(final Container parent, final Class<J> c) {
		final Deque<Component> stack = new ArrayDeque<>();
		stack.push(parent);
		while (!stack.isEmpty()) {
			final Component current = stack.pop();
			if (c.isInstance(current)) {
				return (J) current;
			}
			else if (current instanceof Container) {
				stack.addAll(Arrays.asList(((Container) current).getComponents()));
			}
		}
		return null;
	}

	@Override
	public void paletteDisplayed() {
		paletteStatus = PALETTE_IS_RUNNING;
		updatePrompt();
		ui.changeState(SNTUI.RUNNING_CMD); // exit SNTUI.WAITING_FOR_SIGMA_POINT_I
	}

	@Override
	public void paletteDismissed() {
		paletteStatus = PALETTE_CLOSED;
		updatePrompt();
		ui.setSigmaPaletteListener(null);
	}

	@Override
	public Window getParent() {
		return getPrompt();
	}

	@Override
	public void setSigmas(final List<Double> list) {
		this.sigmas = list;
		sigmas.sort(Comparator.naturalOrder());
		updatePrompt();
	}

	@Override
	protected void msg(final String msg, final String title) {
		if (getPrompt() == null) {
			super.msg(msg, title);
		} else {
			new GuiUtils(getPrompt()).centeredMsg(msg, title);
		}
	}

	@Override
	protected void error(final String msg) {
		if (getPrompt() == null) {
			super.error(msg);
		} else {
			new GuiUtils(getPrompt()).error(msg);
		}
		exit(false);
	}

	private void loadPreferences() {
		filter = prefService.get(getClass(), "filter", TUBENESS);
		sizeOfStructuresString = prefService.get(getClass(), "sizeOfStructuresString", getSigmasAsString());
		useLazyChoice = prefService.get(getClass(), "useLazyChoice", LAZY_LOADING_FALSE);
		numThreads = prefService.getInt(getClass(), "numThreads", SNTPrefs.getThreads());
		show = prefService.getBoolean(getClass(), "show", false);
	}

	private void savePreferences() {
		prefService.put(getClass(), "filter", filter);
		prefService.put(getClass(), "sizeOfStructuresString", sizeOfStructuresString);
		prefService.put(getClass(), "useLazyChoice", useLazyChoice);
		prefService.put(getClass(), "numThreads", numThreads);
		prefService.put(getClass(), "show", show);
	}


	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(ComputeSecondaryImg.class, true);
	}

	//FIXME: SigmaPalette does not update when filter choice changes.
	//SigmaPaletterListener could be used to set the updated filter

}
