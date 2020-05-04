/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2019 Fiji developers.
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

import java.awt.Color;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.UIManager;

/**
 * A factory for {@link FADerivedIcon}s presets.
 *
 * @author Tiago Ferreira
 */
public class IconFactory {

	private static Color DEFAULT_COLOR = UIManager.getColor("Button.foreground");
	private static Color INACTIVE_COLOR = UIManager.getColor("Button.disabledText");
	private static Color PRESSED_COLOR = UIManager.getColor("Button.highlight");

	static {
		if (DEFAULT_COLOR == null) DEFAULT_COLOR = new Color(60, 60, 60);
		if (INACTIVE_COLOR == null) INACTIVE_COLOR = new Color(120, 120, 120);
		if (PRESSED_COLOR == null) PRESSED_COLOR = new Color(180, 180, 180);
	}

	public enum GLYPH {
			ARCHIVE('\uf1c6', false), //
			ADJUST('\uf042', true), //
			ATLAS('\uf558', true), //
			//ATOM('\uf5d2', true), //
			BINOCULARS('\uf1e5', true), //
			BOOK_READER('\uf5da', true), //
			BRAIN('\uf5dc', true), //
			BRANCH_CODE('\uf126', true), //
			BROOM('\uf51a', true), //
			BUG('\uf188', true), //
			BULB('\uf0eb', true), //
			BULLSEYE('\uf140', true), //
			CAMERA('\uf030', true), //
			CALCULATOR('\uf1ec', true), //
			CHART('\uf080', false), //
			CHECK_DOUBLE('\uf560', true), //
			CIRCLE('\uf192', false), //
			CLOUD('\uf381', true), //
			CODE('\uf120', true), //
			CODE2('\uf121', true), //
			COG('\uf013', true), //
			COGS('\uf085', true), //
			COLOR('\uf53f', true), //
			COLOR2('\uf5c3', true), //
			COMMENTS('\uf086', false), //
			CROSSHAIR('\uf05b', true), //
			CSV('\uf6dd', true), //
			CUBE('\uf1b2', true), //
			DANGER('\uf071', true), //
			DATABASE('\uf1c0', true), //
			DELETE('\uf55a', true), //
			DIAGRAM('\uf542', true), //
			DOTCIRCLE('\uf192', true), //
			//DOWNLOAD('\uf019', true), //
			DRAFT('\uf568', true), //
			EQUALS('\uf52c', true), //
			EXPAND('\uf065', true), //
			EXPAND_ARROWS1('\uf337', true), //
			EXPAND_ARROWS2('\uf31e', true), //
			EXPLORE('\uf610', true), //
			EXPORT('\uf56e', true), //
			EYE('\uf06e', false), //
			EYE_SLASH('\uf070', false), //
			FILE_IMAGE('\uf1c5', false), //
			FILL('\uf575', true), //
			FILTER('\uf0b0', true), //
			FOLDER('\uf07b', false), //
			FOOTPRINTS('\uf54b', true), //
			//GLOBE('\uf0ac', true), //
			HAND('\uf256', false), //
			HOME('\uf015', true), //
			ID('\uf2c1', false), //
			INFO('\uf129', true), //
			IMAGE('\uf03e', false), //
			IMPORT('\uf56f', true), //
			//JET('\uf0fb', true), //
			KEYBOARD('\uf11c', false), //
			LINK('\uf0c1', true), //
			LIST('\uf03a', true), //
			MAGIC('\uf0d0', true), //
			MARKER('\uf3c5', true), //
			MASKS('\uf630', true), //
			NAVIGATE('\uf14e', false), //
			MOVE('\uf0b2', true), //
			NEXT('\uf35b', false), //
			OPEN_FOLDER('\uf07c', false), //
			OPTIONS('\uf013', true), //
			PEN('\uf303', true), //
			POINTER('\uf245', true), //
			PLUS('\uf0fe', false), //
			PREVIOUS('\uf358', false), //
			QUESTION('\uf128', true), //
			QUIT('\uf011', true), //
			RECYCLE('\uf1b8', true), //
			REDO('\uf01e', true), //
			ROCKET('\uf135', true), //
			RULER('\uf546', true), //
			SAVE('\uf0c7', false), //
			SCROLL('\uf70e', true), //
			SEARCH('\uf002', true), //
			SIGNS('\uf277', true), //
			SLIDERS('\uf1de', true), //
			SORT('\uf15d', true), //
			STETHOSCOPE('\uf0f1', true), //
			STREAM('\uf550', true), //
			SUN('\uf185', true), //
			SYNC('\uf2f1', true), //
			//TACHOMETER('\uf3fd', true), //
			TABLE('\uf0ce', true), //
			TAG('\uf02b', true), //
			TEXT('\uf031', true), //
			TIMES('\uf00d', true), //
			TOOL('\uf0ad', true), //
			TRASH('\uf2ed', false), //
			TREE('\uf1bb', true), //
			UNDO('\uf0e2', true), //
			UNLINK('\uf127', true), //
			VIDEO('\uf03d', true), //
			WIDTH('\uf337', true), //
			WINDOWS('\uf2d2', false);

		private final char id;
		private final boolean solid;

		GLYPH(final char id, final boolean solid) {
			this.id = id;
			this.solid = solid;
		}

	}

	/**
	 * Creates a new icon from a Font Awesome glyph. The icon's size is set from
	 * the System's default font.
	 *
	 * @param entry the glyph defining the icon's unicode ID
	 * @param size the icon's size
	 * @param color the icon's color
	 * @return the icon
	 */
	public static Icon getIcon(final GLYPH entry, final float size,
		final Color color)
	{
		return new FADerivedIcon(entry.id, size, color, entry.solid);
	}

	public static JButton getButton(final GLYPH glyph) {
		final JButton button = new JButton();
		applyIcon(button, UIManager.getFont("Button.font").getSize(), glyph);
		return button;
	}

	public static void applyIcon(final AbstractButton button, final float iconSize,
		final GLYPH glyph) {
		final Icon defIcon = IconFactory.getIcon(glyph, iconSize, DEFAULT_COLOR);
		final Icon disIcon = IconFactory.getIcon(glyph, iconSize, INACTIVE_COLOR);
		final Icon prssdIcon = IconFactory.getIcon(glyph, iconSize, PRESSED_COLOR);
		button.setIcon(defIcon);
		button.setRolloverIcon(defIcon);
		button.setDisabledIcon(disIcon);
		button.setPressedIcon(prssdIcon);
	}

	public static Icon getButtonIcon(final GLYPH entry, final float scalingFactor) {
		return new FADerivedIcon(entry.id, UIManager.getFont("Button.font")
			.getSize() * scalingFactor, UIManager.getColor("Button.foreground"), entry.solid);
	}

	public static Icon getButtonIcon(final GLYPH entry) {
		return getButtonIcon(entry, 1.4f);
	}

	public static Icon getTabbedPaneIcon(final GLYPH entry) {
		return new FADerivedIcon(entry.id, UIManager.getFont("TabbedPane.font")
			.getSize(), UIManager.getColor("TabbedPane.foreground"), entry.solid);
	}

	public static Icon getMenuIcon(final GLYPH entry) {
		return new FADerivedIcon(entry.id, UIManager.getFont("MenuItem.font")
			.getSize() * 0.9f, UIManager.getColor("MenuItem.foreground"),
			entry.solid);
	}

	public static Icon getListIcon(final GLYPH entry) {
		return new FADerivedIcon(entry.id, UIManager.getFont("List.font")
			.getSize() * 0.9f, UIManager.getColor("List.foreground"),
			entry.solid);
	}

}
