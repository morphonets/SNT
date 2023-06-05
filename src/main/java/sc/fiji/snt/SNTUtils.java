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

package sc.fiji.snt;

import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.scif.services.DatasetIOService;
import net.imagej.display.ImageDisplayService;
import net.imagej.lut.LUTService;
import net.imagej.ops.OpService;
import org.scijava.Context;
import org.scijava.app.StatusService;
import org.scijava.command.CommandService;
import org.scijava.convert.ConvertService;
import org.scijava.display.DisplayService;
import org.scijava.io.IOService;
import org.scijava.log.LogService;
import org.scijava.plot.PlotService;
import org.scijava.prefs.PrefService;
import org.scijava.script.ScriptService;
import org.scijava.table.Table;
import org.scijava.table.io.TableIOService;
import org.scijava.thread.ThreadService;
import org.scijava.ui.UIService;
import org.scijava.ui.console.ConsolePane;
import org.scijava.util.FileUtils;
import org.scijava.util.VersionUtils;

import fiji.util.Levenshtein;
import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.plugin.Colors;
import ij.plugin.ContrastEnhancer;
import ij.plugin.ZProjector;
import ij.process.ImageConverter;
import ij.process.LUT;
import ij.process.StackConverter;
import net.imglib2.display.ColorTable;
import sc.fiji.snt.analysis.sholl.ShollUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.util.BoundingBox;
import sc.fiji.snt.viewer.Viewer3D;

/** Static utilities for SNT **/
public class SNTUtils {

	/*
	 * NB: This pattern needs to be OS agnostic: I.e., Microsoft Windows does not
	 * support colons in filenames
	 */
	private static final String TIMESTAMP_PATTERN = "'_D'yyyy-MM-dd'T'HH-mm-ss";
	private static final String TIMESTAMP_REGEX = "(.+?)_D(\\d{4}-\\d{2}-\\d{2})T(\\d{2}-\\d{2}-\\d{2})";
	private static Context context;
	private static LogService logService;

	public static final String VERSION = getVersion();

	private static boolean initialized;
	private static SNT plugin;
	private static HashMap<Integer, Viewer3D> viewerMap;

	private SNTUtils() {}

	private static synchronized void initialize() {
		if (initialized) return;
		if (context == null) getContext();
		if (logService == null) logService = context.getService(LogService.class);
		initialized = true;
	}

	public static String getReadableVersion() {
		if (VERSION.length() < 21) return "SNT " + VERSION;
		return "SNT " + VERSION.substring(0, 21) + "...";
	}

	/**
	 * Retrieves SNT's version
	 *
	 * @return the version or a non-empty place holder string if version could
	 *         not be retrieved.
	 *
	 */
	private static String getVersion() {
		try {
			return VersionUtils.getVersion(SNT.class);
		} catch (final Exception | Error ignored) {
			return "N/A";
		}
	}

	public static synchronized void addViewer(final Viewer3D viewer) {
		if (viewerMap == null) viewerMap = new HashMap<>();
		viewerMap.put(viewer.getID(), viewer);
	}

	public static synchronized void removeViewer(final Viewer3D viewer) {
		if (viewerMap != null && viewer != null) {
			viewerMap.entrySet().removeIf(entry -> entry.getValue().equals(viewer));
			if (plugin != null && plugin.getUI() != null) {
				final Viewer3D v = plugin.getUI().getReconstructionViewer(false);
				if (v != null && v.getID() == viewer.getID()) {
					plugin.getUI().setReconstructionViewer(null);
				}
			}
		}
	}

	protected static HashMap<Integer, Viewer3D> getViewers() {
		return viewerMap;
	}

	public static synchronized void error(final String string) {
		if (SNTUtils.isDebugMode()) nonDebugError(string);
	}

	protected static void setPlugin(final SNT plugin) {
		SNTUtils.plugin = plugin;
		if (plugin == null)
			context = null;
		else if (context == null)
			setContext(plugin.getContext());
	}

	public static SNT getPluginInstance() {
		return plugin;
	}

	protected static synchronized void nonDebugError(final String string) {
		if (!initialized) initialize();
		logService.error("[SNT] " + string);
	}

	public static synchronized void error(final String string,
		final Throwable t)
	{
		if (!SNTUtils.isDebugMode()) return;
		if (!initialized) initialize();
		if ( t == null) 
			logService.error("[SNT] " + string);
		else
			logService.error("[SNT] " + string, t);
	}

	public static synchronized void log(final String string) {
		if (!SNTUtils.isDebugMode()) return;
		if (!initialized) initialize();
		logService.info("[SNT] " + string);
	}

	protected static synchronized void warn(final String string) {
		if (!SNTUtils.isDebugMode()) return;
		if (!initialized) initialize();
		logService.warn("[SNT] " + string);
	}

	protected static void convertTo32bit(final ImagePlus imp) throws IllegalArgumentException {
		if (imp.getBitDepth() == 32)
			return;
		if (imp.getNSlices() == 1)
			new ImageConverter(imp).convertToGray32();
		else
			new StackConverter(imp).convertToGray32();
	}

	public static void convertTo8bit(final ImagePlus imp) {
		if (imp.getType() != ImagePlus.GRAY8) {
			final boolean doScaling = ImageConverter.getDoScaling();
			ImageConverter.setDoScaling(true);
			new ImageConverter(imp).convertToGray8();
			ImageConverter.setDoScaling(doScaling);
		}
	}

	public static ImagePlus getMIP(final ImagePlus imp) {
		final ImagePlus mip = ZProjector.run(imp, "max");
		mip.setLut(imp.getLuts()[0]);
		mip.copyScale(imp);
		new ContrastEnhancer().stretchHistogram(mip, 0.35);
		return mip;
	}

	public static void csvQuoteAndPrint(final PrintWriter pw, final Object o) {
		pw.print(stringForCSV("" + o));
	}

	private static String stringForCSV(final String s) {
		boolean quote = false;
		String result = s;
		if (s.indexOf(',') >= 0 || s.indexOf(' ') >= 0) quote = true;
		if (s.indexOf('"') >= 0) {
			quote = true;
			result = s.replaceAll("\"", "\"\"");
		}
		if (quote) return "\"" + result + "\"";
		else return result;
	}

	protected static String getColorString(final Color color) {
		//String name = "none";
		//name = Colors.getColorName(color, name);
		//if (!"none".equals(name)) name = Colors.colorToString(color);
		return Colors.colorToString2(color);
	}

	protected static Color getColor(String colorName) {
		if (colorName == null) colorName = "none";
		Color color = null;
		color = Colors.getColor(colorName, color);
		if (color == null) color = Colors.decode(colorName, color);
		return color;
	}

	public static String stripExtension(final String filename) {
		final int lastDot = filename.lastIndexOf(".");
		return (lastDot > 0) ? filename.substring(0, lastDot) : filename;
	}

	public static File getUniquelySuffixedTifFile(final File referenceFile) {
		if (referenceFile != null && !referenceFile.isDirectory() && !referenceFile.getName().endsWith(".tif")) {
			return getUniquelySuffixedFile(new File(referenceFile.getAbsolutePath() + ".tif"));
		}
		return getUniquelySuffixedFile(referenceFile);
	}

	public static File getUniquelySuffixedFile(final File referenceFile) {
		if (referenceFile.exists()) {
			final String extension = "." + FileUtils.getExtension(referenceFile);
			final String filenameWithoutExt = stripExtension(referenceFile.getName());
			return getUniqueFileName(filenameWithoutExt, extension, referenceFile.getParentFile());
		}
		return referenceFile;
	}

	private static File getUniqueFileName(String filename, final String extension, final File parent) {
		// From ij.WindowManaget#getUniqueName()
		final int lastDash = filename.lastIndexOf("-");
		final int len = filename.length();
		if (lastDash != -1 && len - lastDash < 4 && lastDash < len - 1
				&& Character.isDigit(filename.charAt(lastDash + 1)) && filename.charAt(lastDash + 1) != '0')
			filename = filename.substring(0, lastDash);
		for (int i = 1; i <= 500; i++) {
			final String newName = filename + "-" + i;
			final File putatiteUniqueFile = new File(parent, newName + extension);
			if (!putatiteUniqueFile.exists())
				return putatiteUniqueFile;
		}
		return new File(parent, filename + extension);
	}

	public static void saveTable(final Table<?, ?> table, final char columnSep, final boolean saveColHeaders,
			final boolean saveRowHeaders, final File outputFile) throws IOException {
		if(!outputFile.exists()) outputFile.getParentFile().mkdirs();
		final FileOutputStream fos = new FileOutputStream(outputFile, false);
		final OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
		final PrintWriter pw = new PrintWriter(new BufferedWriter(osw), true);
		final int columns = table.getColumnCount();
		final int rows = table.getRowCount();
		final boolean saveRows = saveRowHeaders && table.getRowHeader(0) != null;
		// Print a column header to hold row headers
		if (saveRows) {
			csvQuoteAndPrint(pw, "-");
			pw.print(columnSep);
		}
		if (saveColHeaders) {
			for (int col = 0; col < columns; ++col) {
				csvQuoteAndPrint(pw, table.getColumnHeader(col));
				if (col < (columns - 1))
					pw.print(columnSep);
			}
			pw.print("\r\n");
		}
		for (int row = 0; row < rows; row++) {
			if (saveRows) {
				csvQuoteAndPrint(pw, table.getRowHeader(row));
				pw.print(columnSep);
			}
			for (int col = 0; col < columns; col++) {
				csvQuoteAndPrint(pw, table.get(col, row));
				if (col < (columns - 1))
					pw.print(columnSep);
			}
			pw.print("\r\n");
		}
		pw.close();
	}

	public static boolean fileAvailable(final File file) {
		try {
			return file != null && file.exists();
		}
		catch (final SecurityException ignored) {
			return false;
		}
	}

	protected static boolean isValidURL(final String url) {
		try {
			new URL(url).toURI();
			return true;
		}
		catch (final Exception e) {
			return false;
		}
	}

	public static String formatDouble(final double value, final int digits) {
		return (Double.isNaN(value)) ? "NaN" : getDecimalFormat(value, digits).format(value);
	}

	public static DecimalFormat getDecimalFormat(final double value, final int digits) {
		StringBuilder pattern = new StringBuilder("0.");
		while (pattern.length() < digits + 2)
			pattern.append("0");
		final double absValue = Math.abs(value);
		if ((absValue > 0 && absValue < 0.01) || absValue >= 1000) pattern.append("E0");
		final NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
		final DecimalFormat df = (DecimalFormat)nf;
		df.applyLocalizedPattern(pattern.toString());
		return df;
	}

	/**
	 * Assesses if SNT is running in debug mode
	 *
	 * @return the debug flag
	 */
	public static boolean isDebugMode() {
		return SNT.verbose;
	}

	/**
	 * Enables/disables debug mode
	 *
	 * @param b verbose flag
	 */
	public static void setDebugMode(final boolean b) {
		if (isDebugMode() && !b) {
			log("Exiting debug mode...");
		}
		SNT.verbose = b;
		if (isDebugMode()) {
			log("Entering debug mode...");
			try {
				final ConsolePane<?> console = getContext().service(UIService.class).getDefaultUI().getConsolePane();
				if (console != null) console.show();
			} catch (final Exception ignored) {
				// do nothing;
			}
		}
	}

	public static File findClosestPair (final File file, final String[] pairExts) {
		SNTUtils.log("Finding closest paired file for " + file);
		for (final String ext : pairExts) {
			final File candidate = findClosestPairInternal(file, ext);
			if (candidate != null) return candidate;
		}
		return null;
	}

	public static File findClosestPair(final File file, final String pairExt) {
		return findClosestPair(file, new String[] {pairExt});
	}

	private static File findClosestPairInternal(final File file, final String pairExt) {
		try {
			final File dir = file.getParentFile();
			final String[] list = dir.list((f, s) -> s.endsWith(pairExt));
			SNTUtils.log("Found " + list.length + " " + pairExt + " files");
			if (list.length == 0) return null;
			Arrays.sort(list);
			String dirPath = dir.getAbsolutePath();
			if (!dirPath.endsWith(File.separator)) dirPath += File.separator;
			int cost = Integer.MAX_VALUE;
			final String seed = stripExtension(file.getName().toLowerCase());
			String closest = null;
			final Levenshtein levenshtein = new Levenshtein(5, 10, 1, 5, 5, 0);
			for (final String item : list) {
				final String filename = stripExtension(Paths.get(item).getFileName()
					.toString()).toLowerCase();
				final int currentCost = levenshtein.cost(seed, filename);
				SNTUtils.log("Levenshtein cost for '" + item + "': " + currentCost);
				if (currentCost <= cost) {
					cost = currentCost;
					closest = item;
				}
			}
			SNTUtils.log("Identified pair '" + closest + "'");
			return new File(dirPath + closest);
		}
		catch (final SecurityException | NullPointerException ignored) {
			return null;
		}
	}

	public static boolean similarCalibrations(final Calibration a,
		final Calibration b)
	{
		double ax = 1, ay = 1, az = 1;
		double bx = 1, by = 1, bz = 1;
		String aunit = "", bunit = "";
		if (a != null) {
			ax = a.pixelWidth;
			ay = a.pixelHeight;
			az = a.pixelDepth;
			aunit = a.getUnit();
		}
		if (b != null) {
			bx = b.pixelWidth;
			by = b.pixelHeight;
			bz = b.pixelDepth;
			bunit = a.getUnit();
		}
		if (!aunit.equals(bunit)) return false;
		final double epsilon = 0.000001;
		final double pixelWidthDifference = Math.abs(ax - bx);
		if (pixelWidthDifference > epsilon) return false;
		final double pixelHeightDifference = Math.abs(ay - by);
		if (pixelHeightDifference > epsilon) return false;
		final double pixelDepthDifference = Math.abs(az - bz);
		return pixelDepthDifference <= epsilon;
	}

	public static String getSanitizedUnit(final String unit) {
		final BoundingBox bd = new BoundingBox();
		bd.setUnit(unit);
		return bd.getUnit();
	}

	/**
	 * Generates a list of random paths. Only useful for debugging purposes
	 *
	 * @return the list of random Paths
	 */
	public static List<Path> randomPaths() {
		final List<Path> data = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			final Path p = new Path(1, 1, 1, "unit");
			final double v1 = new Random().nextGaussian();
			final double v2 = new Random().nextGaussian();
			p.addPointDouble(v1, v2, v1);
			p.addPointDouble(v2, v1, v2);
			data.add(p);
		}
		return data;
	}

	/* see net.imagej.legacy.translate.ColorTableHarmonizer */
	public static LUT getLut(final ColorTable cTable) {
		final byte[] reds = new byte[256];
		final byte[] greens = new byte[256];
		final byte[] blues = new byte[256];
		for (int i = 0; i < 256; i++) {
			reds[i] = (byte) cTable.getResampled(ColorTable.RED, 256, i);
			greens[i] = (byte) cTable.getResampled(ColorTable.GREEN, 256, i);
			blues[i] = (byte) cTable.getResampled(ColorTable.BLUE, 256, i);
		}
		return new LUT(reds, greens, blues);
	}

	public static String getElapsedTime(final long fromStart) {
		final long time = System.currentTimeMillis() - fromStart;
		if (time < 1000)
			return String.format("%02d msec", time);
		else if (time < 90000)
			return String.format("%02d sec", TimeUnit.MILLISECONDS.toSeconds(time));
		return String.format("%02d min, %02d sec", TimeUnit.MILLISECONDS.toMinutes(time),
				TimeUnit.MILLISECONDS.toSeconds(time)
						- TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(time)));
	}

	/**
	 * Retrieves Sholl Analysis implementation date
	 *
	 * @return the implementation date or an empty strong if date could not be
	 *         retrieved.
	 */
	public static String buildDate() {
		String BUILD_DATE = "";
		final Class<ShollUtils> clazz = ShollUtils.class;
		final String className = clazz.getSimpleName() + ".class";
		final String classPath = clazz.getResource(className).toString();
		final String manifestPath = classPath.substring(0, classPath.lastIndexOf("!") + 1) + "/META-INF/MANIFEST.MF";
		try {
			final Manifest manifest = new Manifest(new URL(manifestPath).openStream());
			final Attributes attr = manifest.getMainAttributes();
			BUILD_DATE = attr.getValue("Implementation-Date");
			BUILD_DATE = BUILD_DATE.substring(0, BUILD_DATE.lastIndexOf("T"));
		} catch (final Exception ignored) {
			BUILD_DATE = "";
		}
		return BUILD_DATE;
	}

	/**
	 * Retrieves a list of reconstruction files stored in a common directory
	 * matching the specified criteria.
	 *
	 * @param dir     the directory containing the reconstruction files (.(e)swc,
	 *                .traces, .json extension)
	 * @param pattern the filename substring (case-sensitive) to be matched. Only
	 *                filenames containing {@code pattern} will be imported from the
	 *                directory. {@code null} allowed.
	 * @return the array of files. An empty list is retrieved if {@code dir} is not a
	 *         valid, readable directory.
	 */
	public static File[] getReconstructionFiles(final File dir, final String pattern) {
		if (dir == null || !dir.isDirectory() || !dir.exists() || !dir.canRead()) {
			return null;
		}
		final String validatedPattern = (pattern == null) ? "" : pattern;
		final FileFilter filter = (file) -> {
			final String name = file.getName();
			if (!name.contains(validatedPattern))
				return false;
			return file.canRead() && isReconstructionFile(file);
		};
		return dir.listFiles(filter);
	}

	public static boolean isReconstructionFile(final File file) {
		final String lName = file.getName().toLowerCase();
		return (lName.endsWith("swc") || lName.endsWith(".traces") || lName.endsWith(".json") || lName.endsWith(".ndf"));
	}

	/**
	 * Retrieves a list of time-stamped backup files associated with a TRACES file
	 *
	 * @param tracesFile the TRACES file
	 * @return the list of backup files. An empty list is retrieved if none could be
	 *         found.
	 */
	public static List<File> getBackupCopies(final File tracesFile) {
		final List<File> copies = new ArrayList<>();
		if (tracesFile == null)
			return copies;
		final File dir = tracesFile.getParentFile();
		if (dir == null || !dir.isDirectory() || !dir.exists() || !dir.canRead()) {
			return copies;
		}
		final File[] candidates = getReconstructionFiles(dir, stripExtension(tracesFile.getName()));
		if (candidates == null)
			return copies;
		Pattern p = Pattern.compile(SNTUtils.TIMESTAMP_REGEX);
		for (final File candidate : candidates) {
			try {
				if (p.matcher(candidate.getName()).find())
					copies.add(candidate);
			} catch (final Exception ignored) {
				// do nothing
				ignored.printStackTrace();
			}
		}
		return copies;
	}

	public static String getTimeStamp() {
		return new SimpleDateFormat(TIMESTAMP_PATTERN).format(new Date());
	}

	public static String extractReadableTimeStamp(final File file) {
		final Pattern p = Pattern.compile(SNTUtils.TIMESTAMP_REGEX);
		final Matcher m = p.matcher(file.getName());
		if (m.find()) {
			// NB: m.group(0) returns the full match
			return m.group(1) + " " + m.group(2) + " " + m.group(3).replace("-", ":");
		}
		return file.getName();
	}

	public static void setIsLoading(boolean isLoading) {
		if (isLoading && !GraphicsEnvironment.isHeadless())
			GuiUtils.initSplashScreen();
		else
			GuiUtils.closeSplashScreen();
	}

	/**
	 * Convenience method to access the context of the running Fiji instance
	 * 
	 * @return the context of the active ImageJ instance. Never null
	 */
	public static Context getContext() {
		if (context == null) {
			try {
				if (ij.IJ.getInstance() != null)
					context = (Context) IJ.runPlugIn("org.scijava.Context", "");
			} catch (final Exception | Error ignored) {
				error("Failed to retrieve context from IJ1", ignored);
			} finally {
				if (context == null) {
					try {
						context = new Context();
					} catch (final Exception e) {
						System.out.println("SciJava context could not be initialized properly [" + e.getMessage()
								+ "] Some services may not be available!");
						// FIXME: When running SNT outside IJ, LegacyService fails to initialize!?
						// We'll try to initialize a context with the most common services needed by SNT
						// skipping the problematic ones
						context = new Context(//
								// ImageJService.class, // Invalid service: net.imagej.legacy.LegacyService
								// LegacyService.class, // Invalid service: net.imagej.legacy.LegacyService
								CommandService.class, //
								ConvertService.class, //
								DatasetIOService.class, //
								DisplayService.class, //
								ImageDisplayService.class, //
								IOService.class, //
								LogService.class, //
								LUTService.class, //
								OpService.class, //
								PlotService.class, //
								PrefService.class, //
								ScriptService.class, //
								SNTService.class, //
								StatusService.class, //
								TableIOService.class, //
								ThreadService.class, //
								UIService.class //
						);
					}
				}
			}
		}
		return context;
	}

	public static boolean isContextSet() {
		return null != SNTUtils.context;
	}

	public static void setContext(final Context context) {
		SNTUtils.context = context;
	}
}

