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

import javax.swing.JMenuItem;

/**
 * Non-editing commands shared by the contextual menus of the tracing canvas and of the Bdv/Bvv viewers: single
 * source of truth for their labels and icons, so that both front-ends expose the same entries. Shortcuts and
 * tooltips are not defined here because they differ between front-ends (e.g., bookmarking is
 * {@code Shift+B} in the canvas and {@code M} in the viewers). Node-editing commands are defined in
 * {@link NodeEditCommand}.
 *
 * @author Tiago Ferreira
 */
public enum ContextMenuCommand {

	GRAB(Labels.GRAB, '\uf076', true, false),
	APPEND(Labels.APPEND, '\uf076', true, true),
	DESELECT_LAST(Labels.DESELECT_LAST, '\uf146', false, false),
	SELECT_NONE(Labels.SELECT_NONE, IconFactory.GLYPH.SQUARE),
	LASSO(Labels.LASSO, '\uf5ee', true, false),
	HIDE(Labels.HIDE, IconFactory.GLYPH.EYE),
	ORIENTATION(Labels.ORIENTATION, IconFactory.GLYPH.NAVIGATE),
	BOOKMARK(Labels.BOOKMARK, '\uf02e', false, false),
	SHOLL(Labels.SHOLL, IconFactory.GLYPH.BULLSEYE),
	BRIGHTEST(Labels.BRIGHTEST, IconFactory.GLYPH.SUN),
	EXTEND(Labels.EXTEND, IconFactory.GLYPH.TAPE),
	FORK(Labels.FORK, '\ue13b', true, false),
	UNDO_SEGMENT(Labels.UNDO_SEGMENT, IconFactory.GLYPH.UNDO),
	FINISH(Labels.FINISH, '\uf11e', true, false),
	PAUSE_TRACING(Labels.PAUSE_TRACING, '\uf04c', true, false),
	PAUSE_SNT(Labels.PAUSE_SNT, IconFactory.GLYPH.PAUSE2);

	/** Hold-key descriptions used by {@link GuiUtils#showKeyTip(sc.fiji.snt.SNTPrefs, String, String)} */
	public static final String HIDE_KEY_TIP = "temporarily hide paths";
	public static final String ORIENTATION_KEY_TIP = "show path orientation (start \u2192 end)";

	/** The labels as compile-time constants, for front-ends that dispatch on them (e.g., in a {@code switch}) */
	public static final class Labels {
		public static final String GRAB = "Grab Nearest Path";
		public static final String APPEND = "Add Nearest Path to Selection";
		public static final String DESELECT_LAST = "Deselect Last Path";
		public static final String SELECT_NONE = "Select None";
		public static final String LASSO = "Lasso Select Paths";
		public static final String HIDE = "Hide Paths (Hold H)";
		public static final String ORIENTATION = "Path Orientation (Hold O)";
		public static final String BOOKMARK = "Bookmark Cursor Position";
		public static final String SHOLL = "Sholl Analysis at Nearest Node";
		public static final String BRIGHTEST = "Click on Brightest Voxel Above/Below Cursor";
		public static final String EXTEND = "Continue Extending Path";
		public static final String FORK = "Fork at Nearest Node";
		public static final String UNDO_SEGMENT = "Undo Last Segment";
		public static final String FINISH = "Finish Path";
		public static final String PAUSE_TRACING = "Pause Tracing";
		public static final String PAUSE_SNT = "Pause SNT";

		private Labels() {}
	}

	private final String label;
	private final IconFactory.GLYPH glyph;
	private final char symbol;
	private final boolean solid;
	private final boolean selectedColor;

	ContextMenuCommand(final String label, final IconFactory.GLYPH glyph) {
		this.label = label;
		this.glyph = glyph;
		this.symbol = ' ';
		this.solid = false;
		this.selectedColor = false;
	}

	ContextMenuCommand(final String label, final char symbol, final boolean solid, final boolean selectedColor) {
		this.label = label;
		this.glyph = null;
		this.symbol = symbol;
		this.solid = solid;
		this.selectedColor = selectedColor;
	}

	/** @return the menu label */
	public String label() {
		return label;
	}

	/**
	 * Assigns the icon of this command to a menu item. Colors are resolved on every call, so that the icon matches
	 * the current look and feel
	 *
	 * @param item the menu item
	 */
	public void applyIcon(final JMenuItem item) {
		if (glyph != null) {
			IconFactory.assignIcon(item, glyph);
		} else {
			IconFactory.assignIcon(item, symbol, solid,
					(selectedColor) ? IconFactory.selectedColor() : IconFactory.defaultColor());
		}
	}
}
