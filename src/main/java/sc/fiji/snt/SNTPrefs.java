/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2019 Fiji developers.
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

import java.awt.Point;
import java.io.File;

import ij.Prefs;
import ij.io.FileInfo;
import ij3d.Content;
import ij3d.ContentConstants;

/**
 * Class handling SNT preferences.
 *
 * @author Tiago Ferreira
 */
public class SNTPrefs { // TODO: Adopt PrefService

	private static final int DRAW_DIAMETERS_XY = 1;
	private static final int SNAP_CURSOR = 2;
	// private static final int SHOW_MIP_OVERLAY = 4;
	private static final int AUTO_CANVAS_ACTIVATION = 8;
	// @Deprecated//private static final int ENFORCE_LUT = 16;
	private static final int USE_THREE_PANE = 32;
	private static final int USE_3D_VIEWER = 64;
	// @Deprecated//private static final int LOOK_FOR_TUBES = 128;
	// @Deprecated//private static final int LOOK_FOR_OOF = 256;
	private static final int SHOW_ONLY_SELECTED = 512;
	private static final int STORE_WIN_LOCATIONS = 1024;
	// @Deprecated//private static final int JUST_NEAR_SLICES = 1024;
	private static final int ENFORCE_DEFAULT_PATH_COLORS = 2048;
	private static final int DEBUG = 4096;
	// @Deprecated//private static final int LOOK_FOR_TRACES = 8192;
	private static final int COMPRESSED_XML = 16384;

	private static final String BOOLEANS = "tracing.snt.booleans";
	private static final String SNAP_XY = "tracing.snt.xysnap";
	private static final String SNAP_Z = "tracing.snt.zsnap";
	private static final String PATHWIN_LOC = "tracing.snt.pwloc";
	private static final String FILLWIN_LOC = "tracing.snt.fwloc";
	private static final String FILTERED_IMG_PATH = "tracing.snt.fipath";

	@Deprecated
	private static final String LOAD_DIRECTORY_KEY = "tracing.snt.lastdir";

	private static File recentFile;

	private final SNT snt;
	private final int UNSET_PREFS = -1;
	private int currentBooleans;
	private boolean ij1ReverseSliderOrder;
	private boolean ij1PointerCursor;
	private int resFactor3Dcontent = -1;

	public SNTPrefs(final SNT snt) {
		this.snt = snt;
		getBooleans();
		storeIJ1Prefs();
		imposeIJ1Prefs();
	}

	protected int get3DViewerResamplingFactor() {
		if (resFactor3Dcontent == -1) {
			resFactor3Dcontent = Content.getDefaultResamplingFactor(snt
				.getImagePlus(), ContentConstants.VOLUME);
		}
		return resFactor3Dcontent;
	}

	protected void set3DViewerResamplingFactor(final int factor) {
		if (factor == -1) {
			resFactor3Dcontent = Content.getDefaultResamplingFactor(snt
				.getImagePlus(), ContentConstants.VOLUME);
		}
		else {
			resFactor3Dcontent = factor;
		}
	}

	private void storeIJ1Prefs() {
		ij1ReverseSliderOrder = Prefs.reverseNextPreviousOrder;
		ij1PointerCursor = Prefs.usePointerCursor;
	}

	private void imposeIJ1Prefs() {
		Prefs.reverseNextPreviousOrder = true; // required for scroll wheel
																						// z-tracing
		Prefs.usePointerCursor = false; // required for tracing mode/editing mode
																		// distinction
	}

	private void restoreIJ1Prefs() {
		Prefs.reverseNextPreviousOrder = ij1ReverseSliderOrder;
		Prefs.usePointerCursor = ij1PointerCursor;
	}

	private int getDefaultBooleans() {
		return DRAW_DIAMETERS_XY + SNAP_CURSOR + COMPRESSED_XML + AUTO_CANVAS_ACTIVATION;
	}

	private void getBooleans() {
		// Somehow Prefs.getInt() fails. We'll cast from double instead
		currentBooleans = (int) Prefs.get(BOOLEANS, UNSET_PREFS);
		if (currentBooleans == UNSET_PREFS) currentBooleans = getDefaultBooleans();
	}

	protected void loadPluginPrefs() {
		getBooleans();
		snt.autoCanvasActivation = getPref(AUTO_CANVAS_ACTIVATION);
		snt.snapCursor = !snt.tracingHalted && getPref(SNAP_CURSOR);
		snt.drawDiametersXY = getPref(DRAW_DIAMETERS_XY);
		snt.displayCustomPathColors = !getPref(ENFORCE_DEFAULT_PATH_COLORS);
		snt.setShowOnlySelectedPaths(getPref(SHOW_ONLY_SELECTED), false);
		if (!SNTUtils.isDebugMode()) SNTUtils.setDebugMode(getPref(DEBUG));
		snt.cursorSnapWindowXY = (int) Prefs.get(SNAP_XY, 6);
		snt.cursorSnapWindowXY = whithinBoundaries(snt.cursorSnapWindowXY,
			SNT.MIN_SNAP_CURSOR_WINDOW_XY,
			SNT.MAX_SNAP_CURSOR_WINDOW_XY);
		snt.cursorSnapWindowZ = (int) Prefs.get(SNAP_Z, 2);
		snt.cursorSnapWindowZ = whithinBoundaries(snt.cursorSnapWindowZ,
			SNT.MIN_SNAP_CURSOR_WINDOW_Z,
			SNT.MAX_SNAP_CURSOR_WINDOW_Z);
		if (snt.cursorSnapWindowZ > snt.depth) snt.cursorSnapWindowZ = snt.depth;
		{
			final String fIpath = Prefs.get(FILTERED_IMG_PATH, null);
			if (fIpath != null) snt.setSecondaryImage(new File(fIpath));
		}
	}

	private int whithinBoundaries(final int value, final int min, final int max) {
		if (value < min) return min;
		if (value > max) return max;
		return value;
	}

	@Deprecated
	protected void loadStartupPrefs() {
		// snt.forceGrayscale = getPref(ENFORCE_LUT);
		// snt.look4oofFile = getPref(LOOK_FOR_OOF);
		// snt.look4tubesFile = getPref(LOOK_FOR_TUBES);
		// snt.look4tracesFile = getPref(LOOK_FOR_TRACES);
		snt.setSinglePane(!getPref(USE_THREE_PANE));
		snt.use3DViewer = getPref(USE_3D_VIEWER);
	}

	private boolean getPref(final int key) {
		return (currentBooleans & key) != 0;
	}

	@Deprecated
	protected void saveStartupPrefs() {
		// setPref(USE_THREE_PANE, !snt.getSinglePane());
		setPref(USE_3D_VIEWER, snt.use3DViewer);
		Prefs.set(BOOLEANS, currentBooleans);
		Prefs.savePreferences();
	}

	protected void savePluginPrefs(final boolean restoreIJ1prefs) {
		setSaveCompressedTraces(isSaveCompressedTraces());
		setPref(AUTO_CANVAS_ACTIVATION, snt.autoCanvasActivation);
		if (!snt.tracingHalted) setPref(SNAP_CURSOR, snt.snapCursor);
		Prefs.set(SNAP_XY, snt.cursorSnapWindowXY);
		Prefs.set(SNAP_Z, snt.cursorSnapWindowZ);
		setPref(DRAW_DIAMETERS_XY, snt.drawDiametersXY);
		setPref(ENFORCE_DEFAULT_PATH_COLORS, !snt.displayCustomPathColors);
		setPref(SHOW_ONLY_SELECTED, snt.showOnlySelectedPaths);
		setPref(DEBUG, SNTUtils.isDebugMode());
		Prefs.set(BOOLEANS, currentBooleans);
		if (isSaveWinLocations()) {
			final SNTUI rd = snt.getUI();
			if (rd == null) return;
			final PathManagerUI pw = rd.getPathManager();
			if (pw != null) Prefs.saveLocation(PATHWIN_LOC, pw.getLocation());
			final FillManagerUI fw = rd.getFillManager();
			if (fw != null) Prefs.saveLocation(FILLWIN_LOC, fw.getLocation());
		}
		if (snt.getFilteredImageFile() != null) {
			Prefs.set(FILTERED_IMG_PATH, snt.getFilteredImageFile().getAbsolutePath());
		}
		if (restoreIJ1prefs) restoreIJ1Prefs();
		clearLegacyPrefs();
		Prefs.savePreferences();
	}

	public boolean isSaveCompressedTraces() {
		return getPref(COMPRESSED_XML);
	}

	public void setSaveCompressedTraces(final boolean bool) {
		setPref(COMPRESSED_XML, bool);
	}

	public boolean isSaveWinLocations() {
		return getPref(STORE_WIN_LOCATIONS);
	}

	public void setSaveWinLocations(final boolean value) {
		setPref(STORE_WIN_LOCATIONS, value);
	}

	public void setThreads(int n) {
		Prefs.setThreads(n);
	}

	public int getThreads() {
		return Prefs.getThreads();
	}

	private void setPref(final int key, final boolean value) {
		if (value) currentBooleans |= key;
		else currentBooleans &= ~key;
	}

	protected Point getPathWindowLocation() {
		return Prefs.getLocation(PATHWIN_LOC);
	}

	protected Point getFillWindowLocation() {
		return Prefs.getLocation(FILLWIN_LOC);
	}

	protected void resetOptions() {
		clearAll();
		currentBooleans = UNSET_PREFS;
	}

	public static void clearAll() {
		clearLegacyPrefs();
		Prefs.setThreads(Runtime.getRuntime().availableProcessors());
		Prefs.set(BOOLEANS, null);
		Prefs.set(SNAP_XY, null);
		Prefs.set(SNAP_Z, null);
		Prefs.set(FILLWIN_LOC, null);
		Prefs.set(PATHWIN_LOC, null);
		Prefs.set(FILTERED_IMG_PATH, null);
		Prefs.savePreferences();
	}

	private static void clearLegacyPrefs() {
		Prefs.set(LOAD_DIRECTORY_KEY, null);
		Prefs.set("tracing.Simple_Neurite_Tracer.drawDiametersXY", null);
	}

	protected void setRecentFile(final File file) {
		recentFile = file;
	}

	protected File getRecentFile() {
		if (recentFile == null) {
			if (snt.accessToValidImageData()) {
				try {
					final FileInfo fInfo = snt.getImagePlus().getOriginalFileInfo();
					recentFile = new File(fInfo.directory, fInfo.fileName);
				} catch (final NullPointerException npe) {
					// ignored;
				}
			} else {
				recentFile = new File(System.getProperty("user.home"), "SNT_data");
			}
		}
		return recentFile;
	}

	protected File getRecentDir() {
		return getRecentFile().getParentFile();
	}

}
