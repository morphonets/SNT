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
import org.scijava.ui.DialogPrompt;

import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.GuiUtils;

import javax.swing.SwingUtilities;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Handles {@code snt://} links, allowing SNT to be launched (or driven) from
 * outside Fiji, e.g., from a browser, a paper's supplementary HTML, or another
 * SciJava application, via scijava-desktop's {@link LinkHandler} mechanism.
 * <p>
 * Recognized forms (the operation is the authority of {@code snt://} links, and
 * the first path segment of the {@code fiji://snt/} fallback; see {@link Links}):
 * </p>
 * <pre>
 *   snt://open
 *   snt://open?image=/path/or/url/to/image.tif&amp;channel=2&amp;ui=simple
 *   snt://open?tracings=/path/or/url/to/reconstruction.swc
 *   snt://open?tracings=/path/to/reconstruction.swc&amp;mode=replace
 *   snt://demo
 *   snt://demo?tree=OP_1
 *   fiji://snt/open?tracings=... (fallback form: same operations and parameters)
 * </pre>
 * <p>
 * NB: {@link #handle(URI)} may be called off the EDT (e.g., by
 * {@code LinkArgument} while processing command-line/launch arguments), so all
 * SNT/UI calls are marshalled onto the EDT.
 * </p>
 *
 * @author Tiago Ferreira
 */
@Plugin(type = LinkHandler.class)
public class SNTLinkHandler extends AbstractLinkHandler {

	/** The URI scheme handled by this plugin. */
	public static final String SCHEME = "snt";

	private static final String FIJI_SCHEME = "fiji";

	private static final String OP_OPEN = "open";
	private static final String OP_DEMO = "demo";

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

	/** Operation: {@code snt://<operation>} or {@code fiji://snt/<operation>} */
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
		return Arrays.asList(SCHEME);
	}

	@Override
	public void handle(final URI uri) {
		if (!supports(uri)) return;
		final String operation = operation(uri);
		final Map<String, String> query = Links.query(uri);
		SwingUtilities.invokeLater(() -> {
			try {
				if (OP_DEMO.equalsIgnoreCase(operation)) {
					handleDemo(query);
				}
				else {
					// default to "open" for snt:// and snt://open
					handleOpen(query);
				}
			}
			catch (final Throwable t) {
				SNTUtils.error("Error handling link: " + uri, t);
				if (logService != null) logService.error(t);
			}
		});
	}

	private void handleOpen(final Map<String, String> query) throws Exception {
		if (sntService != null && sntService.isActive() && sntService.getUI() != null) {
			// SNT is already running: do not start a second instance. Bring it
			// forward and, if present, honor a "tracings" request against it.
			sntService.getUI().toFront();
			final String tracings = query.get("tracings");
			if (tracings != null && !tracings.isEmpty()) {
				final boolean replace = replace(query);
				if (replace && sntService.hasUnsavedPaths() && new GuiUtils(sntService.getUI())
						.yesNoPrompt("Replace existing (unsaved) paths with the received tracings?",
								null) != DialogPrompt.Result.YES_OPTION) {
					return;
				}
				sntService.loadTracings(tracings, replace);
			}
			return;
		}
		final String image = query.get("image");
		final boolean startUI = true;
		if (image == null || image.isEmpty()) {
			sntService.initialize(startUI);
		}
		else {
			sntService.initialize(image, startUI);
		}
		final String tracings = query.get("tracings");
		if (tracings != null && !tracings.isEmpty()) {
			sntService.loadTracings(tracings, replace(query));
		}
		// NB: "channel" and "ui" (simple|default) query params are accepted by
		// SNTLoaderCmd's interactive prompt but are not currently exposed by
		// SNTService#initialize(...). Wiring them through is a small follow-up
		// if this link format is adopted (either by extending SNTService, or by
		// routing through CommandService with SNTLoaderCmd's inputs pre-filled).
	}

	/**
	 * "mode=add" (default) keeps existing paths, "mode=replace" discards them.
	 * TRACES files always replace
	 */
	private static boolean replace(final Map<String, String> query) {
		return "replace".equalsIgnoreCase(query.get("mode"));
	}

	private void handleDemo(final Map<String, String> query) {
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
