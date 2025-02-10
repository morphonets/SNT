/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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

import javax.swing.*;
import java.awt.*;

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
			ANGLE_RIGHT('\uf105', true),
			ARCHIVE('\uf1c6', false), //
			ADJUST('\uf042', true), //
			ALIGN_LEFT('\uf036', true), //
			ALIGN_CENTER('\uf037', true), //
			ALIGN_RIGHT('\uf038', true), //
			ATLAS('\uf558', true), //
			ARROWS_LR('\uf0ec', true), //
			ARROWS_LR_TO_LINE('\ue4ba', true), //
			ARROWS_TO_CIRCLE('\ue4bd', true), //
			ARROWS_DLUR('\uf422', true), //
			ARROWS_SPLIT('\ue4bc', true), //
			//ARROWS_V('\uf338', true), //
			//ATOM('\uf5d2', true), //
			BARCODE('\uf02a', true), //
			BEZIER_CURVE('\uf55b', true),//
			BINOCULARS('\uf1e5', true), //
			BOLD('\uf032', true),//
			BOOKMARK('\uf02e', true), //
			BOOK_READER('\uf5da', true), //
			BRAIN('\uf5dc', true), //
			BRANCH_CODE('\uf126', true), //
			BROOM('\uf51a', true), //
			BUG('\uf188', true), //
			BULB('\uf0eb', true), //
			BULLSEYE('\uf140', true), //
			CAMERA('\uf030', true), //
			CALCULATOR('\uf1ec', true), //
			CHART('\ue0e3', true), //
			CHART_LINE('\uf201', true), //
			CHART_AREA('\uf1fe', true), //
			CHART_MAGNIFIED('\ue522', true), //
			CHECK_DOUBLE('\uf560', true), //
			CHILD('\uf1ae', true), //
			CHILDREN('\ue4e1', true), //
			CIRCLE('\uf192', false), //
			CLOCK_ROTATE_LEFT('\uf1da', true), //
			CLIPBOARD('\uf328', false), //
			CLOUD('\uf381', true), //
			CLONE('\uf24d', false), //
			CODE('\uf120', true), //
			CODE2('\uf121', true), //
			COG('\uf013', true), //
			COGS('\uf085', true), //
			COLOR('\uf53f', true), //
			COLOR2('\uf5c3', true), //
			COMMENTS('\uf086', false), //
			CUBES('\uf1b3', true), //
			//COMPASS('\uf14e', false), //
			//COMPRESS('\uf422', true), //
			GLASSES('\uf530', true), //
			RESIZE('\uf424', true), //
			COPY('\uf0c5', false), //
			CROSSHAIR('\uf05b', true), //
			CSV('\uf6dd', true), //
			CUBE('\uf1b2', true), //
			CUT('\uf0c4', true), //
			DANGER('\uf071', true), //
			DATABASE('\uf1c0', true), //
			DELETE('\uf55a', true), //
			DIAGRAM('\uf542', true), //
			DICE_20('\uf6cf', true), //
			DOTCIRCLE('\uf192', true), //
			//DOWNLOAD('\uf019', true), //
			DRAFT('\uf568', true), //
			ELLIPSIS('\uf141', true), //
			ELLIPSIS_VERTICAL('\uf142', true), //
			EMPTY('\u00a0', false), //
			EQUALS('\uf52c', true), //
			EXPAND('\uf065', true), //
			EXPAND_ARROWS1('\uf337', true), //
			EXPAND_ARROWS2('\uf31e', true), //
			EXPLORE('\uf610', true), //
			EXPORT('\uf56e', true), //
			EXTERNAL_LINK('\uf35d', true), //
			EYE('\uf06e', false), //
			EYE_SLASH('\uf070', false), //
			FILE('\uf15b', false), //
			FILE_IMAGE('\uf1c5', false), //
			FILL('\uf575', true), //
			FILTER('\uf0b0', true), //
			FIRST_AID('\uf469', true), //
			FOLDER('\uf07b', false), //
			FONT('\uf031', true), //
			FOOTPRINTS('\uf54b', true), //
			GEM('\uf3a5', true), //
			GLOBE('\uf0ac', true), //
			GRID('\uf00a', true), //
			GRADUATION_CAP('\uf19d', true), //
			HAND('\uf256', false), //
			HOME('\uf015', true), //
			ID('\uf2c1', false), //
			ID_ALT('\uf47f', true), //
			INFO('\uf129', true), //
			IMAGE('\uf03e', false), //
			IMPORT('\uf56f', true), //
			ITALIC('\uf033', true), //
			//JET('\uf0fb', true), //
			KEYBOARD('\uf11c', false), //
			KIWI_BIRD('\uf535', true), //
			LAYERS('\uf5fd', true), //
			LINK('\uf0c1', true), //
			LIST('\uf03a', true), //
			LIST_ALT('\uf022', true), //
			LOCK('\uf023', true), //
			LOCK_OPEN('\uf3c1', true), //
			MAGIC('\uf0d0', true), //
			MAP_PIN('\uf276', true), //
			MARKER('\uf3c5', true), //
			MASKS('\uf630', true), //
			MATH('\uf698', true), //
			MICROCHIP('\uf2db', true), //
			//MINIMIZE('\uf78c', true), //
			MINUS('\uf146', false), //
			NAVIGATE('\uf14e', false), //
			MOVE('\uf0b2', true), //
			NEWSPAPER('\uf1ea', false), //
			NEXT('\uf35b', false), //
			OPEN_FOLDER('\uf07c', false), //
			OPTIONS('\uf013', true), //
			PASTE('\uf0ea', true), //
			PEN('\uf303', true), //
			PERSON_CHALKBOARD('\ue53d', true),//
			POINTER('\uf245', true), //
			PLUS('\uf0fe', false), //
			PREVIOUS('\uf358', false), //
			QUESTION('\uf059', false), //
			QUIT('\uf011', true), //
			RECYCLE('\uf1b8', true), //
			REDO('\uf01e', true), //
			ROBOT('\uf544', true), //
			ROCKET('\uf135', true), //
			ROUTE('\uf4d7', true), //
			RULER('\uf546', true), //
			RULER_VERTICAL('\uf548', true), //
			SAVE('\uf0c7', false), //
			SCALE_BALANCED('\uf24e', true), //
			SCALE_UNBALANCED('\uf515', true), //
			SCISSORS('\uf0c4', true), //
			SCROLL('\uf70e', true), //
			SEARCH('\uf002', true), //
			SEARCH_MINUS('\uf010', true), //
			SEARCH_PLUS('\uf00e', true), //
			SEARCH_ARROW('\ue521', true), //
			SHUFFLE('\uf074', true), //
			SIGNS('\uf277', true), //
			SLIDERS('\uf1de', true), //
			SPINNER('\uf110', true), //
			SORT('\uf15d', true), //
			SQUARE('\uf0c8', false), //
			STAIRS('\ue289', true), //
			STETHOSCOPE('\uf0f1', true), //
			STREAM('\uf550', true), //
			SUN('\uf185', true), //
			SYNC('\uf2f1', true), //
			//TACHOMETER('\uf3fd', true), //
			TABLE('\uf0ce', true), //
			TAG('\uf02b', true), //
			TAPE('\uf4db', true), //
			TEXT('\uf031', true), //
			TIMES('\uf00d', true), //
			TOILET('\uf7d8', true), //
			TOOL('\uf0ad', true), //
			TRASH('\uf2ed', false), //
			TREE('\uf1bb', true), //
			UNDO('\uf0e2', true), //
			UNLINK('\uf127', true), //
			VIDEO('\uf03d', true), //
			WIDTH('\uf337', true), //
			WINDOWS('\uf2d2', false), //
			WINDOWS2('\uf2d2', true), //
			WIZARD('\uf6e8', true),//
			X_RAY('\uf497', true);

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

	public static JButton getButton(final GLYPH glyph1, final GLYPH glyph2) {
		final JButton button = new JButton();
		final int size = UIManager.getFont("Button.font").getSize();
		Icon icon1 = IconFactory.getIcon(glyph1, size, DEFAULT_COLOR);
		Icon icon2 = IconFactory.getIcon(glyph2, size, DEFAULT_COLOR);
		button.setIcon(new SideBySideDoubleIcon(icon1, icon2));
		icon1 = IconFactory.getIcon(glyph1, size, INACTIVE_COLOR);
		icon2 = IconFactory.getIcon(glyph2, size, INACTIVE_COLOR);
		button.setDisabledIcon(new SideBySideDoubleIcon(icon1, icon2));
		icon1 = IconFactory.getIcon(glyph1, size, PRESSED_COLOR);
		icon2 = IconFactory.getIcon(glyph2, size, PRESSED_COLOR);
		button.setPressedIcon(new SideBySideDoubleIcon(icon1, icon2));
		return button;
	}

	private static void updateColors() {
		DEFAULT_COLOR = UIManager.getColor("Button.foreground");
		INACTIVE_COLOR = UIManager.getColor("Button.disabledText");
		PRESSED_COLOR = UIManager.getColor("Button.highlight");
	}

	public static void applyIcon(final AbstractButton button, final float iconSize,
		final GLYPH glyph) {
		updateColors();
		final Icon defIcon = IconFactory.getIcon(glyph, iconSize, DEFAULT_COLOR);
		final Icon disIcon = IconFactory.getIcon(glyph, iconSize, INACTIVE_COLOR);
		final Icon prssdIcon = IconFactory.getIcon(glyph, iconSize, PRESSED_COLOR);
		button.setIcon(defIcon);
		button.setRolloverIcon(defIcon);
		button.setDisabledIcon(disIcon);
		button.setPressedIcon(prssdIcon);
	}

	public static Icon getButtonIcon(final GLYPH entry, final float scalingFactor) {
		if (GLYPH.EMPTY.equals(entry)) {
			return new EmptyIcon( UIManager.getFont("Button.font").getSize() * scalingFactor);
		}
		return new FADerivedIcon(entry.id, UIManager.getFont("Button.font")
			.getSize() * scalingFactor, UIManager.getColor("Button.foreground"), entry.solid);
	}

	public static Icon getButtonIcon(final GLYPH entry) {
		return getButtonIcon(entry, 1.4f);
	}

	public static Icon getTabbedPaneIcon(final GLYPH entry) {
		if (GLYPH.EMPTY.equals(entry)) {
			return new EmptyIcon(UIManager.getFont("TabbedPane.font").getSize());
		}
		return new FADerivedIcon(entry.id, UIManager.getFont("TabbedPane.font").getSize(),
				UIManager.getColor("TabbedPane.foreground"), entry.solid);
	}

	public static Icon getMenuIcon(final GLYPH entry) {
		return getMenuIcon(entry, UIManager.getColor("MenuItem.foreground"));
	}

	public static Icon getMenuIcon(final GLYPH entry, final Color color) {
		if (GLYPH.EMPTY.equals(entry)) {
			return new EmptyIcon(UIManager.getFont("MenuItem.font").getSize() * 0.9f);
		}
		return new FADerivedIcon(entry.id, UIManager.getFont("MenuItem.font")
				.getSize() * 0.9f, color, entry.solid);
	}

	public static Icon getMenuIcon(final char symbol, final boolean solid) {
		return new FADerivedIcon(symbol, UIManager.getFont("MenuItem.font")
				.getSize() * 0.9f, UIManager.getColor("MenuItem.foreground"),
				solid);
	}

	public static Icon getListIcon(final GLYPH entry) {
		return new FADerivedIcon(entry.id, UIManager.getFont("List.font")
			.getSize() * 0.9f, UIManager.getColor("List.foreground"),
			entry.solid);
	}

	static class EmptyIcon implements Icon {

		private float size;

		protected EmptyIcon(final float size) {
			this.size = size;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
			// do nothing
		}

		@Override
		public int getIconWidth() {
			return (int) size;
		}

		@Override
		public int getIconHeight() {
			return (int) size;
		}

	}

	static class SideBySideDoubleIcon implements Icon {

		private final int iconGap = 4;
		private final Icon icon1;
		private final Icon icon2;

		public SideBySideDoubleIcon(final Icon icon1, final Icon icon2) {
			this.icon1 = icon1;
			this.icon2 = icon2;
		}

		@Override
		public void paintIcon(final Component c, final Graphics g, final int x, final int y) {
			final int mid = getIconHeight() / 2;
			final int y1 = y + mid - icon1.getIconHeight() / 2;
			final int y2 = y + mid - icon2.getIconHeight() / 2;
			icon1.paintIcon(c, g, x, y1);
			icon2.paintIcon(c, g, x + icon1.getIconWidth() + iconGap, y2);
		}

		@Override
		public int getIconWidth() {
			return icon1.getIconWidth() + icon2.getIconWidth() + iconGap;
		}

		@Override
		public int getIconHeight() {
			return Math.max(icon1.getIconHeight(), icon2.getIconHeight());
		}
	}
}
