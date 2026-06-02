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
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.seed.SeedOverlay;
import sc.fiji.snt.seed.SeedPoint;
import sc.fiji.snt.tracing.auto.AbstractGWDTTracer;
import sc.fiji.snt.tracing.auto.AutoTracer;

import java.util.*;

/**
 * Single-cell GWDT autotracing wrapper that uses the soma ROI on the canvas
 * (or the auto-detected soma) as the {@linkplain AutoTracer.SeedRole#ROOT root}
 * and the filtered seeds from SNT's {@link SeedOverlay} as
 * {@linkplain AutoTracer.SeedRole#TIP tip targets}. The tracer extracts the
 * union of root-to-tip parent walks instead of running its usual full-tree
 * pruning pipeline, producing one {@link Tree} that connects the soma to every
 * reachable tip.
 * <p>
 * Inherits all GWDT tracing knobs and the soma-ROI strategy from
 * {@link GWDTTracerCommonCmd}; adds the same {@code source} and {@code type}
 * seed filters as {@link AutotraceFromSeedsCmd}.
 * <p>
 * Tips whose nearest voxel is not reached by Fast Marching (state &ne; ALIVE)
 * are logged and skipped by the tracer; they do not abort the run.
 *
 * @author Tiago Ferreira
 * @see GWDTTracerCommonCmd
 * @see AutotraceFromSeedsCmd
 * @see SeedOverlay
 */
@Plugin(type = Command.class, initializer = "init", label = "Autotrace Single Cell from Seeded Tips (GWDT)...")
public class AutotraceFromTipsCmd extends GWDTTracerCommonCmd {

    // Source filter labels: protected so SNTUI / SeedManager can
    // pass them via the SciJava input map when invoking the command headless-ish
    protected static final String SOURCE_ALL = "All seeds";
    protected static final String SOURCE_VISIBLE = "Visible (within confidence range)";
    public static final String SOURCE_SELECTION = "Selection only";

    static final String TYPE_ANY = "(any)";
    /**
     * Display label for the empty-string ({@link SeedPoint#TAG_UNSET}) type.
     */
    private static final String TYPE_UNSET_LABEL = "(unset)";

    /**
     * Common prefix for all log lines emitted by this command.
     */
    private static final String LOG_PREFIX = "Autotrace from Tips: ";

    @Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
    private String SEEDS_HEADER = "<HTML><b>Tip Selection</b>";

    @Parameter(label = "Seed source",
            choices = {SOURCE_VISIBLE, SOURCE_ALL, SOURCE_SELECTION},
            description = "<HTML>Which seeds to use as tip targets:<dl>" +
                    "<dt><i>" + SOURCE_VISIBLE + "</i></dt>" +
                    "<dd>Seeds currently passing the confidence filter (Seeds tab)</dd>" +
                    "<dt><i>" + SOURCE_ALL + "</i></dt>" +
                    "<dd>Every seed in the overlay regardless of filter</dd>" +
                    "<dt><i>" + SOURCE_SELECTION + "</i></dt>" +
                    "<dd>Only the seeds currently selected in the Seeds table</dd>" +
                    "</dl>")
    private String sourceFilter = SOURCE_VISIBLE;

    @Parameter(label = "Seed type filter", choices = {TYPE_ANY},
            description = "<HTML>Restrict the tip set to seeds whose <i>type</i> field equals " +
                    "the chosen value. <i>(any)</i> matches all types; choices are " +
                    "populated from the distinct values currently in the overlay.")
    private String typeFilterChoice = TYPE_ANY;

    /**
     * Tips resolved at the start of {@link #runCommand}; consumed by {@link #createAndConfigureTracer(ImgPlus)} when
     * the parent's flow reaches the tracer-configuration step. Field-level state (rather than a parameter passed
     * through the parent) is the minimally invasive way to inject extra configuration into the parent's runCommand
     * without re-implementing its soma-ROI resolution and tracing machinery.
     */
    private List<SeedPoint> filteredTips;

    /**
     * Initializer. Inherits image-related setup from {@link GWDTTracerCommonCmd#initForImage()} (and unlike
     * {@link AutotraceFromSeedsCmd} ) keeps {@code somaStrategyChoice} and {@code roiPlaneOnly} visible: the soma ROI
     * on the canvas (or auto-detected) is the root for the single-cell trace, and the user needs the strategy picker to
     * choose how that ROI is interpreted.
     */
    @SuppressWarnings("unused")
    protected void init() {
        initForImage();
        if (isCanceled() || abortRun) return;

        // Dynamic type-filter choices populated from the distinct types  currently in the overlay
        // (mirrors AutotraceFromSeedsCmd)
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
        // Pre-flight: resolve and validate the tip set before delegating to
        // the parent's full single-cell flow. The parent doesn't know about
        // seeds, so failing here gives the user a focused error ("no tips
        // match your filters") rather than letting the trace run unseeded
        // and silently produce a normal full-tree result.
        final SeedOverlay overlay = snt.getSeedOverlay();
        if (overlay == null || overlay.isEmpty()) {
            error("Seed overlay is empty. Import or generate seeds first.");
            return;
        }
        filteredTips = applyFilters(overlay.list(), overlay);
        if (filteredTips.isEmpty()) {
            error("No seeds match the chosen filters (source=" + sourceFilter
                    + ", type=" + typeFilterChoice + ").");
            return;
        }
        SNTUtils.log(String.format("%s%,d of %,d seed(s) selected as tips (source=%s, type=%s).",
                LOG_PREFIX, filteredTips.size(), overlay.size(), sourceFilter, typeFilterChoice));

        // Delegate to the parent's full single-cell flow (image resolution, soma ROI strategy, auto-detection,
        // tracer config, traceTrees, handleTracedTrees). createAndConfigureTracer (overridden below) injects setTips
        // on the configured tracer
        super.runCommand();
    }

    /**
     * Extends the parent's tracer setup with a {@code setTips} call. By the time the parent's runCommand reaches this
     * method, all the standard GWDT knobs have been applied; we just hand it the user's tips and verify the tracer
     * honors the {@link AutoTracer.SeedRole#TIP TIP} role.
     */
    @Override
    protected AbstractGWDTTracer<?> createAndConfigureTracer(final ImgPlus<?> img) {
        final AbstractGWDTTracer<?> tracer = super.createAndConfigureTracer(img);
        if (tracer == null) return null;
        if (filteredTips == null || filteredTips.isEmpty()) {
            // Defensive: runCommand() above guarantees this is non-empty when reached normally. If a future caller
            // invokes us via a differen= path, fall through and let the parent's regular full-graph behavior stand
            // rather than producing an empty trace
            SNTUtils.log(LOG_PREFIX + "no tips configured; falling back to full-graph trace.");
            return tracer;
        }
        if (!tracer.honoredSeedRoles().contains(AutoTracer.SeedRole.TIP)) {
            error("The configured tracer does not consume tip seeds.");
            return null;
        }
        tracer.setTips(filteredTips);
        SNTUtils.log(String.format("%sconfigured %,d tip(s) on tracer (root will be set from soma ROI).",
                LOG_PREFIX, filteredTips.size()));
        return tracer;
    }

    @Override
    protected boolean isFileMode() {
        return false;
    }

    /**
     * Applies the source-filter (Selection / Visible / All) and the type-filter to a snapshot of the overlay, returning
     * a fresh list. Logic mirrors {@code AutotraceFromSeedsCmd.applyFilters}; kept inline for now (the two will diverge
     * if a future "exclude root seed" toggle gets added here).
     */
    private List<SeedPoint> applyFilters(final Collection<SeedPoint> snapshot,
                                         final SeedOverlay overlay) {
        // Source filter first.
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
        // Type filter.
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
