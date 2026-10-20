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

import org.scijava.desktop.links.AbstractLinkHandler;
import org.scijava.desktop.links.LinkHandler;
import org.scijava.desktop.links.Links;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.thread.ThreadService;
import org.scijava.ui.DialogPrompt;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.DemoRunner;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.viewer.AbstractBigViewer;

import javax.swing.*;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Handles {@code snt://} links, allowing SNT to be launched (or driven) from outside Fiji, e.g., from a browser, a
 * paper's supplementary HTML, or another SciJava application, via scijava-desktop's {@link LinkHandler} mechanism.
 * <p>
 * The canonical form is {@code fiji://snt/<operation>?<parameters>}, which works on all platforms.
 * {@code snt://<operation>?<parameters>} is also accepted where the OS allows registering it (see {@link Links}).
 * Parameter values must be percent-encoded. Images and tracings must be given as URLs or as absolute local paths/
 * {@code file:} URIs (local ones only work on the machine hosting them).
 * </p>
 * <pre>
 * Operations:
 *   open (default)  Opens image (SNT Stream for streamed data), loads tracings and flies to view. If image is already
 *   open, only the latter two are applied
 *   fly             As open, but view is mandatory
 *   demo            Loads a demo (see id), or a demo reconstruction (see tree)
 *
 * Parameters of open/fly:
 *   image     URL, path, file: URI, or demo:name for a bundled image (as in SNTService#initialize(String, boolean)).
 *             If omitted, acts on what is open
 *   stream    true|false. Inferred from image (.zarr, .n5, .ims, .xml, s3:, gs: mean stream)
 *   viewer    bvv (default)|bdv. Streamed launches only
 *   tracer    true (default)|false. false opens a plain viewer with no SNT session. Streamed launches only
 *   tracings  URL, path, file: URI, or demo:name for a bundled reconstruction. Streamed launches also accept a folder
 *             or .zip archive (but not demos). Not loaded into an already-open
 *             standalone viewer
 *   mode      add (default)|replace. Whether tracings replace existing paths (TRACES files always do). Ignored when
 *             launching a stream
 *   view      x,y,z[,t]: world coordinates and optional 1-based timepoint. ig viewers only (ignored by traditional
 *             launches)
 *
 * Parameters of demo:
 *   id        Stable id of a DemoRunner scenario, e.g., 4 (image, tracings, notes, etc.). Takes precedence over tree
 *   tree      Name of a bundled reconstruction (default: AA0001)
 * </pre>
 * <p>
 * Examples:
 * </p>
 * <pre>
 *   fiji://snt/open?image=https%3A%2F%2Fhost%2Fdata.ome.zarr&amp;view=82.9,-239,-65
 *   fiji://snt/open?image=file%3A%2F%2F%2FUsers%2Fme%2Fimg.tif
 *   fiji://snt/open?tracings=https%3A%2F%2Fhost%2Frec.swc&amp;mode=replace
 *   fiji://snt/fly?view=10,20,30,2
 *   fiji://snt/open?image=demo:OP1&amp;tracings=demo:OP1
 *   fiji://snt/demo?id=4
 *   fiji://snt/demo?tree=OP_1
 * </pre>
 * <p>
 * NB: {@code channel} and {@code ui} are not supported yet, and are ignored
 * </p>
 * <p>
 * Links are pre-flighted against the current state of SNT (see
 * {@code SNTLink#preflight}) before anything is changed. {@code view} (world
 * coordinates, optionally followed by a 1-based timepoint) is only supported
 * in SNT Stream/big viewers. Streamed links launch SNT Stream (see
 * {@link BigDataLoaderCmd#open}), closing a conflicting session first.
 * </p>
 * <p>
 * NB: {@link #handle(URI)} may be called off the EDT (e.g., by
 * {@code LinkArgument} while processing command-line/launch arguments), so all
 * SNT/UI calls are marshaled onto the EDT.
 * </p>
 *
 * @author Tiago Ferreira
 */
@Plugin(type = LinkHandler.class)
public class SNTLinkHandler extends AbstractLinkHandler {

    /**
     * The URI scheme handled by this plugin.
     */
    public static final String SCHEME = "snt";

    private static final String FIJI_SCHEME = "fiji";

    private static final String OP_OPEN = "open";
    private static final String OP_DEMO = "demo";
    private static final String OP_FLY = "fly";

    @Parameter
    private SNTService sntService;

    @Parameter
    private LogService logService;

    @Override
    public boolean supports(final URI uri) {
        if (uri == null) return false;
        return SCHEME.equalsIgnoreCase(uri.getScheme()) || isFijiLink(uri);
    }

    /**
     * Fallback form, {@code fiji://snt/<operation>?...}, handled wherever
     * {@code fiji://} is registered, i.e., also on macOS, where {@code snt://}
     * cannot be registered at runtime
     */
    private static boolean isFijiLink(final URI uri) {
        return FIJI_SCHEME.equalsIgnoreCase(uri.getScheme()) && SCHEME.equalsIgnoreCase(uri.getAuthority());
    }

    /**
     * Operation: {@code snt://<operation>} or {@code fiji://snt/<operation>}
     */
    private static String operation(final URI uri) {
        if (isFijiLink(uri)) return Links.operation(uri);
        return (uri.getAuthority() == null) ? Links.operation(uri) : uri.getAuthority();
    }

    @Override
    public List<String> getSchemes() {
        // Ask scijava-desktop to register snt:// with the OS. If SNT ships inside
        // a distribution (Fiji) that already owns a shared, higher-level scheme
        // (e.g., fiji://) and dispatches by operation, this can return
        // Collections.emptyList() instead, and supports()/the URIs above would be
        // rewritten as fiji://snt/open?... etc.
        return List.of(SCHEME);
    }

    @Override
    public void handle(final URI uri) {
        if (!supports(uri)) return;
        final String operation = operation(uri);
        final Map<String, String> query = SNTLink.query(uri);
        SwingUtilities.invokeLater(() -> {
            try {
                if (operation == null || operation.isEmpty() || OP_OPEN.equalsIgnoreCase(operation)) {
                    handleOpen(query); // also the default, e.g., for a bare snt://
                } else if (OP_FLY.equalsIgnoreCase(operation)) {
                    handleFly(query);
                } else if (OP_DEMO.equalsIgnoreCase(operation)) {
                    handleDemo(query);
                } else {
                    GuiUtils.errorPrompt("Unknown link operation: " + operation, false);
                }
            } catch (final Throwable t) {
                SNTUtils.error("Error handling link: " + uri, t);
                if (logService != null) logService.error(t);
            }
        });
    }

    private void handleFly(final Map<String, String> query) throws Exception {
        if (SNTLink.parseView(SNTLink.view(query)) == null) {
            GuiUtils.errorPrompt("Link has a missing or invalid location "
                    + "(expected view=x,y,z[,t]).", false);
            return;
        }
        handleOpen(query);
    }

    private void handleOpen(final Map<String, String> query) throws Exception {
        final SNTLink.Outcome outcome = SNTLink.preflight(query, SNTUtils.getInstance());
        if (outcome != SNTLink.Outcome.LAUNCH) {
            // SNT is already running: do not start a second instance
            handleRunning(outcome, query);
            return;
        }
        if (SNTLink.wantsStream(query)) {
            launchStream(query);
            return;
        }
        final String image = SNTLink.normalize(query.get("image"));
        final boolean startUI = true;
        if (image == null || image.isEmpty()) {
            sntService.initialize(startUI);
        } else {
            sntService.initialize(image, startUI);
        }
        final String tracings = SNTLink.normalize(query.get("tracings"));
        if (tracings != null && !tracings.isEmpty()) {
            loadTracingsFrom(tracings, replace(query));
        }
        // NB: "channel" and "ui" (simple|default) query params are accepted by
        // SNTLoaderCmd's interactive prompt but are not currently exposed by
        // SNTService#initialize(...). Wiring them through is a small follow-up
        // if this link format is adopted (either by extending SNTService, or by
        // routing through CommandService with SNTLoaderCmd's inputs pre-filled).
    }

    private void handleRunning(final SNTLink.Outcome outcome,
                               final Map<String, String> query) throws Exception {
        final SNTUI ui = sntService.getUI();
        if (ui != null) ui.toFront();
        switch (outcome) {
            case FLY_ONLY -> {
                loadTracings(query);
                flyTo(query);
            }
            case CLOSE_THEN_LAUNCH -> closeAndLaunch(query);
            case APPEND_OR_RESTART, RESTART -> GuiUtils.errorPrompt("This link points "
                    + "to a different source than the one currently streamed. Restart "
                    + "Fiji and open the link again.", false);
            default -> { /* LAUNCH is handled by the caller */ }
        }
    }

    /**
     * Closes the current session (after confirmation) and re-handles the link,
     * which will then be launched
     */
    private void closeAndLaunch(final Map<String, String> query) throws Exception {
        final SNTUI ui = sntService.getUI();
        final String target = SNTLink.wantsStream(query) ? "SNT Stream"
                : "SNT (traditional mode)";
        if (!new GuiUtils(ui).getConfirmation("<HTML>This link opens " + target
                        + ".<br>Close the current session first?", "Close Current Session?",
                "Close and Open", "Cancel")) {
            return;
        }
        if (ui != null) {
            if (!ui.close()) return; // unsaved changes: user canceled
        } else {
            final AbstractBigViewer viewer = SNTLink.activeViewer(null);
            if (viewer != null) viewer.dispose();
        }
        if (SNTLink.preflight(query, SNTUtils.getInstance()) != SNTLink.Outcome.LAUNCH) {
            GuiUtils.errorPrompt("The current session could not be closed.", false);
            return;
        }
        handleOpen(query);
    }

    /**
     * Opens streamed data through BigDataLoaderCmd. Loading is slow and blocks,
     * so it is run off the EDT, flying to {@code view} once the viewer is ready
     */
    private void launchStream(final Map<String, String> query) {
        final String image = SNTLink.normalize(query.get("image"));
        final String tracings = SNTLink.normalize(query.get("tracings"));
        if (SNTLink.isDemo(tracings)) {
            GuiUtils.errorPrompt("Bundled demo reconstructions cannot be loaded into "
                    + "SNT Stream.", false);
            return;
        }
        final boolean bvv = SNTLink.wantsBvv(query);
        final boolean tracer = SNTLink.wantsTracer(query);
        SNTUtils.getContext().getService(ThreadService.class).queue(() -> {
            try {
                BigDataLoaderCmd.open(image, tracings, bvv, tracer);
                SwingUtilities.invokeLater(() -> flyWhenReady(query));
            } catch (final Throwable t) {
                SNTUtils.error("Error opening link: " + image, t);
            }
        });
    }

    /**
     * Flies to {@code view} as soon as the (just launched) viewer has a size
     */
    private void flyWhenReady(final Map<String, String> query) {
        if (SNTLink.view(query) == null) return;
        final int[] tries = {0};
        final Timer timer = new Timer(250, null);
        timer.addActionListener(e -> {
            final AbstractBigViewer v = SNTLink.activeViewer(SNTUtils.getInstance());
            if (v != null && v.getViewerWidth() > 0) {
                timer.stop();
                flyTo(query);
            } else if (++tries[0] > 40) {
                timer.stop(); // viewer never became ready: give up (10 s)
            }
        });
        timer.start();
    }

    private void loadTracings(final Map<String, String> query) throws Exception {
        final String tracings = SNTLink.normalize(query.get("tracings"));
        if (tracings == null || tracings.isEmpty()) return;
        if (sntService.getUI() == null) {
            GuiUtils.errorPrompt("Tracings can only be loaded into SNT, but only a "
                    + "standalone viewer is open.", false);
            return;
        }
        final boolean replace = replace(query);
        if (replace && sntService.hasUnsavedPaths() && new GuiUtils(sntService.getUI())
                .yesNoPrompt("Replace existing (unsaved) paths with the received tracings?",
                        null) != DialogPrompt.Result.YES_OPTION) {
            return;
        }
        loadTracingsFrom(tracings, replace);
    }

    /**
     * Loads a reconstruction from a path/URL, or a bundled one ({@code demo:<name>})
     */
    private void loadTracingsFrom(final String tracings, final boolean replace)
            throws Exception {
        if (!SNTLink.isDemo(tracings)) {
            sntService.loadTracings(tracings, replace);
            return;
        }
        // NB: demoTree() falls back to a default for unrecognized names, and
        // "replace" is not honored: demo reconstructions are always added
        final Tree tree = sntService.demoTree(tracings.trim().substring(5).trim());
        if (tree == null) {
            GuiUtils.errorPrompt("Could not load SNT's demo reconstruction.", false);
            return;
        }
        sntService.loadTree(tree);
    }

    private void flyTo(final Map<String, String> query) {
        final String raw = SNTLink.view(query);
        if (raw == null || raw.isEmpty()) return;
        final double[] view = SNTLink.parseView(raw);
        final AbstractBigViewer viewer = SNTLink.activeViewer(SNTUtils.getInstance());
        if (view == null) {
            GuiUtils.errorPrompt("Link has an invalid location (expected "
                    + "view=x,y,z[,t]): " + raw, false);
        } else if (viewer == null) {
            GuiUtils.errorPrompt("Locations in links are only supported in SNT "
                    + "Stream/big viewers.", false);
        } else {
            final int t = (view[3] < 1) ? viewer.getCurrentTimepoint() : (int) view[3];
            viewer.flyTo(view[0], view[1], view[2], t, "linked location");
        }
    }

    /**
     * "mode=add" (default) keeps existing paths, "mode=replace" discards them.
     * TRACES files always replace
     */
    private static boolean replace(final Map<String, String> query) {
        return "replace".equalsIgnoreCase(query.get("mode"));
    }

    /**
     * Loads a full DemoRunner scenario (image, reconstructions, notes, etc.),
     * identified by its stable id. Mirrors the "Demo" command of SNT's menu,
     * which handles everything on the EDT as well
     */
    private void handleDemoScenario(final String rawId) {
        final int id;
        try {
            id = Integer.parseInt(rawId.trim());
        } catch (final NumberFormatException ex) {
            GuiUtils.errorPrompt("Invalid demo id: " + rawId, false);
            return;
        }
        if (sntService.isActive() && sntService.getUI() != null) {
            final SNTUI ui = sntService.getUI();
            ui.toFront();
            final boolean stream = SNTUtils.getInstance().isStreamMode();
            if (stream || sntService.hasUnsavedPaths()) {
                if (!new GuiUtils(ui).getConfirmation("<HTML>Loading this demo will "
                        + (stream ? "close SNT Stream." : "replace the current image and "
                        + "paths.") + "<br>Proceed?", "Load Demo?", "Proceed", "Cancel")) {
                    return;
                }
            }
            if (stream && !ui.close()) return; // unsaved changes: user canceled
        }
        if (!sntService.isActive() || sntService.getUI() == null) {
            sntService.initialize(true);
        }
        try {
            new DemoRunner(sntService.getUI(), SNTUtils.getInstance()).load(id);
        } catch (final IllegalArgumentException ex) {
            GuiUtils.errorPrompt("Invalid demo id: " + id, false);
        }
    }

    private void handleDemo(final Map<String, String> query) {
        final String id = query.get("id");
        if (id != null) {
            handleDemoScenario(id);
            return;
        }
        if (sntService != null && sntService.isActive() && sntService.getUI() != null) {
            sntService.getUI().toFront();
            return;
        }
        final String treeName = query.get("tree");
        // NB: SNTService#demoTree(String) never returns null for an unrecognized
        // name -- it falls back to a default demo (AA0001). It can only return
        // null if the bundled demo resource itself failed to load.
        final Tree tree = sntService.demoTree(treeName);
        if (tree == null) {
            GuiUtils.errorPrompt("Could not load SNT's demo dataset.", false);
            return;
        }
        sntService.initialize(true);
        sntService.loadTree(tree);
    }

}
