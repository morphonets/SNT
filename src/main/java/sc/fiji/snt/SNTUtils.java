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

package sc.fiji.snt;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FileFilter;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
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

import org.scijava.Context;
import org.scijava.app.StatusService;
import org.scijava.batch.BatchService;
import org.scijava.command.CommandService;
import org.scijava.convert.ConvertService;
import org.scijava.display.DisplayService;
import org.scijava.io.IOService;
import org.scijava.log.LogService;
import org.scijava.platform.PlatformService;
import org.scijava.plot.PlotService;
import org.scijava.prefs.PrefService;
import org.scijava.script.ScriptHeaderService;
import org.scijava.script.ScriptService;
import org.scijava.service.Service;
import org.scijava.service.ServiceHelper;
import org.scijava.table.io.TableIOService;
import org.scijava.thread.ThreadService;
import org.scijava.ui.UIService;
import org.scijava.ui.console.ConsolePane;
import org.scijava.ui.swing.script.LanguageSupportService;
import org.scijava.util.FileUtils;
import org.scijava.util.VersionUtils;

import fiji.util.Levenshtein;
import io.scif.services.DatasetIOService;
import net.imagej.ImageJ;
import net.imagej.display.ImageDisplayService;
import net.imagej.lut.LUTService;
import net.imagej.ops.OpService;
import sc.fiji.snt.analysis.sholl.ShollUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.util.BoundingBox;
import sc.fiji.snt.viewer.Viewer3D;

/** Static utilities for SNT **/
public class SNTUtils {

	/*
	 * NB: This pattern needs to be OS agnostic: e.g., Microsoft Windows does not
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
		} catch (final Throwable ignored) {
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

	/**
	 * @deprecated use {@link #getInstance()} instead
	 */
	@Deprecated
	public static SNT getPluginInstance() {
		return getInstance();
	}

	public static SNT getInstance() {
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
			new URI(url);
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
		String BUILD_DATE;
		final Class<ShollUtils> clazz = ShollUtils.class;
		final String className = clazz.getSimpleName() + ".class";
		try {
			final String classPath = clazz.getResource(className).toString();
			final String manifestPath = classPath.substring(0, classPath.lastIndexOf("!") + 1) + "/META-INF/MANIFEST.MF";
			final Manifest manifest = new Manifest(new URI(manifestPath).toURL().openStream());
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
			System.out.println("[SNTUtils] Retrieving org.scijava.Context...");
			try {
				if (ij.IJ.getInstance() != null)
					context = (Context) ij.IJ.runPlugIn("org.scijava.Context", "");
			} catch (final Throwable ex) {
				System.out.println("[ERROR] [SNT] Failed to retrieve context from IJ1: " + ex.getMessage());
			} finally {
				if (context == null) {
					try {
						context = new Context();
					} catch (final Throwable e) {
						System.out.println("[SNTUtils] Full SciJava context could not be initialized: " + e.getMessage());
						System.out.print("[SNTUtils] Trying initialization with preset services...");
						// FIXME: When running SNT outside IJ, some services fail to initialize!?
						// We'll try to initialize a context with the services known to be needed by SNT
						context = new Context(requiredServices());
						System.out.print(" Done.");
					} finally {
						System.out.println();
					}
				}
				// Make sure required services have been loaded. Somehow SNTService is not when IJ is not running!?
				final ServiceHelper sh = new ServiceHelper(context);
				requiredServices().forEach(s -> {
					if (context.getService(s) == null) {
						try {
							sh.loadService(s);
						} catch (final IllegalArgumentException ex) {
							ex.printStackTrace();
						}
					}
				});
				if (context != null)
					System.out.printf("[INFO] [SNT] %d scijava services loaded%n", context.getServiceIndex().size());
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

	private static List<Class<? extends Service>> requiredServices() {
		final List<Class<? extends Service>> services = new ArrayList<>();
		services.add(BatchService.class);
		services.add(CommandService.class);
		services.add(ConvertService.class);
		services.add(DatasetIOService.class);
		services.add(DisplayService.class);
		services.add(ImageDisplayService.class);
		services.add(IOService.class);
		services.add(LanguageSupportService.class);
		services.add(LogService.class);
		services.add(LUTService.class);
		services.add(OpService.class);
		services.add(PlatformService.class);
		services.add(PlotService.class);
		services.add(PrefService.class);
		services.add(ScriptHeaderService.class);
		services.add(ScriptService.class);
		services.add(StatusService.class);
		services.add(TableIOService.class);
		services.add(ThreadService.class);
		services.add(UIService.class);
		services.add(SNTService.class);
		return services;
	}

	/**
	 * Convenience method to start up SNT's GUI.
	 * 
	 * @return a reference to the {@link SNT} instance just started.s
	 */
	public static SNT startApp() {
		GuiUtils.setLookAndFeel(); // needs to be called here to set L&F of image's contextual menu!?
		if (context == null && ij.IJ.getInstance() == null) {
			new ImageJ().ui().showUI();
		}
		setIsLoading(true);
		final PathAndFillManager pathAndFillManager = new PathAndFillManager();
		final SNT snt = new SNT(getContext(), pathAndFillManager);
		snt.initialize(null);
		try {
			javax.swing.SwingUtilities.invokeAndWait(() -> snt.startUI());
		} catch (final InvocationTargetException | InterruptedException e) {
			e.printStackTrace();
		}
		return snt;
	}
}

