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

package sc.fiji.snt.viewer;

import bdv.tools.InitializeViewerState;
import bdv.tools.brightness.ConverterSetup;
import bdv.viewer.ConverterSetups;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerState;
import ij.ImagePlus;
import ij.io.FileInfo;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.util.ImgUtils;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Static helpers shared by {@link Bdv} and {@link Bvv}: persistence of viewer
 * settings (display range, colors) and initialization of display ranges
 */
final class ViewerSettingsUtils {

    private ViewerSettingsUtils() {
    }

    /** Wall-clock budget (seconds) for {@link #initBrightnessSafely}. */
    private static final long BRIGHTNESS_INIT_TIMEOUT_SECONDS = 30;

    /**
     * Cumulative cutoff (0-1 fraction) for the low/high ends of the auto-brightness percentile range: 1%/99%.
     * A general-purpose default, not a formal standard like IJ's  "Auto" B&C button that saturates to 0.35%
     * (that needs a full histogram, which we won't compute)
     * {@link #initBrightnessSafely(ViewerState, ConverterSetups, String)} (expects a 0-1 fraction)
     * and {@link #initBrightnessSafely(SourceAndConverter, ConverterSetup, int, String)} (expects a 0-100 percentile)
     */
    private static final double BRIGHTNESS_CUTOFF_LOW = 0.01; // 1%
    private static final double BRIGHTNESS_CUTOFF_HIGH = 0.99; // 99%

    /**
     * Computes and applies a display range from data percentiles ({@link InitializeViewerState#initBrightness})
     * on a bounded background thread so that a remote N5/Zarr/SPIM data hit by a bad chunk or network stall cannot
     * block the caller indefinitely (see {@link SNTUtils#runWithTimeout}).
     * Callers should invoke this off the EDT; a caller that doesn't is still bounded by the timeout, just at the
     * cost of freezing the UI for up to {@link #BRIGHTNESS_INIT_TIMEOUT_SECONDS} seconds instead of indefinitely.
     * <p>
     * On timeout or any other failure, the failure is logged and swallowed rather than thrown: a slow/failed
     * brightness estimate should never prevent a viewer from opening, or block whatever triggered this call
     * (initial load, or a manual "Auto Brightness/Contrast" button click).
     *
     * @param state  the viewer state to sample and update
     * @param setups the converter setups whose display ranges are updated
     * @param label  short, human-readable description of the viewer/dataset (used only in the failure log)
     */
    static void initBrightnessSafely(final ViewerState state,
            final ConverterSetups setups, final String label) {
        try {
            SNTUtils.runWithTimeout(() -> {
                InitializeViewerState.initBrightness(BRIGHTNESS_CUTOFF_LOW, BRIGHTNESS_CUTOFF_HIGH, state, setups);
                return null;
            }, BRIGHTNESS_INIT_TIMEOUT_SECONDS, "computing display range for " + label);
        } catch (final IOException e) {
            SNTUtils.log("Could not auto-adjust brightness/contrast for " + label + " (" + e.getMessage()
                    + "); keeping current display range. Use the 'Auto Brightness/Contrast' button to retry.");
        }
    }

    /**
     * Single-source counterpart of {@link #initBrightnessSafely(bdv.viewer.ViewerState,
     * ConverterSetups, String)}, for {@link AbstractBigViewer.BrightnessScope#CURRENT}/{@link AbstractBigViewer.BrightnessScope#ACTIVE}.
     * {@link InitializeViewerState}. This samples the source's own data directly via {@link ImgUtils#computePercentile}
     * (max 100k pixels, regardless of image size) at its coarsest available resolution level
     */
    static void initBrightnessSafely(final SourceAndConverter<?> source, final ConverterSetup setup,
            final int timepoint, final String label) {
        try {
            SNTUtils.runWithTimeout(() -> {
                final var spimSource = source.getSpimSource();
                final int level = Math.max(0, spimSource.getNumMipmapLevels() - 1); // coarsest level
                @SuppressWarnings("unchecked")
                final net.imglib2.RandomAccessibleInterval<? extends net.imglib2.type.numeric.RealType<?>> rai =
                        (net.imglib2.RandomAccessibleInterval<? extends net.imglib2.type.numeric.RealType<?>>)
                                spimSource.getSource(timepoint, level);
                final double min = ImgUtils.computePercentile(rai, BRIGHTNESS_CUTOFF_LOW * 100);
                final double max = ImgUtils.computePercentile(rai, BRIGHTNESS_CUTOFF_HIGH * 100);
                setup.setDisplayRange(min, max);
                return null;
            }, BRIGHTNESS_INIT_TIMEOUT_SECONDS, "computing display range for " + label);
        } catch (final IOException e) {
            SNTUtils.log("Could not auto-adjust brightness/contrast for " + label + " (" + e.getMessage()
                    + "); keeping current display range. Use the 'Auto Brightness/Contrast' button to retry.");
        }
    }

    /**
     * Dispatches {@link AbstractBigViewer#applyAutoBrightness(AbstractBigViewer.BrightnessScope)} for a given scope: the single {@link
     * #getCurrentSource() current source}, every currently active source ({@link
     * bdv.viewer.ViewerState#isSourceActive}), or (for {@link AbstractBigViewer.BrightnessScope#ALL}) the whole scene via the
     * aggregate {@link #initBrightnessSafely(ViewerState, ConverterSetups, String)}.
     * Shared by {@link Bdv} and {@link Bvv}
     *
     * @param scope         which source(s) to recompute
     * @param state         the viewer state (sources, active flags)
     * @param setups        the converter setups (source -> display-range control lookup)
     * @param currentSource the viewer's current/selected source, or null if none
     * @param label         short, human-readable description of the viewer (for logging)
     */
    static void applyBrightnessScope(final AbstractBigViewer.BrightnessScope scope, final ViewerState state,
            final ConverterSetups setups, final SourceAndConverter<?> currentSource, final String label) {
        final int timepoint = state.getCurrentTimepoint();
        switch (scope) {
            case ALL -> initBrightnessSafely(state, setups, label);
            case CURRENT -> {
                if (currentSource == null) return;
                final ConverterSetup setup = setups.getConverterSetup(currentSource);
                if (setup != null) initBrightnessSafely(currentSource, setup, timepoint, label + " (current source)");
            }
            case ACTIVE -> {
                for (final SourceAndConverter<?> sac : state.getSources()) {
                    if (!state.isSourceActive(sac)) continue;
                    final ConverterSetup setup = setups.getConverterSetup(sac);
                    if (setup != null) initBrightnessSafely(sac, setup, timepoint, label + " (active sources)");
                }
            }
        }
    }


    /**
     * Maps the converter setup ids of a viewer to the names of their sources
     *
     * @param state  the viewer state (may be null)
     * @param setups the converter setups (may be null)
     * @return the id to source name map (empty if state or setups are null)
     */
    static Map<Integer, String> idToName(final ViewerState state, final ConverterSetups setups) {
        final Map<Integer, String> map = new LinkedHashMap<>();
        if (state == null || setups == null) return map;
        for (final SourceAndConverter<?> soc : state.getSources()) {
            final ConverterSetup cs = setups.getConverterSetup(soc);
            if (cs != null) map.put(cs.getSetupId(), soc.getSpimSource().getName());
        }
        return map;
    }

    /**
     * Reads an XML file
     *
     * @param f the file to be read
     * @return the parsed document
     * @throws JDOMException if the file is not valid XML
     * @throws IOException   if the file could not be read
     */
    static Document readXml(final File f) throws JDOMException, IOException {
        return new SAXBuilder().build(f);
    }

    /**
     * Writes a document to a file, using a pretty format
     *
     * @param doc the document to be written
     * @param f   the destination file
     * @throws IOException if the file could not be written
     */
    static void writeXml(final Document doc, final File f) throws IOException {
        try (final FileWriter out = new FileWriter(f)) {
            new XMLOutputter(Format.getPrettyFormat()).output(doc, out);
        }
    }

    /**
     * Returns an identifier for an image: its path if it was opened from disk,
     * its title otherwise
     *
     * @param imp the image
     * @return the identifier, never null
     */
    static String idOf(final ImagePlus imp) {
        final FileInfo fi = imp.getOriginalFileInfo();
        return (fi != null && fi.directory != null && fi.fileName != null)
                ? fi.directory + fi.fileName : imp.getTitle();
    }

    /**
     * Builds the name of the settings file for a dataset: a readable title, a
     * short hash of the dataset id and number of sources (so that distinct
     * datasets sharing a title do not collide) and the viewer tag
     *
     * @param title    the dataset title
     * @param id       the dataset identifier (e.g., path)
     * @param nSources the number of sources displayed
     * @param tag      the viewer tag, e.g., "bdv" or "bvv"
     * @return the file name, e.g., "stack.tif-1a2b3c4d-bdv.xml"
     */
    static String settingsFileName(final String title, final String id, final int nSources, final String tag) {
        String safe = (title == null) ? "" : title.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (safe.length() > 60) safe = safe.substring(0, 60);
        if (safe.isBlank()) safe = "viewer";
        final String hash = String.format("%08x", (id + "|" + nSources).hashCode());
        return safe + "-" + hash + "-" + tag + ".xml";
    }

    /**
     * Checks if a settings file can be restored by a viewer, i.e., if it is
     * valid and holds as many setups as the viewer has sources
     *
     * @param f        the settings file
     * @param idToName the current converter setup ids and their source names
     * @return true if compatible
     */
    static boolean isCompatible(final File f, final Map<Integer, String> idToName) {
        try {
            final List<Element> nodes = converterSetupNodes(readXml(f));
            return nodes != null && nodes.size() == idToName.size();
        } catch (final Exception ex) {
            return false;
        }
    }

    private static List<Element> converterSetupNodes(final Document doc) {
        final Element sa = doc.getRootElement().getChild("SetupAssignments");
        if (sa == null || sa.getChild("ConverterSetups") == null) return null;
        return sa.getChild("ConverterSetups").getChildren("ConverterSetup");
    }

    /**
     * Adds a name element to each ConverterSetup node of a settings file saved
     * by BDV/BVV. Both ignore unknown elements so the file remains loadable.
     * Failures are logged and swallowed
     *
     * @param f        the settings file
     * @param idToName the current converter setup ids and their source names
     */
    static void tagSourceNames(final File f, final Map<Integer, String> idToName) {
        try {
            final Document doc = readXml(f);
            final List<Element> nodes = converterSetupNodes(doc);
            if (nodes == null) return;
            for (final Element node : nodes) {
                final String name = idToName.get(Integer.parseInt(node.getChildText("id")));
                if (name != null) node.addContent(new Element("name").setText(name));
            }
            writeXml(doc, f);
        } catch (final Exception ex) {
            SNTUtils.log("Settings: could not tag source names: " + ex);
        }
    }

    /**
     * Converter setup ids change between viewer instances (e.g., 2-5 when first
     * opened, 3-6 after closing and reopening), but BDV/BVV restore settings by
     * id and fail with an NPE (leaving their groups corrupted) when ids differ.
     * This remaps the saved ids onto the current ones (by source name when
     * saved in the file, by position otherwise), validates the setup count and
     * replaces out-of-range group ids (BVV may write -1). A temporary file is
     * returned when changes were needed
     *
     * @param f        the settings file
     * @param idToName the current converter setup ids and their source names
     * @return the file to be loaded: f itself, or a temporary file
     * @throws IllegalArgumentException if the number of setups does not match
     */
    static File remapIds(final File f, final Map<Integer, String> idToName) throws Exception {
        final Document doc = readXml(f);
        final List<Element> nodes = converterSetupNodes(doc);
        if (nodes == null) return f;
        if (nodes.size() != idToName.size()) {
            throw new IllegalArgumentException(String.format(
                    "Settings file has %d channels/sources but viewer has %d", nodes.size(), idToName.size()));
        }
        Boolean changed = remapByName(nodes, idToName);
        if (changed == null) {
            SNTUtils.log("Settings: source names unavailable or not unique/matching: mapping by position");
            changed = remapByPosition(nodes, idToName);
        }
        changed |= repairGroupIds(doc, nodes);
        if (!changed) return f;
        final File tmp = File.createTempFile("snt-viewer-settings", ".xml");
        writeXml(doc, tmp);
        SNTUtils.log("Settings: saved setup/group ids adjusted to current viewer");
        return tmp;
    }

    /** Returns null if names cannot be used, otherwise whether any id changed */
    private static Boolean remapByName(final List<Element> nodes, final Map<Integer, String> idToName) {
        final Map<String, Integer> byName = new HashMap<>();
        for (final var entry : idToName.entrySet()) {
            if (byName.put(entry.getValue(), entry.getKey()) != null) return null; // duplicated names
        }
        final Set<String> saved = new HashSet<>();
        for (final Element n : nodes) {
            final String name = n.getChildText("name");
            if (name == null || !byName.containsKey(name) || !saved.add(name)) return null;
        }
        boolean changed = false;
        for (final Element n : nodes) {
            changed |= setText(n, "id", String.valueOf(byName.get(n.getChildText("name"))));
        }
        return changed;
    }

    private static boolean remapByPosition(final List<Element> nodes, final Map<Integer, String> idToName) {
        final List<Integer> currentIds = new ArrayList<>(idToName.keySet());
        Collections.sort(currentIds);
        final List<Element> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparingInt(n -> Integer.parseInt(n.getChildText("id"))));
        boolean changed = false;
        for (int i = 0; i < sorted.size(); i++) {
            changed |= setText(sorted.get(i), "id", String.valueOf(currentIds.get(i)));
        }
        return changed;
    }

    /** BVV may write groupId=-1, which makes the restore fail */
    private static boolean repairGroupIds(final Document doc, final List<Element> nodes) {
        final Element groups = doc.getRootElement().getChild("SetupAssignments").getChild("MinMaxGroups");
        final int nGroups = (groups == null) ? 0 : groups.getChildren("MinMaxGroup").size();
        if (nGroups == 0) return false;
        boolean changed = false;
        for (int i = 0; i < nodes.size(); i++) {
            final String txt = nodes.get(i).getChildText("groupId");
            final int gid = (txt == null) ? -1 : Integer.parseInt(txt.trim());
            if (gid < 0 || gid >= nGroups) {
                changed |= setText(nodes.get(i), "groupId", String.valueOf(Math.min(i, nGroups - 1)));
            }
        }
        return changed;
    }

    /** Sets the text of a child element (created if missing). Returns true if it changed */
    private static boolean setText(final Element parent, final String child, final String text) {
        Element e = parent.getChild(child);
        if (e == null) {
            e = new Element(child);
            parent.addContent(e);
        } else if (text.equals(e.getText().trim())) {
            return false;
        }
        e.setText(text);
        return true;
    }
}
