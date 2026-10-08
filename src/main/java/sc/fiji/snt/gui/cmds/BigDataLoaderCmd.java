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

import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import net.imagej.ImgPlus;
import mpicbg.spim.data.generic.AbstractSpimData;
import org.janelia.saalfeldlab.n5.bdv.N5ViewerCreator;
import org.janelia.saalfeldlab.n5.bdv.N5ViewerTreeCellRenderer;
import org.janelia.saalfeldlab.n5.ij.N5Importer;
import org.janelia.saalfeldlab.n5.ui.DatasetSelectorDialog;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.widget.Button;
import org.scijava.widget.FileWidget;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.io.SpimDataUtils;
import sc.fiji.snt.seed.SeedOverlay;
import sc.fiji.snt.util.BoundingBox;
import sc.fiji.snt.util.GLUtils;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.TreeUtils;
import sc.fiji.snt.viewer.AbstractBigViewer;
import sc.fiji.snt.viewer.Bdv;
import sc.fiji.snt.viewer.Bvv;
import sc.fiji.snt.viewer.BvvUtils;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Convenience command for starting a standalone Bdv/Bvv instance, including SNT's Stream mode.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, label = "Big Data/SNT Stream", initializer = "init")
public class BigDataLoaderCmd extends ContextCommand {

    /** Initial budget given to {@link #awaitBvvDataOrPrompt}; doubled each time the user chooses to keep waiting */
    private static final long INITIAL_PREFETCH_TIMEOUT_SECONDS = 30;

    private static final String TOOLTIP =
            """
            Supports standard formats (e.g., TIFF), bio-formats supported files,
            and big data formats with lazy loading (N5, Zarr, HDF5, OME-TIFF,
            IMS, BDV .xml). Large datasets are opened virtually without loading
            the entire file into memory.""";

    private static final int REACHABILITY_TIMEOUT_MS = 4000;
    private static final String ABORT = "Abort. I will convert/crop the file elsewhere";
    private static final String DOWNSAMPLE = "Downsample to fit";
    private static final String FULL_RES = "Load at full resolution (tiled pyramid, needs more RAM)";
    private static final String CONVERT = "Convert to multi-resolution OME-Zarr and open";


    @Parameter(required = false, visibility= ItemVisibility.MESSAGE, persist = false)
    String msgHeader= "<HTML>This is the initialization prompt for TB-sized images (w/ optional tracing via <i>SNT " +
            "Stream</i>). For regular, in-memory<br>" +
            "images, use the <i>Startup...</i> option instead. All files can be specified by either local paths or " +
            "remote URLs. Only<br>" +
            "the <i>Main volume</i> field is mandatory.";

    // NB: persist = false on all four File parameters below: SciJava's own generic File-parameter persistence restores
    // a value via `new File(persistedString)`, which mangles a remote URL (see toPathString()). Persistence is instead
    // handled manually below via PrefService, storing/restoring the repaired string (toPathString()) so URLs work

    @Parameter(label = "Main volume", style = FileWidget.FILE_AND_DIRECTORY_STYLE, persist = false,
            description = "Primary image volume.\n"+ TOOLTIP)
    File img1File;

    @Parameter(required = false, style = FileWidget.FILE_AND_DIRECTORY_STYLE, persist = false,
            label = "Secondary volume", description = "Optional image volume (e.g., a second channel saved separately).\n"+ TOOLTIP)
    File img2File;

    @Parameter(required = false, label = "Reconstruction(s)", persist = false,
            description = """
                    Optional.
                    Either a single file (TRACES, SWC, JSON, Neurolucida XML), or a
                    folder/.zip archive of several such files. Coordinates are assumed
                    to be properly scaled. If you need to apply an offset/scaling factor,
                    use the "Import" menu commands instead once tracing starts.""")
    File recFiles;

    @Parameter(required = false, label = "Markers", persist = false,
            description = "Optional.\nA CSV file containing bookmarked locations.")
    File markerFile;

    @Parameter(required = false, label = "Seeds", persist = false,
            description = "<HTML>Optional.<br>A CSV file containing candidate tracing seeds "
                    + "(header: x,y,z,confidence,radius).<br>Requires <i>Enable tracing (SNT Stream)</i> "
                    + "to be checked.")
    File seedFile;

    @Parameter
    private PrefService prefService;

    private static final String IMG1_KEY = "img1File";
    private static final String IMG2_KEY = "img2File";
    private static final String REC_KEY = "recFiles";
    private static final String MARKER_KEY = "markerFile";
    private static final String SEED_KEY = "seedFile";

    @Parameter(label = "Viewer type", description = "The type of viewer.",
            choices = {"Big Data Viewer (BDV): Interactive reslicing", "Big Volume Viewer (BVV): 3D rendering"})
    String viewerType;

    @Parameter(label = "Enable tracing (SNT Stream)",
            description = "<HTML>If enabled, the viewer becomes the main tracing canvas w/ tracing capabilities.<br>"
                    + "If disabled, a plain, lightweight viewer is opened with no SNT session attached.",
            callback = "tracingEnabledChanged")
    boolean tracingEnabled = true;

    @Parameter(required = false, visibility= ItemVisibility.MESSAGE, persist = false)
    String msg;

    @Parameter(label = "Load Remote Demo", callback = "loadDemo", persist = false, required = false)
    private Button demoButton;

    @SuppressWarnings("unused")
    private void loadDemo() {
        final String demoRoot = "https://raw.githubusercontent.com/morphonets/misc/3ffb1bee97666634faf12c060650a6052a30fc26/dataset-demos/marmoset_neurons/";
        // fine to declare URL as file even though File collapses "//" -> "/": restoreUrlScheme() already handles that
        img1File = new File("https://ome-zarr-scivis.s3.us-east-1.amazonaws.com/v0.5/96x2/marmoset_neurons.ome.zarr");
        img2File = null;
        recFiles = new File(demoRoot + "autotracings.traces");
        markerFile = new File(demoRoot + "soma_detections.csv");
        viewerType = "Big Volume Viewer (BVV): 3D rendering";
        tracingEnabled = true;
    }

    @SuppressWarnings("unused")
    private void init() {
        updateRunningInstanceWarning();
        populateLastUsed();
    }

    @SuppressWarnings("unused")
    private void tracingEnabledChanged() {
        updateRunningInstanceWarning();
    }

    private void updateRunningInstanceWarning() {
        msg = (tracingEnabled && SNTUtils.getInstance() != null)
                ? "<HTML>NB: Enabling tracing (<i>SNT Stream</i>) requires SNT to not already be running. "
                        + "Please close the active instance first,<br>or disable tracing above."
                : "";
    }

    /** Restores the four File fields from PrefService, in place of SciJava's own (URL-mangling) persistence. */
    private void populateLastUsed() {
        final String lastImg1 = prefService.get(BigDataLoaderCmd.class, IMG1_KEY);
        if (lastImg1 != null) img1File = new File(lastImg1);
        final String lastImg2 = prefService.get(BigDataLoaderCmd.class, IMG2_KEY);
        if (lastImg2 != null) img2File = new File(lastImg2);
        final String lastRec = prefService.get(BigDataLoaderCmd.class, REC_KEY);
        if (lastRec != null) recFiles = new File(lastRec);
        final String lastMarker = prefService.get(BigDataLoaderCmd.class, MARKER_KEY);
        if (lastMarker != null) markerFile = new File(lastMarker);
        final String lastSeed = prefService.get(BigDataLoaderCmd.class, SEED_KEY);
        if (lastSeed != null) seedFile = new File(lastSeed);
    }

    /** Persists the four File fields as their repaired (toPathString()) string form. */
    private void saveLastUsed() {
        putOrRemove(IMG1_KEY, img1File);
        putOrRemove(IMG2_KEY, img2File);
        putOrRemove(REC_KEY, recFiles);
        putOrRemove(MARKER_KEY, markerFile);
        putOrRemove(SEED_KEY, seedFile);
    }

    private void putOrRemove(final String key, final File file) {
        if (file == null) prefService.remove(BigDataLoaderCmd.class, key);
        else prefService.put(BigDataLoaderCmd.class, key, toPathString(file));
    }

    @Override
    public void run() {
        if (img1File == null) {
            error("Main volume is required.");
            return;
        }
        saveLastUsed();
        final String[] filePaths = Stream.of(img1File, img2File)
                .filter(Objects::nonNull)
                .map(BigDataLoaderCmd::toPathString)
                .toArray(String[]::new);
        if (filePaths.length == 0) {
            error("No volume files specified.");
            return;
        }
        if (!checkReachable(filePaths)) return; // single friendly dialog if any remote field is unreachable
        final boolean threeD = viewerType != null && viewerType.toLowerCase().contains("bvv");
        final boolean tracer = tracingEnabled;

        if (tracer && SNTUtils.getInstance() != null && SNTUtils.getInstance().getUI() != null) {
            error("SNT seems to be already running. Please close the current instance and re-run.");
            return;
        }

        AbstractBigViewer viewer = null;
        boolean splashClosed = false; // guards against the finally block closing it a 2nd time below
        try {
            SNTUtils.setIsLoading(true, true);
            if (tracer && threeD)
                viewer = runBvvWithTracing(filePaths);
            else if (tracer)
                viewer = runBdvWithTracing(filePaths);
            else if (threeD)
                viewer = runBvv(filePaths);
            else
                viewer = runBdv(filePaths);
        } catch (final Exception e) {
            splashClosed = true;
            SNTUtils.setIsLoading(false, false); // hide splashscreen behind error dialog
            // Full exception (stack trace incl.) goes to the log; the dialog below only gets a friendly summary
            SNTUtils.error("BVV: could not open " + String.join(", ", filePaths), e, false);
            error("An error occurred: " + GuiUtils.friendlyErrorMessage(e));
        } finally {
            if (!splashClosed) SNTUtils.setIsLoading(false, false);
            if (viewer != null && viewer.getViewerFrame() != null && BoundingBox.UNSET_SPACING_UNIT.equals(viewer.getPhysicalUnit())) {
                // viewer is reassigned above, so it is not effectively final: capture it for the lambda below
                final AbstractBigViewer finalViewer = viewer;
                GuiUtils.Notices.queueNotice(
                        "<HTML><b>Spatial calibration values appear to be invalid.</b><br>"
                                + "Click here to set it, or right-click the scale bar button in <i>Scene Controls</i>.",
                        null, () -> {
                            finalViewer.getViewerFrame().toFront();
                            finalViewer.showCalibrationDialog(finalViewer.getViewerFrame());
                        }, GuiUtils.Notices.PendingNotice.WARN);
            }
        }
    }

    /**
     * Converts a (possibly user-typed) {@link File} parameter back to the string form
     * {@link SpimDataUtils#resolvePathToSource(String)} expects. {@code File#getAbsolutePath()} mangles
     * remote URLs (e.g. {@code https://.../dataset.ome.zarr}) by prepending the current working
     * directory, since a URL scheme isn't a recognized absolute-path prefix. Even {@code File#getPath()}
     * isn't a clean escape hatch here: by the time the typed text becomes a {@code File} at all, its own
     * constructor has already collapsed the URL's "://" down to ":/" (consecutive slashes are merged),
     * so that has to be repaired too (see {@link SpimDataUtils#restoreUrlScheme(String)}).
     */
    private static String toPathString(final File f) {
        return SpimDataUtils.isRemoteUrl(f.getPath())
                ? SpimDataUtils.restoreUrlScheme(f.getPath())
                : f.getAbsolutePath();
    }

    /**
     * @return true if every remote field is reachable (or none are remote); false if the run was
     *         aborted because at least one was not
     */
    private boolean checkReachable(final String[] filePaths) {
        final LinkedHashMap<String, String> byLabel = new LinkedHashMap<>();
        if (filePaths.length > 0) byLabel.put("Main volume", filePaths[0]);
        if (filePaths.length > 1) byLabel.put("Secondary volume", filePaths[1]);
        if (recFiles != null) byLabel.put("Reconstruction(s)", toPathString(recFiles));
        if (markerFile != null) byLabel.put("Markers", toPathString(markerFile));
        if (seedFile != null) byLabel.put("Seeds", toPathString(seedFile));
        final List<String> unreachable = new ArrayList<>();
        for (final Map.Entry<String, String> entry : byLabel.entrySet()) {
            final String path = entry.getValue();
            if (SpimDataUtils.isRemoteUrl(path) && !SNTUtils.isReachable(path, REACHABILITY_TIMEOUT_MS))
                unreachable.add(entry.getKey() + ": " + path);
        }
        if (unreachable.isEmpty()) return true;
        error("<HTML>No internet access. Could not reach:<br>&nbsp;&nbsp;"
                + String.join("<br>&nbsp;&nbsp;", unreachable)
                + "<br>Please check your connection and try again.");
        return false;
    }

    /** True if path is an existing .n5/.zarr directory, or a remote URL to one. */
    private static boolean isN5OrZarrDir(final String path) {
        if (SpimDataUtils.isRemoteUrl(path)) {
            final String lower = path.toLowerCase();
            return lower.endsWith(".n5") || lower.endsWith(".n5/") || lower.endsWith(".zarr") || lower.endsWith(".zarr/");
        }
        final File f = new File(path);
        final String lower = f.getName().toLowerCase();
        return f.isDirectory() && (lower.endsWith(".n5") || lower.endsWith(".zarr"));
    }

    /**
     * Fallback UI for when {@link SpimDataUtils#resolvePathToSource(String)} cannot auto-discover a dataset in an
     * N5/Zarr container on its own (e.g. an ambiguous or unusually structured container). Lets the user pick a
     * dataset interactively.
     *
     * @param n5ZarrDir the {@code .n5} or {@code .zarr} directory
     * @param viewer    the already-created {@link Bvv} or {@link Bdv} to add the user's eventual selection to
     */
    private void datasetDialog(final String n5ZarrDir, final AbstractBigViewer viewer) {
        // n5-ij's DatasetSelectorDialog feeds this path straight into java.net.URI's single-string constructor (see
        // ImprovedFormattedTextField) to populate its "container path" text field. A raw Windows path (e.g.,
        // "E:\foo\bar") crashes that parser: "E:" is read as a URI scheme,  and the backslash right after it is illegal.
        // Forward slashes don't have this problem, so normalizing here should be safe.
        final String normalizedPath = n5ZarrDir.replace('\\', '/');
        final ExecutorService exec = Executors.newFixedThreadPool(SNTPrefs.getThreads());
        final DatasetSelectorDialog datasetDialog = getDatasetSelectorDialog(normalizedPath, exec);
        SwingUtilities.invokeLater(() -> {
            datasetDialog.run(selection -> {
                try {
                    final SpimDataUtils.N5Sources n5Sources =
                            SpimDataUtils.resolveN5Selection(selection, new File(normalizedPath).getName());
                    // Same non-pyramidal-dataset risk as resolveBvvSources(), only relevant for BVV
                    if (viewer instanceof Bvv bvv) {
                        if (confirmPyramidOrAbort(n5Sources, n5ZarrDir)) showN5InBvv(bvv, n5Sources);
                    } else if (viewer instanceof Bdv bdv) {
                        bdv.show(n5Sources);
                    }
                } catch (final Exception e) {
                    GuiUtils.errorPrompt("Could not open '" + n5ZarrDir + "': " + e.getMessage());
                } finally {
                    exec.shutdown();
                }
            });
            datasetDialog.openContainer(normalizedPath); // run() calls buildDialog() synchronously before returning
        });
    }

    private static DatasetSelectorDialog getDatasetSelectorDialog(final String n5ZarrDir, final ExecutorService exec) {
        final DatasetSelectorDialog datasetDialog = new DatasetSelectorDialog(
                new N5Importer.N5ViewerReaderFun(), new N5Importer.N5BasePathFun(), n5ZarrDir,
                N5ViewerCreator.n5vGroupParsers, N5ViewerCreator.n5vParsers);
        datasetDialog.setLoaderExecutor(exec);
        datasetDialog.setTreeRenderer(new N5ViewerTreeCellRenderer(false));
        datasetDialog.setContainerPathUpdateCallback(path -> {}); // required; NPEs otherwise
        return datasetDialog;
    }

    /** Resolves sources, enforces GPU texture limits, then opens BVV. */
    private AbstractBigViewer runBvv(final String[] filePaths) {
        final GLUtils.Info gl = GLUtils.getInfo();
        final int maxTexSize = gl.maxTexture3DSize();
        // available=false means the GL query failed and maxTexture3DSize is a conservative fallback, not a real reading
        SNTUtils.log("BVV: GL_MAX_3D_TEXTURE_SIZE = " + maxTexSize + (gl.available() ? "" : " (fallback, GL query failed)")
                + " [" + gl.vendor() + " | " + gl.renderer() + " | OpenGL " + gl.version() + "]");
        final ResolvedSources resolved = resolveBvvSources(filePaths, maxTexSize, null, false);
        if (resolved == null) return null; // user chose Abort (oversized image or non-pyramidal dataset)
        final Bvv bvv = new Bvv();
        addSourcesToBvv(bvv, resolved);
        loadReconstructions(bvv);
        loadMarkers(bvv);
        loadSeeds(bvv);
        return bvv;
    }

    /**
     * Same as {@link #runBvv(String[])}, but tethers BVV to a full SNT instance (own SNTUI window,
     * Path Manager, etc.) so that {@code Bvv}'s tracing toggle (manual and/or A*) is functional.
     * <p>
     * SNT's own image state (used by A* search, via {@code getLoadedData()}) is populated from the
     * <i>primary</i> volume ({@link #img1File}) only when that file is safe/fast to also open
     * conventionally, i.e., not a lazily-loaded N5/Zarr container. In that case (or if opening the
     * primary volume conventionally fails for any other reason), SNT starts without image data:
     * manual tracing still works (segment tracing only needs spacing, which defaults to 1 regardless
     * of a loaded image), but A* has nothing real to search until an image is loaded via the SNTUI.
     */
    private AbstractBigViewer runBvvWithTracing(final String[] filePaths) {
        final GLUtils.Info gl = GLUtils.getInfo();
        final int maxTexSize = gl.maxTexture3DSize();
        // available=false means the GL query failed and maxTexture3DSize is a conservative fallback, not a real reading
        SNTUtils.log("BVV: GL_MAX_3D_TEXTURE_SIZE = " + maxTexSize + (gl.available() ? "" : " (fallback, GL query failed)")
                + " [" + gl.vendor() + " | " + gl.renderer() + " | OpenGL " + gl.version() + "]");
        // Resolve the primary volume (img1File, i.e. filePaths[0]) exactly once: startTracingSNT() needs
        // it for calibration/A* wiring, and the viewer needs the very same source. Doing this before
        // resolveBvvSources() lets that primary resolution be reused there instead of re-running N5/Zarr
        // discovery a second time for an unchanged path
        final TracingSetup setup = startTracingSNT(img1File);
        final ResolvedSources resolved = resolveBvvSources(filePaths, maxTexSize, setup.primarySource(), true);
        if (resolved == null) return null; // user chose Abort (oversized image or non-pyramidal dataset)
        final Bvv bvv = new Bvv(setup.snt());
        addSourcesToBvv(bvv, resolved);
        loadReconstructions(bvv);
        loadMarkers(bvv);
        loadSeeds(bvv);
        return bvv;
    }

    /** Holds the outcome of {@link #resolveBvvSources(String[], int, Object, boolean)}. */
    private record ResolvedSources(List<Object> sources, List<String> deferredPaths) {}

    /**
     * Resolves each path to a BVV-displayable source (an {@link ImgPlus}, {@link AbstractSpimData},
     * or {@link SpimDataUtils.N5Sources}), enforcing the GPU's 3D texture size limit along the way.
     * N5/Zarr directories that can't be auto-discovered headlessly are collected into {@code
     * deferredPaths} instead, to be resolved later via the interactive {@link #datasetDialog}.
     *
     * @param cachedPrimarySource {@code filePaths[0]}'s source, if already resolved elsewhere (see
     *                            {@link #startTracingSNT}), to avoid resolving it a second time; or
     *                            {@code null} to resolve every path here
     * @param tracing             whether the viewer is tethered to SNT for tracing. If so, the (memory-hungry)
     *                            full-resolution option is not offered for oversized images, since SNT already
     *                            holds its own copy of the primary image
     * @return the resolved sources, or {@code null} if the user chose to Abort when prompted about
     *         an oversized image (see {@link #handleOversizedImage}) or a non-pyramidal N5/Zarr
     *         source (see {@link #confirmPyramidOrAbort})
     */
    private ResolvedSources resolveBvvSources(final String[] filePaths, final int maxTexSize,
                                               final Object cachedPrimarySource, final boolean tracing) {
        final List<Object> sources = new ArrayList<>();
        final List<String> deferredPaths = new ArrayList<>(); // need the interactive dialog
        for (int i = 0; i < filePaths.length; i++) {
            final String path = filePaths[i];
            final Object source;
            try {
                source = (i == 0 && cachedPrimarySource != null) ? cachedPrimarySource
                        : SpimDataUtils.resolvePathToSource(path);
            } catch (final IllegalArgumentException e) {
                if (isN5OrZarrDir(path)) {
                    SNTUtils.log("BVV: headless N5/Zarr discovery failed for '" + path + "' (" + e.getMessage()
                            + "); will prompt for dataset selection");
                    deferredPaths.add(path);
                    continue;
                }
                throw e;
            }
            if (source instanceof SpimDataUtils.N5Sources n5 && !n5.sources().isEmpty()
                    && n5.sources().getFirst().getSpimSource().getNumMipmapLevels() <= 1) {
                final AbstractSpimData<?> sibling = pyramidalSiblingXml(path);
                if (sibling != null) {
                    sources.add(sibling);
                    continue;
                }
            }
            if (source instanceof SpimDataUtils.N5Sources n5 && !confirmPyramidOrAbort(n5, path)) {
                return null; // user chose Abort
            }
            if (source instanceof ImgPlus<?> img) {
                // Diagnostic only. A plain ImgPlus has no pyramid, so unlike AbstractSpimData/N5Sources there is
                // nothing to force here, only a remote-origin freeze risk worth logging
                BvvUtils.warnIfLikelyRemoteImgPlus(img, path);
                if (ImgUtils.exceedsDimension(img, maxTexSize)
                        || ImgUtils.exceedsVoxelCount(img, BvvUtils.MAX_SINGLE_TEXTURE_VOXELS)) {
                    final Object handled = handleOversizedImage(img, maxTexSize, path, tracing);
                    if (handled == null) return null; // user chose Abort
                    sources.add(handled);
                    continue;
                }
            }
            sources.add(source);
        }
        return new ResolvedSources(sources, deferredPaths);
    }

    /**
     * Makes sure BVV's GPU tile cache is large enough for a streamed pyramid, prompting (once, unless suppressed) if
     * the user's preference is too low or if the JVM heap limits the cache below what is needed. Must run before the
     * first BVV window is created (the cache size cannot change afterwards)
     */
    private void checkGpuCache(final Bvv bvv, final int nChannels) {
        if (bvv.getViewerFrame() != null) return; // the cache size cannot change once a window exists
        final int cap = BvvUtils.maxCacheMB();
        final int want = Math.min(nChannels * BvvUtils.CACHE_MB_PER_CHANNEL, BvvUtils.MAX_CACHE_SIZE_MB);
        final int pref = BvvUtils.getCachePrefMB();
        final int recommended = BvvUtils.recommendedCacheMB(nChannels);
        final boolean lowPref = pref > 0 && Math.min(pref, cap) < recommended;
        final boolean lowHeap = cap < want;
        SNTUtils.log("BVV: GPU tile cache pref=" + (pref > 0 ? pref + "MB" : "auto") + ", recommended="
                + recommended + "MB, cap=" + cap + "MB (lowPref=" + lowPref + ", lowHeap=" + lowHeap + ")");
        if (!(lowPref || lowHeap) || BvvUtils.isCachePromptSuppressed() || java.awt.GraphicsEnvironment.isHeadless())
            return;
        final StringBuilder msg = new StringBuilder("<html><div style='width:420px'>");
        if (lowPref) {
            msg.append("Your GPU tile cache preference (").append(Math.min(pref, cap)).append(" MB) is lower than ")
                    .append("recommended for ").append(nChannels).append(nChannels == 1 ? " channel" : " channels")
                    .append(" (").append(recommended).append(" MB). The volume may flicker as tiles are ")
                    .append("repeatedly evicted and reloaded.<br><br>");
        }
        if (lowHeap) {
            msg.append("The JVM memory limit (").append(Runtime.getRuntime().maxMemory() / (1024 * 1024))
                    .append(" MB) restricts the GPU tile cache to ").append(cap).append(" MB, below the ")
                    .append(want).append(" MB ideal for this dataset. Consider increasing the amount of memory ")
                    .append("available to Fiji/SNT and restarting.<br><br>");
        }
        final boolean sntRunning = SNTUtils.getInstance() != null;
        final String useLabel = "Use " + recommended + " MB (this session)";
        final String prefsLabel = "Open Preferences...";
        final String continueLabel = "Continue as is";
        final List<String> options = new ArrayList<>();
        if (lowPref) options.add(useLabel);
        if (sntRunning) options.add(prefsLabel);
        options.add(continueLabel);
        // See confirmPyramidOrAbort(): the loading splash screen can end up rendered on top of this
        // dialog, so hide it for the duration of the prompt and restore it afterward
        final Object[] result;
        SNTUtils.setIsLoading(false, true);
        try {
            result = new GuiUtils(null).getChoiceWithOptionAndInfo("BVV GPU Cache", msg.toString(),
                    options.toArray(new String[0]), continueLabel, null, "Do not ask again", false);
        } finally {
            SNTUtils.setIsLoading(true, true);
        }
        if (result == null) return; // dismissed
        if ((Boolean) result[1]) BvvUtils.setCachePromptSuppressed(true);
        final String picked = (String) result[0];
        if (useLabel.equals(picked)) {
            BvvUtils.setSessionCacheMB(recommended);
        } else if (prefsLabel.equals(picked)) {
            try {
                getContext().getService(org.scijava.command.CommandService.class).run(PrefsCmd.class, true).get();
            } catch (final InterruptedException | java.util.concurrent.ExecutionException ex) {
                SNTUtils.log("BVV: could not open Preferences: " + ex.getMessage());
            }
        }
    }

    /**
     * Shows N5/Zarr sources in {@code bvv}: makes them streamable, reports unfavorable chunking, checks the GPU tile
     * cache and builds a synthetic pyramid if they have none
     */
    private void showN5InBvv(final Bvv bvv, final SpimDataUtils.N5Sources n5) {
        // Wrapped before the prefetch: the prefetch must see the final stack type, otherwise it touches
        // every voxel of level 0
        final SpimDataUtils.N5Sources cellBacked = cellBacked(n5);
        BvvUtils.reportChunking(n5.name(), n5.chunkShape());
        if (awaitBvvSourcesReady(cellBacked)) {
            checkGpuCache(bvv, cellBacked.sources().size());
            bvv.show(withSyntheticPyramid(cellBacked));
        }
    }

    /** Adds each resolved source to {@code bvv}, and opens the interactive dialog for deferred N5/Zarr paths. */
    private void addSourcesToBvv(final Bvv bvv, final ResolvedSources resolved) {
        for (final Object source : resolved.sources()) {
            if (source instanceof AbstractSpimData<?> spim) {
                bvv.show(spim);
            } else if (source instanceof SpimDataUtils.N5Sources n5) {
                showN5InBvv(bvv, n5);
            } else if (source instanceof ImgPlus<?> img) {
                //noinspection unchecked,rawtypes
                bvv.show((ImgPlus) img);
            }
        }
        for (final String path : resolved.deferredPaths())
            datasetDialog(path, bvv);
    }

    /**
     * Outcome of {@link #startTracingSNT(File)}: the SNT instance it started, plus whatever
     * {@code primaryVolume} resolved to along the way (an {@link ImgPlus}, {@link AbstractSpimData},
     * {@link SpimDataUtils.N5Sources}, or {@code null} if resolution failed). Callers that also need
     * to display {@code primaryVolume} themselves (i.e. {@code filePaths[0]}) should reuse
     * {@link #primarySource} rather than resolving the same path a second time -- for a remote
     * (S3/HTTPS) container, N5/Zarr discovery is a real network round-trip, not a cheap local check.
     */
    private record TracingSetup(SNT snt, Object primarySource) {}

    /**
     * Starts a full SNT instance (SNTUI window included) without displaying an ImagePlus window;
     * the BVV/BDV viewer being opened is the only display, and SNT exists here for its Path Manager
     * (and, when possible, A* search). Shared by {@link #runBvvWithTracing(String[])} and
     * {@link #runBdvWithTracing(String[])}.
     * <p>
     * When {@code primaryVolume} resolves headlessly to a plain {@link ImgPlus} (the common case:
     * TIFF, and often N5/Zarr too), it is wired directly into SNT via the {@code SNT(ImgPlus)}
     * "Tracing Mode" constructor: this sets SNT's own {@code ctSlice3d} (what A* search reads via
     * {@code getLoadedData()}) straight from the same object the viewer renders, without ever
     * assembling or showing the classic 2D tracing canvas ({@code setFieldsFromImgPlus} sets
     * {@code xy = null}). {@link SNT#accessToValidImageData()} treats {@code ctSlice3d != null} as
     * sufficient on its own
     * <p>
     * Since {@code ctSlice3d} only needs to be a {@code RandomAccessibleInterval}, this also works
     * transparently when the resolved {@code ImgPlus} is lazily backed (e.g. N5/Zarr): A* search's
     * random-access reads trigger on-demand chunk loading the same way the viewer's own rendering does
     * <p>
     * Deliberately uses the <i>original</i>, full-resolution {@code ImgPlus} here, not whatever
     * (possibly downsampled) version {@link #resolveBvvSources} produces for BVV specifically (BDV has
     * no such downsampling step to begin with): SNT's A* search is plain CPU-side iteration with no GPU
     * texture-size constraint, so there's no reason to degrade it to match a viewer's rendering limits.
     * <p>
     * When the primary volume instead resolves to an {@link AbstractSpimData} (e.g. IMS, BDV .xml
     * multi-view containers) or {@link SpimDataUtils.N5Sources} (ambiguous N5/Zarr layouts still
     * pending the interactive dataset dialog), there is no {@code ImgPlus} to hand to the
     * {@code SNT(ImgPlus)} constructor. SNT instead starts blank ("Analysis Mode") and {@link
     * #applyFallbackCalibration} attempts to wire dimensions/calibration and the underlying pixel
     * data (via {@link SNT#setImageMetadata}/{@link SNT#setImageData}) directly from the
     * source's own {@code ImgLoader}/{@code Source}, timepoint 0. When that succeeds, both manual
     * tracing and A* search work as usual; if it fails for any reason (unexpected loader
     * implementation, etc.), tracing falls back to manual-only.
     */
    private TracingSetup startTracingSNT(final File primaryVolume) {
        GuiUtils.LAF.setLookAndFeel(); // needs to be called here to set L&F of image's contextual menu!?
        if (getContext() == null && ij.IJ.getInstance() == null) {
            new net.imagej.ImageJ().ui().showUI();
        }
        Object primarySource = null; // ImgPlus, AbstractSpimData, or SpimDataUtils.N5Sources; null if resolution failed
        ImgPlus<?> primaryImgPlus = null;
        Object primaryFallbackSource = null; // AbstractSpimData or SpimDataUtils.N5Sources, for calibration only
        if (primaryVolume != null) {
            try {
                primarySource = SpimDataUtils.resolvePathToSource(toPathString(primaryVolume));
                if (primarySource instanceof ImgPlus<?> img) {
                    primaryImgPlus = img;
                } else {
                    SNTUtils.log("SNT: primary volume resolves to " + primarySource.getClass().getSimpleName()
                            + " (not a plain ImgPlus); will attempt to wire dimensions/pixel data from it directly");
                    primaryFallbackSource = primarySource;
                }
            } catch (final Exception e) {
                SNTUtils.log("SNT: could not resolve '" + primaryVolume.getName() + "' for SNT (" + e.getMessage()
                        + "); tracing will fall back to manual-only (no image data for A*)");
            }
        }

        final SNT snt;
        if (primaryImgPlus != null) {
            //noinspection unchecked,rawtypes
            snt = new SNT((ImgPlus) primaryImgPlus); // "Tracing Mode": no window; ctSlice3d set directly
        } else {
            final PathAndFillManager pathAndFillManager = new PathAndFillManager();
            snt = new SNT(getContext(), pathAndFillManager);
            snt.initialize(null);
            if (primaryFallbackSource != null) applyFallbackCalibration(snt, primaryFallbackSource);
        }
        try {
            snt.startUI(true); // self-dispatches to the EDT as needed; do not wrap in invokeAndWait here
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return new TracingSetup(snt, primarySource);
    }

    /**
     * Extracts image dimensions/calibration and, when possible, the actual pixel data from a
     * resolved {@link AbstractSpimData} or {@link SpimDataUtils.N5Sources} and applies them to
     * {@code snt} via {@link SNT#setImageMetadata}/{@link SNT#setImageData}. Without at least the
     * metadata call, {@code snt} keeps its all-zero default dimensions, which breaks bounds checks.
     * <p>
     * Dimensions/calibration mirror the equivalent logic in {@link Bvv#show(AbstractSpimData)}/
     * {@link Bvv#show(SpimDataUtils.N5Sources)}, which populates BVV's own bounds for the same
     * reason. Pixel-data extraction is best-effort and wrapped separately: if it fails (e.g. an
     * unexpected {@code ImgLoader} implementation), tracing falls back to manual-only.
     * <p>
     * Caveat: this reads timepoint 0 (single-timepoint use case) directly from the loader/{@code
     * Source}, in the volume's own pixel grid. For the {@link AbstractSpimData} branch, any
     * registration transform beyond plain size/calibration (e.g. a non-identity {@code
     * ViewRegistration}, or BVV's own manual-transform mode) is <em>not</em> applied, so traced
     * world coordinates could diverge from what's rendered if such a transform is present. The
     * {@link SpimDataUtils.N5Sources} branch does compensate for its own {@code Source}'s
     * translation (see {@link #applyWorldOriginOffset}), since that is the fallback path actually
     * exercised for typical headless N5/Zarr loading.
     */
    private static void applyFallbackCalibration(final SNT snt, final Object source) {
        try {
            if (source instanceof AbstractSpimData<?> spimData) {
                final var setups = spimData.getSequenceDescription().getViewSetupsOrdered();
                if (setups.isEmpty()) return;
                final var setup = setups.getFirst();
                if (setup.hasSize() && setup.hasVoxelSize()) {
                    final var sz = setup.getSize();
                    final var vs = setup.getVoxelSize();
                    snt.setImageMetadata((int) sz.dimension(0), (int) sz.dimension(1), (int) sz.dimension(2),
                            vs.dimension(0), vs.dimension(1), vs.dimension(2), vs.unit());
                }
                try {
                    final var setupLoader = spimData.getSequenceDescription().getImgLoader().getSetupImgLoader(setup.getId());
                    snt.setImageData(setupLoader.getImage(0)); // timepoint 0
                } catch (final Exception e) {
                    SNTUtils.log("BVV: could not access pixel data from SpimData ImgLoader for A* search ("
                            + e.getMessage() + "); manual tracing only");
                }
            } else if (source instanceof SpimDataUtils.N5Sources n5Sources && !n5Sources.sources().isEmpty()) {
                final var spimSource = n5Sources.sources().getFirst().getSpimSource();
                final var itvl = spimSource.getSource(0, 0); // timepoint 0, full-resolution level 0
                final var vd = spimSource.getVoxelDimensions();
                snt.setImageMetadata((int) itvl.dimension(0), (int) itvl.dimension(1), (int) itvl.dimension(2),
                        (vd == null) ? 0 : vd.dimension(0), (vd == null) ? 0 : vd.dimension(1),
                        (vd == null) ? 0 : vd.dimension(2), (vd == null) ? null : vd.unit());
                snt.setImageData(itvl); // same lazily-loaded interval backing BVV's own rendering
                applyWorldOriginOffset(snt, spimSource);
            }
        } catch (final Exception e) {
            SNTUtils.log("SNT: could not extract calibration for tracing (" + e.getMessage() + ")");
        }
    }

    /**
     * Reads {@code spimSource}'s own {@code sourceTransform} (timepoint 0, full-resolution level 0)
     * and, if it carries a translation, records it on {@code snt} via {@link SNT#setWorldOriginOffset}
     * so that {@code GWDTTracerCommonCmd#applyWorldOriginOffsetIfAny} can shift traced {@link Tree}s
     * to compensate.
     * <p>
     * {@link #applyFallbackCalibration} above wires {@code snt}'s dimensions/spacing/pixel data
     * directly from {@code spimSource.getSource(0, 0)}/{@code getVoxelDimensions()}, i.e. in that
     * Source's own raw voxel grid, with no notion of where that grid sits in world space. A
     * {@code sourceTransform} translation (common for N5/Zarr multiscale or BDV-registered
     * datasets - the same transform {@code Bvv} reads to render/align this Source correctly) would
     * otherwise be silently dropped, producing traced coordinates that are internally consistent
     * (correct shape/scale) but uniformly shifted relative to the volume's true position.
     * <p>
     * Only the translation component is applied (a rigid shift); a non-identity rotation/shear in
     * {@code sourceTransform} is not handled here and would require transforming the traced
     * geometry itself, not just its origin.
     */
    private static void applyWorldOriginOffset(final SNT snt, final bdv.viewer.Source<?> spimSource) {
        final net.imglib2.realtransform.AffineTransform3D t = new net.imglib2.realtransform.AffineTransform3D();
        spimSource.getSourceTransform(0, 0, t);
        final double dx = t.get(0, 3);
        final double dy = t.get(1, 3);
        final double dz = t.get(2, 3);
        if (dx != 0 || dy != 0 || dz != 0) {
            snt.setWorldOriginOffset(dx, dy, dz);
            SNTUtils.log("SNT: N5Sources sourceTransform carries a translation of (" + dx + ", " + dy + ", " + dz
                    + ") in calibrated units; will be applied to traced Trees to correct for it. "
                    + "(Any rotation/shear in the same transform is NOT compensated for.)");
        }
    }

    /** Resolves sources (no texture-size constraint) then opens BDV. */
    private AbstractBigViewer runBdv(final String[] filePaths) {
        final Bdv bdv = new Bdv();
        final List<String> deferredPaths = new ArrayList<>(); // need the interactive dialog
        for (final String path : filePaths) {
            final Object source;
            try {
                source = SpimDataUtils.resolvePathToSource(path);
            } catch (final IllegalArgumentException e) {
                if (isN5OrZarrDir(path)) {
                    SNTUtils.log("BDV: headless N5/Zarr discovery failed for '" + path + "' (" + e.getMessage()
                            + "); will prompt for dataset selection");
                    deferredPaths.add(path);
                    continue;
                }
                throw e;
            }
            if (source instanceof AbstractSpimData<?> spim) {
                bdv.show(spim, path); // path-aware overload populates spimDataFilePaths
            } else if (source instanceof SpimDataUtils.N5Sources n5) {
                bdv.show(n5);
            } else if (source instanceof ImgPlus<?> img) {
                //noinspection unchecked,rawtypes
                bdv.show((ImgPlus) img);
            }
        }
        for (final String path : deferredPaths)
            datasetDialog(path, bdv);
        loadReconstructions(bdv);
        loadMarkers(bdv);
        loadSeeds(bdv);
        return bdv;
    }

    /** BDV counterpart to runBvvWithTracing(final String[] filePaths); */
    private AbstractBigViewer runBdvWithTracing(final String[] filePaths) {
        final TracingSetup setup = startTracingSNT(img1File);
        final Bdv bdv = new Bdv(setup.snt());
        final List<String> deferredPaths = new ArrayList<>(); // need the interactive dialog
        for (int i = 0; i < filePaths.length; i++) {
            final String path = filePaths[i];
            final Object source;
            try {
                // Reuse filePaths[0]'s already-resolved source (see startTracingSNT) instead of
                // re-running N5/Zarr discovery a second time for the same container.
                source = (i == 0 && setup.primarySource() != null) ? setup.primarySource()
                        : SpimDataUtils.resolvePathToSource(path);
            } catch (final IllegalArgumentException e) {
                if (isN5OrZarrDir(path)) {
                    SNTUtils.log("BDV: headless N5/Zarr discovery failed for '" + path + "' (" + e.getMessage()
                            + "); will prompt for dataset selection");
                    deferredPaths.add(path);
                    continue;
                }
                throw e;
            }
            if (source instanceof AbstractSpimData<?> spim) {
                bdv.show(spim, path); // path-aware overload populates spimDataFilePaths
            } else if (source instanceof SpimDataUtils.N5Sources n5) {
                bdv.show(n5);
            } else if (source instanceof ImgPlus<?> img) {
                //noinspection unchecked,rawtypes
                bdv.show((ImgPlus) img);
            }
        }
        for (final String path : deferredPaths)
            datasetDialog(path, bdv);
        loadReconstructions(bdv);
        loadMarkers(bdv);
        loadSeeds(bdv);
        return bdv;
    }

    private void loadMarkers(final AbstractBigViewer viewer) {
        if (markerFile == null) return;
        // Only pop open the standalone floating "Markers" dialog when this viewer has no SNT/SNTUI at all
        final boolean standalone = viewer.getSNT() == null || viewer.getSNT().getUI() == null;
        final String path = toPathString(markerFile);
        if (SpimDataUtils.isRemoteUrl(path)) {
            // fileAvailable() below only makes sense for local files: there is no cheap way to check
            // a remote URL's existence without a network round-trip, so just attempt the load directly
            // and let BookmarkManager#load(String) report a clear error if the URL turns out to be bad
            if (standalone) viewer.getMarkerManager().showPanel();
            viewer.getMarkerManager().load(path);
            return;
        }
        if (!SNTUtils.fileAvailable(markerFile)) {
            error(String.format("%s does not exist or is not available.", markerFile.getName()));
            return;
        }
        if (standalone) viewer.getMarkerManager().showPanel();
        viewer.getMarkerManager().load(markerFile); // error if invalid file
    }

    /**
     * Counterpart to {@link #loadMarkers(AbstractBigViewer)} for candidate tracing seeds.
     * <p>
     * Unlike bookmarks, {@link SeedOverlay} is owned by the {@link sc.fiji.snt.SNT} instance, not
     * the viewer (one overlay per SNT, shared across BVV/BDV), so there is nothing to load into
     * unless tracing is enabled. When it is, {@code SNTUI} has already wired the viewer's own
     * seed-rendering bridge by the time this runs (see {@code SNTUI#setBvv}/{@code #setBdv}),
     * so a plain overlay insert here is all that's needed for seeds to show up on screen too.
     */
    private void loadSeeds(final AbstractBigViewer viewer) {
        if (seedFile == null) return;
        final SNT viewerSnt = viewer.getSNT();
        if (viewerSnt == null) {
            error("Loading seeds requires \"Enable tracing (SNT Stream)\" to be checked.");
            return;
        }
        final String path = toPathString(seedFile);
        if (!SpimDataUtils.isRemoteUrl(path) && !SNTUtils.fileAvailable(seedFile)) {
            error(String.format("%s does not exist or is not available.", seedFile.getName()));
            return;
        }
        try {
            final SeedOverlay.CsvImportResult result = viewerSnt.getSeedOverlay().loadCsv(path, false);
            SNTUtils.log(String.format("Loaded %,d seed(s) (%,d row(s) skipped) from %s.",
                    result.seeds().size(), result.skipped(), seedFile.getName()));
        } catch (final IOException | SeedOverlay.CsvHeaderException ex) {
            error(String.format("Could not load seeds from %s: %s", seedFile.getName(), ex.getMessage()));
        }
    }

    /**
     * Loads reconstruction files, if any were specified: rendered in the viewer's overlay (same as before) and also
     * registered with {@link PathAndFillManager} so they show up as regular, editable Paths in the Path Manager
     * <p>
     * Coordinates are taken at face value, with no {@link SNT#getWorldOriginOffset() world-origin offset} correction
     * applied: unlike a freshly-traced {@link Tree} (which is known to be in the fallback-loaded source's own raw
     * pixel*spacing frame, see {@code GWDTTracerCommonCmd#applyWorldOriginOffsetIfAny}), an externally-supplied
     * SWC/reconstruction file carries no standardized way to say whether it needs that same correction or not.
     * Users who do need to correct for the offset should use the interactive "Import > SWC..." command instead
     * (once tracing has started), which supports specifying one manually (see {@code SWCImportDialog})
     */
    private void loadReconstructions(final AbstractBigViewer viewer) {
        if (recFiles == null) return;
        final String path = toPathString(recFiles);
        final Collection<Tree> trees = new ArrayList<>();
        if (SpimDataUtils.isRemoteUrl(path)) {
            // fileAvailable()/getReconstructionFiles() below are local-filesystem-only checks. Tree.listFromFile()
            // already knows how to handle a remote URL directly (a single reconstruction file is streamed, a .zip is
            // downloaded/extracted and treated as a directory), so route straight through it instead for this case
            try {
                trees.addAll(Tree.listFromFile(path));
            } catch (final IllegalArgumentException e) {
                error("Could not load reconstructions from " + path + ": " + GuiUtils.friendlyErrorMessage(e));
                return;
            }
            if (trees.isEmpty()) {
                error(String.format("No reconstructions found at %s.", path));
                return;
            }
            SNTUtils.log(String.format("Loading %d reconstruction(s) from %s.", trees.size(), path));
        } else {
            if (!SNTUtils.fileAvailable(recFiles)) {
                error(String.format("%s does not exist or is not available.", recFiles.getName()));
                return;
            }
            final File[] files = SNTUtils.getReconstructionFiles(recFiles, null);
            final int fileCount = (files == null) ? 0 : files.length;
            SNTUtils.log(String.format("Loading %d reconstruction file(s) from %s.", fileCount, recFiles.getAbsolutePath()));
            if (fileCount == 0) {
                error(String.format("No reconstruction files found in %s.", recFiles.getName()));
                return;
            }
            for (final File f : files) {
                try {
                    trees.addAll(Tree.listFromFile(f.getAbsolutePath()));
                } catch (final Exception ex) {
                    SNTUtils.log("Could not load " + f.getName() + ": " + ex.getMessage());
                }
            }
            if (trees.isEmpty()) {
                error(String.format("No reconstructions found in %s.", recFiles.getName()));
                return;
            }
        }
        TreeUtils.assignUniqueColorsIfUncolored(trees, "dim"); // Only color trees that don't already carry authored path/node colors
        viewer.add(trees); // renders in the viewer's overlay
        if (viewer.getSNT() != null) { // tracing capabilities present
            final PathAndFillManager pafm = viewer.getSNT().getPathAndFillManager();
            trees.forEach(tree -> pafm.addTree(tree, tree.getLabel())); // registers as editable Paths
            // addTree() (unlike addTrees()) intentionally skips this check, so trigger it explicitly here. If a SNTUI
            // exists, reuse its own persistent-warning dialog (same one shown by traditional-mode reconstruction
            // imports) instead of a one-off dialog of our own
            pafm.validateImageDimensions();
            if (viewer.getSNT().getUI() != null) {
                try {
                    viewer.getSNT().getUI().runCommand("validateImgDimensions");
                } catch (final IllegalArgumentException ignored) {
                    // command unavailable in the current UI state; RESIZE_REQUIRED (set above, if
                    // applicable) remains armed and will surface on the next reconstruction import
                }
            }
        } else {
            // No SNT/PathAndFillManager in this case (plain, non-tracing viewer): compare directly
            // against the viewer's own loaded volume instead
            warnIfOutOfBounds(viewer, trees);
        }
    }

    /**
     * Warns (once, with a permanent opt-out) if {@code trees} fall at least partially outside
     * {@code viewer}'s loaded volume -- typically a sign that the reconstruction and image files
     * specified are not a matching pair. Only used for the plain (non-tracing) viewer case; when
     * tracing capabilities are present, {@link PathAndFillManager#validateImageDimensions()} plus
     * {@code SNTUI}'s own dialog (see {@link #loadReconstructions}) is used instead
     */
    private void warnIfOutOfBounds(final AbstractBigViewer viewer, final Collection<Tree> trees) {
        final BoundingBox volumeBox = viewer.getBoundingBox();
        if (volumeBox == null) return;
        BoundingBox treesBox = null;
        for (final Tree tree : trees) {
            final BoundingBox tb = tree.getBoundingBox(true);
            if (tb == null) continue;
            if (treesBox == null) treesBox = tb.clone();
            else treesBox.combine(tb);
        }
        if (treesBox == null || volumeBox.contains(treesBox)
                || prefService.getBoolean(BigDataLoaderCmd.class, "oob-skipnag", false)) {
            return;
        }
        final Boolean skipNag = new GuiUtils(null).getPersistentWarning(
                "The loaded reconstruction(s) fall (at least partially) outside the loaded volume. "
                        + "This typically indicates the reconstruction and image are not a matching pair.",
                "Reconstruction Outside Image Bounds");
        if (skipNag != null) prefService.put(BigDataLoaderCmd.class, "oob-skipnag", skipNag);
    }

    /**
     * Checks whether {@code n5Sources} has a multi-resolution pyramid. If not, offers to build one locally via
     * {@link BvvUtils#synthesizeMipmapPyramid}, since BVV's 3D renderer requires one and becomes unresponsive w/o one!?
     *
     * @param n5Sources the sources to check
     * @param path      the original path, used only for the confirmation message
     * @return true if there is already a pyramid, or the user chose to build one locally; false if
     * the user chose to cancel
     */
    private static boolean confirmPyramidOrAbort(final SpimDataUtils.N5Sources n5Sources, final String path) {
        if (n5Sources.sources().isEmpty()) return true; // nothing to check; downstream logic already handles this
        final int nLevels = n5Sources.sources().getFirst().getSpimSource().getNumMipmapLevels();
        if (nLevels > 1) return true;
        final String message = String.format(
                "'%s' has no multi-resolution pyramid (a single resolution level only). Big Volume "
                        + "Viewer's 3D renderer requires one, so SNT can build one on the fly instead. "
                        + "Coarser levels are computed on demand; volumes that fit in memory are first "
                        + "copied locally, which may take a while for a remote dataset. Build a pyramid now?",
                new File(path).getName());
        // The loading splash screen (SNTUtils#setIsLoading(true), running since run() started) is an
        // always-on-top window that can end up rendered above this confirmation
        SNTUtils.setIsLoading(false, true);
        try {
            return new GuiUtils(null).getConfirmation(message, "Non-pyramidal N5/Zarr Dataset",
                    "Build Pyramid", "Cancel");
        } finally {
            SNTUtils.setIsLoading(true, true);
        }
    }

    /**
     * Rewraps every source in {@code n5Sources} via {@link BvvUtils#synthesizeMipmapPyramid},
     * preserving each source's existing converter. No-op for sources that already have a pyramid.
     * Level 0 is copied into memory only if all sources fit comfortably in the JVM heap; otherwise
     * the sources are used as they are and only the coarser levels are synthesized (lazily)
     *
     * @param n5Sources the sources to rewrap
     * @return an equivalent {@link SpimDataUtils.N5Sources} with pyramid-backed sources
     * @throws IllegalStateException if there is not enough memory to build the pyramid
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SpimDataUtils.N5Sources withSyntheticPyramid(final SpimDataUtils.N5Sources n5Sources) {
        // Nothing to do (nor to log) if every source already has a pyramid
        if (n5Sources.sources().stream().allMatch(soc -> soc.getSpimSource().getNumMipmapLevels() > 1))
            return n5Sources;
        long needed = 0;
        for (final SourceAndConverter<?> soc : n5Sources.sources())
            needed += BvvUtils.estimateLocalCopyBytes(soc.getSpimSource());
        final long available = (long) (Runtime.getRuntime().maxMemory() * 0.5);
        final boolean copyLocally = needed <= available;
        if (!copyLocally) {
            SNTUtils.log(String.format("BVV: local copy would need ~%s but only %s can be used; "
                    + "building the pyramid lazily instead", SNTUtils.formatBytes(needed),
                    SNTUtils.formatBytes(available)));
        }
        try {
            final List<SourceAndConverter<?>> rewrapped = (List<SourceAndConverter<?>>) (List<?>) n5Sources
                    .sources()
                    .stream()
                    .map(soc -> new SourceAndConverter(
                            BvvUtils.synthesizeMipmapPyramid((Source) soc.getSpimSource(), 3, copyLocally),
                            soc.getConverter()))
                    .toList();
            return new SpimDataUtils.N5Sources(rewrapped, n5Sources.numTimepoints(), n5Sources.name());
        } catch (final OutOfMemoryError oom) {
            throw new IllegalStateException("Not enough memory to build a pyramid for '" + n5Sources.name()
                    + "'. Increase the amount of memory available to Fiji/SNT, or convert the image to a multi-resolution "
                    + "N5.", oom);
        }
    }

    /**
     * Makes every source of {@code n5Sources} streamable by BVV (see {@link BvvUtils#ensureCellBacked})
     *
     * @param n5Sources the sources to check
     * @return {@code n5Sources} itself if no source needed wrapping, or an equivalent set of sources
     */
    private static SpimDataUtils.N5Sources cellBacked(final SpimDataUtils.N5Sources n5Sources) {
        final List<SourceAndConverter<?>> wrapped = new ArrayList<>();
        boolean changed = false;
        for (final SourceAndConverter<?> soc : n5Sources.sources()) {
            final SourceAndConverter<?> w = BvvUtils.ensureCellBacked(soc, n5Sources.chunkShape());
            changed |= (w != soc);
            wrapped.add(w);
        }
        return changed ? new SpimDataUtils.N5Sources(wrapped, n5Sources.numTimepoints(), n5Sources.name(),
                n5Sources.chunkShape()) : n5Sources;
    }

    /**
     * Looks for a BDV XML descriptor next to an N5 container (same base name, e.g., written by the
     * former ConvertToN5 recipe) that describes a multi-resolution pyramid. Such a descriptor carries the
     * downsampling factors, which are not always discoverable from the N5 container alone
     *
     * @param n5Path the path to the {@code .n5} directory
     * @return the multi-resolution data described by the XML, or {@code null} if there is none
     */
    private static AbstractSpimData<?> pyramidalSiblingXml(final String n5Path) {
        final String lower = n5Path.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".n5") && !lower.endsWith(".n5/")) return null;
        final String base = n5Path.endsWith("/") ? n5Path.substring(0, n5Path.length() - 1) : n5Path;
        final File xml = new File(base.substring(0, base.length() - 3) + ".xml");
        if (!xml.isFile()) return null;
        try {
            if (SpimDataUtils.resolvePathToSource(xml.getAbsolutePath()) instanceof AbstractSpimData<?> spim
                    && spim.getSequenceDescription().getImgLoader() instanceof bdv.ViewerImgLoader loader
                    && !spim.getSequenceDescription().getViewSetupsOrdered().isEmpty()
                    && loader.getSetupImgLoader(spim.getSequenceDescription().getViewSetupsOrdered().getFirst()
                    .getId()).numMipmapLevels() > 1) {
                SNTUtils.log("BVV: using multi-resolution XML next to non-pyramidal N5: " + xml);
                return spim;
            }
        } catch (final RuntimeException e) {
            SNTUtils.log("BVV: ignoring unusable XML sibling '" + xml + "': " + e.getMessage());
        }
        return null;
    }


    /**
     * Calls {@link #awaitBvvDataOrPrompt} for every source in {@code n5Sources}
     *
     * @param n5Sources the sources about to be shown in BVV
     * @return true if the caller should proceed to show {@code n5Sources}; false if the user canceled
     */
    private static boolean awaitBvvSourcesReady(final SpimDataUtils.N5Sources n5Sources) {
        for (final SourceAndConverter<?> soc : n5Sources.sources()) {
            if (!awaitBvvDataOrPrompt(soc.getSpimSource())) return false;
        }
        return true;
    }

    /**
     * Ensures {@code source}'s data is fetched before it is first shown in BVV, since BVV's own
     * first-paint fetch happens synchronously on the EDT and can freeze the GUI with no feedback
     * (see {@link BvvUtils#prefetchForShow}). Also decides up front whether {@code source} can use
     * BVV's pyramid-aware renderer instead of its single-texture fallback (see {@link
     * BvvUtils#preferMultiResolutionIfSafe}), since that decision determines which level needs to be
     * warmed. If the fetch is taking unusually long (slow/remote connection), prompts the user to
     * keep waiting, proceed anyway, or cancel, doubling the wait budget each time they choose to
     * keep waiting
     *
     * @param source the source about to be shown in BVV
     * @return true if the caller should proceed to show {@code source}; false if the user canceled
     */
    private static boolean awaitBvvDataOrPrompt(final Source<?> source) {
        GuiUtils.setSplashMessage("Fetching '" + source.getName() + "'...");
        BvvUtils.preferMultiResolutionIfSafe(source, 0);
        // Submitted once and waited on repeatedly below (rather than resubmitted via SNTUtils#runWithTimeout
        // on every retry), so a fetch that keeps running while the user is away from the "keep waiting?"
        // prompt is not silently abandoned and restarted from scratch once they return.
        final SNTUtils.BackgroundTask<Void> task = SNTUtils.submitBackground(() -> {
            BvvUtils.prefetchForShow(source, 0);
            return null;
        }, "SNT-BVV-Prefetch");
        try {
            long timeoutSeconds = INITIAL_PREFETCH_TIMEOUT_SECONDS;
            while (true) {
                try {
                    task.future().get(timeoutSeconds, TimeUnit.SECONDS);
                    return true;
                } catch (final TimeoutException te) {
                    final String message = String.format(
                            """
                            <html>
                            <i>%s</i> is taking longer than %ds to load (slow or remote connection?).<br>
                            Fetching continues in the background no matter what you choose here, so it is safe to<br>
                            leave this dialog alone: it closes on its own once loading finishes.
                            <dl>
                            <dt><b>Keep Waiting</b></dt>
                            <dd>Simply dismisses the dialog for now</dd>
                            <dt><b>Open As-is</b></dt>
                            <dd>Attempts to open the volume immediately (may render incorrectly until fully loaded)</dd>
                            <dt><b>Abort</b></dt>
                            <dd>Stops the fetch</dd>
                            </dl>""",
                            GuiUtils.Text.escapeHtml(source.getName()), timeoutSeconds);
                    SNTUtils.setIsLoading(false, true);
                    final Optional<String> choice;
                    try {
                        choice = new GuiUtils(null).getChoiceRaceable(message, "BVV: Slow Remote Volume",
                                new String[]{"Keep Waiting", "Open As-is", "Abort"}, "Keep Waiting", task.future());
                    } finally {
                        SNTUtils.setIsLoading(true, true);
                    }
                    if (choice.isEmpty()) {
                        // Dismissed without an explicit answer. If the task actually finished (the watcher auto-closed
                        // the dialog), task.future().get() above will now return immediately; otherwise the user closed
                        // the dialog without picking an option, which we treat the same as "Keep Waiting" so the next
                        // prompt reports the correct (doubled) elapsed timeout
                        if (!task.future().isDone()) timeoutSeconds *= 2;
                        continue;
                    }
                    if ("Abort".equals(choice.get())) return false;
                    if ("Open As-is".equals(choice.get())) return true; // task keeps running in the background; may still warm the cache
                    timeoutSeconds *= 2; // "Keep Waiting"
                }
            }
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } catch (final ExecutionException ee) {
            final Throwable cause = (ee.getCause() != null) ? ee.getCause() : ee;
            if (cause instanceof RuntimeException re) throw re; // preserve original control-flow
            throw new RuntimeException("Failed while fetching '" + source.getName() + "' for BVV", cause);
        } finally {
            task.cancel(); // no-op if already finished; ensures we never leak the executor/thread
        }
    }

    /**
     * Handles an ImgPlus whose spatial dimensions exceed the GPU's 3D texture
     * limit. Prompts the user to choose between aborting, downsampling, loading at
     * full resolution as a tiled pyramid (view-only sessions with enough heap), or
     * converting it to a multi-resolution OME-Zarr that is then opened.
     *
     * @return the (possibly downsampled) source to display, or {@code null} if
     *         the user chose to abort
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object handleOversizedImage(final ImgPlus<?> img, final int maxTexSize, final String path,
                                        final boolean tracing) {
        final String reason = ImgUtils.exceedsDimension(img, maxTexSize)
                ? String.format("has spatial dimensions that exceed your GPU's 3D texture limit (%d texels)", maxTexSize)
                : String.format("has more voxels per channel (%.2f G) than BVV can upload as a single texture (%.2f G)",
                img.dimension(0) * (double) img.dimension(1) * img.dimension(2) / 1e9,
                BvvUtils.MAX_SINGLE_TEXTURE_VOXELS / 1e9);
        final String choice = promptOversizedImageChoice(img, reason, path, tracing);

        if (choice == null || ABORT.equals(choice)) {
            cancel("");
            return null;
        }
        if (CONVERT.equals(choice)) {
            final Object converted = convertToOmeZarr(img, path);
            if (converted == null) {
                cancel("");
                return null;
            }
            return converted;
        }
        if (FULL_RES.equals(choice)) {
            GuiUtils.setSplashMessage("Building full-resolution pyramid for '" + img.getName() + "'...");
            try {
                return BvvUtils.buildTiledPyramid((ImgPlus) img, 3);
            } catch (final OutOfMemoryError oom) {
                SNTUtils.log("BVV: out of memory building tiled pyramid; falling back to downsampling");
                new GuiUtils(null).error("Not enough memory to load '" + img.getName() + "' at full resolution. "
                        + "Downsampling instead. Increase the amount of memory available to Fiji/SNT to avoid this.");
            }
        }
        return ImgUtils.downsampleToFit((ImgPlus) img, maxTexSize, BvvUtils.MAX_SINGLE_TEXTURE_VOXELS);
    }

    /** An option of the prompt of {@link #promptOversizedImageChoice}; {@code unavailableReason} is null if it can be chosen */
    private record ChoiceItem(String label, String description, String unavailableReason) {
        boolean available() {
            return unavailableReason == null;
        }
    }

    /**
     * Asks the user how to handle an image that cannot be uploaded to the GPU as a single texture. All options are
     * listed with a short explanation; those that cannot be chosen are struck through, together with the reason why
     *
     * @param img     the oversized image
     * @param reason  why the image is too large, to complete the sentence "The image ... "
     * @param path    the path of the image (where a converted copy would be written)
     * @param tracing whether the image is also being used for tracing
     * @return the label of the chosen option, or {@code null} if the prompt was dismissed
     */
    private String promptOversizedImageChoice(final ImgPlus<?> img, final String reason, final String path,
                                              final boolean tracing) {
        final boolean layoutOk = hasSupportedLayout(img);
        final String fullResDescription = layoutOk
                ? String.format("Loads every voxel into memory as a tiled, multi-resolution pyramid. Needs ~%s of "
                + "memory and may take a minute or so. This is only a rendering: It cannot be used for tracing.",
                SNTUtils.formatBytes(BvvUtils.estimateTiledPyramidBytes(img)))
                : "Loads every voxel into memory as a tiled, multi-resolution pyramid. This is only a rendering: It "
                + "cannot be used for tracing.";
        final List<ChoiceItem> items = List.of(
                new ChoiceItem(DOWNSAMPLE, "Shrinks the image so that it fits in a single texture. Opens quickly, but "
                        + "fine detail is lost.", null),
                new ChoiceItem(FULL_RES, fullResDescription, fullResUnavailableReason(img, tracing)),
                new ChoiceItem(CONVERT, "Writes a multi-resolution '" + GuiUtils.Text.escapeHtml(omeZarrName(path))
                        + "' folder next to the original image and opens it. May take several minutes, but the result is "
                        + "streamed from disk, so it opens quickly and needs little memory.",
                        convertUnavailableReason(img, path)));

        final StringBuilder message = new StringBuilder("<html><body><div style='width:520px;'>")
                .append("The image <i>").append(GuiUtils.Text.escapeHtml(img.getName())).append("</i> ")
                .append(GuiUtils.Text.escapeHtml(reason)).append(".<br><br>")
                .append("What would you like to do? Your options are:<ol>");
        for (final ChoiceItem item : items) {
            message.append("<li>");
            if (item.available()) {
                message.append("<b>").append(item.label()).append("</b>: ").append(item.description());
            } else {
                message.append("<strike><b>").append(item.label()).append("</b></strike>: ")
                        .append(item.description()).append(" <i>This option is not available: ")
                        .append(item.unavailableReason()).append("</i>");
            }
            message.append("</li>");
        }
        message.append("</ol></div></body></html>");

        final List<String> choices = new ArrayList<>();
        for (final ChoiceItem item : items) {
            if (item.available()) choices.add(item.label());
        }
        choices.add(ABORT);
        // See confirmPyramidOrAbort(): the loading splash screen can end up rendered on top of this
        // dialog, so hide it for the duration of the prompt and restore it afterward
        SNTUtils.setIsLoading(false, true);
        try {
            return new GuiUtils(null).getChoice(message.toString(), "BVV: Volume Too Large",
                    choices.toArray(new String[0]), DOWNSAMPLE);
        } finally {
            SNTUtils.setIsLoading(true, true);
        }
    }

    /**
     * Why {@code img} cannot be loaded as a tiled pyramid (see {@link BvvUtils#buildTiledPyramid}): while tracing
     * (the image would be held in memory twice), for an unsupported layout, or if its estimated footprint exceeds
     * ~70% of the JVM's max heap
     *
     * @return the reason, or {@code null} if it can
     */
    private static String fullResUnavailableReason(final ImgPlus<?> img, final boolean tracing) {
        if (tracing) return "the image is also used for tracing and would be held in memory twice.";
        if (!hasSupportedLayout(img)) return unsupportedLayoutReason();
        final long needed = BvvUtils.estimateTiledPyramidBytes(img);
        final long available = (long) (Runtime.getRuntime().maxMemory() * 0.7);
        if (needed > available) {
            return String.format("it needs ~%s of memory but only %s can be used. Please increase the amount of memory " +
                            "available to Fiji/SNT.",
                    SNTUtils.formatBytes(needed), SNTUtils.formatBytes(available));
        }
        return null;
    }

    /** Why {@code img} cannot be converted to OME-Zarr next to {@code path}, or {@code null} if it can */
    private static String convertUnavailableReason(final ImgPlus<?> img, final String path) {
        if (!hasSupportedLayout(img)) return unsupportedLayoutReason();
        if (!canWriteBeside(path)) return "the folder containing the image is not a local, writable folder.";
        return null;
    }

    private static String unsupportedLayoutReason() {
        return "it requires X, Y, Z[, C] axes, numeric pixels and no time series.";
    }

    /**
     * Whether {@code img} has an XYZ[C] layout, no time series, and a numeric native type, i.e., it can be handled by
     * {@link BvvUtils#buildTiledPyramid} and {@link ImgUtils#saveAsOmeZarr}
     */
    private static boolean hasSupportedLayout(final ImgPlus<?> img) {
        final int tDim = img.dimensionIndex(net.imagej.axis.Axes.TIME);
        return ImgUtils.hasXYZLeading(img) && (tDim < 0 || img.dimension(tDim) <= 1)
                && img.firstElement() instanceof net.imglib2.type.numeric.RealType
                && img.firstElement() instanceof net.imglib2.type.NativeType;
    }

    /** Whether the directory containing {@code path} is a local, writable directory */
    private static boolean canWriteBeside(final String path) {
        final File parent = new File(path).getAbsoluteFile().getParentFile();
        return parent != null && parent.isDirectory() && parent.canWrite();
    }

    /** The name of the OME-Zarr directory written by {@link #convertToOmeZarr} for the image at {@code path} */
    private static String omeZarrName(final String path) {
        return new File(path).getName().replaceFirst("\\.[^.]+$", "") + ".ome.zarr";
    }

    /** Best-effort recursive deletion of a (partial) output directory */
    private static void deleteQuietly(final File dir) {
        try (final java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(dir.toPath())) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (final Exception ignored) {
            // nothing to do
        }
    }

    /**
     * Converts {@code img} to a multi-resolution OME-Zarr next to {@code path} (see
     * {@link ImgUtils#saveAsOmeZarr}), and resolves the result as a BVV source. An existing output is never
     * overwritten: a numeric suffix is appended to the name instead
     *
     * @return the pyramid-backed sources of the converted image, or {@code null} if the conversion failed (the user
     * has been told why)
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object convertToOmeZarr(final ImgPlus<?> img, final String path) {
        final File parent = new File(path).getAbsoluteFile().getParentFile();
        final String name = omeZarrName(path);
        File out = new File(parent, name);
        for (int i = 1; out.exists(); i++)
            out = new File(parent, name.replace(".ome.zarr", "_" + i + ".ome.zarr"));
        SNTUtils.log("BVV: converting '" + path + "' to " + out);
        GuiUtils.setSplashMessage("Converting '" + img.getName() + "' to OME-Zarr...");
        try {
            final long start = System.currentTimeMillis();
            ImgUtils.saveAsOmeZarr((ImgPlus) img, out, SNTPrefs.getThreads(), GuiUtils::setSplashMessage);
            SNTUtils.log("BVV: conversion finished in " + (System.currentTimeMillis() - start) / 1000 + "s");
            final Object resolved = SpimDataUtils.resolvePathToSource(out.getAbsolutePath());
            if (resolved instanceof SpimDataUtils.N5Sources n5
                    && (n5.sources().isEmpty() || n5.sources().getFirst().getSpimSource().getNumMipmapLevels() > 1))
                return resolved;
            GuiUtils.errorPrompt("'" + out.getName() + "' was written but could not be read back as a "
                    + "multi-resolution image. Please report this issue.", true);
        } catch (final Exception | OutOfMemoryError e) {
            SNTUtils.error("BVV: OME-Zarr conversion failed", e instanceof Exception ex ? ex : new RuntimeException(e),
                    false);
            deleteQuietly(out); // do not leave a partial container behind
            GuiUtils.errorPrompt("Conversion failed: " + GuiUtils.friendlyErrorMessage(e), true);
        }
        return null;
    }


    private void error(final String msg) {
        GuiUtils.errorPrompt(msg, true);
        cancel("");
    }

}
