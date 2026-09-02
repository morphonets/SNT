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

package sc.fiji.snt.gui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.icons.FlatClearIcon;
import com.formdev.flatlaf.ui.FlatEmptyBorder;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

/**
 * Displays dismissible "speech bubble" callouts anchored to a component, for building onboarding/first-run walkthroughs
 * of a GUI.
 * <p>
 * Callouts are registered with {@link #add(Component, int, String)} (or its grouped overload,
 * {@link #add(Component, int, String, String)}) while a GUI is being built; use the
 * {@link #add(Component, int, String, String, int)} overload if registration order does not match the desired
 * chain sequence. Nothing is displayed at that point. Once
 * the host window is fully realized, {@link #showAll(Object)} or {@link #showPending(Object)} displays every callout
 * registered for a given scope, as a single sequential, "Got it!"-dismissible chain, in registration order.
 * </p>
 * <p>
 * A callout's scope ("group") defaults to its owner's top-level window, so callouts registered without an explicit
 * group are automatically chained together with every other such callout in the same window; unrelated callers
 * sharing a window get independent chains only if each supplies its own, distinct {@code group} key. Dismissal is
 * persisted per callout (keyed off the owner's identity in the component tree), so {@link #showPending(Object)}
 * does not repeat a chain the user already stepped through in an earlier session; {@link #showAll(Object)} always
 * (re)displays it regardless, e.g., from a "replay tour" menu command.
 * </p>
 * <p>
 * Example:
 * </p>
 * <pre>{@code
 * // while building the GUI, e.g., in a dialog's constructor -- AFTER adding each button to its parent
 * // container, so the callout's persisted identity is derived from a stable position in the component tree:
 * toolbar.add(saveButton);
 * CalloutManager.add(saveButton, SwingConstants.BOTTOM, "Click here to save your work");
 * toolbar.add(exportButton);
 * CalloutManager.add(exportButton, CalloutManager.AUTO, "Export results to a spreadsheet or image stack");
 *
 * // once the dialog is fully built. Safe to call even before it is showing: display of each callout is
 * // deferred automatically until its owner component is actually visible on screen
 * SwingUtilities.invokeLater(() -> CalloutManager.showPending(this));
 *
 * // e.g., wired to a "Replay Tour" menu item, to show the same walkthrough again on demand, regardless
 * // of whether the user already stepped through (and dismissed) it in an earlier session
 * replayTourItem.addActionListener(e -> {
 *     CalloutManager.hideAll();
 *     CalloutManager.showAll(this);
 * });
 * }</pre>
 * <p>
 * Tips are unrelated to callouts: a tip is a single, standalone "tooltip"-like balloon shown on demand (e.g., a
 * rotating pool of tips behind a "hints" button), not part of an onboarding chain. It has no group, no persisted
 * dismissal, and no arrow -- {@link #showTip(Component, String, int)} and {@link #showTip(Component, String, int,
 * int)} are entirely independent of {@link #add(Component, int, String)}/{@link #showAll(Object)} and friends
 * above.
 * </p>
 * <p>
 * Example:
 * </p>
 * <pre>{@code
 * // load (and shuffle) a plain-text, one-tip-per-line resource once, e.g. as a field or in a constructor;
 * // lineProcessor is called on every surviving line, so a caller-specific placeholder token (there is nothing
 * // hint-related about ctrlKey() below -- CalloutManager has no notion of it) can be substituted on load
 * final List<String> hints = CalloutManager.loadTips(MyDialog.class, "hints.txt",
 *         line -> line.replace("ctrlKey()", myPlatformSpecificCtrlKeyLabel));
 *
 * // several resources can be merged into a single shuffled pool, e.g. tips common to every mode plus a set
 * // specific to the current one; a missing/unreadable resource is skipped rather than failing the whole load
 * final List<String> hints2 = CalloutManager.loadTips(MyDialog.class,
 *         List.of("hints-common.txt", myDialog.isAdvancedMode() ? "hints-advanced.txt" : "hints-basic.txt"),
 *         UnaryOperator.identity());
 *
 * // cycle through the pool each time a "hints" button is clicked; a new tip for the same owner automatically
 * // replaces (rather than stacks on top of) whichever one is already showing there
 * final int[] index = {0};
 * hintsButton.addActionListener(e -> {
 *     CalloutManager.showTip(hintsButton, hints.get(index[0]), CalloutManager.AUTO, 30000); // auto-dismiss in 30s
 *     index[0] = (index[0] + 1) % hints.size();
 * });
 *
 * // or a one-off tip, e.g. contextual feedback after some action, left on screen until dismissed (Escape, a
 * // click elsewhere, or the owner going away) since no auto-dismiss delay is given
 * CalloutManager.showTip(resultsPanel, "Nothing found -- try widening your search", SwingConstants.TOP);
 * }</pre>
 * <p>
 * Adapted from {@code HintManager}, part of the FlatLaf demo application (Apache License 2.0, Copyright 2020 FormDev
 * Software GmbH, author Karl Tauber): {@code flatlaf-demo/.../com/formdev/flatlaf/demo/HintManager.java}
 * </p>
 * <p>
 * Like the rest of Swing, this class is not thread-safe: every public method that touches on-screen state runs
 * on (or is redirected via {@code invokeLater} to) the event dispatch thread, so calls to {@link #add(Component,
 * int, String)}/{@link #showTip(Component, String, int)} and friends are expected to originate there, same as
 * any other Swing call.
 * </p>
 *
 * @author Tiago Ferreira
 */
public class CalloutManager {

    private static final Preferences PREFS = Preferences.userNodeForPackage(CalloutManager.class);
    private static final List<CalloutPanel> activeCallouts = new ArrayList<>();
    // the chain currently on screen for a given resolved scope (at most one shown panel per scope at a time), so
    // showAllOrAdvance() can tell an already-playing chain from one that needs to be started fresh
    private static final Map<String, CalloutPanel> activeChains = new HashMap<>();
    // standalone, unregistered balloons shown via showTip(): tracked ONLY so Escape can dismiss whichever is
    // currently on screen. Deliberately kept out of registrations/activeChains: a tip must never be reachable
    // from add()/showAll()/showPending() (it has no group, no persisted dismissal, no chain position), so it can
    // never be swept into (or clobber the state of) an actual onboarding chain
    private static final List<TipPanel> activeTips = new ArrayList<>();
    // durable registry of add()-ed callouts, keyed by generateKey(); NOT drained on display, so the same
    // scope can be displayed again later (e.g., from a "Replay Tour" menu command), any number of times
    private static final List<Registration> registrations = new ArrayList<>();
    private static boolean escDispatcherInstalled;
    // listeners notified whenever a chain starts, ends, or is paused/resumed for some scope, so e.g. a "tour"
    // button can keep its icon in sync even when the state change did not originate from that button itself
    // (Escape pausing the chain, or the chain running to completion via a callout's own "Got It!" button)
    private static final List<Runnable> stateListeners = new ArrayList<>();
    // per-group equivalent of stateListeners, so a listener scoped to one group is cleared automatically by
    // clearGroup(), instead of requiring the caller to hold onto the Runnable solely to call removeStateListener()
    private static final Map<String, List<Runnable>> groupStateListeners = new HashMap<>();

    /**
     * Sentinel {@code position} value for {@link #add(Component, int, String)}: the side to display the callout on
     * is chosen automatically, at display time, based on which side of {@code owner} currently has the most free
     * screen space
     */
    public static final int AUTO = -1;

    private CalloutManager() {} // do not allow instantiation

    /**
     * Registers a callout for later display via {@link #showAll(Object)} or {@link #showPending(Object)}; does not
     * show anything by itself. Equivalent to {@code add(owner, position, message, null)}: the callout's group defaults
     * to the owner's own top-level window. {@code position} may be {@link #AUTO} to pick the side automatically at
     * display time
     */
    public static String add(final Component owner, final int position, final String message) {
        return add(owner, position, message, null);
    }

    /**
     * Registers a callout for later display; does not show anything itself.
     * <p>
     * Registering again for the exact same {@code owner} instance (reference equality, regardless of whether it
     * generates the same key) replaces the earlier registration in place, preserving its original position in the
     * eventual display order
     * </p>
     * <p>
     * The returned key is derived from {@code owner}'s position in its component tree, so it is only reliably
     * unique once {@code owner} has at least been added to its parent container (it need not be showing yet); call
     * {@code add()} after that, not before, to avoid two distinct, still-unparented components of the same type
     * generating the same persisted-dismissal key
     * </p>
     *
     * @param owner    the component the callout will point at
     * @param position the {@link SwingConstants} side of {@code owner} the callout is displayed on, or
     *                 {@link #AUTO} to pick, at display time, whichever side currently has the most free screen space
     * @param message  the (HTML-capable) message to display
     * @param group    an explicit key callouts sharing it are displayed together by, as one chain; {@code null}
     *                 defaults to {@code owner}'s top-level window, so callers that both leave {@code group} unset
     *                 are automatically swept into the same chain whenever their owners share a window, pass a
     *                 distinct group key if that is not wanted.
     * @return a stable key identifying this registration, usable with {@link #forget(String...)}
     * @see #groupFor(Object)
     */
    public static String add(final Component owner, final int position, final String message, final String group) {
        return add(owner, position, message, group, Integer.MAX_VALUE);
    }

    /**
     * Same as {@link #add(Component, int, String, String)}, but with an explicit position in the eventual display
     * chain, for when the order {@code add()} calls happen to be made in (e.g., interleaved with unrelated
     * GUI-building code) does not match the desired callout sequence.
     * <p>
     * {@code order} is only a sort key, not a list index: entries sharing a chain are sorted by it, ties (including
     * every entry that omits an explicit order, via {@link #add(Component, int, String)} or {@link #add(Component,
     * int, String, String)}) broken by registration order. Values need not be contiguous, unique, or bounded by the
     * eventual chain length; an "out of range" order simply sorts to whichever end it is closest to, it never throws
     * </p>
     *
     * @param order this callout's position in its group's chain, relative to other explicitly-ordered entries
     */
    public static String add(final Component owner, final int position, final String message, final String group,
                             final int order) {
        if (owner == null)
            throw new IllegalArgumentException("owner == null");
        if (message == null)
            throw new IllegalArgumentException("message == null");
        if (position != AUTO && position != SwingConstants.TOP
                && position != SwingConstants.BOTTOM && position != SwingConstants.LEFT
                && position != SwingConstants.RIGHT)
            throw new IllegalArgumentException("Invalid position: " + position);
        final String key = generateKey(owner);
        final Registration entry = new Registration(message, new WeakReference<>(owner), position, key, group, order);
        synchronized (registrations) {
            final int idx = indexOfOwner(owner);
            if (idx >= 0)
                registrations.set(idx, entry);
            else
                registrations.add(entry);
        }
        return key;
    }

    /**
     * Displays, as a sequential "Got it!"-dismissible chain, every callout registered for {@code scope} (a
     * {@link Component}, resolved to its top-level window, or an explicit group key used with
     * {@link #add(Component, int, String, String)}), regardless of whether it was already dismissed in an earlier
     * session
     */
    public static void showAll(final Object scope) {
        runOnEdt(() -> showChain(entriesFor(scope), true, resolveScope(scope)));
    }

    /**
     * Same as {@link #showAll(Object)}, but skips (and does not re-show) callouts already dismissed in an earlier
     * session
     */
    public static void showPending(final Object scope) {
        runOnEdt(() -> showChain(entriesFor(scope), false, resolveScope(scope)));
    }

    /**
     * Same as {@link #showAll(Object)}, except that if a chain for {@code scope} is already on screen, this
     * advances it instead (as if the user had pressed "Got It!" on whichever callout is currently showing),
     * rather than hiding it and restarting the chain.
     * <p>
     * Wire a "tour"/"hints" button's action listener to this instead of {@link #showAll(Object)} directly: users
     * routinely click such a button again to page to the next tip once a tour has started, and {@code showAll()}
     * would otherwise restart the whole chain on every click
     * </p>
     */
    public static void showAllOrAdvance(final Object scope) {
        runOnEdt(() -> {
            final String target = resolveScope(scope);
            final CalloutPanel active = activeChains.get(target);
            if (active != null) {
                // a paused callout is one the user asked to get out of the way, not one they already read and
                // dismissed: bring it back rather than skipping past it as if "Got It!" had been pressed
                if (active.isPaused())
                    active.setPaused(false);
                else
                    active.gotIt();
            } else
                showChain(entriesFor(scope), true, target);
        });
    }

    /**
     * Displays a single, standalone balloon pointing at {@code owner} -- e.g., a rotating one-liner tip cycled
     * on each click of some ever-present control -- entirely outside the {@link #add(Component, int, String)}/
     * {@link #showAll(Object)} machinery: it is never registered, belongs to no group or chain, and its dismissal
     * is never persisted. Calling this repeatedly for the same {@code owner} (e.g., a new tip string on every
     * click) simply shows a new balloon each time; nothing here can be pulled into -- or interfere with -- an
     * actual onboarding chain running via {@link #showAll(Object)}/{@link #showPending(Object)}, even one
     * sharing the same window or {@code owner}.
     * <p>
     * Rendered without the directional arrow {@link #add(Component, int, String)} callouts use: that arrow means
     * "this text describes what I point to", which does not hold for a tip whose content is typically unrelated
     * to {@code owner} (owner is merely where the tip happens to surface, e.g., the button that was clicked)
     * </p>
     * <p>
     * Dismissed by its own close button, or by Escape (which, unlike its effect on a chain, closes the tip
     * outright rather than merely pausing it -- a standalone tip has no "resume where I left off" state to
     * preserve)
     * </p>
     *
     * @param owner    the component the tip is anchored near
     * @param message  the (HTML-capable) message to display
     * @param position the {@link SwingConstants} side of {@code owner} to display on, or {@link #AUTO} to pick
     *                 automatically
     */
    public static void showTip(final Component owner, final String message, final int position) {
        showTip(owner, message, position, -1);
    }

    /**
     * Same as {@link #showTip(Component, String, int)}, but auto-dismissed after {@code autoDismissMs} if the
     * user does not close it first; {@code autoDismissMs <= 0} means no timeout (dismissed only by its close
     * button, or Escape)
     */
    public static void showTip(final Component owner, final String message, final int position,
                               final int autoDismissMs) {
        if (owner == null)
            throw new IllegalArgumentException("owner == null");
        if (message == null)
            throw new IllegalArgumentException("message == null");
        if (position != AUTO && position != SwingConstants.TOP && position != SwingConstants.BOTTOM
                && position != SwingConstants.LEFT && position != SwingConstants.RIGHT)
            throw new IllegalArgumentException("Invalid position: " + position);
        runOnEdt(() -> {
            ensureEscDispatcherInstalled();
            // a new tip for the same owner (e.g., the next click of a "hints" button cycling through a pool)
            // replaces whichever one is already showing there, rather than stacking on top of it
            new ArrayList<>(activeTips).stream().filter(p -> p.owner == owner).forEach(TipPanel::close);
            ensureVisible(owner);
            if (!owner.isShowing()) {
                awaitShowing(owner, () -> showTip(owner, message, position, autoDismissMs));
                return;
            }
            final int resolvedPosition = (position == AUTO) ? bestAutoPosition(owner) : position;
            try {
                new TipPanel(owner, message, resolvedPosition, autoDismissMs).display();
            } catch (final RuntimeException ex) {
                // mirrors showChain()'s handling of CalloutPanel#display(): a tip failing to display (e.g., an
                // environment where translucency/screen-bounds lookups misbehave) must not surface as an
                // uncaught exception on the EDT, but the failure is still worth logging
                System.err.println("[CalloutManager] failed to display tip for " + owner.getClass().getSimpleName() + ": " + ex);
            }
        });
    }

    /**
     * Loads a shuffled list of tips/hints from a plain-text classpath resource: blank lines and lines starting
     * with {@code #} (comments) are skipped, every other line is trimmed and passed through {@code lineProcessor}
     * (e.g., to substitute a placeholder token with a platform-specific key name), then the result is shuffled.
     * Deliberately agnostic about what, if anything, needs substituting in a line: that is entirely up to the
     * caller-supplied {@code lineProcessor}, so this class needs no knowledge of any particular token scheme
     *
     * @param anchor            the resource is resolved via <em>this class's</em> class loader, not
     *                          {@code CalloutManager}'s own, nor the calling thread's context class loader: the
     *                          resource lives in the caller's module/jar (e.g., SNT's), which this class -- by
     *                          design -- knows nothing about, and a thread's context class loader is not
     *                          guaranteed to see it either (it may be {@code null}, e.g. on a background worker
     *                          thread, or scoped to some other module entirely). Pass, e.g., {@code SNTUI.class}
     * @param classpathResource the resource path (e.g., {@code "gui/hints.txt"}), resolved the same way
     *                          {@code anchor.getClassLoader().getResourceAsStream(...)} would
     * @param lineProcessor     applied to each surviving line before it is added to the result; {@code null} (or
     *                          {@link UnaryOperator#identity()}) to leave lines unmodified
     * @return the shuffled tips, or a single-element fallback list if the resource could not be read
     */
    public static List<String> loadTips(final Class<?> anchor, final String classpathResource,
                                        final UnaryOperator<String> lineProcessor) {
        return loadTips(anchor, List.of(classpathResource), lineProcessor);
    }

    /**
     * As {@link #loadTips(Class, String, UnaryOperator)}, but merges tips from several classpath resources into a
     * single shuffled pool (e.g., a set of tips common to all modes plus a set specific to the current mode). Each
     * resource is read independently: one that is missing or unreadable is skipped (and logged) rather than
     * aborting the whole load, so a single bad/renamed file does not take down the others. The fallback
     * single-element list is only returned if none of the requested resources yielded any tips
     *
     * @param anchor             see {@link #loadTips(Class, String, UnaryOperator)}
     * @param classpathResources the resource paths to load and merge, e.g.
     *                           {@code List.of("gui/hints-common.txt", "gui/hints-stream.txt")}
     * @param lineProcessor      applied to each surviving line before it is added to the result; {@code null} (or
     *                           {@link UnaryOperator#identity()}) to leave lines unmodified
     * @return the shuffled, merged tips, or a single-element fallback list if no resource could be read
     */
    public static List<String> loadTips(final Class<?> anchor, final List<String> classpathResources,
                                        final UnaryOperator<String> lineProcessor) {
        final UnaryOperator<String> processor = (lineProcessor != null) ? lineProcessor : UnaryOperator.identity();
        final List<String> tips = new ArrayList<>();
        for (final String classpathResource : classpathResources) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    Objects.requireNonNull(anchor.getClassLoader().getResourceAsStream(classpathResource))))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) // skip empty lines and comments
                        tips.add(processor.apply(line));
                }
            } catch (final Exception e) {
                System.err.println("[CalloutManager] failed to load tips from " + classpathResource + ": " + e);
                // don't bail out: the other resources in the list may still load fine
            }
        }
        if (tips.isEmpty())
            tips.add("No tips available"); // fallback, only if every resource failed to yield anything
        Collections.shuffle(tips);
        return tips;
    }

    /**
     * Hides (without dismissing or advancing) whichever callout is currently on screen for {@code scope}, leaving
     * the chain's position untouched so {@link #resume(Object)} (or {@link #showAllOrAdvance(Object)}) shows it
     * again exactly where it was. A no-op if no chain is currently active for {@code scope}
     */
    public static void pause(final Object scope) {
        runOnEdt(() -> {
            final CalloutPanel active = activeChains.get(resolveScope(scope));
            if (active != null)
                active.setPaused(true);
        });
    }

    /**
     * Reverses {@link #pause(Object)}: re-shows whichever callout was hidden for {@code scope}. A no-op if no
     * chain is currently active for {@code scope}, or it was not paused
     */
    public static void resume(final Object scope) {
        runOnEdt(() -> {
            final CalloutPanel active = activeChains.get(resolveScope(scope));
            if (active != null)
                active.setPaused(false);
        });
    }

    /**
     * Toggles {@link #pause(Object)}/{@link #resume(Object)} for {@code scope}. A no-op if no chain is currently
     * active for {@code scope}
     */
    public static void togglePause(final Object scope) {
        runOnEdt(() -> {
            final CalloutPanel active = activeChains.get(resolveScope(scope));
            if (active != null)
                active.setPaused(!active.isPaused());
        });
    }

    /**
     * @return whether the callout currently on screen for {@code scope} (if any) is paused. Always {@code false}
     * if no chain is currently active for {@code scope}
     */
    public static boolean isPaused(final Object scope) {
        final CalloutPanel active = activeChains.get(resolveScope(scope));
        return active != null && active.isPaused();
    }

    /**
     * @return whether a chain is currently active (on screen, or paused/hidden mid-chain) for {@code scope}.
     * Handy for driving a "tour" button's icon between an idle/playing/paused state
     */
    public static boolean isActive(final Object scope) {
        return activeChains.containsKey(resolveScope(scope));
    }

    /**
     * Registers a listener invoked (on the EDT) whenever any chain starts, ends, or is paused/resumed, for
     * any scope. Intended for driving a UI element, such as a "tour" button's icon, that needs to reflect
     * {@link #isActive(Object)}/{@link #isPaused(Object)} accurately regardless of what triggered the change
     * (this class' own API, or Escape, or a callout's own "Got It!" button)
     */
    public static void addStateListener(final Runnable listener) {
        stateListeners.add(listener);
    }

    /**
     * Same as {@link #addStateListener(Runnable)}, but {@code listener} is also unregistered automatically when
     * {@code group} is cleared via {@link #clearGroup(Object)}.
     *
     * @param group the group whose cleanup should also unregister {@code listener} (see
     *              {@link #groupFor(Object)})
     */
    public static void addStateListener(final Runnable listener, final Object group) {
        groupStateListeners.computeIfAbsent(resolveScope(group), g -> new ArrayList<>()).add(listener);
    }

    public static void removeStateListener(final Runnable listener) {
        stateListeners.remove(listener);
        for (final List<Runnable> listeners : groupStateListeners.values())
            listeners.remove(listener);
    }

    private static void fireStateChanged() {
        final List<Runnable> listeners = new ArrayList<>(stateListeners);
        groupStateListeners.values().forEach(listeners::addAll);
        for (final Runnable listener : listeners) {
            try {
                listener.run();
            } catch (final RuntimeException ex) {
                // one misbehaving listener should not stop the others from being notified
                System.err.println("[CalloutManager] state listener failed: " + ex);
            }
        }
    }

    /**
     * Hides all currently visible callouts without marking them as dismissed
     */
    public static void hideAll() {
        runOnEdt(() -> new ArrayList<>(activeCallouts).forEach(CalloutPanel::close));
    }

    /**
     * Clears the dismissed flag of the given callout keys, so they will be shown again by a subsequent
     * {@link #showPending(Object)}
     *
     * @param prefsKeys the preference keys to clear; {@code null} is equivalent to calling {@link #forgetAll()}.
     */
    public static void forget(final String... prefsKeys) {
        if (prefsKeys == null) {
            forgetAll();
        } else for (final String key : prefsKeys) {
            if (key != null)
                PREFS.remove(key);
        }
    }

    /**
     * Clears every dismissed flag ever recorded by this class
     */
    public static void forgetAll() {
        try {
            // NOTE: PREFS.removeNode() would also do this, but it leaves the node itself unusable
            PREFS.clear();
        } catch (final BackingStoreException ignored) {
            // best effort only
        }
    }

    /**
     * Discards every {@link #add}-registered callout belonging to {@code group}, hiding its chain if currently on
     * screen.
     * <p>
     * Unlike {@link #hideAll()} (only hides whatever chain is currently visible, without forgetting it) or
     * {@link #forget(String...)}/{@link #forgetAll()} (only clear persisted dismissal so a chain can be replayed),
     * this permanently removes {@code group}'s entries from {@link #registrations}. Call it when the session that
     * registered them is going away, e.g., when closing a window or shutting down a program: Without clearing the
     * group, the group key itself (and anything reachable from it) is kept alive, as well as its {@link #registrations}
     * list.
     * </p>
     * <p>
     * Callouts registered without an explicit group (i.e., scoped to their owner's own top-level window) are
     * unaffected unless that window itself is passed as {@code group}. {@link #showTip(Component, String, int)}
     * balloons are always unaffected: they are never part of {@link #registrations} to begin with, having no
     * group of their own.
     * </p>
     * <p>
     * Also unregisters any listener added for {@code group} via {@link #addStateListener(Runnable, Object)}.
     * </p>
     *
     * @param group the group previously passed to {@link #add(Component, int, String, String)} (or resolved
     *              implicitly, if a {@link Component}/{@link Window} whose owners were registered without an
     *              explicit group)
     */
    public static void clearGroup(final Object group) {
        runOnEdt(() -> {
            final String target = resolveScope(group);
            final CalloutPanel active = activeChains.get(target);
            if (active != null) active.close(); // also removes itself from activeCallouts/activeChains
            synchronized (registrations) {
                registrations.removeIf(e -> {
                    final Component owner = e.owner.get();
                    return owner == null || Objects.equals(resolveGroup(e, owner), target);
                });
            }
            groupStateListeners.remove(target);
        });
    }

    private static int indexOfOwner(final Component owner) {
        for (int i = 0; i < registrations.size(); i++)
            if (registrations.get(i).owner.get() == owner)
                return i;
        return -1;
    }

    /**
     * Returns, in display order (explicitly-ordered entries first, sorted by {@link Registration#order}, then
     * everything else in registration order), every registered callout belonging to {@code scope}. Entries are
     * NOT removed, so the same scope can be shown again by a later call (e.g., a "Replay Tour" button clicked
     * more than once)
     */
    private static List<Callout> entriesFor(final Object scope) {
        final String target = resolveScope(scope);
        // snapshot the still-live entries (pruning any whose owner was garbage-collected) while holding the
        // lock; resolveGroup()/windowOf() below walk the AWT component tree, which must NOT be done while
        // holding this lock -- AWT's own tree operations are internally synchronized, and calling into them
        // here would risk a lock-ordering deadlock with code that acquires the two locks in the other order
        final List<LiveEntry> live = new ArrayList<>();
        synchronized (registrations) {
            final Iterator<Registration> it = registrations.iterator();
            while (it.hasNext()) {
                final Registration e = it.next();
                final Component owner = e.owner.get();
                if (owner == null) {
                    it.remove(); // owner has been garbage-collected: prune the stale registration
                    continue;
                }
                live.add(new LiveEntry(e, owner));
            }
        }
        // explicitly-ordered entries sort by that order; everything else keeps its natural registration
        // order, since Comparator (like List.sort()) is guaranteed stable
        live.sort(Comparator.comparingInt(le -> le.entry.order));
        final List<Callout> found = new ArrayList<>();
        for (final LiveEntry le : live) {
            if (Objects.equals(resolveGroup(le.entry, le.owner), target))
                found.add(new Callout(le.entry.message, le.owner, le.entry.position, le.entry.prefsKey));
        }
        return found;
    }

    /**
     * Returns a String key derived from {@code instance}'s identity, e.g. for use as  the {@code group} passed to
     * {@link #add(Component, int, String, String)}/
     * <p>
     * A {@code group} is a plain {@code String}, precisely so this class can never be handed (and made to hold
     * on to, for as long as the entry is registered) an arbitrary live object; this method is the sanctioned way
     * to scope by such an object anyway. The returned key holds no reference back to {@code instance}: it is
     * derived once, from {@code instance}'s identity hash code and class name, and never looked at again. Two
     * calls with the very same instance (while it is still alive) return equal keys.
     * </p>
     *
     * @param instance the object whose identity to derive a group key from; never retained by this class
     */
    public static String groupFor(final Object instance) {
        return instance.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(instance));
    }

    private static String resolveScope(final Object scope) {
        if (scope == null || scope instanceof String) return (String) scope;
        if (scope instanceof Component c) return groupFor(windowOf(c));
        throw new IllegalArgumentException(
                "scope must be a Component or a String group key (see groupFor()): " + scope.getClass());
    }

    /**
     * The effective group a pending entry belongs to: its explicit group, or, if none was given, a key for
     * {@code owner}'s top-level window (see {@link #groupFor(Object)})
     */
    private static String resolveGroup(final Registration entry, final Component owner) {
        return (entry.group != null) ? entry.group : groupFor(windowOf(owner));
    }

    private static Window windowOf(final Component c) {
        return (c instanceof Window w) ? w : SwingUtilities.getWindowAncestor(c);
    }

    /**
     * Runs {@code r} on the EDT: immediately if already there, otherwise via {@link SwingUtilities#invokeLater}.
     */
    private static void runOnEdt(final Runnable r) {
        if (SwingUtilities.isEventDispatchThread())
            r.run();
        else
            SwingUtilities.invokeLater(r);
    }

    /**
     * The screen area actually usable for positioning windows on {@code c}'s screen, i.e., its
     * {@link GraphicsConfiguration} bounds shrunk by the OS-reported screen insets (taskbar, dock, menu bar), so
     * callouts are never placed on top of them
     */
    private static Rectangle usableScreenBounds(final Component c) {
        final GraphicsConfiguration gc = c.getGraphicsConfiguration();
        final Rectangle bounds = gc.getBounds();
        final Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(bounds.x + insets.left, bounds.y + insets.top,
                bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom);
    }

    /**
     * If {@code callout}'s position is {@link #AUTO}, resolves it to whichever {@link SwingConstants} side of its
     * owner currently has the most free screen space (ties broken by preferring, in order, BOTTOM, TOP, RIGHT, LEFT);
     * otherwise returns {@code callout} unchanged. Only called once the owner is confirmed showing, so its on-screen
     * location is known
     */
    private static Callout resolveAutoPosition(final Callout callout) {
        if (callout.position != AUTO)
            return callout;
        return new Callout(callout.message, callout.owner, bestAutoPosition(callout.owner), callout.prefsKey);
    }

    private static int bestAutoPosition(final Component owner) {
        final Rectangle anchor = anchorBounds(owner);
        final Rectangle screen = usableScreenBounds(owner);
        final int spaceAbove = anchor.y - screen.y;
        final int spaceBelow = (screen.y + screen.height) - (anchor.y + anchor.height);
        final int spaceLeft = anchor.x - screen.x;
        final int spaceRight = (screen.x + screen.width) - (anchor.x + anchor.width);
        if (spaceBelow >= spaceAbove && spaceBelow >= spaceLeft && spaceBelow >= spaceRight)
            return SwingConstants.BOTTOM;
        if (spaceAbove >= spaceLeft && spaceAbove >= spaceRight)
            return SwingConstants.TOP;
        return (spaceRight >= spaceLeft) ? SwingConstants.RIGHT : SwingConstants.LEFT;
    }

    /**
     * The on-screen rectangle a callout should actually point at for {@code owner}. Normally just
     * {@code owner}'s own bounds, except when {@code owner} is itself a tab's content root (its direct parent
     * is a {@link JTabbedPane}): pointing an arrow at an entire tab's content area is rarely useful, so such an
     * {@code owner} is anchored to that tab's clickable header instead, via {@link JTabbedPane#getBoundsAt(int)}
     */
    private static Rectangle anchorBounds(final Component owner) {
        if (owner.getParent() instanceof JTabbedPane tabs) {
            final int idx = tabs.indexOfComponent(owner);
            final Rectangle tabBounds = (idx >= 0) ? tabs.getBoundsAt(idx) : null;
            if (tabBounds != null) {
                final Point tabsOnScreen = tabs.getLocationOnScreen();
                return new Rectangle(tabsOnScreen.x + tabBounds.x, tabsOnScreen.y + tabBounds.y,
                        tabBounds.width, tabBounds.height);
            }
        }
        return new Rectangle(owner.getLocationOnScreen(), owner.getSize());
    }

    private static void showChain(final List<Callout> list, final boolean ignoreDismissed, final String target) {
        ensureEscDispatcherInstalled();
        // an onboarding chain is always meant to be front-and-center; a standalone tip left on screen is just
        // as "always on top" as the callout about to appear, and the two competing for front-most z-order is
        // not reliably resolved in the callout's favor by the OS/LAF. Tips are cheap to re-summon (the next
        // click of whatever showed them) and persist nothing, so closing them all here -- rather than trying
        // to scope this to just the incoming chain's window -- is a simple, safe default
        new ArrayList<>(activeTips).forEach(TipPanel::close);
        showChain(list, 0, ignoreDismissed, target);
    }

    /**
     * Lazily installs (once, the first time any chain is shown) a {@link KeyEventDispatcher} that pauses every
     * currently VISIBLE callout (i.e., {@link CalloutPanel#setPaused(boolean)}, same as the chain's own
     * pause/resume gesture) when Escape is pressed. Chosen over dismissing outright so Escape reads as "get this
     * out of my way for now", not "abandon the tour"; {@link #resume(Object)} (or a later
     * {@link #showAllOrAdvance(Object)}) picks it back up exactly where it was left. A callout already paused
     * has nothing on screen to get out of the way of, so it is left alone and Escape is not consumed, letting it
     * fall through to whatever else may be listening for it (e.g., closing an unrelated dialog). Likewise inert
     * for a chain entry still parked inside {@link #awaitShowing} (its owner not showing yet)
     */
    private static void ensureEscDispatcherInstalled() {
        if (escDispatcherInstalled)
            return;
        escDispatcherInstalled = true;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED || e.getKeyCode() != KeyEvent.VK_ESCAPE)
                return false;
            boolean consumed = false;
            final List<CalloutPanel> visible = activeCallouts.stream().filter(p -> !p.isPaused()).toList();
            if (!visible.isEmpty()) {
                visible.forEach(p -> p.setPaused(true));
                consumed = true;
            }
            // a standalone tip has no "paused" state to preserve (nothing resumes it later), so Escape just
            // closes it outright, same as clicking its own close button
            final List<TipPanel> tips = new ArrayList<>(activeTips);
            if (!tips.isEmpty()) {
                tips.forEach(TipPanel::close);
                consumed = true;
            }
            return consumed; // consume: do not also let it, e.g., close/cancel the host dialog
        });
    }

    /**
     * Runs {@code r} once {@code c} is actually showing: immediately, if it already is; otherwise the first
     * time its showing state changes to {@code true} (e.g., once its top-level window is made visible)
     */
    private static void awaitShowing(final Component c, final Runnable r) {
        if (c.isShowing()) {
            r.run();
            return;
        }
        c.addHierarchyListener(new HierarchyListener() {
            @Override
            public void hierarchyChanged(final HierarchyEvent e) {
                if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && c.isShowing()) {
                    c.removeHierarchyListener(this);
                    r.run();
                }
            }
        });
    }

    /**
     * Best-effort attempt to make {@code owner} {@link Component#isShowing()} (or, failing that, actually seen by
     * the user) before it is checked for that: scrolls it into view within any ancestor {@link JScrollPane}, shows
     * every ancestor {@link JToolBar} it is nested in, selects the tab of every ancestor {@link JTabbedPane} it is
     * nested in, brings its top-level window to front (de-iconifying it first if needed), and, if {@code owner} is
     * a {@link JMenuItem}, opens and arms its enclosing menu chain. Failures are swallowed either way; the caller
     * falls back to {@link #awaitShowing} if {@code owner} still is not showing afterward
     * <p>
     * Not handled: a {@link JSplitPane} divider collapsed onto {@code owner}'s side, or a
     * {@link java.awt.CardLayout} card that is not the one currently selected. Both leave {@code owner}
     * {@code isShowing() == true} regardless, so a callout there is silently mispositioned rather than skipped
     * outright; add explicit support for either if this class starts being relied on where that matters. Also not
     * handled: a modal dialog, or another always-on-top window, sitting over {@code owner}'s window. There is no
     * safe generic action to take there ({@link Window#toFront()} cannot un-block a modal dialog, and forcing it
     * aside is not this class's call to make), so the callout may still render behind or alongside it
     * </p>
     */
    private static void ensureVisible(final Component owner) {
        if (owner instanceof JComponent jc)
            jc.scrollRectToVisible(new Rectangle(0, 0, jc.getWidth(), jc.getHeight()));
        // owner itself may be a hidden toolbar (e.g., toggled off by the user), not just nested in one:
        // the ancestor walk below only ever inspects owner's parents, never owner itself
        if (owner instanceof JToolBar toolBar && !toolBar.isVisible())
            toolBar.setVisible(true);
        Component c = owner;
        Container p = c.getParent();
        while (p != null) {
            if (p instanceof JTabbedPane tabs) {
                final int idx = tabs.indexOfComponent(c);
                if (idx >= 0)
                    tabs.setSelectedIndex(idx);
            } else if (p instanceof JToolBar toolBar && !toolBar.isVisible()) {
                toolBar.setVisible(true);
            }
            c = p;
            p = p.getParent();
        }
        ensureWindowVisible(windowOf(owner));
        // a JMenu sitting directly in a JMenuBar is already reachable/clickable at all times, regardless of
        // whether its dropdown is open, so it needs no menu-selection trick; only a genuinely nested JMenuItem
        // (inside a currently-closed menu) does. Arming/selecting a top-level JMenu here would leave it stuck
        // highlighted in the menu bar, with nothing to ever clear that state once the callout is dismissed
        if (owner instanceof JMenuItem jmi && !(jmi.getParent() instanceof JMenuBar))
            openMenuChain(jmi);
    }

    /**
     * Brings {@code window} to front, de-iconifying it first if it is an iconified {@link Frame}. A no-op if
     * {@code window} is {@code null} (e.g., {@code owner} is not parented to one yet)
     */
    private static void ensureWindowVisible(final Window window) {
        if (window == null)
            return;
        if (window instanceof Frame frame) {
            final int state = frame.getExtendedState();
            if ((state & Frame.ICONIFIED) != 0)
                frame.setExtendedState(state & ~Frame.ICONIFIED);
        }
        window.toFront();
    }

    /**
     * Opens (and arms) {@code jmi}'s enclosing {@link JMenu}/{@link JMenuBar} chain, so a callout can anchor to a
     * menu item that is not currently armed: a plain {@link JMenuItem#isShowing()} check is false for one whose
     * menu is closed. Standalone popups (not anchored to a JMenu/JMenuBar, e.g. a right-click context menu) have
     * no chain to open and are left alone; a callout anchored there should invoke the menu itself before showing
     */
    private static void openMenuChain(final JMenuItem jmi) {
        try {
            final MenuElement[] path = GuiUtils.MenuItems.getMenuPath(jmi);
            if (path.length > 1) {
                MenuSelectionManager.defaultManager().setSelectedPath(path);
                jmi.setArmed(true);
            }
        } catch (final RuntimeException ignored) {
            // best effort only; caller falls back to awaitShowing if this did not make jmi showing
        }
    }

    private static void showChain(final List<Callout> list, final int index, final boolean ignoreDismissed,
                                  final String target) {
        if (index >= list.size()) {
            activeChains.remove(target);
            return;
        }
        Callout callout = list.get(index);
        if (!ignoreDismissed && PREFS.getBoolean(callout.prefsKey, false)) {
            showChain(list, index + 1, ignoreDismissed, target);
            return;
        }
        // always attempt this, not just when owner is not yet showing: isShowing() is true as long as
        // owner and its ancestors are visible, regardless of whether owner's window is the frontmost one,
        // so a window sitting behind some unrelated, currently-frontmost window still passes it, and would
        // otherwise never get the toFront() call (inside ensureVisible()) needed to surface the callout
        ensureVisible(callout.owner);
        if (!callout.owner.isShowing()) {
            // owner not showing YET: callers give no guarantee about when, relative to their window
            // actually becoming visible, add()/showAll()/showPending() get called (e.g., a dialog's own
            // setVisible(true) may be queued in a *later* invokeLater than the one that triggers this),
            // so wait for it rather than silently dropping this entry from the chain
            awaitShowing(callout.owner, () -> showChain(list, index, ignoreDismissed, target));
            return;
        }
        callout = resolveAutoPosition(callout);
        final CalloutPanel panel = new CalloutPanel(callout, index + 1, list.size(),
                () -> showChain(list, index + 1, ignoreDismissed, target));
        boolean shown;
        try {
            shown = panel.display();
        } catch (final RuntimeException ex) {
            // do not let one misbehaving callout silently kill the rest of the chain, but do not hide the
            // failure either, since a caller debugging a missing callout has nothing else to go on
            System.err.println("[CalloutManager] failed to display callout for " + callout.owner.getClass().getSimpleName() + ": " + ex);
            shown = false;
        }
        if (shown) {
            activeCallouts.add(panel);
            activeChains.put(target, panel);
            fireStateChanged();
        } else
            // display() failed (e.g., owner stopped showing in the meantime): skip, keep the chain moving
            showChain(list, index + 1, ignoreDismissed, target);
    }

    private static String generateKey(final Component comp) {
        assert comp != null;
        final StringBuilder path = new StringBuilder();
        if (comp instanceof AbstractButton b && b.getText() != null)
            path.append(b.getText()).append("|");
        Component current = comp;
        while (current != null) {
            final Container parent = current.getParent();
            final int index = (parent != null) ? parent.getComponentZOrder(current) : 0;
            path.append(current.getClass().getSimpleName()).append("[").append(index).append("].");
            current = parent;
        }
        // masked to non-negative: Math.abs(Integer.MIN_VALUE) is still negative
        final String hash = Integer.toString(path.toString().hashCode() & 0x7fffffff, 36);
        return comp.getClass().getSimpleName().toLowerCase() + "_" + hash;
    }

    /**
     * @return the number of registered callouts (i.e., past {@link #add(Component, int, String)} calls, whether
     * they have been displayed yet). Handy for assigning explicit, gap-free {@code order} values to a
     * batch of {@code add()} calls that should continue on from where an earlier batch (e.g., in a different
     * class) left off
     */
    public static int size() {
        synchronized (registrations) {
            return registrations.size();
        }
    }

    /**
     * A single balloon message pointing at a component, resolved from a {@link Registration} by
     * {@link #entriesFor(Object)} (and, for an {@link #AUTO} position, finalized by
     * {@link #resolveAutoPosition(Callout)} once the owner is confirmed showing). Display order and
     * dismiss-driven sequencing are handled entirely by {@link CalloutManager}, not by this class
     */
    private record Callout(String message, Component owner, int position, String prefsKey) {
    }

    /**
     * A registered callout, together with the (possibly {@code null}, i.e., not-yet-resolved) group it was
     * registered under. Stays in {@link #registrations} even after being displayed, so the same scope can be
     * shown again later. Holds its owner only via a {@link WeakReference}: once nothing else in the application
     * references that component (e.g., its window was shown once and never reopened), this registration stops
     * being the reason its whole component tree stays reachable, and {@link #entriesFor(Object)} prunes it the
     * next time it scans
     */
    // order: sort key used by entriesFor() to arrange a group's chain; Integer.MAX_VALUE (the default used
    // by the add() overload that omits it) sorts an entry after every explicitly-ordered one
    private record Registration(String message, WeakReference<Component> owner, int position, String prefsKey,
                                String group, int order) {
    }

    /**
     * A {@link Registration} paired with its (already resolved, non-null) owner; used by {@link #entriesFor(Object)}
     * to snapshot live entries while holding the {@link #registrations} lock, so the AWT-tree-walking resolution
     * that follows can happen safely outside it
     */
    private record LiveEntry(Registration entry, Component owner) {
    }

    /**
     * Shared "floating balloon window" plumbing for {@link CalloutPanel} (a chain-callout balloon) and
     * {@link TipPanel} (a standalone tip balloon): creating, positioning, and disposing the owned {@link JWindow},
     * tracking the owner window so the balloon follows it (or is dismissed alongside it) as it moves, resizes,
     * hides, or closes, and the translucent-background painting trick that keeps a rounded/arrow border from
     * being clipped by an otherwise-rectangular, opaque window.
     * <p>
     * Deliberately does NOT own {@link #reposition()} (a chain callout aligns its arrow with the anchor's
     * center; a tip simply centers the box on it -- different enough that sharing one formula would obscure
     * more than it saves) or any notion of dismissal bookkeeping beyond the {@link #onShown()}/
     * {@link #onClosed()} hooks: keeping this class ignorant of {@link #registrations}/{@link #activeChains}/
     * {@link #PREFS} (chain-only) vs. {@link #activeTips} (tip-only) is what keeps the two balloon kinds
     * structurally independent -- see {@link #showTip(Component, String, int, int)}
     * </p>
     */
    private abstract static class Balloon extends JPanel {

        final Component owner;
        JWindow popup;
        private Window ownerWindow;
        private ComponentAdapter ownerWindowListener;
        private WindowAdapter ownerCloseListener;
        private boolean translucentWindow;

        Balloon(final Component owner) {
            this.owner = owner;
            setOpaque(false);
            // swallow mouse events so components underneath do not receive them
            addMouseListener(new MouseAdapter() {
            });
        }

        @Override
        protected void paintComponent(final Graphics g) {
            if (translucentWindow) {
                final Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setComposite(AlphaComposite.Clear);
                    g2.fillRect(0, 0, getWidth(), getHeight());
                } finally {
                    g2.dispose();
                }
            }
            super.paintComponent(g);
        }

        /**
         * Creates, positions, and shows this balloon's owned {@link JWindow}, wires up the listeners that keep
         * it tracking (or close it alongside) {@code owner}'s top-level window, then runs {@link #onShown()}.
         * Returns {@code false} (without doing anything) if {@code owner} is not currently showing, or has no
         * resolvable top-level window
         * <p>
         * Named {@code display()}, not {@code show()}: {@link Component} already declares a public, deprecated,
         * {@code void}-returning {@code show()}, which a package-private {@code boolean show()} here would
         * clash with (incompatible return type, reduced visibility) rather than override
         * </p>
         */
        final boolean display() {
            if (!owner.isShowing()) return false;
            ownerWindow = windowOf(owner);
            if (ownerWindow == null) return false;

            // an owned top-level window, not the owner's layered pane, so the
            // balloon can extend past a narrow/short parent dialog uncropped
            popup = new JWindow(ownerWindow);
            if (isTranslucencySupported(owner)) {
                try {
                    popup.setBackground(new Color(0, 0, 0, 0));
                    popup.getRootPane().setOpaque(false);
                    translucentWindow = true;
                } catch (final UnsupportedOperationException ignored) {
                    // keep the default (opaque) background
                }
            }
            popup.setContentPane(this);
            popup.pack();
            try {
                popup.setAlwaysOnTop(true);
            } catch (final SecurityException ignored) {
                // best effort only, not critical
            }
            reposition();
            popup.setVisible(true);

            // keep the balloon anchored if the owner window is moved/resized
            ownerWindowListener = new ComponentAdapter() {
                @Override
                public void componentMoved(final ComponentEvent e) {
                    reposition();
                }

                @Override
                public void componentResized(final ComponentEvent e) {
                    reposition();
                }

                @Override
                public void componentHidden(final ComponentEvent e) {
                    // covers the common JDialog default of HIDE_ON_CLOSE, which fires this instead of
                    // windowClosed (ownerCloseListener only ever sees an actual dispose())
                    close();
                }
            };
            ownerWindow.addComponentListener(ownerWindowListener);

            // make sure this balloon is cleaned up if the owner window is closed while it is still showing
            ownerCloseListener = new WindowAdapter() {
                @Override
                public void windowClosed(final WindowEvent e) {
                    close();
                }
            };
            ownerWindow.addWindowListener(ownerCloseListener);
            onShown();
            return true;
        }

        /**
         * Repositions the already-visible {@link #popup} relative to {@code owner}'s current on-screen location
         */
        abstract void reposition();

        /**
         * Run once {@link #display()} has successfully displayed the balloon; a no-op unless overridden
         */
        void onShown() {
        }

        /**
         * Disposes {@link #popup} and tears down the owner-window listeners; safe to call even if
         * {@link #display()} was never called, or already failed. Subclasses hook {@link #onClosed()} (not this
         * method) for their own bookkeeping, which runs unconditionally in a {@code finally}, so a failure
         * disposing the popup can never leave that bookkeeping out of sync
         */
        final void close() {
            try {
                if (popup != null) {
                    if (ownerWindow != null) {
                        if (ownerWindowListener != null)
                            ownerWindow.removeComponentListener(ownerWindowListener);
                        if (ownerCloseListener != null)
                            ownerWindow.removeWindowListener(ownerCloseListener);
                    }
                    popup.dispose();
                }
            } finally {
                onClosed();
            }
        }

        /**
         * Run (always, even if disposing {@link #popup} failed) once {@link #close()} has torn this balloon down
         */
        abstract void onClosed();

        /**
         * If {@link #popup} is currently showing, re-packs it (border/content insets may have changed, e.g.
         * after a LAF switch) and repositions it; a no-op otherwise. Call from an {@code updateUI()} override
         * after refreshing colors/border, not before
         */
        final void repackAndReposition() {
            if (popup != null) {
                popup.pack();
                reposition();
            }
        }

        static boolean isTranslucencySupported(final Component owner) {
            final GraphicsConfiguration gc = owner.getGraphicsConfiguration();
            final GraphicsDevice gd = (gc != null) ? gc.getDevice()
                    : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            return gd.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        }
    }

    private static class CalloutPanel extends Balloon {

        // These are based on FlatLaf's demo app colors for its "HintPanel" (com.formdev.flatlaf.demo/*.properties):
        private static final Color FLATLAF_HINT_LIGHT = new Color(0xffffe9); // FlatLightLaf.properties
        private static final Color FLATLAF_HINT_DARK = new Color(0x505000); // FlatDarkLaf.properties: darken(#ffffe9,80%)

        private final Callout callout;
        // this callout's 1-based position within its chain, and the chain's current total size; both purely
        // informational (a small "step/total" indicator), never affects ordering or display logic
        private final int step;
        private final int total;
        private final Runnable onDismiss;
        private JLabel messageLabel;
        private JLabel stepLabel; // "step/total" indicator; null (and omitted) whenever total <= 1
        private BalloonBorder balloonBorder;
        private boolean paused;

        private CalloutPanel(final Callout callout, final int step, final int total, final Runnable onDismiss) {
            super(callout.owner);
            this.callout = callout;
            this.step = step;
            this.total = total;
            this.onDismiss = onDismiss;
            build();
            updateBalloonBorder();
            messageLabel.setText("<html>" + callout.message + "</html>");
        }

        @Override
        public void updateUI() {
            super.updateUI();
            setBackground(calloutBackground());
            if (stepLabel != null)
                stepLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            if (callout != null) {
                updateBalloonBorder();
                // a LAF/theme switch while this callout is showing must also refresh the popup's size
                // (border insets may have changed) and the arrow's re-derived position, or it is left
                // pointing at a stale offset
                repackAndReposition();
            }
        }

        private static Color calloutBackground() {
            final Color info = UIManager.getColor("info");
            final boolean isFlatLaf = UIManager.getLookAndFeel() instanceof FlatLaf;
            return Stream.of(
                            UIManager.getColor("Callout.background"), // lets a custom LAF/theme override just this class
                            UIManager.getColor("HintPanel.backgroundColor"), // key used by FlatLaf's own demo/IntelliJ themes
                            isFlatLaf ? (FlatLaf.isLafDark() ? FLATLAF_HINT_DARK : FLATLAF_HINT_LIGHT) : null,
                            FlatUIUtils.nonUIResource(info), // reached only when NOT FlatLaf (the FlatLaf case is already covered above)
                            UIManager.getColor("Popup.background"),
                            UIManager.getColor("Panel.background")
                    )
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(Color.WHITE); // guaranteed non-null fallback
        }

        private void updateBalloonBorder() {
            final int direction = switch (callout.position) {
                case SwingConstants.LEFT -> SwingConstants.RIGHT;
                case SwingConstants.TOP -> SwingConstants.BOTTOM;
                case SwingConstants.RIGHT -> SwingConstants.LEFT;
                case SwingConstants.BOTTOM -> SwingConstants.TOP;
                default -> throw new IllegalArgumentException("Invalid position: " + callout.position);
            };
            balloonBorder = new BalloonBorder(direction, FlatUIUtils.getUIColor("PopupMenu.borderColor", Color.GRAY));
            setBorder(balloonBorder);
        }

        @Override
        void reposition() {
            if (popup == null || !callout.owner.isShowing()) return;
            final Rectangle anchor = anchorBounds(callout.owner);
            final Point pt = anchor.getLocation();
            final Dimension ownerSize = anchor.getSize();
            final Dimension size = popup.getSize();
            final int gap = UIScale.scale(6);
            // offset from the balloon's top-left corner to where its arrow tip actually is, so the tip lands
            // on the anchor's center rather than the box being merely flush with the anchor's corner
            final int arrowXY = UIScale.scale(BalloonBorder.PAD + BalloonBorder.ARROW_XY + BalloonBorder.ARROW_SIZE);
            int x;
            int y;
            switch (callout.position) {
                case SwingConstants.TOP -> {
                    x = pt.x + ownerSize.width / 2 - arrowXY;
                    y = pt.y - size.height - gap;
                }
                case SwingConstants.BOTTOM -> {
                    x = pt.x + ownerSize.width / 2 - arrowXY;
                    y = pt.y + ownerSize.height + gap;
                }
                case SwingConstants.LEFT -> {
                    x = pt.x - size.width - gap;
                    y = pt.y + ownerSize.height / 2 - arrowXY;
                }
                case SwingConstants.RIGHT -> {
                    x = pt.x + ownerSize.width + gap;
                    y = pt.y + ownerSize.height / 2 - arrowXY;
                }
                default -> throw new IllegalArgumentException("Invalid position: " + callout.position);
            }
            // clamp to the current screen's usable area (i.e., not under the taskbar/dock), so edge-anchored
            // balloons stay fully visible
            final Rectangle screen = usableScreenBounds(callout.owner);
            x = Math.max(screen.x, Math.min(x, screen.x + screen.width - size.width));
            y = Math.max(screen.y, Math.min(y, screen.y + screen.height - size.height));
            popup.setLocation(x, y);

            // re-derive the arrow's target from the box's FINAL (possibly clamped) position, so it keeps pointing
            // at the anchor's center instead of drifting off once the box gets pushed off an edge
            if (balloonBorder != null) {
                final boolean horizontal = callout.position == SwingConstants.TOP
                        || callout.position == SwingConstants.BOTTOM;
                final int ownerCenter = horizontal
                        ? pt.x + ownerSize.width / 2
                        : pt.y + ownerSize.height / 2;
                balloonBorder.setArrowOffset(ownerCenter - (horizontal ? x : y));
                repaint();
            }
        }

        @Override
        void onClosed() {
            activeCallouts.remove(this);
            activeChains.values().remove(this);
            fireStateChanged();
        }

        private void gotIt() {
            close();
            PREFS.putBoolean(callout.prefsKey, true);
            if (onDismiss != null)
                onDismiss.run();
        }

        /**
         * Shows or hides this callout's balloon in place, without disposing it: everything that {@link #close()}
         * would tear down (owner-window listeners, {@link #activeChains}/{@link #activeCallouts} membership,
         * the chain's position) is left exactly as-is, so setting this back to {@code false} later resumes
         * exactly where it was paused
         */
        private void setPaused(final boolean paused) {
            this.paused = paused;
            if (popup != null)
                popup.setVisible(!paused);
            fireStateChanged();
        }

        private boolean isPaused() {
            return paused;
        }

        private void build() {
            messageLabel = new JLabel();
            final JButton gotItButton = new JButton("Got It!");
            gotItButton.setFocusable(false);
            gotItButton.addActionListener(e -> gotIt());
            gotItButton.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
            final JPanel footer = new JPanel(new BorderLayout());
            footer.setOpaque(false);
            if (total > 1) {
                stepLabel = new JLabel(step + "/" + total);
                stepLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
                stepLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
                footer.add(stepLabel, BorderLayout.WEST);
            }
            footer.add(gotItButton, BorderLayout.EAST);
            setLayout(new GridBagLayout());
            final GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = 0;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(8, 8, 4, 8);
            add(messageLabel, c);
            c.gridy = 1;
            c.insets = new Insets(4, 8, 8, 8);
            add(footer, c);
        }
    }

    /**
     * A single, standalone balloon shown via {@link #showTip(Component, String, int, int)}. Deliberately does
     * not extend or share any state with {@link CalloutPanel} -- beyond {@link Balloon}'s generic window
     * plumbing and the same static positioning helpers ({@link #windowOf}, {@link #anchorBounds},
     * {@link #usableScreenBounds}, etc.) -- so it belongs to no chain, is never registered, and its dismissal is
     * never persisted to {@link #PREFS}. Keeping the two classes structurally independent (rather than, say, a
     * shared "chain vs. tip" flag on one class) is what makes it impossible for a tip to be pulled into -- or
     * clobber the {@link #activeChains} state of -- an actual onboarding chain
     */
    private static class TipPanel extends Balloon {

        // Distinct from CalloutPanel's FLATLAF_HINT_* colors: a tip is not an onboarding-chain step, so it gets
        // its own palette rather than reusing the chain's yellow. Derived from a preferred mint/seafoam hue
        // (~154 deg, as in #a0e3c6) by matching FLATLAF_HINT_LIGHT/DARK's exact saturation (100%) and lightness
        // (95.7% / 15.7%, i.e. the same "L minus 80 points" dark transform) instead of diluting that hue toward
        // white, so both tints sit at the same visual "weight" as the callout's yellow, just a different hue
        private static final Color FLATLAF_TIP_LIGHT = new Color(0xe9fff5);
        private static final Color FLATLAF_TIP_DARK = new Color(0x00502d);

        // a tip wraps onto multiple lines instead of growing into a single, very wide one beyond this width;
        // left unconstrained below it, so a short tip stays compact (like an actual tooltip) rather than being
        // padded out to a fixed box
        private static final int MAX_WIDTH = 300;
        private final int position;
        private final int autoDismissMs;
        private JLabel messageLabel;
        private Timer dismissTimer;

        private TipPanel(final Component owner, final String message, final int position, final int autoDismissMs) {
            super(owner);
            this.position = position;
            this.autoDismissMs = autoDismissMs;
            build();
            // no direction/arrow: a tip's content is typically unrelated to owner (owner is merely where it
            // happens to surface, e.g., the button that was clicked), so pointing an arrow at it would
            // misleadingly imply otherwise. Generous inner padding (beyond BalloonBorder's own ~PAD-px margin)
            // so this reads as an actual tooltip, not a cramped label
            setBorder(BorderFactory.createCompoundBorder(
                    new BalloonBorder(FlatUIUtils.getUIColor("PopupMenu.borderColor", Color.GRAY)),
                    BorderFactory.createEmptyBorder(8, 10, 8, 10)));
            messageLabel.setText(htmlText(message));
        }

        /**
         * Wraps {@code text} in HTML, capping the rendered width at {@link #MAX_WIDTH} (scaled) once the text's
         * natural (unwrapped) width would exceed it, so a long tip wraps onto multiple lines instead of becoming
         * one very wide one; a short tip is left unconstrained, so it stays compact instead of being padded out
         * to that width. {@code text} is expected to be plain text (tips are loaded from a plain-text resource,
         * not authored as HTML), so it is escaped before being placed in the markup: this is what keeps a tip
         * that happens to contain '&amp;'/'&lt;'/'&gt;' (e.g., "Tracings & Open Next/Previous Image") from being
         * misread as markup -- an unescaped '&lt;' in particular would otherwise be parsed as the start of a tag
         * and swallow the rest of the tip
         */
        private String htmlText(final String text) {
            final int maxWidth = UIScale.scale(MAX_WIDTH);
            final FontMetrics fm = messageLabel.getFontMetrics(messageLabel.getFont());
            // measured on the raw (unescaped) text: that reflects what is actually rendered, since none of the
            // entities introduced by escaping (e.g., "&amp;") are themselves laid out as separate characters
            final int naturalWidth = (fm != null) ? fm.stringWidth(text) : maxWidth + 1;
            final String escaped = GuiUtils.Text.escapeHtml(text);
            return (naturalWidth <= maxWidth)
                    ? "<html>" + escaped + "</html>"
                    : "<html><div style='width:" + maxWidth + "px'>" + escaped + "</div></html>";
        }

        @Override
        public void updateUI() {
            super.updateUI();
            setBackground(tipBackground());
            repackAndReposition();
        }

        private static Color tipBackground() {
            final boolean isFlatLaf = UIManager.getLookAndFeel() instanceof FlatLaf;
            return Stream.of(
                            UIManager.getColor("Tip.background"), // lets a custom LAF/theme override just this class
                            isFlatLaf ? (FlatLaf.isLafDark() ? FLATLAF_TIP_DARK : FLATLAF_TIP_LIGHT) : null,
                            UIManager.getColor("Popup.background"),
                            UIManager.getColor("Panel.background")
                    )
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(Color.WHITE);
        }

        @Override
        void reposition() {
            if (popup == null || !owner.isShowing()) return;
            final Rectangle anchor = anchorBounds(owner);
            final Point pt = anchor.getLocation();
            final Dimension ownerSize = anchor.getSize();
            final Dimension size = popup.getSize();
            final int gap = UIScale.scale(6);
            int x;
            int y;
            switch (position) {
                case SwingConstants.TOP -> {
                    x = pt.x + ownerSize.width / 2 - size.width / 2;
                    y = pt.y - size.height - gap;
                }
                case SwingConstants.BOTTOM -> {
                    x = pt.x + ownerSize.width / 2 - size.width / 2;
                    y = pt.y + ownerSize.height + gap;
                }
                case SwingConstants.LEFT -> {
                    x = pt.x - size.width - gap;
                    y = pt.y + ownerSize.height / 2 - size.height / 2;
                }
                case SwingConstants.RIGHT -> {
                    x = pt.x + ownerSize.width + gap;
                    y = pt.y + ownerSize.height / 2 - size.height / 2;
                }
                default -> throw new IllegalArgumentException("Invalid position: " + position);
            }
            final Rectangle screen = usableScreenBounds(owner);
            x = Math.max(screen.x, Math.min(x, screen.x + screen.width - size.width));
            y = Math.max(screen.y, Math.min(y, screen.y + screen.height - size.height));
            popup.setLocation(x, y);
        }

        @Override
        void onShown() {
            activeTips.add(this);
            if (autoDismissMs > 0) {
                dismissTimer = new Timer(autoDismissMs, e -> close());
                dismissTimer.setRepeats(false);
                dismissTimer.start();
            }
        }

        @Override
        void onClosed() {
            if (dismissTimer != null)
                dismissTimer.stop();
            activeTips.remove(this);
        }

        private void build() {
            messageLabel = new JLabel();
            final JButton closeButton = new JButton(new FlatClearIcon());
            closeButton.setFocusable(false);
            // the LAST of these to be set wins (they all key off the same client property); kept to just the
            // one actually intended, rather than the three that had accumulated here
            closeButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
            closeButton.addActionListener(e -> close());
            setLayout(new BorderLayout(8, 0));
            add(messageLabel, BorderLayout.CENTER);
            add(closeButton, BorderLayout.EAST);
        }
    }

    private static class BalloonBorder extends FlatEmptyBorder {

        private static final int ARC = 8;
        private static final int ARROW_XY = 16;
        private static final int ARROW_SIZE = 8;
        private static final int PAD = 3;

        private final int direction;
        private final Color borderColor;
        // false for TipPanel's plain rounded-box border (see the 1-arg constructor below): no arrow is ever
        // drawn, and direction is then unused beyond satisfying the field's assignment
        private final boolean hasArrow;
        // full (unshifted) component-local cross-axis target for the arrow
        // tip; -1 until CalloutPanel.reposition() has computed a real one
        private int arrowOffset = -1;

        private BalloonBorder(final int direction, final Color borderColor) {
            super(1 + PAD, 1 + PAD, 1 + PAD, 1 + PAD);
            this.direction = direction;
            this.borderColor = borderColor;
            this.hasArrow = true;
            switch (direction) {
                case SwingConstants.LEFT -> left += ARROW_SIZE;
                case SwingConstants.TOP -> top += ARROW_SIZE;
                case SwingConstants.RIGHT -> right += ARROW_SIZE;
                case SwingConstants.BOTTOM -> bottom += ARROW_SIZE;
            }
        }

        /**
         * Plain rounded-box variant, with no arrow: used only by {@link TipPanel}, whose content is typically
         * unrelated to what it is anchored near, so an arrow (which the chain-callout variant above uses to mean
         * "this text describes what I point to") would be misleading
         */
        private BalloonBorder(final Color borderColor) {
            super(1 + PAD, 1 + PAD, 1 + PAD, 1 + PAD);
            this.direction = SwingConstants.BOTTOM; // unused: hasArrow == false
            this.borderColor = borderColor;
            this.hasArrow = false;
        }

        /**
         * Sets where (in full, unshifted component-local coordinates, along the cross axis of {@link #direction}) the
         * arrow tip should point; clamped at paint time to stay on the balloon's straight edge
         */
        void setArrowOffset(final int offset) {
            this.arrowOffset = offset;
        }

        @Override
        public void paintBorder(final Component c, final Graphics g, final int x, final int y, final int width,
                                final int height) {
            final Graphics2D g2 = (Graphics2D) g.create();
            try {
                FlatUIUtils.setRenderingHints(g2);
                g2.translate(x, y);

                final int bxy = UIScale.scale(PAD);
                final int bw = width - UIScale.scale(PAD + PAD);
                final int bh = height - UIScale.scale(PAD + PAD);
                g2.translate(bxy, bxy);

                final Shape shape;
                if (hasArrow) {
                    final int arrowSize = UIScale.scale(ARROW_SIZE);
                    final boolean horizontalAxis = direction == SwingConstants.TOP || direction == SwingConstants.BOTTOM;
                    final int crossLength = horizontalAxis ? bw : bh;
                    final int xy = clampToEdge(
                            (arrowOffset < 0) ? UIScale.scale(ARROW_XY) : (arrowOffset - bxy - arrowSize), crossLength);
                    shape = createBalloonShape(bw, bh, xy);
                } else {
                    final int arc = UIScale.scale(ARC);
                    shape = new RoundRectangle2D.Float(0, 0, bw - 1, bh - 1, arc, arc);
                }

                g2.setColor(c.getBackground());
                g2.fill(shape);
                g2.setColor(borderColor);
                g2.setStroke(new BasicStroke(UIScale.scale(1f)));
                g2.draw(shape);
            } finally {
                g2.dispose();
            }
        }

        private int clampToEdge(final int xy, final int crossLength) {
            final int arc = UIScale.scale(ARC);
            final int awh = UIScale.scale(ARROW_SIZE);
            final int max = Math.max(arc, crossLength - 1 - arc - 2 * awh);
            return Math.max(arc, Math.min(xy, max));
        }

        private Shape createBalloonShape(final int width, final int height, final int xy) {
            final int arc = UIScale.scale(ARC);
            final int awh = UIScale.scale(ARROW_SIZE);

            final Shape rect;
            final Shape arrow;
            switch (direction) {
                case SwingConstants.LEFT -> {
                    rect = new RoundRectangle2D.Float(awh, 0, width - 1 - awh, height - 1, arc, arc);
                    arrow = FlatUIUtils.createPath(awh, xy, 0, xy + awh, awh, xy + awh + awh);
                }
                case SwingConstants.TOP -> {
                    rect = new RoundRectangle2D.Float(0, awh, width - 1, height - 1 - awh, arc, arc);
                    arrow = FlatUIUtils.createPath(xy, awh, xy + awh, 0, xy + awh + awh, awh);
                }
                case SwingConstants.RIGHT -> {
                    rect = new RoundRectangle2D.Float(0, 0, width - 1 - awh, height - 1, arc, arc);
                    final int x = width - 1 - awh;
                    arrow = FlatUIUtils.createPath(x, xy, x + awh, xy + awh, x, xy + awh + awh);
                }
                case SwingConstants.BOTTOM -> {
                    rect = new RoundRectangle2D.Float(0, 0, width - 1, height - 1 - awh, arc, arc);
                    final int y = height - 1 - awh;
                    arrow = FlatUIUtils.createPath(xy, y, xy + awh, y + awh, xy + awh + awh, y);
                }
                default -> throw new IllegalArgumentException("Invalid direction: " + direction);
            }
            final Area area = new Area(rect);
            area.add(new Area(arrow));
            return area;
        }
    }
}
