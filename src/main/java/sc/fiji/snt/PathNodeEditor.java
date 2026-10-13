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

package sc.fiji.snt;

import java.awt.Color;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.jgrapht.Graphs;
import org.jgrapht.traverse.DepthFirstIterator;

import ij.measure.Calibration;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.DirectedWeightedSubgraph;
import sc.fiji.snt.analysis.graph.SWCWeightedEdge;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SWCPoint;
import sc.fiji.snt.util.TreeUtils;

/**
 * Viewer-agnostic node-editing operations on a single {@link Path}: the logic behind the 'Edit Mode' commands of
 * {@link InteractiveTracerCanvas} (delete, insert, move, lock, radius, tag, re-root, split, undo), but free of any
 * canvas/ImagePlus dependency so that it can be driven by other front-ends (e.g., Bdv/Bvv).
 * <p>
 * This class lives in {@code sc.fiji.snt} because several of the {@link Path}/{@link PathAndFillManager} members it
 * relies on are package-scoped. It is a front-end helper: it holds no GUI state beyond the (optional) dialogs it is
 * explicitly handed. All coordinates are calibrated (world) coordinates, i.e., the same space as
 * {@link Path#getNode(int)}.
 * </p>
 * <p>
 * Every operation returns a {@link Result}: callers are expected to display {@link Result#message()} (if any) and,
 * when {@link Result#pathsChanged()} is {@code true}, re-sync any rendering of the Path Manager.
 * </p>
 *
 * @author Tiago Ferreira
 */
public final class PathNodeEditor {

	/**
	 * Outcome of an edit operation
	 *
	 * @param ok           whether the operation was performed
	 * @param message      message for the user, or null
	 * @param pathsChanged whether paths were added/removed/replaced in the Path Manager (as opposed to a node of
	 *                     the editing path having merely changed). If true, the editing path is no longer valid
	 *                     and {@link #getPath()} returns null
	 * @param modal        whether the message reports an aborted operation that should not be missed, i.e.,
	 *                     should be displayed in a dialog rather than in a transient notification
	 */
	public record Result(boolean ok, String message, boolean pathsChanged, boolean modal) {
		Result(final boolean ok, final String message, final boolean pathsChanged) {
			this(ok, message, pathsChanged, false);
		}

		static Result ok(final String msg) {
			return new Result(true, msg, false);
		}

		/** A refusal that merely needs a transient notification (e.g., precondition not met) */
		static Result fail(final String msg) {
			return new Result(false, msg, false);
		}

		/** An aborted operation: the message should be displayed in a dialog */
		static Result abort(final String msg) {
			return new Result(false, msg, false, true);
		}

		/**
		 * Routes this outcome to the front-end reaction that applies, in order of precedence: paths changed
		 * (whether or not the operation succeeded), nodes changed, or operation refused/failed with the paths
		 * intact. Keeps that precedence in one place for all front-ends (canvas, Bdv, Bvv)
		 *
		 * @param onPathsChanged called if {@link #pathsChanged()}: the editing path is no longer valid
		 * @param onNodesChanged called if the operation succeeded and the editing path remains valid
		 * @param onRefused      called if the operation failed or was refused and the paths are intact
		 */
		public void dispatch(final Consumer<Result> onPathsChanged, final Consumer<Result> onNodesChanged,
				final Consumer<Result> onRefused) {
			if (pathsChanged) onPathsChanged.accept(this);
			else if (ok) onNodesChanged.accept(this);
			else onRefused.accept(this);
		}
	}

	private final SNT snt;
	private Path path;
	private final Deque<Path> undoStack = new ArrayDeque<>();

	public PathNodeEditor(final SNT snt) {
		if (snt == null) throw new IllegalArgumentException("SNT instance cannot be null");
		this.snt = snt;
	}

	/**
	 * Sets the path to be edited, resetting the active node of the previous path. See {@link #attach(Path)} for a
	 * variant without side effects on the previous path. Package-scoped: the editing path is managed by {@link SNT}
	 *
	 * @param p the path to be edited. null to release the current path
	 */
	void setPath(final Path p) {
		if (path != null) {
			path.setEditableNode(-1);
			path.setEditableNodeLocked(false);
		}
		attach(p);
	}

	/** Sets the path to be edited, discarding the undo history if it differs from the previous one */
	void attach(final Path p) {
		if (p != path) undoStack.clear();
		path = p;
	}

	/** @return the path being edited (see {@link SNT#getEditingPath()}), or null */
	public Path getPath() {
		return path;
	}

	/**
	 * Tags a node of the editing path as 'active', i.e., the target of subsequent operations. Does nothing if the
	 * active node is locked.
	 *
	 * @param index the node index or -1 for none
	 */
	public void setActiveNode(final int index) {
		if (path == null || path.isEditableNodeLocked()) return;
		path.setEditableNode((index < 0 || index >= path.size()) ? -1 : index);
	}

	/** @return the index of the active node, or -1 */
	public int getActiveNode() {
		return (path == null) ? -1 : path.getEditableNodeIndex();
	}

	/** @return whether the active node is locked */
	public boolean isActiveNodeLocked() {
		return path != null && path.isEditableNodeLocked();
	}

	/** @return whether there are edits that can be undone */
	public boolean canUndo() {
		return !undoStack.isEmpty();
	}

	/** @return whether the editing path is in fitted-display mode (structural edits are unsupported) */
	public boolean isFitted() {
		return path != null && path.getUseFitted();
	}

	private Result notReady(final boolean needNode) {
		if (path == null || !snt.getPathAndFillManager().isSelected(path)) return Result.fail("Editing path not selected");
		if (needNode && (path.getEditableNodeIndex() < 0 || path.getEditableNodeIndex() >= path.size()))
			return Result.fail("No node selected");
		return null;
	}

	private Result unsupportedIfFitted(final String label) {
		return isFitted() ? Result.fail(label + " not supported on fitted paths. Un-fit first.") : null;
	}

	/**
	 * Records the current state of the editing path so that the next edit can be undone. Operations of this class
	 * do it themselves. It is only needed by front-ends modifying the path directly, e.g., once for a whole gesture
	 * (a drag) made of many small changes
	 */
	public void pushUndo() {
		if (path == null) return;
		undoStack.push(path.clone());
		if (undoStack.size() > SNTPrefs.MAX_UNDO_STEPS) undoStack.removeLast();
	}

	/** Drops the snapshot of the last {@link #pushUndo()}, for operations that failed without altering the path */
	private void discardLastUndo() {
		if (!undoStack.isEmpty()) undoStack.pop();
	}

	private void notifyPlausibilityMonitor(final int nodeIndex) {
		if (snt.getUI() != null && snt.getUI().getPlausibilityMonitor().isEnabled())
			snt.getUI().getPlausibilityMonitor().onNodeEdited(path, nodeIndex);
	}

	/** Undoes the last node edit */
	public Result undo() {
		if (path == null || undoStack.isEmpty()) return Result.fail("Nothing to undo");
		path.replaceNodes(undoStack.pop());
		// The affected node(s) are unknown: index 0 makes the monitor re-check the fork neighborhood
		notifyPlausibilityMonitor(0);
		return Result.ok("Edit undone");
	}

	/** Toggles the locked state of the active node. A locked node remains active regardless of the cursor */
	public Result toggleLock() {
		Result r = notReady(true);
		if (r != null) return r;
		path.setEditableNodeLocked(!path.isEditableNodeLocked());
		return Result.ok(path.isEditableNodeLocked() ? "Active node locked" : "Active node unlocked");
	}

	/**
	 * Deletes the active node. Single-node/two-node paths cannot be shortened: use {@link #deleteWholePath()}
	 * if {@link #isTooShortToDeleteNode()} is true (after confirming with the user)
	 */
	public Result deleteNode() {
		Result r = notReady(true);
		if (r == null) r = unsupportedIfFitted("Delete node");
		if (r != null) return r;
		if (path.size() <= 2) return Result.fail("Path too short: Delete the entire path?");
		final int idx = path.getEditableNodeIndex();
		final PointInImage node = path.getNode(idx);
		if (path.getBranchPoints().stream().anyMatch(n -> n.equals(node)))
			return Result.abort("Cannot delete junction node. Try to split instead.");
		pushUndo();
		try {
			path.removeNode(idx);
			path.setEditableNodeLocked(false); // the lock belongs to the deleted node, not to its neighbor
			path.setEditableNode(Math.min(idx, path.size() - 1));
			notifyPlausibilityMonitor(idx);
			return Result.ok("Node deleted");
		} catch (final IllegalArgumentException ex) {
			discardLastUndo();
			return Result.abort("Node deletion failed!");
		}
	}

	/**
	 * Deletes the active node or, if the editing path is too short to lose nodes (see
	 * {@link #isTooShortToDeleteNode()}), the entire path after asking for confirmation
	 *
	 * @param gui the GuiUtils instance used to display the confirmation prompt
	 */
	public Result deleteNodeOrPath(final GuiUtils gui) {
		final Result r = notReady(true);
		if (r != null) return r;
		if (isTooShortToDeleteNode() && !isFitted()) {
			if (!gui.getConfirmation("Delete " + path + "?", "Delete Path?")) return Result.fail(null);
			return deleteWholePath();
		}
		return deleteNode();
	}

	/** @return true if the editing path cannot lose further nodes (see {@link #deleteNode()}) */
	public boolean isTooShortToDeleteNode() {
		return path != null && path.size() <= 2;
	}

	/** Deletes the entire editing path from the Path Manager. Callers should confirm with the user first */
	public Result deleteWholePath() {
		if (path == null) return Result.fail("No path");
		final PathAndFillManager pafm = snt.getPathAndFillManager();
		boolean rebuild = false;
		if (path.connectedPaths != null) {
			for (final Path p : path.connectedPaths) {
				if (p.getParentPath() == path) {
					rebuild = true;
					break;
				}
			}
		}
		path.disconnectFromAll(); // fixes ghost connection after deleting last node in a forked path
		pafm.deletePath(path);
		if (rebuild) pafm.rebuildRelationships();
		path = null;
		undoStack.clear();
		releaseSecondaryPath();
		return new Result(true, "Path deleted", true);
	}

	/**
	 * Inserts a node at the specified position, immediately before the active node. The active node remains active.
	 */
	public Result insertNode(final double x, final double y, final double z) {
		Result r = notReady(true);
		if (r == null) r = unsupportedIfFitted("Insert node");
		if (r != null) return r;
		pushUndo();
		final int idx = path.getEditableNodeIndex();
		try {
			path.insertNode(idx, new PointInImage(x, y, z));
			path.setEditableNode(idx + 1);
			notifyPlausibilityMonitor(idx);
			return Result.ok("New node inserted (N=" + idx + ")");
		} catch (final IllegalArgumentException ex) {
			discardLastUndo();
			return Result.abort("Node insertion failed!");
		}
	}

	/** Moves the active node to the specified position */
	public Result moveNode(final double x, final double y, final double z) {
		return moveNode(x, y, z, true);
	}

	/**
	 * Moves the active node to the specified position
	 *
	 * @param recordUndo if false, the move is not recorded in the undo history (see {@link #pushUndo()})
	 */
	public Result moveNode(final double x, final double y, final double z, final boolean recordUndo) {
		final Result r = notReady(true);
		if (r != null) return r;
		if (recordUndo) pushUndo();
		final int idx = path.getEditableNodeIndex();
		try {
			path.moveNode(idx, new PointInImage(x, y, z));
			notifyPlausibilityMonitor(idx);
			return Result.ok("Node moved");
		} catch (final IllegalArgumentException ex) {
			if (recordUndo) discardLastUndo();
			return Result.abort("Node displacement failed!");
		}
	}

	/** Assigns (or removes, if null) a color tag to the active node */
	public Result tagNode(final Color color) {
		Result r = notReady(true);
		if (r == null) r = unsupportedIfFitted("Tag node");
		if (r != null) return r;
		final int idx = path.getEditableNodeIndex();
		pushUndo();
		path.setNodeColor(color, idx);
		if (snt.getUI() != null) {
			snt.getUI().getPathManager().update(true);
			final BookmarkManager bm = snt.getUI().getBookmarkManager();
			if (color == null) bm.remove(path, idx);
			else bm.add(path, idx);
		}
		return Result.ok((color == null) ? String.format("Tag removed from node #%d", idx) : null);
	}

	/**
	 * As {@link #tagNode(Color)}, for a node that was the active one when a color chooser was opened: the active
	 * node follows the cursor while a chooser is open, so it is restored first
	 *
	 * @param target the editing path the chooser was opened for. Nothing is done if it is no longer the editing path
	 * @param index  the index of the node the chooser was opened for
	 * @param color  the color, or null to remove the tag
	 */
	public Result tagNode(final Path target, final int index, final Color color) {
		if (target != path) return Result.fail(null);
		setActiveNode(index);
		return tagNode(color);
	}

	/**
	 * Prompts for, and assigns, a radius to the active node (mirrors the dialog of the classic canvas)
	 *
	 * @param gui the GuiUtils instance used to display dialogs
	 */
	public Result promptAndSetRadius(final GuiUtils gui) {
		final Result nr = notReady(true);
		if (nr != null) return nr;
		final Path edPath = path;
		final int edNode = edPath.getEditableNodeIndex();
		final boolean hasRadii = edPath.hasRadii();
		final String[] defChoices = new String[(hasRadii) ? 5 : 3];
		defChoices[0] = "<HTML>Assign <b>average of flanking nodes</b> (if any)";
		defChoices[1] = "<HTML>Assign half of <b>minimum voxel separation";
		defChoices[2] = "<HTML>Assign <b>value</b> specified below:";
		if (hasRadii) {
			defChoices[3] = "<HTML>Multiply existing radius by <b>multiplier</b> specified below:";
			defChoices[4] = "<HTML>Assign <b>mean path radius</b>";
		}
		String msg = "<HTML>Which value should be set as radius for node #" + edNode + "?<br>";
		if (hasRadii) msg += String.format("(Current radius is %.2f)", edPath.getNodeRadius(edNode));
		else msg += "NB: No nodes in <i>" + edPath.getName() + "</i> have assigned radii!";

		final String prevChoice = snt.getPrefs().getTemp("lastRadChoice", defChoices[0]);
		final String prevDouble = snt.getPrefs().getTemp("lastRadDouble", null);
		double defValue = edPath.getNodeRadius(edNode);
		if (prevDouble != null) {
			try {
				defValue = Double.parseDouble(prevDouble);
			} catch (final NumberFormatException ignored) {
				// corrupted preference: fall back to the radius of the active node
			}
		}
		final Object[] usrChoice = gui.getChoiceAndDouble(msg, edPath.getName() + ": Ad hoc Radius", defChoices,
				prevChoice, defValue);
		if (usrChoice == null) return Result.fail(null);

		double r = Double.NaN;
		if (defChoices[0].equals(usrChoice[0])) {
			if (edPath.size() == 1) return Result.abort("Path has only one node. No flanking nodes exist.");
			final int n1 = Math.max(0, edNode - 1);
			final int n2 = Math.min(edPath.size() - 1, edNode + 1);
			r = (edPath.getNodeRadius(n1) + edPath.getNodeRadius(n2)) / 2;
		} else if (defChoices[1].equals(usrChoice[0])) {
			r = snt.getMinimumSeparation() / 2;
		} else if (defChoices[2].equals(usrChoice[0])) {
			r = (double) usrChoice[1];
			snt.getPrefs().setTemp("lastRadDouble", Double.toString((double) usrChoice[1]));
		} else if (hasRadii && defChoices[3].equals(usrChoice[0])) {
			r = edPath.getNodeRadius(edNode) * (double) usrChoice[1];
			snt.getPrefs().setTemp("lastRadDouble", Double.toString((double) usrChoice[1]));
		} else if (hasRadii && defChoices[4].equals(usrChoice[0])) {
			r = edPath.getMeanRadius();
		}
		snt.getPrefs().setTemp("lastRadChoice", (String) usrChoice[0]);
		if (Double.isNaN(r) || r < 0d) return Result.abort("Invalid radius. Must be a non-negative, floating-point value.");
		pushUndo();
		edPath.setRadius(r, edNode);
		return Result.ok(String.format("Radius set to %.2f", r));
	}

	/**
	 * Makes the active node the root of its tree. Asks for confirmation (the operation cannot be undone). On
	 * success the editing path is released: Callers must re-sync and leave edit mode.
	 */
	public Result setActiveNodeAsRoot(final GuiUtils gui) {
		Result r = notReady(true);
		if (r == null) r = unsupportedIfFitted("Set tree root");
		if (r != null) return r;
		if (!gui.getConfirmation("Set active node as tree root? This operation cannot be undone.", "Confirm Re-root"))
			return Result.fail(null);
		final PathAndFillManager pafm = snt.getPathAndFillManager();
		final Path editingPath = path;
		try {
			final Tree editingTree = TreeUtils.getConnectedTree(editingPath);
			final boolean existingEnableUiUpdates = pafm.enableUIupdates;
			pafm.enableUIupdates = false;
			try {
				final PointInImage editingNode = editingPath.getNode(editingPath.getEditableNodeIndex());
				final DirectedWeightedGraph editingGraph = new DirectedWeightedGraph(editingTree, false);
				SWCPoint newRoot = getMatchingPointInGraph(editingNode, editingGraph);
				if (newRoot == null) {
					final SWCPoint nearest = getNearestPointInGraph(editingNode, editingGraph);
					if (nearest == null) return Result.abort("Could not find a valid node for the new root.");
					final int uniqueId = editingGraph.vertexSet().stream().mapToInt(v -> v.id).max().orElse(-2) + 1;
					newRoot = new SWCPoint(uniqueId, nearest.type, editingNode.x, editingNode.y, editingNode.z,
							editingPath.getNodeRadius(editingPath.getEditableNodeIndex()), nearest.id);
					newRoot.setPath(editingPath);
					editingGraph.addVertex(newRoot);
					editingGraph.addEdge(nearest, newRoot);
					if (editingPath.size() == 1) pafm.deletePath(editingPath);
					else editingPath.removeNode(editingPath.getEditableNodeIndex());
				}
				editingGraph.setRoot(newRoot);
				replaceTree(editingTree, editingGraph.getTreeWithSamePathStructure());
			} finally {
				pafm.enableUIupdates = existingEnableUiUpdates;
			}
			return structuralChange("Tree re-rooted");
		} catch (final IllegalArgumentException e) {
			// If the failure occurred after the editing path was released, callers must still re-sync
			if (path == null) releaseSecondaryPath();
			return new Result(false, "<HTML>Could not re-root tree:<br><i>" + e.getMessage() + "</i>", path == null, true);
		}
	}

	/**
	 * Splits the tree at the active node. Asks for confirmation (the operation cannot be undone). On success the
	 * editing path is released: Callers must re-sync and leave edit mode.
	 */
	public Result splitTreeAtActiveNode(final GuiUtils gui) {
		Result r = notReady(true);
		if (r == null) r = unsupportedIfFitted("Split tree");
		if (r != null) return r;
		final Path editingPath = path;
		try {
			final Tree editingTree = TreeUtils.getConnectedTree(editingPath);
			if (!gui.getConfirmation("Split tree at active node? This operation cannot be undone.", "Confirm Split"))
				return Result.fail(null);
			final PointInImage editingPoint = editingPath.getNode(editingPath.getEditableNodeIndex());
			if (editingTree.getRoot().equals(editingPoint)) return Result.abort("Cannot split tree at root node.");

			final DirectedWeightedGraph editingGraph = new DirectedWeightedGraph(editingTree, false);
			final SWCPoint editingVertex = getMatchingPointInGraph(editingPoint, editingGraph);
			if (editingVertex == null) return Result.abort("Could not locate the selected node in the tree graph.");
			final Set<SWCWeightedEdge> incomingEdges = editingGraph.incomingEdgesOf(editingVertex);
			if (incomingEdges.isEmpty()) return Result.abort("Selected node has no incoming edges. Cannot split here.");

			editingGraph.removeEdge(incomingEdges.iterator().next());
			final DepthFirstIterator<SWCPoint, SWCWeightedEdge> it = editingGraph.getDepthFirstIterator(editingVertex);
			final Set<SWCPoint> descendantVertexSet = new HashSet<>();
			while (it.hasNext())
				descendantVertexSet.add(it.next());
			final DirectedWeightedSubgraph descendantSubgraph = editingGraph.getSubgraph(descendantVertexSet);
			final DirectedWeightedGraph descendantGraph = new DirectedWeightedGraph();
			Graphs.addGraph(descendantGraph, descendantSubgraph);
			editingGraph.removeAllVertices(descendantVertexSet);

			final Tree ancestorTree = editingGraph.getTreeWithSamePathStructure();
			final Tree descendantTree = descendantGraph.getTreeWithSamePathStructure();
			replaceTree(editingTree, ancestorTree, descendantTree);
			return structuralChange("Tree split");
		} catch (final IllegalArgumentException e) {
			// If the failure occurred after the editing path was released, callers must still re-sync
			if (path == null) releaseSecondaryPath();
			return new Result(false, "<HTML>Could not split tree:<br><i>" + e.getMessage() + "</i>", path == null, true);
		}
	}

	private static void runWithoutUiUpdates(final PathAndFillManager pafm, final Runnable task) {
		final boolean existing = pafm.enableUIupdates;
		pafm.enableUIupdates = false;
		try {
			task.run();
		} finally {
			pafm.enableUIupdates = existing;
		}
	}

	/** Releases the editing path and replaces the paths of a tree by the paths of the trees that supersede it */
	private void replaceTree(final Tree old, final Tree... added) {
		final PathAndFillManager pafm = snt.getPathAndFillManager();
		setPath(null);
		final Calibration cal = snt.getCalibration();
		runWithoutUiUpdates(pafm, () -> {
			pafm.deletePaths(old.list());
			for (final Tree t : added) {
				for (final Path p : t.list()) {
					p.setSpacing(cal);
					pafm.addPath(p, false, true);
				}
			}
		});
	}

	/** Bookkeeping common to operations that replaced paths, which cannot be undone */
	private Result structuralChange(final String msg) {
		undoStack.clear();
		releaseSecondaryPath();
		return new Result(true, msg, true);
	}

	/**
	 * Forgets the 'secondary' path. To be called whenever paths were deleted/replaced, since the secondary path may
	 * no longer be in the Path Manager. Must be called after the editing path was released
	 */
	private void releaseSecondaryPath() {
		snt.setEditingPath(null); // previous path := current (null)
	}

	/** @return the 'secondary' path, i.e., the path that was being edited before the current one, or null */
	public Path getSecondaryPath() {
		return snt.getPreviousEditingPath();
	}

	/**
	 * @return whether a secondary path with an active node exists, i.e., whether
	 *         {@link #connectToSecondaryPath(GuiUtils, boolean)} may be applicable
	 */
	public boolean hasSecondaryNode() {
		final Path secondary = snt.getPreviousEditingPath();
		return path != null && secondary != null && secondary != path && secondary.getEditableNodeIndex() != -1;
	}

	/**
	 * Connects the active node of the editing path to the active node of the secondary path (the one edited
	 * previously). By default the editing path becomes the child (branching from the secondary path), but a
	 * heuristic and the user may swap roles. Asks for confirmation (the operation cannot be undone). On success the
	 * editing path is released: Callers must re-sync and leave edit mode.
	 *
	 * @param gui        used to display prompts and errors
	 * @param showDialog whether the user should be prompted to confirm (and possibly swap) the parent and child
	 * @return the outcome. A failure with no message means the operation was canceled or reported to the user
	 */
	public Result connectToSecondaryPath(final GuiUtils gui, final boolean showDialog) {
		final Path secondary = snt.getPreviousEditingPath();
		if (path == null || path.getEditableNodeIndex() == -1 || secondary == null
				|| secondary.getEditableNodeIndex() == -1) {
			gui.error("Connect to Secondary Editing Path: No connectable node exist.", "No Connectable Nodes");
			return Result.fail(null);
		}
		// Default assignment: current path = child, previous path = parent
		Path childPath = path;
		Path parentPath = secondary;
		if (wouldCreateLoop(childPath, parentPath)) {
			gui.error("Cannot connect selected nodes: A loop would be created!",
					"Reconstruction Cannot Contain Loops");
			return Result.fail(null);
		}
		final int childNodeIdx = childPath.getEditableNodeIndex();
		final int parentNodeIdx = parentPath.getEditableNodeIndex();

		// Auto-swap heuristics
		final boolean shouldSwap;
		final int childMaxOrder = TreeUtils.getMaxOrder(childPath);
		final int parentMaxOrder = TreeUtils.getMaxOrder(parentPath);
		if (childMaxOrder != parentMaxOrder) {
			// Deeper tree (higher max order) is likely the main structure
			shouldSwap = childMaxOrder > parentMaxOrder;
		} else {
			// Same depth: larger path is likely the parent
			shouldSwap = childPath.size() > parentPath.size();
		}

		if (showDialog) {
			final boolean childAtEndpoint = childNodeIdx == 0 || childNodeIdx == childPath.size() - 1;
			final StringBuilder msg = new StringBuilder("<HTML>");
			msg.append("<b>Connection Summary:</b><br>");
			msg.append("<b>Parent:</b> ").append(StringUtils.abbreviate(parentPath.getName(), 30));
			msg.append(" (branch at node #").append(parentNodeIdx).append(")<br>");
			msg.append("<b>Child:</b> ").append(StringUtils.abbreviate(childPath.getName(), 30));
			msg.append(" (connect from node #").append(childNodeIdx).append(")<br>");
			msg.append("<b>Note:</b><br>");
			if (!childAtEndpoint && childPath.size() > 2) {
				msg.append("&nbsp;&nbsp;- Child connects from a mid-path node<br>");
			}
			msg.append("&nbsp;&nbsp;- This operation cannot be undone<br><br>");
			final boolean[] result = gui.getConfirmationAndOption(msg.toString(), "Connect Paths",
					"Swap parent \u2194 child", shouldSwap, new String[] { "Connect", "Cancel" });
			if (result == null || !result[0]) return Result.fail(null); // User canceled
			if (result[1]) { // Swap if requested
				final Path temp = childPath;
				childPath = parentPath;
				parentPath = temp;
				if (wouldCreateLoop(childPath, parentPath)) {
					gui.error("Cannot connect with swapped parent \u2194 child: A loop would be created!",
							"Reconstruction Cannot Contain Loops");
					return Result.fail(null);
				}
			}
		}
		return connectAndRelease(childPath, parentPath, gui);
	}

	/**
	 * Connects the active node of the editing path to a node of another path (e.g., the node snapped to by a
	 * 'magnetic' cursor). The editing path is always the child, i.e., it branches from {@code parent}. Unlike
	 * {@link #connectToSecondaryPath(GuiUtils, boolean)}, no confirmation is asked. On success the editing path
	 * is released: Callers must re-sync and leave edit mode.
	 *
	 * @param parent  the path to branch from
	 * @param nodeIdx the index of the node of {@code parent} to branch from
	 * @param gui     used to display errors
	 * @return the outcome. A failure with no message means the operation was reported to the user
	 * @see #canConnectTo(Path)
	 */
	public Result connectToNode(final Path parent, final int nodeIdx, final GuiUtils gui) {
		final Result r = notReady(true);
		if (r != null) return r;
		if (parent == null || nodeIdx < 0 || nodeIdx >= parent.size()) return Result.fail("No node to connect to");
		if (!canConnectTo(parent)) return Result.abort("Cannot connect to " + parent.getName()
				+ ": A loop would be created!");
		parent.setEditableNode(nodeIdx);
		return connectAndRelease(path, parent, gui);
	}

	/**
	 * Resolves a node of a displayed path to the node that connections must refer to. The paths displayed in the
	 * viewers are the versions in use: A path displayed as its fitted version is a copy that does not hold the
	 * topology, so it is resolved to its un-fitted original (see {@link PathAndFillManager#getVersionInUse(Path)})
	 *
	 * @param displayed the displayed path
	 * @param index     the index of a node of {@code displayed}
	 * @return the equivalent node of the path holding the topology. It is the same node if {@code displayed} is not
	 *         a fitted copy
	 */
	public PathAndFillManager.NodeRef resolveNode(final Path displayed, final int index) {
		final Path original = resolvePath(displayed);
		if (original == displayed) return new PathAndFillManager.NodeRef(displayed, index);
		if (original.size() == displayed.size()) return new PathAndFillManager.NodeRef(original, index);
		final PointInImage n = displayed.getNode(index);
		return new PathAndFillManager.NodeRef(original, original.indexNearestTo(n.x, n.y, n.z, Double.MAX_VALUE));
	}

	/**
	 * @param displayed the displayed path
	 * @return the path holding the topology: the un-fitted original if {@code displayed} is a fitted copy,
	 *         otherwise {@code displayed}
	 */
	public Path resolvePath(final Path displayed) {
		if (!displayed.isFittedVersionOfAnotherPath() || snt.getPathAndFillManager().isDeFactoPath(displayed))
			return displayed;
		return displayed.getUnfitted();
	}

	/**
	 * @param other the path to be tested
	 * @return whether the editing path could branch from {@code other} without creating a loop
	 */
	public boolean canConnectTo(final Path other) {
		return path != null && other != null && !wouldCreateLoop(path, other);
	}

	private Result connectAndRelease(final Path child, final Path parent, final GuiUtils gui) {
		final Result r = connectToEditingPath(child, parent, gui);
		if (r.pathsChanged()) {
			setPath(null);
			releaseSecondaryPath();
			if (snt.getUI() != null) snt.getUI().getPathManager().setPathList(null, false);
		}
		return r;
	}

    /**
     * Checks if connecting child to parent would create a loop.
     */
    private boolean wouldCreateLoop(final Path child, final Path parent) {
        if (child == parent) {
            return true;
        }
        if (child.getChildren().isEmpty()) {
            return false;
        }
        return TreeUtils.isInSubtree(parent, child);
    }

    /**
     * Connects child path to parent path, creating physical connection segment
     * and proper T-junction topology when needed.
     * <p>
     * If the child's selected node is a slab (middle) node, the child path is split
     * at that point to create proper branching topology.
     *
     * @param child  The path to be connected as a child branch
     * @param parent The path where the child will branch from
     */
    private Result connectToEditingPath(final Path child, final Path parent, final GuiUtils gui) {
        final PathAndFillManager pafm = snt.getPathAndFillManager();
        try {
            final long start = System.currentTimeMillis();

            final int childNodeIdx = child.getEditableNodeIndex();
            final int parentNodeIdx = parent.getEditableNodeIndex();
            final PointInImage parentPoint = parent.getNode(parentNodeIdx);

            // Check if child's selected node is a slab (middle) node
            final boolean childAtStart = childNodeIdx == 0;
            final boolean childAtEnd = childNodeIdx == child.size() - 1;
            final boolean childAtSlab = !childAtStart && !childAtEnd && child.size() > 2;

            final String childName = child.getName();
            final boolean[] fitKept = { true };
            runWithoutUiUpdates(pafm, () -> {
                if (childAtSlab) {
                    // T-JUNCTION: Split child path and handle directly without graph transformation
                    fitKept[0] = connectWithTJunction(child, parent, childNodeIdx, parentNodeIdx, parentPoint);
                } else {
                    // ENDPOINT CONNECTION: Use direct path manipulation
                    fitKept[0] = connectAtEndpoint(child, parent, childAtStart, parentNodeIdx, parentPoint);
                }
            });
            SNTUtils.log("Finished connection in " + (System.currentTimeMillis() - start) + "ms");
            return new Result(true, fitKept[0] ? "Paths connected"
                    : "Paths connected. Fit of " + StringUtils.abbreviate(childName, 30) + " discarded", true);

        } catch (final IllegalArgumentException e) {
            gui.error("<HTML>Cannot connect these paths:<br><br><i>" + e.getMessage() + "</i>", "Connection Failed");
            return new Result(false, null, true); // topology may have been partially altered: callers must re-sync
        }
    }

    /**
     * Recursively updates the tree ID of a path and all its descendants.
     */
    private void updateTreeIDRecursively(final Path root, final int treeID) {
        root.setIDs(root.getID(), treeID);
        for (final Path child : root.getChildren()) {
            updateTreeIDRecursively(child, treeID);
        }
    }

    /**
     * Re-links the fitted counterparts of a path and its descendants to the (new) topology, and syncs their IDs
     */
    private void refreshFittedCounterparts(final Path root) {
        if (root.isFittedVersionOfAnotherPath()) return; // fitted copies are also listed as children
        final Path fit = root.getFitted();
        if (fit != null) {
            root.rebuildConnectionsOfFittedVersion();
            fit.setIDs(-root.getID(), root.getTreeID());
        }
        for (final Path child : new ArrayList<>(root.getChildren())) refreshFittedCounterparts(child);
    }

    /**
     * Connects child path to parent at an endpoint (start or end of child). The fitted version of the child (if
     * any) undergoes the same changes, so that the fit is preserved
     *
     * @return true if the fit of the child (if any) was preserved
     */
    private boolean connectAtEndpoint(final Path child, final Path parent,
                                   final boolean childAtStart,
                                   final int parentNodeIdx, final PointInImage parentPoint) {
        final PathAndFillManager pafm = snt.getPathAndFillManager();

        // Sync canvas offset before any operations
        TreeUtils.syncCanvasOffset(child, parent);

        final Path childFit = child.getFitted();
        final boolean useFit = child.getUseFitted();

        // Check if we need to reverse the child to connect from its start
        Path childToConnect = child;
        if (!childAtStart) {
            // Child connects from end - reverse it so it connects from start
            childToConnect = child.reversed();
            if (childFit != null) {
                childToConnect.setFitted(childFit.reversed());
                childToConnect.setUseFitted(useFit);
            }

            // Transfer children with updated branch points
            for (final Path grandchild : new ArrayList<>(child.getChildren())) {
                final int oldBranchIdx = grandchild.getBranchPointIndex();
                final int newBranchIdx = child.size() - 1 - oldBranchIdx;
                grandchild.detachFromParent();
                grandchild.setBranchFrom(childToConnect, childToConnect.getNode(newBranchIdx));
            }

            // Remove original and add reversed
            pafm.deletePath(child);
            pafm.addPath(childToConnect, true, true);
        }

        // Check if we need a bridging node
        final double distance = childToConnect.firstNode().distanceTo(parentPoint);
        final double threshold = snt.getMinimumSeparation() * 2;
        if (distance > threshold) {
            // Insert bridging node at start of child
            final PointInImage bridgePoint = new PointInImage(parentPoint.x, parentPoint.y, parentPoint.z);
            childToConnect.insertNode(0, bridgePoint);
            if (childToConnect.getFitted() != null) {
                childToConnect.getFitted().insertNode(0, new PointInImage(bridgePoint.x, bridgePoint.y, bridgePoint.z));
            }
        }

        // Connect child to parent. A child that already branches from another path is re-attached
        // (setBranchFrom does not replace existing connections)
        if (childToConnect.getParentPath() != null) childToConnect.detachFromParent();
        childToConnect.setBranchFrom(parent, parent.getNode(parentNodeIdx));
        // Recursively update tree ID for child and all descendants
        updateTreeIDRecursively(childToConnect, parent.getTreeID());
        refreshFittedCounterparts(childToConnect);
        return true;
    }

    /**
     * Connects child path to parent by splitting child at a mid-path node (T-junction). The fitted version of the
     * child (if any) is split accordingly, so that the fit is preserved
     *
     * @return true if the fit of the child (if any) was preserved. Fits that cannot be mirrored (a different number
     *         of nodes than the child) are discarded
     */
    private boolean connectWithTJunction(final Path child, final Path parent,
                                      final int childNodeIdx, final int parentNodeIdx,
                                      final PointInImage parentPoint) {
        final PathAndFillManager pafm = snt.getPathAndFillManager();

        // Sync canvas offset before any operations
        TreeUtils.syncCanvasOffset(child, parent);

        final Path childFit = child.getFitted();
        final boolean useFit = child.getUseFitted();
        final boolean mirrorFit = childFit != null && childFit.size() == child.size();

        // Store children info before we modify anything
        final List<Path> grandchildren = new ArrayList<>(child.getChildren());
        final Map<Path, Integer> grandchildBranchIndices = new HashMap<>();
        for (final Path gc : grandchildren) {
            grandchildBranchIndices.put(gc, gc.getBranchPointIndex());
            gc.detachFromParent();
        }

        // Create segment 1: nodes [0, childNodeIdx]
        final Path segment1 = child.createPath();
        for (int i = 0; i <= childNodeIdx; i++) {
            segment1.addNode(child.getNode(i));
        }
        // Create segment 2: nodes [childNodeIdx, end] - includes junction node for continuity
        Path segment2 = null;
        if (childNodeIdx < child.size() - 1) {
            segment2 = child.createPath();
            for (int i = childNodeIdx; i < child.size(); i++) {
                segment2.addNode(child.getNode(i));
            }
        }

        if (mirrorFit) {
            segment1.setFitted(childFit.getSection(0, childNodeIdx));
            segment1.setUseFitted(useFit);
            if (segment2 != null) {
                segment2.setFitted(childFit.getSection(childNodeIdx, child.size() - 1));
                segment2.setUseFitted(useFit);
            }
        }

        // Check if we need a bridging node for the connection to parent
        final PointInImage junctionPoint = segment1.lastNode();
        final double distance = junctionPoint.distanceTo(parentPoint);
        final double threshold = snt.getMinimumSeparation() * 2;
        if (distance > threshold) {
            // Add bridging node at the END of segment1 (after junction point)
            // This extends segment1 toward the parent
            final PointInImage bridgePoint = new PointInImage(parentPoint.x, parentPoint.y, parentPoint.z);
            segment1.addNode(bridgePoint);
            if (segment1.getFitted() != null) {
                segment1.getFitted().addNode(new PointInImage(bridgePoint.x, bridgePoint.y, bridgePoint.z));
            }
        }

        // Delete original child path
        pafm.deletePath(child);

        // Add segment1 and connect it to parent
        pafm.addPath(segment1, true, true);
        segment1.setBranchFrom(parent, parent.getNode(parentNodeIdx));
        // Recursively update tree ID for segment1 and its subtree
        updateTreeIDRecursively(segment1, parent.getTreeID());

        // Add segment2 and connect it to segment1 at the junction (before the bridge if present)
        if (segment2 != null) {
            pafm.addPath(segment2, true, true);
            // segment2 branches from segment1 at the junction point
            // The junction is at index childNodeIdx in segment1 (which has nodes 0..childNodeIdx, possibly + bridge)
            segment2.setBranchFrom(segment1, segment1.getNode(childNodeIdx));
            // Recursively update tree ID for segment2 and its subtree
            updateTreeIDRecursively(segment2, parent.getTreeID());
        }

        // Reassign grandchildren to appropriate segment
        for (final Path gc : grandchildren) {
            final int origBranchIdx = grandchildBranchIndices.get(gc);

            if (origBranchIdx <= childNodeIdx) {
                // Grandchild branches from segment1 portion
                // Index in segment1 is same as original
                gc.setBranchFrom(segment1, segment1.getNode(origBranchIdx));
            } else if (segment2 != null) {
                // Grandchild branches from segment2 portion
                // Index in segment2: origBranchIdx - childNodeIdx (since segment2 starts at childNodeIdx)
                final int newIdx = origBranchIdx - childNodeIdx;
                gc.setBranchFrom(segment2, segment2.getNode(newIdx));
            }
            // Tree ID already propagated recursively above; no need to update grandchildren here
        }
        refreshFittedCounterparts(segment1);
        return childFit == null || mirrorFit;
    }


	private static SWCPoint getNearestPointInGraph(final PointInImage point, final DirectedWeightedGraph graph) {
		double best = Double.MAX_VALUE;
		SWCPoint nearest = null;
		for (final SWCPoint p : graph.vertexSet()) {
			final double d2 = point.distanceSquaredTo(p.x, p.y, p.z);
			if (d2 < best) {
				nearest = p;
				best = d2;
			}
		}
		return nearest;
	}

	private static SWCPoint getMatchingPointInGraph(final PointInImage point, final DirectedWeightedGraph graph) {
		for (final SWCPoint p : graph.vertexSet()) {
			if (p.isSameLocation(point) && p.getPath().equals(point.getPath())) return p;
		}
		return null;
	}
}
