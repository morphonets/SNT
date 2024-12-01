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

package sc.fiji.snt.plugin.ij1;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.io.OpenDialog;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import sc.fiji.snt.NearPoint;
import sc.fiji.snt.Path;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.EnhancedGenericDialog;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.ShollAnalysisDialog;
import sc.fiji.snt.gui.ShollAnalysisDialog.ShollPoint;
import sc.fiji.snt.gui.ShollAnalysisDialog.ShollResults;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.util.PointInImage;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Vector;
import java.util.regex.Matcher;

/**
 * Implements the IJ1 "Sholl Analysis" (deprecated) command.
 *
 * @author Tiago Ferreira
 */
@Deprecated
public class ShollAnalysisPlugin implements PlugIn, DialogListener {

	private static final int START_FIRST_PRIMARY = 0;
	private static final int CENTER_OF_SOMA = 1;
	private static final int START_FIRST_AXON = 2;
	private static final int START_FIRST_DENDRITE = 3;
	private static final int START_FIRST_APICAL_DENDRITE = 4;
	private static final int START_FIRST_CUSTOM = 5;
	// NB: Indices of CENTER_CHOICES labels must reflect defined constants
	private static final String[] CENTER_CHOICES = new String[] {
		"Start of main path", "Center of soma", "Start of main path: Axon",
		"Start of main path: (Basal) Dendrite", "Start of main path: Dendrite",
		"Start of main path: Custom" };

	private EnhancedGenericDialog gd;
	private Label infoMsg;
	private static final String defaultInfoMsg = getDefaultInfoMessage();
	private boolean debug;

	private String tracesPath;
	private String imgPath;
	private boolean impRequired;
	private boolean restrictBySWCType;
	private int centerChoice;
	private double radiusStepSize;
	private ImagePlus imp;
	private PathAndFillManager pafm;
	private ArrayList<ShollPoint> shollPoints;
	private ArrayList<Integer> swcTypeCodes;

	public static void main(final String[] args) {
		new ij.ImageJ(); // start ImageJ
		final ShollAnalysisPlugin plugin = new ShollAnalysisPlugin();
		plugin.run("");
	}

	private static String getDefaultInfoMessage() {
		return "Running " + "Sholl Analysis v" + Sholl_Analysis.VERSION +
			" / SNT v" + SNTUtils.VERSION + "...";
	}

	@Override
	public void run(final String ignoredArgument) {

		if (!showDialog()) return;

		imp = (impRequired) ? ImpUtils.open(imgPath) : null;
		if (impRequired && imp == null || !validTracesFile(new File(tracesPath))) {
			GuiUtils.errorPrompt("Invalid image or invalid Traces/(e)SWC file\n \n" +
				imgPath + "\n" + tracesPath);
			return;
		}

		if (impRequired) {
			final Calibration cal = imp.getCalibration();
			pafm = new PathAndFillManager(cal.pixelWidth, cal.pixelHeight,
				cal.pixelDepth, cal.getUnit());
		}
		pafm = new PathAndFillManager();
		if (!pafm.load(tracesPath)) {
			GuiUtils.errorPrompt("File could not be loaded:\n" + tracesPath);
			return;
		}

		OpenDialog.setLastDirectory(new File(tracesPath).getParent());
		final Path[] primaryPaths = pafm.getPathsStructured();
		PointInImage shollCenter = null;

		switch (centerChoice) {
			case START_FIRST_PRIMARY:
				shollCenter = primaryPaths[0].getNode(0);
				break;
			case CENTER_OF_SOMA:
				final ArrayList<PointInImage> somaPoints = new ArrayList<>();
				for (final Path p : primaryPaths) {
					if (p.getSWCType() == Path.SWC_SOMA) {
						for (int i = 0; i < p.size(); i++)
							somaPoints.add(p.getNode(i));
					}
					double sumx = 0, sumy = 0, sumz = 0;
					for (final PointInImage sp : somaPoints) {
						sumx += sp.x;
						sumy += sp.y;
						sumz += sp.z;
					}
					final NearPoint np = pafm.nearestPointOnAnyPath(sumx / somaPoints
						.size(), sumy / somaPoints.size(), sumz / somaPoints.size(), sumx +
							sumy + sumz);
					if (np != null && np.getPath() != null) shollCenter = np.getPath()
						.getNode((np.getPath().size() - 1) / 2);
				}
				break;
			case START_FIRST_AXON:
				shollCenter = getFirstPathPoint(primaryPaths, Path.SWC_AXON);
				break;
			case START_FIRST_DENDRITE:
				shollCenter = getFirstPathPoint(primaryPaths, Path.SWC_DENDRITE);
				break;
			case START_FIRST_APICAL_DENDRITE:
				shollCenter = getFirstPathPoint(primaryPaths, Path.SWC_APICAL_DENDRITE);
				break;
			case START_FIRST_CUSTOM:
				shollCenter = getFirstPathPoint(primaryPaths, Path.SWC_CUSTOM);
				break;
			default:
				throw new RuntimeException(
					"BUG: Somehow center choice was not understood");
		}

		if (swcTypeCodes.isEmpty()) restrictBySWCType = false;

		if (shollCenter != null) {

			shollPoints = new ArrayList<>();
			int chosenPaths = 0;
			double maxDepth = 0d;
			for (Path p : pafm.getPaths()) {
				if (p.getUseFitted()) p = p.getFitted();
				else if (p.isFittedVersionOfAnotherPath()) continue;

				if (!restrictBySWCType || (restrictBySWCType && swcTypeCodes.contains(p
					.getSWCType())))
				{
					chosenPaths++;
					final double lastPointDepth = p.getZUnscaledDouble(p.size() - 1);
					if (lastPointDepth > maxDepth) maxDepth = lastPointDepth;
					ShollAnalysisDialog.addPathPointsToShollList(p, shollCenter.x,
						shollCenter.y, shollCenter.z, shollPoints);
				}
			}

			if (shollPoints.size() == 0) {
				if (!restrictBySWCType) throw new RuntimeException(
					"BUG: Somehow could not load Sholl Points when loading " +
						tracesPath);
				IJ.error("No Data",
					"No paths matched the selected filtering options:\n\"" +
						swcTypeCodesToString() + "\"");
			}

			final boolean threeD = maxDepth > 0d;
			final File analyzedFile = new File(tracesPath);
			final ShollResults sr = new ShollResults(shollPoints, imp, true,
				chosenPaths, shollCenter.x, shollCenter.y, shollCenter.z, analyzedFile
					.getName(), ShollAnalysisDialog.AXES_NORMAL,
				ShollAnalysisDialog.NOT_NORMALIZED, radiusStepSize, threeD);

			final double[] distances = sr.getSampledDistances();
			final double[] counts = sr.getSampledCounts();
			if (distances == null || counts == null || (distances.length == 1 &&
				distances[0] == 0d))
			{
				IJ.error("No Data", "No data retrieved using radius step size of " + IJ
					.d2s(radiusStepSize, 3) + " for\n" + tracesPath);
				return;
			}

			final Sholl_Analysis sa = new Sholl_Analysis();
			sa.setExportPath(analyzedFile.getParent());
			sa.setDescription(analyzedFile.getName() + " " + swcTypeCodesToString() +
				" (" + pointToString(shollCenter) + ")", false);
			sa.setUnit(pafm.getBoundingBox(false).getUnit());
			sa.setPrimaryBranches(primaryPaths.length);
			sa.setStepRadius(radiusStepSize);
			if (impRequired) {
				final Calibration cal = imp.getCalibration();
				if (cal != null) {
					final int pX = (int) cal.getRawX(shollCenter.x);
					final int pY = (int) cal.getRawY(shollCenter.y, imp.getHeight());
					final int pZ = (int) (shollCenter.z / cal.pixelDepth + cal.zOrigin);
					sa.setCenter(pX, pY, pZ);
				}
			}
			else sa.setCenter(-1, -1, -1);
			sa.analyzeProfile(distances, counts, threeD);

		}
		else {

			final String msg = "No points associated with \"" +
				CENTER_CHOICES[centerChoice] +
				"\". You can either re-run with \ncorrected settings or analyze the file interactively in SNT.";
			IJ.error("Error: Center Point Not Found", msg);
			return;

		}

	}

	private PointInImage getFirstPathPoint(final Path[] paths,
		final int swcType)
	{
		for (final Path p : paths) {
			if (p.getSWCType() == swcType) return p.getNode(0);
		}
		return null;
	}

	private boolean showDialog() {

		if (!IJ.macroRunning() && (imgPath == null || tracesPath == null || imgPath
			.isEmpty() || tracesPath.isEmpty())) guessInitialPaths();

		gd = new EnhancedGenericDialog("Sholll Analysis (Tracings)...");
		gd.addFileField("Traces/(e)SWC file", tracesPath, 32);
		gd.addFileField("Image file", imgPath, 32);
		gd.setInsets(0, 40, 20);
		gd.addCheckbox("Load tracings without image", !impRequired);
		gd.addChoice("Center", CENTER_CHOICES, CENTER_CHOICES[centerChoice]);
		gd.addNumericField("Radius step size", radiusStepSize, 2, 5,
			"(Zero for continuous sampling)");

		// Assemble SWC choices
		final ArrayList<String> swcTypeNames = Path.getSWCtypeNames();
		final int nTypes = swcTypeNames.size();
		final String[] typeNames = swcTypeNames.toArray(new String[nTypes]);
		final boolean[] typeChoices = new boolean[nTypes];
		for (int i = 0; i < nTypes; i++)
			typeChoices[i] = typeNames[i].contains("dendrite");
		swcTypeCodes = new ArrayList<>();
		gd.setInsets(20, 40, 0);
		gd.addCheckbox("Include_only paths tagged with the following SWC labels:",
			restrictBySWCType);
		gd.setInsets(0, 100, 0);
		gd.addCheckboxGroup(nTypes / 2, 2, typeNames, typeChoices);

		gd.addMessage(defaultInfoMsg);
		infoMsg = (Label) gd.getMessage();
		gd.setInsets(10, 70, 0);
		gd.addCitationMessage();
		gd.assignPopupToHelpButton(createMenu());
		gd.addDialogListener(this);
		dialogItemChanged(gd, null);
		gd.showDialog();
		if (gd.wasCanceled()) return false;
		else if (gd.wasOKed()) {
			EnhancedGenericDialog.improveRecording();
			return dialogItemChanged(gd, null);
		}
		return false;

	}

	/** Creates optionsMenu */
	private JPopupMenu createMenu() {
		final JPopupMenu popup = new JPopupMenu();
		JMenuItem mi;
		mi = new JMenuItem(ShollOptions.COMMAND_LABEL);
		mi.addActionListener(e -> {
			final Thread newThread = new Thread(() -> {
				if (Recorder.record) Recorder.setCommand(ShollOptions.COMMAND_LABEL);
				IJ.runPlugIn(ShollOptions.class.getName(),
					ShollOptions.SKIP_BITMAP_OPTIONS_LABEL);
				if (Recorder.record) Recorder.saveCommand();
			});
			newThread.start();
		});
		popup.add(mi);
		popup.addSeparator();
		mi = EnhancedGenericDialog.menuItemTrigerringURL("Online Documentation",
			Sholl_Analysis.URL + "#Traces");
		popup.add(mi);
		mi = EnhancedGenericDialog.menuItemTriggeringResources();
		popup.add(mi);
		return popup;
	}

	@Override
	public boolean dialogItemChanged(final GenericDialog arg0,
		final AWTEvent event)
	{

		// The 'Browse...' action will call OpenDialog that is macro recordable
		// and will always generate the same (non-unique) command option 'key='.
		// We'll need to nullify such calls or he Recorder will keep complaining
		// every time the 'Browse...' button is pressed
		if (Recorder.record && event != null && event.toString().contains(
			"textfield"))
		{
			final String commandName = Recorder.getCommand();
			final String commandOptions = Recorder.getCommandOptions();
			if (commandName != null && commandOptions != null && commandOptions
				.contains("browse"))
			{
				Recorder.setCommand(commandName);
				return true;
			}
		}

		tracesPath = normalizedPath(gd.getNextString());
		imgPath = normalizedPath(gd.getNextString());
		impRequired = !gd.getNextBoolean();
		centerChoice = gd.getNextChoiceIndex();
		radiusStepSize = gd.getNextNumber();
		restrictBySWCType = gd.getNextBoolean();
		if (restrictBySWCType) {
			swcTypeCodes.clear();
			for (final int type : Path.getSWCtypes()) {
				// At this point there is a checkbox for each type
				if (gd.getNextBoolean()) swcTypeCodes.add(type);
			}
		}
		final Vector<?> cbxs = gd.getCheckboxes();
		for (int i = 2; i < cbxs.size(); i++)
			((Checkbox) cbxs.get(i)).setEnabled(restrictBySWCType);

		// boolean enableOK = true;
		String warning = "";

		if (impRequired && !validImageFile(new File(imgPath))) {
			// enableOK = false;
			warning += "Not a valid image. ";
		}
		if (!validTracesFile(new File(tracesPath))) {
			// enableOK = false;
			warning += "Not a valid .traces/.(e)swc file";
		}
		if (!warning.isEmpty()) {
			infoMsg.setForeground(EnhancedGenericDialog.warningColor());
			infoMsg.setText("Error: " + warning);
		}
		else {
			infoMsg.setForeground(EnhancedGenericDialog.infoColor());
			infoMsg.setText(defaultInfoMsg);
		}

		return true; // enableOK
	}

	private String getFilePathWithoutExtension(final String filePath) {
		final int index = filePath.lastIndexOf(".");
		if (index > -1) return filePath.substring(0, index);
		return filePath;
	}

	private void guessInitialPaths() {
		final String lastDirPath = OpenDialog.getLastDirectory();
		if (lastDirPath != null && !lastDirPath.isEmpty()) {
			final File lastDir = new File(lastDirPath);
			final File[] tracing_files = lastDir.listFiles(file -> !file
				.isHidden() && tracingsFile(file));
			if (tracing_files != null && tracing_files.length > 0) {
				Arrays.sort(tracing_files);
				tracesPath = tracing_files[0].getAbsolutePath();
				final File[] image_files = lastDir.listFiles(file -> !file
					.isHidden() && expectedImageFile(file));
				if (image_files != null && image_files.length > 0) {
					Arrays.sort(image_files);
					imgPath = image_files[0].getAbsolutePath();
				}
			}
			if (debug && !getFilePathWithoutExtension(imgPath).equals(
				getFilePathWithoutExtension(tracesPath))) SNTUtils.log(
					"Could not pair image to traces file:\n" + imgPath + "\n" +
						tracesPath);
		}
	}

	private boolean expectedImageFile(final File file) {
		final String[] knownImgExts = new String[] { ".tif", ".tiff" };
		for (final String ext : knownImgExts)
			if (file.getName().toLowerCase().endsWith(ext)) return true;
		final String[] knownNonImgExts = new String[] { ".txt", ".csv", ".xls",
			",xlxs", ".ods", ".md" };
		for (final String ext : knownNonImgExts)
			if (file.getName().toLowerCase().endsWith(ext)) return false;
		return true;
	}

	private boolean tracingsFile(final File file) {
		final String[] tracingsExts = new String[] { ".traces", ".swc", ".eswc" };
		for (final String ext : tracingsExts)
			if (file.getName().toLowerCase().endsWith(ext)) return true;
		return false;
	}

	private boolean validFile(final File file) {
		return file != null && file.isFile() && file.exists();
	}

	private boolean validImageFile(final File file) {
		return validFile(file) && expectedImageFile(file);
	}

	private boolean validTracesFile(final File file) {
		return validFile(file) && tracingsFile(file);
	}

	/*
	 * FileFields in GenericDialogPlus can retrieve paths with repeated slashes.
	 * We'll need to remove those to avoid I/O errors.
	 */
	private String normalizedPath(final String path) {
		// chase-seibert.github.io/blog/2009/04/10/java-replaceall-fileseparator.html
		return path.replaceAll("(?<!^)([\\\\/]){2,}", Matcher.quoteReplacement(
			File.separator));
	}

	private String swcTypeCodesToString() {
		if (swcTypeCodes == null || swcTypeCodes.size() == 0) return "no-filtering";
		final StringBuilder sb = new StringBuilder();
		for (final int type : swcTypeCodes)
			sb.append(Path.getSWCtypeName(type, false)).append("+");
		sb.replace(sb.length() - 1, sb.length(), "");
		return sb.toString();
	}

	private String pointToString(final PointInImage p) {
		if (p == null) return "null";
		final StringBuilder sb = new StringBuilder();
		sb.append(IJ.d2s(p.x, 3)).append(",");
		sb.append(IJ.d2s(p.y, 3)).append(",");
		sb.append(IJ.d2s(p.z, 3));
		return sb.toString();
	}

}
