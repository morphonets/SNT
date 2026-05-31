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

package sc.fiji.snt.plugin;

import net.imagej.ImgPlus;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.NumberWidget;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.seed.SeedOverlay;
import sc.fiji.snt.seed.SeedPoint;
import sc.fiji.snt.tracing.auto.AbstractGWDTTracer;
import sc.fiji.snt.tracing.auto.AutoTracer;

import java.util.*;

/**
 * Single-cell GWDT autotracing wrapper that uses the soma ROI on the canvas (or the auto-detected soma) as the
 * {@linkplain AutoTracer.SeedRole#ROOT root} and the filtered seeds from SNT's {@link SeedOverlay} as
 * {@linkplain AutoTracer.SeedRole#WAYPOINT soft path attractors}. Seeds  preferentially pull the trace through them by
 * biasing the GWDT cost map before Fast Marching; the resulting root-to-waypoint paths are protected from the pruning
 * passes that would otherwise remove them.
 * <p>
 * Inherits the soma-ROI strategy and all GWDT tracing options from {@link GWDTTracerCommonCmd}; adds the same
 * {@code source} and {@code type} seed filters as {@link AutotraceFromSeedsCmd} plus three bias controls
 * (source, strength, sphere radius).
 *
 * @author Tiago Ferreira
 * @see GWDTTracerCommonCmd
 * @see AutotraceFromSeedsCmd
 * @see AutotraceFromTipsCmd
 * @see SeedOverlay
 */
@Plugin(type = Command.class, initializer = "init", label = "Autotrace Single Cell with Seeded Waypoints (GWDT)...")
public class AutotraceFromWaypointsCmd extends GWDTTracerCommonCmd {

    // Source filter labels
    static final String SOURCE_ALL = "All seeds";
    static final String SOURCE_VISIBLE = "Visible (within confidence range)";
    public static final String SOURCE_SELECTION = "Selection only";

    static final String TYPE_ANY = "(any)";
    /**
     * Display label for the empty-string ({@link SeedPoint#TAG_UNSET}) type.
     */
    private static final String TYPE_UNSET_LABEL = "(unset)";

    // Bias source labels (must match AbstractGWDTTracer.WaypointBiasSource ordinals)
    static final String BIAS_CONFIDENCE = "From confidence (default)";
    static final String BIAS_RADIUS = "From radius";
    static final String BIAS_FIXED = "Uniform (fixed factor)";

    /**
     * Common prefix for all log lines emitted by this command.
     */
    private static final String LOG_PREFIX = "Autotrace from Waypoints: ";

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String SEEDS_HEADER = "<HTML><b>Waypoint Selection</b>";

    @Parameter(label = "Seed source",
            choices = {SOURCE_VISIBLE, SOURCE_ALL, SOURCE_SELECTION},
            description = "<HTML>Which seeds to use as waypoint attractors:<dl>" +
                    "<dt><i>" + SOURCE_VISIBLE + "</i></dt>" +
                    "<dd>Seeds currently passing the confidence filter (Seeds tab)</dd>" +
                    "<dt><i>" + SOURCE_ALL + "</i></dt>" +
                    "<dd>Every seed in the overlay regardless of filter</dd>" +
                    "<dt><i>" + SOURCE_SELECTION + "</i></dt>" +
                    "<dd>Only the seeds currently selected in the Seeds table</dd>" +
                    "</dl>")
    private String sourceFilter = SOURCE_VISIBLE;

    @Parameter(label = "Seed type filter", choices = {TYPE_ANY},
            description = "<HTML>Restrict the waypoint set to seeds whose <i>type</i> field " +
                    "equals the chosen value. <i>(any)</i> matches all types; choices are " +
                    "populated from the distinct values currently in the overlay.")
    private String typeFilterChoice = TYPE_ANY;

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String BIAS_HEADER = "<HTML><b>Attractor Bias</b>";

    @Parameter(label = "Bias source",
            choices = {BIAS_CONFIDENCE, BIAS_RADIUS, BIAS_FIXED},
            description = "<HTML>How each waypoint's per-seed bias amount is derived:<dl>" +
                    "<dt><i>" + BIAS_CONFIDENCE + "</i></dt>" +
                    "<dd>Bias scales with each seed's confidence value (recommended).</dd>" +
                    "<dt><i>" + BIAS_RADIUS + "</i></dt>" +
                    "<dd>Bias scales with each seed's radius, normalized across the waypoint set.</dd>" +
                    "<dt><i>" + BIAS_FIXED + "</i></dt>" +
                    "<dd>Same bias for every waypoint, controlled by the strength below.</dd>" +
                    "</dl>")
    private String biasSourceChoice = BIAS_CONFIDENCE;

    @Parameter(label = "Bias strength", min = "0", max = "1", stepSize = "0.05", style = NumberWidget.SLIDER_STYLE,
            description = "<HTML>Overall attraction strength. Multiplies the per-seed bias " +
                    "amount; higher values pull the trace more aggressively toward waypoints. " +
                    "The default (0.9) means a confidence-1 seed becomes a near-zero-cost voxel.")
    private double biasStrength = 0.9;

    @Parameter(label = "Bias sphere radius (voxels)", min = "0", max = "10", stepSize = "0.5",
            description = "<HTML>Spatial extent of the bias around each waypoint, in voxels. " +
                    "A small sphere (2–3 voxels) is usually enough — larger values may create " +
                    "phantom attractors at the bias boundary in low-contrast images. " +
                    "Set to 0 to bias only the waypoint voxel itself.")
    private double biasRadiusVoxels = 2.0;

    /**
     * Waypoints resolved at the start of {@link #runCommand}; consumed by  {@link #createAndConfigureTracer(ImgPlus)}
     * when the parent's flow reaches the tracer-configuration step. Same field-injection pattern as
     * {@link AutotraceFromTipsCmd}.
     */
    private List<SeedPoint> filteredWaypoints;

    /**
     * Initializer. Inherits image-related setup from  {@link GWDTTracerCommonCmd#initForImage()} and keeps the soma-ROI
     * inputs visible: the soma ROI on the canvas (or its auto-detection) is the root for the single-cell trace.
     */
    @SuppressWarnings("unused")
    protected void init() {
        initForImage();
        if (isCanceled() || abortRun) return;

        // Dynamic type-filter choices populated from the distinct types currently in the overlay (mirrors
        // AutotraceFromSeedsCmd /  AutotraceFromTipsCmd)
        final SeedOverlay overlay = snt.getSeedOverlay();
        final List<String> typeChoices = new ArrayList<>();
        typeChoices.add(TYPE_ANY);
        if (overlay != null && !overlay.isEmpty()) {
            final Set<String> distinct = new TreeSet<>();
            for (final SeedPoint s : overlay.list()) distinct.add(s.type);
            for (final String t : distinct) {
                typeChoices.add(t.isEmpty() ? TYPE_UNSET_LABEL : t);
            }
        }
        final MutableModuleItem<String> typeItem = getInfo().getMutableInput("typeFilterChoice", String.class);
        typeItem.setChoices(typeChoices);
    }

    @Override
    public void run() {
        if (!isCanceled() && !abortRun) runCommand();
    }

    @Override
    protected void runCommand() {
        // Resolve the waypoint set before delegating to the parent's full single-cell flow. The parent doesn't know
        // about seeds, so failing here gives the user a focused error rather than running an unbiased trace that
        // produces no attractor effect
        final SeedOverlay overlay = snt.getSeedOverlay();
        if (overlay == null || overlay.isEmpty()) {
            error("Seed overlay is empty. Import or generate seeds first.");
            return;
        }
        filteredWaypoints = applyFilters(overlay.list(), overlay);
        if (filteredWaypoints.isEmpty()) {
            error("No seeds match the chosen filters (source=" + sourceFilter
                    + ", type=" + typeFilterChoice + ").");
            return;
        }
        SNTUtils.log(String.format("%s%,d of %,d seed(s) selected as waypoints (source=%s, type=%s).",
                LOG_PREFIX, filteredWaypoints.size(), overlay.size(),
                sourceFilter, typeFilterChoice));

        // Delegate to the parent's full single-cell flow (image resolution, soma ROI strategy, auto-detection, tracer
        // config, traceTrees, handleTracedTrees). createAndConfigureTracer (overridden below) injects the waypoint
        // bias settings + the waypoint list
        super.runCommand();
    }

    /**
     * Extends the parent's tracer setup with bias configuration and a {@code setWaypoints} call. By the time the
     * parent's runCommand reaches this method, all the standard GWDT knobs have been applied; we just  hand it the
     * bias parameters, the waypoint list, and verify the tracer honors the {@link AutoTracer.SeedRole#WAYPOINT WAYPOINT}
     * role.
     */
    @Override
    protected AbstractGWDTTracer<?> createAndConfigureTracer(final ImgPlus<?> img) {
        final AbstractGWDTTracer<?> tracer = super.createAndConfigureTracer(img);
        if (tracer == null) return null;
        if (filteredWaypoints == null || filteredWaypoints.isEmpty()) {
            // Defensive: runCommand() above guarantees non-empty. If a future caller invokes us via a different path,
            // fall through to a  normal full-graph trace rather than producing an empty result
            SNTUtils.log(LOG_PREFIX + "no waypoints configured; falling back to unbiased trace.");
            return tracer;
        }
        if (!tracer.honoredSeedRoles().contains(AutoTracer.SeedRole.WAYPOINT)) {
            error("The configured tracer does not consume waypoint seeds.");
            return null;
        }
        tracer.setWaypointBiasSource(parseBiasSource(biasSourceChoice));
        tracer.setWaypointBiasStrength(biasStrength);
        tracer.setWaypointBiasRadiusVoxels(biasRadiusVoxels);
        tracer.setWaypoints(filteredWaypoints);
        SNTUtils.log(String.format("%sconfigured %,d waypoint(s) on tracer (source=%s, strength=%.2f, radius=%.1f vox).",
                LOG_PREFIX, filteredWaypoints.size(), biasSourceChoice, biasStrength, biasRadiusVoxels));
        return tracer;
    }

    @Override
    protected boolean isFileMode() {
        return false;
    }

    /**
     * Maps the harvester label back to the {@link AbstractGWDTTracer.WaypointBiasSource} enum value.
     */
    private AbstractGWDTTracer.WaypointBiasSource parseBiasSource(final String choice) {
        return switch (choice) {
            case BIAS_RADIUS -> AbstractGWDTTracer.WaypointBiasSource.RADIUS;
            case BIAS_FIXED -> AbstractGWDTTracer.WaypointBiasSource.FIXED;
            default -> AbstractGWDTTracer.WaypointBiasSource.CONFIDENCE;
        };
    }

    /**
     * Applies the source-filter (Selection / Visible / All) and the type-filter to a snapshot of the overlay, returning
     * a fresh list.  Logic mirrors {@code AutotraceFromSeedsCmd}/{@code AutotraceFromTipsCmd};  kept inline rather than
     * refactored into a shared helper because each command might grow command-specific filter knobs (e.g. "exclude the
     * root seed" for tips, "exclude the most-confident seed" for waypoints).
     */
    private List<SeedPoint> applyFilters(final Collection<SeedPoint> snapshot,
                                         final SeedOverlay overlay) {
        // Source filter first
        List<SeedPoint> stream;
        switch (sourceFilter) {
            case SOURCE_SELECTION -> {
                final Set<SeedPoint> sel = overlay.getSelectedSeeds();
                stream = new ArrayList<>(sel.size());
                for (final SeedPoint s : snapshot) if (sel.contains(s)) stream.add(s);
            }
            case SOURCE_ALL -> stream = new ArrayList<>(snapshot);
            default -> {
                // SOURCE_VISIBLE
                final double low = overlay.getLowConfidence();
                final double high = overlay.getHighConfidence();
                stream = new ArrayList<>(snapshot.size());
                for (final SeedPoint s : snapshot) {
                    if (s.confidence >= low && s.confidence <= high) stream.add(s);
                }
            }
        }
        // Type filter
        if (!TYPE_ANY.equals(typeFilterChoice)) {
            final String wanted = TYPE_UNSET_LABEL.equals(typeFilterChoice)
                    ? SeedPoint.TAG_UNSET : typeFilterChoice;
            final List<SeedPoint> typed = new ArrayList<>(stream.size());
            for (final SeedPoint s : stream) {
                if (wanted.equals(s.type)) typed.add(s);
            }
            stream = typed;
        }
        return stream;
    }
}
