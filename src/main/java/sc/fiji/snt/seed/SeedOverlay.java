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

package sc.fiji.snt.seed;

import net.imglib2.display.ColorTable;
import sc.fiji.snt.util.ColorMaps;
import smile.neighbor.KDTree;
import smile.neighbor.Neighbor;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Transient, in-memory store of {@link SeedPoint}s used to render candidate
 * tracing seeds (e.g., the output of a deep-learning point detector) on
 * SNT's canvas and to feed them to downstream consumers (autotracers,
 * click-to-trace anchors).
 * <p>
 * <b>Lifecycle:</b> not persisted with the {@code .traces} file. Cleared
 * when the active image changes (see {@link sc.fiji.snt.SNT#initialize(ij.ImagePlus)}).
 * Users may import additional CSVs (with {@link sc.fiji.snt.gui.cmds.ImportSeedPointsCmd}
 * "Import Seed Points (CSV)…" or another command) at any time.
 * <p>
 * <b>Threading:</b> intended to be accessed from the Swing EDT (panel UI,
 * canvas paint). Bulk loaders should use {@link #addAll(Collection)} to
 * trigger a single listener fire and a single spatial-index rebuild.
 * <p>
 * <b>Spatial queries:</b> {@link #nearest(double, double, double, double)}
 * uses a lazily-built KD-tree from Smile. The tree is invalidated by any
 * mutating call; threshold/visibility changes do not invalidate it (queries
 * are not threshold-aware by default: callers should pre-filter via
 * {@link #filtered()} if threshold-aware nearest-neighbor is required).
 *
 * @author Tiago Ferreira
 * @see SeedPoint
 */
public class SeedOverlay {

    /**
     * Listener fired on any change to the overlay (add/clear/threshold/visibility).
     * Implementations should be cheap; repaint requests are typical.
     */
    public interface SeedOverlayListener {
        /**
         * Called after the overlay's contents, threshold, or visibility changed.
         *
         * @param source the overlay that changed
         */
        void seedOverlayChanged(SeedOverlay source);
    }

    /**
     * Default LUT name (resolved via {@link ColorMaps#get(String)}).
     */
    public static final String DEFAULT_COLOR_TABLE_NAME = "Viridis";

    private final List<SeedPoint> seeds = new ArrayList<>();
    /**
     * Reference-keyed selection set. Insertion-ordered for deterministic
     * iteration when reporting "selected seeds" to consumers. Selection
     * survives reorderings of {@link #seeds} (no index drift). Mutators that
     * remove seeds also remove them from this set.
     */
    private final Set<SeedPoint> selectedSeeds = new LinkedHashSet<>();
    private final List<SeedOverlayListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Lower bound (inclusive) on confidence for a seed to be considered visible.
     */
    private double lowConfidence = 0.0;
    /**
     * Upper bound (inclusive) on confidence for a seed to be considered visible.
     */
    private double highConfidence = 1.0;
    private boolean visible = true;
    private ColorTable colorTable = ColorMaps.get(DEFAULT_COLOR_TABLE_NAME);
    private String colorTableName = DEFAULT_COLOR_TABLE_NAME;
    /**
     * Global transparency multiplier in {@code [0, 1]}. Renderer multiplies
     * this into the per-seed alpha so users can fade the whole overlay without
     * losing per-seed contrast information from confidence/depth.
     * {@code 1.0} = fully opaque (default).
     */
    private double transparency = 1.0;
    /**
     * Determines how a seed's LUT slot is selected at render time.
     * {@link ColorMode#CONFIDENCE} is the default (LUT sampled by the seed's
     * normalized confidence within the filter window).
     */
    private ColorMode colorMode = ColorMode.CONFIDENCE;

    /**
     * How {@link sc.fiji.snt.SeedOverlayRenderer} should pick each seed's LUT slot.
     * <ul>
     *   <li>{@link #CONFIDENCE} (default): LUT is sampled by the seed's
     *       normalized confidence within the active filter window. Alpha
     *       also rides on confidence so dim seeds fade.
     *   <li>{@link #INDEX}: LUT is sampled by the seed's position in
     *       {@link #list()} ({@code idx % LUT.length}). One distinct slot
     *       per seed. Alpha is uniform.
     *   <li>{@link #TYPE}: seeds sharing the same {@link SeedPoint#type}
     *       share a slot. Slot order is the alphabetical order of the
     *       distinct types seen on the overlay.
     *   <li>{@link #SOURCE}: same as {@link #TYPE} but keyed on
     *       {@link SeedPoint#source}.
     * </ul>
     * The confidence range still gates <i>visibility</i> regardless of the
     * mode in effect.
     */
    public enum ColorMode {
        CONFIDENCE,
        INDEX,
        SOURCE,
        TYPE
    }

    /**
     * Lazy KD-tree; {@code null} when stale (rebuilt on next {@link #nearest}).
     */
    private KDTree<SeedPoint> kdTree;
    /**
     * Lazy snapshot sorted by descending confidence: used for fast top-K
     * subsampling by the renderer when the visible set exceeds the smooth-handle
     * threshold. {@code null} when stale (rebuilt on next {@link #topKByConfidence}).
     */
    private List<SeedPoint> sortedByConfDesc;

    /**
     * @return the number of seeds currently held (irrespective of threshold).
     */
    public int size() {
        return seeds.size();
    }

    /**
     * @return {@code true} if no seeds are held.
     */
    public boolean isEmpty() {
        return seeds.isEmpty();
    }

    /**
     * Counts seeds whose voxel coordinates fall outside the given image bounds.
     * <p>
     * Coordinates are interpreted as physical (calibrated) units, divided by the corresponding {@code spacing} to yield
     * voxel indices. A seed is considered out-of-bounds when its rounded voxel index is negative or &ge; the dimension
     * on any axis. The z-axis is only checked when {@code dims.length >= 3}.
     *
     * @param dims    image dimensions in voxels (length 2 for 2D, 3 for 3D); if {@code null} or length &lt; 2, returns 0
     * @param spacing pixel size per axis in physical units; should match  {@code dims} length. {@code null} returns 0
     * @return number of seeds outside the image bounds
     */
    public int countOutOfBounds(final long[] dims, final double[] spacing) {
        if (dims == null || dims.length < 2 || spacing == null) return 0;
        int n = 0;
        for (final SeedPoint s : seeds) {
            if (isOutOfBounds(s, dims, spacing)) n++;
        }
        return n;
    }

    private static boolean isOutOfBounds(final SeedPoint s, final long[] dims, final double[] spacing) {
        if (!inAxis(s.x, spacing[0], dims[0])) return true;
        if (!inAxis(s.y, spacing[1], dims[1])) return true;
        if (dims.length > 2) {
            final double sz = (spacing.length > 2) ? spacing[2] : 1.0;
            return !inAxis(s.z, sz, dims[2]);
        }
        return false;
    }

    private static boolean inAxis(final double physicalCoord, final double spacing, final long dim) {
        if (spacing <= 0) return true; // can't validate without calibration; treat as in-bounds
        final long voxel = Math.round(physicalCoord / spacing);
        return voxel >= 0 && voxel < dim;
    }

    /**
     * @return the number of seeds whose confidence falls in
     * {@code [lowConfidence, highConfidence]}.
     */
    public int visibleSize() {
        if (lowConfidence <= 0 && highConfidence >= 1) return seeds.size();
        int n = 0;
        for (final SeedPoint s : seeds) {
            if (s.confidence >= lowConfidence && s.confidence <= highConfidence) n++;
        }
        return n;
    }

    /**
     * Adds a single seed. Fires listeners and invalidates the spatial index.
     * Prefer {@link #addAll(Collection)} for batches.
     */
    public void add(final SeedPoint seed) {
        Objects.requireNonNull(seed, "seed");
        seeds.add(seed);
        invalidate();
        fireChanged();
    }

    /**
     * Bulk-adds seeds. Fires listeners <b>once</b> at the end and invalidates
     * the spatial index once.
     *
     * @param batch seeds to append; ignored if {@code null} or empty
     */
    public void addAll(final Collection<? extends SeedPoint> batch) {
        if (batch == null || batch.isEmpty()) return;
        seeds.addAll(batch);
        invalidate();
        fireChanged();
    }

    /**
     * Replaces the current contents with the given seeds. Equivalent to
     * {@link #clear()} followed by {@link #addAll(Collection)}, but only fires
     * listeners and rebuilds the spatial index once.
     *
     * @param replacement the new contents (may be {@code null}/empty, which
     *                    behaves like {@link #clear()})
     */
    public void replaceAll(final Collection<? extends SeedPoint> replacement) {
        seeds.clear();
        selectedSeeds.clear();
        if (replacement != null) seeds.addAll(replacement);
        invalidate();
        fireChanged();
    }

    /**
     * Removes all seeds. Fires listeners.
     */
    public void clear() {
        if (seeds.isEmpty() && kdTree == null && selectedSeeds.isEmpty()) {
            // still fire so listeners that care about threshold/visibility reset can refresh
            fireChanged();
            return;
        }
        seeds.clear();
        selectedSeeds.clear();
        invalidate();
        fireChanged();
    }

    /**
     * Removes a single seed by reference equality. Selection is updated. Fires
     * listeners and invalidates indices if the seed was present.
     *
     * @return {@code true} if the seed was present and removed
     */
    public boolean remove(final SeedPoint seed) {
        if (seed == null) return false;
        final boolean removed = seeds.remove(seed);
        if (removed) {
            selectedSeeds.remove(seed);
            invalidate();
            fireChanged();
        }
        return removed;
    }

    /**
     * Bulk-removes seeds by reference equality. Fires listeners <b>once</b>
     * at the end if anything was removed.
     *
     * @return the number of seeds actually removed
     */
    public int removeAll(final Collection<? extends SeedPoint> batch) {
        if (batch == null || batch.isEmpty() || seeds.isEmpty()) return 0;
        // HashSet for O(1) membership; SeedPoint inherits Object identity-based
        // equals/hashCode, which is what we want here.
        final HashSet<SeedPoint> toRemove = new HashSet<>(batch);
        final int before = seeds.size();
        seeds.removeIf(toRemove::contains);
        selectedSeeds.removeAll(toRemove);
        final int removed = before - seeds.size();
        if (removed > 0) {
            invalidate();
            fireChanged();
        }
        return removed;
    }

    /**
     * Removes the seed at the given (data-list) index. Fires listeners.
     *
     * @return the removed seed, or {@code null} if the index was out of range
     */
    public SeedPoint removeAt(final int index) {
        if (index < 0 || index >= seeds.size()) return null;
        final SeedPoint removed = seeds.remove(index);
        selectedSeeds.remove(removed);
        invalidate();
        fireChanged();
        return removed;
    }

    /**
     * Replaces the seed at the given index. If {@code replacement} is the same
     * reference as the existing seed, this is a no-op. Selection is preserved
     * (the new seed inherits the selection state of the old one). Fires
     * listeners on actual change.
     *
     * @return {@code true} on success, {@code false} if the index was out of
     * range or the replacement was {@code null} or identical
     */
    public boolean replaceAt(final int index, final SeedPoint replacement) {
        if (index < 0 || index >= seeds.size() || replacement == null) return false;
        final SeedPoint existing = seeds.get(index);
        if (existing == replacement) return false;
        seeds.set(index, replacement);
        if (selectedSeeds.remove(existing)) selectedSeeds.add(replacement);
        invalidate();
        fireChanged();
        return true;
    }

    /**
     * Bulk-replaces seeds at multiple positions, firing listeners and
     * invalidating the spatial index once at the end. Entries with
     * out-of-range indices or {@code null} replacements are silently skipped.
     *
     * @param replacements map of {@code (model-index, new-seed)}. {@code null}
     *                     or empty input is a no-op.
     * @return the number of slots actually mutated
     */
    public int replaceAllAt(final Map<Integer, SeedPoint> replacements) {
        if (replacements == null || replacements.isEmpty()) return 0;
        int n = 0;
        for (final Map.Entry<Integer, SeedPoint> e : replacements.entrySet()) {
            final Integer key = e.getKey();
            final SeedPoint repl = e.getValue();
            if (key == null || repl == null) continue;
            final int idx = key;
            if (idx < 0 || idx >= seeds.size()) continue;
            final SeedPoint existing = seeds.get(idx);
            if (existing == repl) continue;
            seeds.set(idx, repl);
            if (selectedSeeds.remove(existing)) selectedSeeds.add(repl);
            n++;
        }
        if (n > 0) {
            invalidate();
            fireChanged();
        }
        return n;
    }

    /**
     * Returns the index of {@code seed} in the data list, or {@code -1} if
     * absent. Reference identity (via {@link List#indexOf}) is used.
     */
    public int indexOf(final SeedPoint seed) {
        return seeds.indexOf(seed);
    }

    /**
     * @return an unmodifiable, insertion-ordered view of the currently selected
     * seeds. The result is a snapshot (independent collection).
     */
    public Set<SeedPoint> getSelectedSeeds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(selectedSeeds));
    }

    /**
     * @return {@code true} if {@code seed} is currently selected.
     */
    public boolean isSelected(final SeedPoint seed) {
        return seed != null && selectedSeeds.contains(seed);
    }

    /**
     * Replaces the selection set. Seeds not present in the data list are
     * silently ignored. Fires listeners if the resulting selection differs.
     */
    public void setSelectedSeeds(final Collection<? extends SeedPoint> selection) {
        final LinkedHashSet<SeedPoint> next = new LinkedHashSet<>();
        if (selection != null && !selection.isEmpty()) {
            // Materialize `seeds` into a HashSet once so the membership checks are
            // quicker and won't freeze the EDT
            final HashSet<SeedPoint> known = new HashSet<>(seeds);
            for (final SeedPoint s : selection) {
                if (s != null && known.contains(s)) next.add(s);
            }
        }
        if (!next.equals(selectedSeeds)) {
            selectedSeeds.clear();
            selectedSeeds.addAll(next);
            fireChanged();
        }
    }

    /**
     * Clears the selection. Fires listeners if anything was previously selected.
     */
    public void clearSelection() {
        if (selectedSeeds.isEmpty()) return;
        selectedSeeds.clear();
        fireChanged();
    }

    /**
     * Removes all currently selected seeds from the data list.
     *
     * @return the number of seeds removed (0 if nothing was selected)
     */
    public int removeSelected() {
        if (selectedSeeds.isEmpty()) return 0;
        final List<SeedPoint> snapshot = new ArrayList<>(selectedSeeds);
        return removeAll(snapshot);
    }

    /**
     * @return seed at {@code index} (0-based).
     */
    public SeedPoint get(final int index) {
        return seeds.get(index);
    }

    /**
     * Returns a defensive snapshot of all held seeds (a fresh
     * {@link ArrayList}, safe to iterate while the overlay continues to
     * mutate on another thread).
     *
     * @return a freshly allocated list snapshot
     */
    public List<SeedPoint> list() {
        return new ArrayList<>(seeds);
    }

    /**
     * Returns a new list of seeds whose confidence falls in
     * {@code [lowConfidence, highConfidence]}. O(N); the result is a snapshot,
     * not a live view.
     */
    public List<SeedPoint> filtered() {
        if (lowConfidence <= 0 && highConfidence >= 1) return new ArrayList<>(seeds);
        return seeds.stream()
                .filter(s -> s.confidence >= lowConfidence && s.confidence <= highConfidence)
                .collect(Collectors.toList());
    }

    /**
     * Returns up to {@code k} seeds from the visible range
     * ({@code [lowConfidence, highConfidence]}) with the highest confidence.
     * The result is a snapshot (independent list). Internally uses a cached
     * sort that is rebuilt only when the overlay's data mutates.
     *
     * @param k maximum number of seeds to return; values {@code <= 0} return
     *          an empty list
     * @return up to {@code k} seeds sorted by descending confidence
     */
    public List<SeedPoint> topKByConfidence(final int k) {
        if (k <= 0 || seeds.isEmpty()) return new ArrayList<>();
        ensureSortedByConfidence();
        // sortedByConfDesc is in DESC order over the full set; walk it and pick
        // the first k that fall in [low, high]
        final List<SeedPoint> out = new ArrayList<>(Math.min(k, seeds.size()));
        for (final SeedPoint s : sortedByConfDesc) {
            if (s.confidence < lowConfidence || s.confidence > highConfidence) continue;
            out.add(s);
            if (out.size() >= k) break;
        }
        return out;
    }

    /**
     * @return the lower bound of the visible confidence range, in {@code [0, 1]}.
     */
    public double getLowConfidence() {
        return lowConfidence;
    }

    /**
     * @return the upper bound of the visible confidence range, in {@code [0, 1]}.
     */
    public double getHighConfidence() {
        return highConfidence;
    }

    /**
     * Sets the visible confidence range. Values are clamped to {@code [0, 1]};
     * if {@code low > high} the bounds are swapped. Fires listeners if either
     * bound changed.
     */
    public void setConfidenceRange(double low, double high) {
        if (low < 0) low = 0;
        else if (low > 1) low = 1;
        if (high < 0) high = 0;
        else if (high > 1) high = 1;
        if (low > high) {
            final double t = low;
            low = high;
            high = t;
        }
        if (low != lowConfidence || high != highConfidence) {
            lowConfidence = low;
            highConfidence = high;
            fireChanged();
        }
    }

    /**
     * @return the current global transparency multiplier in {@code [0, 1]}.
     * {@code 1.0} = fully opaque.
     */
    public double getTransparency() {
        return transparency;
    }

    /**
     * Sets the global transparency multiplier (clamped to {@code [0, 1]}).
     * Fires listeners if the value changed.
     */
    public void setTransparency(double t) {
        if (t < 0) t = 0;
        else if (t > 1) t = 1;
        if (t != transparency) {
            transparency = t;
            fireChanged();
        }
    }

    /**
     * @return how seed LUT slots are picked at render time. Never
     *         {@code null}; defaults to {@link ColorMode#CONFIDENCE}.
     */
    public ColorMode getColorMode() {
        return colorMode;
    }

    /**
     * Sets the rendering color mode. Fires listeners if the value changed.
     * {@code null} is treated as {@link ColorMode#CONFIDENCE}.
     */
    public void setColorMode(final ColorMode mode) {
        final ColorMode safe = (mode == null) ? ColorMode.CONFIDENCE : mode;
        if (safe != colorMode) {
            colorMode = safe;
            fireChanged();
        }
    }

    /**
     * @return the active LUT for confidence-to-color mapping. Never {@code null}.
     */
    public ColorTable getColorTable() {
        return colorTable;
    }

    /**
     * @return the canonical name of the active LUT (e.g. {@code "Viridis"}).
     */
    public String getColorTableName() {
        return colorTableName;
    }

    /**
     * Sets the active LUT. If {@code name} is not recognised by
     * {@link ColorMaps#get(String)}, this is a no-op. Fires listeners on
     * success.
     */
    public void setColorTable(final String name) {
        if (name == null || name.equals(colorTableName)) return;
        final ColorTable resolved = ColorMaps.get(name);
        if (resolved == null) return;
        colorTable = resolved;
        colorTableName = name;
        fireChanged();
    }

    /**
     * @return {@code true} if seeds should be drawn on the canvas.
     */
    public boolean isVisible() {
        return visible;
    }

    /**
     * Sets visibility; fires listeners if the value changed.
     */
    public void setVisible(final boolean visible) {
        if (visible != this.visible) {
            this.visible = visible;
            fireChanged();
        }
    }

    /**
     * Returns the seed closest to {@code (x, y, z)} in physical units, or
     * {@code null} if the overlay is empty or no seed lies within
     * {@code maxDistance}.
     * <p>
     * Note: this query <b>does not</b> filter by confidence threshold. Use
     * {@link #filtered()} and search that list manually if threshold-aware
     * nearest-neighbor is required.
     *
     * @param x           query physical X
     * @param y           query physical Y
     * @param z           query physical Z (use 0 for 2D)
     * @param maxDistance maximum acceptable distance in physical units;
     *                    pass {@link Double#POSITIVE_INFINITY} to disable
     * @return the nearest seed, or {@code null}
     */
    public SeedPoint nearest(final double x, final double y, final double z, final double maxDistance) {
        if (seeds.isEmpty()) return null;
        ensureSpatialIndex();
        final Neighbor<double[], SeedPoint> hit = kdTree.nearest(new double[]{x, y, z});
        if (hit == null) return null;
        if (Double.isFinite(maxDistance) && hit.distance() > maxDistance) return null;
        return hit.value();
    }

    /**
     * Writes all held seeds to a CSV file with the canonical header
     * {@code x,y,z,confidence,radius}. Channel/frame are written only if any
     * seed has them set (extra columns appended).
     *
     * @param file destination file (will be overwritten)
     * @throws IOException on write failure
     */
    public void saveAs(final File file) throws IOException {
        Objects.requireNonNull(file, "file");
        final boolean hasCT = seeds.stream()
                .anyMatch(s -> s.channel != SeedPoint.CT_UNSET || s.frame != SeedPoint.CT_UNSET);
        final boolean hasType = seeds.stream().anyMatch(s -> !s.type.isEmpty());
        final boolean hasSource = seeds.stream().anyMatch(s -> !s.source.isEmpty());
        try (final Writer w = new BufferedWriter(
                Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))) {
            final StringBuilder header = new StringBuilder("x,y,z,confidence,radius");
            if (hasCT) header.append(",channel,frame");
            if (hasType) header.append(",type");
            if (hasSource) header.append(",source");
            header.append('\n');
            w.write(header.toString());
            for (final SeedPoint s : seeds) {
                final StringBuilder row = new StringBuilder();
                row.append(String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f,%.6f",
                        s.x, s.y, s.z, s.confidence, s.radius));
                if (hasCT) row.append(String.format(",%d,%d", s.channel, s.frame));
                if (hasType) row.append(',').append(csvEscape(s.type));
                if (hasSource) row.append(',').append(csvEscape(s.source));
                row.append('\n');
                w.write(row.toString());
            }
        }
    }

    /**
     * Minimal CSV-field escaping: if the value contains a comma, quote, or
     * newline, wrap in double-quotes and double any embedded quotes. Empty
     * fields are written as-is (empty cell).
     */
    private static String csvEscape(final String s) {
        if (s == null || s.isEmpty()) return "";
        final boolean needsQuoting = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needsQuoting) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    /**
     * Registers a listener (no-op if already registered).
     */
    public void addListener(final SeedOverlayListener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    /**
     * Removes a listener (no-op if not registered).
     */
    public void removeListener(final SeedOverlayListener listener) {
        listeners.remove(listener);
    }

    private void fireChanged() {
        for (final SeedOverlayListener l : listeners) l.seedOverlayChanged(this);
    }

    private void invalidate() {
        kdTree = null;
        sortedByConfDesc = null;
    }

    private void ensureSpatialIndex() {
        if (kdTree != null) return;
        final SeedPoint[] snapshot = seeds.toArray(new SeedPoint[0]);
        final double[][] coords = new double[snapshot.length][3];
        for (int i = 0; i < snapshot.length; i++) {
            coords[i][0] = snapshot[i].x;
            coords[i][1] = snapshot[i].y;
            coords[i][2] = snapshot[i].z;
        }
        kdTree = new KDTree<>(coords, snapshot);
    }

    /**
     * Builds {@link #sortedByConfDesc} on demand. O(N log N) the first time
     * after a mutation; subsequent calls are O(1) until the next mutation.
     */
    private void ensureSortedByConfidence() {
        if (sortedByConfDesc != null) return;
        final List<SeedPoint> snapshot = new ArrayList<>(seeds);
        snapshot.sort(Comparator.comparingDouble((SeedPoint s) -> s.confidence).reversed());
        sortedByConfDesc = snapshot;
    }
}
