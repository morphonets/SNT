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

import com.formdev.flatlaf.icons.FlatAbstractIcon;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * A factory for {@link FADerivedIcon}s presets.
 *
 * @author Tiago Ferreira
 */
public class IconFactory {

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
			EYE_DROPPER('\uf1fb', true), //
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

	private static Color DEF_COLOR;

	/**
	 * Creates a new icon from a Font Awesome glyph. The icon's size is set from
	 * the System's default font.
	 *
	 * @param entry the glyph defining the icon's Unicode ID
	 * @param size the icon's size
	 * @param color the icon's color
	 * @return the icon
	 */
	public static Icon get(final GLYPH entry, final float size, final Color color) {
		return new FADerivedIcon(entry.id, size, color, entry.solid);
	}

	public static Icon buttonIcon(final GLYPH entry, final float scalingFactor) {
		return new FADerivedIcon(entry.id, FADerivedIcon.defSize() * scalingFactor, defaultColor(), entry.solid);
	}

	public static Icon menuIcon(final GLYPH entry) {
		return menuIcon(entry, defaultColor());
	}

	public static Icon menuIcon(final GLYPH entry, final Color color) {
		return new FADerivedIcon(entry.id, FADerivedIcon.defSize() * 0.9f, color, entry.solid);
	}

	public static Icon menuIcon(final char symbol, final boolean solid) {
		return new FADerivedIcon(symbol, FADerivedIcon.defSize() * 0.9f, defaultColor(), solid);
	}

	public static Icon listIcon(final JList<?> list, final GLYPH entry) {
		return new FADerivedIcon(entry.id, list.getFont().getSize() * 0.9f, list.getForeground(), entry.solid);
	}

	public static Icon tabbedPaneIcon(final JTabbedPane tabbedPane, final GLYPH entry) {
		return new FADerivedIcon(entry.id, tabbedPane.getFont().getSize(), tabbedPane.getForeground(), entry.solid);
	}

	public static TitledBorder titledBorder(final char symbol, final boolean solid) {
		final TitledBorder border = new TitledBorder(String.valueOf(symbol));
		border.setTitleFont(FADerivedIcon.getFont(solid));
		border.setTitleColor(UIManager.getColor("TitlePane.embeddedForeground"));
		border.setTitlePosition(TitledBorder.BOTTOM);
		return border;
	}

	public static void assignIcon(final AbstractButton button, final GLYPH glyph) {
		button.setText(String.valueOf(glyph.id));
		button.setFont(FADerivedIcon.getFont(glyph.solid));
		if (button instanceof JToggleButton) {
			button.addItemListener(e -> {
				if (button.isSelected()) {
					button.setForeground(selectedColor());
				} else {
					button.setForeground(defaultColor());
				}
			});
		} else {
			button.addMouseListener(new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) { button.setForeground(pressedColor()); }
				@Override
				public void mouseReleased(MouseEvent e) {  button.setForeground(defaultColor()); }
			});
		}
	}

	public static void assignIcon(final AbstractButton button, final GLYPH glyph, final int iconSize) {
		assignIcon(button, glyph);
		button.setFont(button.getFont().deriveFont((float)iconSize));
	}

	public static void assignIcon(final AbstractButton button, final GLYPH glyph, final float scaleFactor) {
		assignIcon(button, glyph);
		button.setFont(button.getFont().deriveFont(button.getFont().getSize2D()*scaleFactor));
	}

	public static JToggleButton assignIcon(final JToggleButton button, final GLYPH defaulttGlyph, final GLYPH pressedtGlyph) {
		button.setFont(FADerivedIcon.getFont(defaulttGlyph.solid));
		button.setText( (button.isSelected()) ? String.valueOf(pressedtGlyph.id) : String.valueOf(defaulttGlyph.id));
		button.addItemListener( e -> {
			if (button.isSelected()) {
				button.setText(String.valueOf(pressedtGlyph.id));
				button.setFont(FADerivedIcon.getFont(pressedtGlyph.solid));
			} else {
				button.setText(String.valueOf(defaulttGlyph.id));
				button.setFont(FADerivedIcon.getFont(defaulttGlyph.solid));
			}
		});
		return button;
	}

	public static Color defaultColor() {
		if (DEF_COLOR == null)
			DEF_COLOR = UIManager.getColor("Button.foreground");
		return DEF_COLOR;
	}

	public static Color pressedColor() {
		return UIManager.getColor("Button.highlight");
	}

	public static Color selectedColor() {
		return UIManager.getColor("List.selectionBackground");
	}


	public static Icon nodeIcon(final Color color) {
		return new NodeIcon(color, false, true, false);
	}

	public static Icon nodeIcon(final Color color, final boolean isLeaf, final boolean isExpanded) {
		if (isLeaf)
			return new NodeIcon(color, false, true, false);
		else if (isExpanded)
			return new NodeIcon(color, false, false, true);
		else
			return new NodeIcon(color, false, false, false);
	}

	public static Icon nodeIconMulticolor(final boolean isLeaf, final boolean isExpanded) {
		if (isLeaf)
			return new NodeIcon(null, true, true, false);
		else if (isExpanded)
			return new NodeIcon(null, true, false, true);
		else
			return new NodeIcon(null, true, false, false);
	}

	public static Icon accentIcon(final Color color, final int w, final int h) {
		return new AccentIcon(color, w, h);
	}

	/* Creation of colorful JTree node icons */
    private static class NodeIcon implements Icon {

        private static final int SIZE = preferredIconSize();
        private static final Color FOREGROUND_COLOR = UIManager.getColor("Tree.foreground");
        private final static Color[] Q_COLORS = new Color[]{
                new Color(0xF2C926), new Color(0x42C0F0), //
                new Color(0xD924A3), new Color(0xD5D4D3)};
        final Color fillColor;
        final Color typeColor;
        final boolean multiColor;
		final boolean isLeaf;
		final boolean isExpanded;

		NodeIcon(final Color fillColor, final boolean multiColor, final boolean isLeaf, final boolean isExpanded) {
            this.fillColor = fillColor;
            this.multiColor = multiColor;
			this.isLeaf = isLeaf;
			this.isExpanded = isExpanded;
            if (multiColor) {
                this.typeColor = Color.BLACK;
            } else if (fillColor == null) {
                this.typeColor = FOREGROUND_COLOR;
            } else if (closerToBlack(fillColor)) {
                this.typeColor = Color.WHITE;
            } else {
                this.typeColor = Color.BLACK;
            }
        }

        static int preferredIconSize() {
            final JTree tree = new JTree();
            final int size = tree.getFontMetrics(tree.getFont()).getAscent();
            return (size % 2 == 0) ? size - 1 : size;
        }

        boolean closerToBlack(final Color c) {
            final double y = 0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue();
            return y < 100; // https://stackoverflow.com/a/9780689
        }

        @Override
        public void paintIcon(final Component c, final Graphics g, final int x, final int y) { //http://stackoverflow.com/a/7984734
            final Graphics2D g2 = (Graphics2D) g;

            // fill rectangle
            if (multiColor) { // Path has multiple node colors
                final int cellWidth = SIZE / 2;
                final int cellHeight = SIZE / 2;
                g2.setColor(Q_COLORS[0]);
                g2.fillRect(x + 1, y + 1, cellWidth, cellHeight); // top-left
                g2.setColor(Q_COLORS[1]);
                g2.fillRect(x + cellWidth + 1, y + 1, cellWidth, cellHeight); // top-right
                g2.setColor(Q_COLORS[2]);
                g2.fillRect(x + 1, y + cellHeight + 1, cellWidth, cellHeight); // bottom-left
                g2.setColor(Q_COLORS[3]);
                g2.fillRect(x + cellWidth + 1, y + cellHeight + 1, cellWidth, cellHeight); // bottom-right
            } else if (fillColor != null) { // monochrome path
                g2.setColor(fillColor);
                g2.fillRect(x + 1, y, SIZE - 1, SIZE - 1);
            }
            // draw contour
            g2.setColor(FOREGROUND_COLOR);
            g2.drawRect(x, y, SIZE - 1, SIZE - 1);
            if (isLeaf) return;
            // draw 'minus' or 'plus'
            g2.setColor(typeColor);
            g2.fillRect(x + 1, y + SIZE / 2, SIZE - 2, 2);
            if (!isExpanded) {
                g2.fillRect(x + SIZE / 2, y + 1, 2, SIZE - 2);
            }
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }

	private static class AccentIcon extends FlatAbstractIcon {
		Color color;

		AccentIcon(final Color color, final int w, final int h) {
			super( w, h, color);
			this.color = (color == null) ? Color.LIGHT_GRAY : color;
		}

		@Override
		protected void paintIcon(Component c, Graphics2D g ) {
//			if( !c.isEnabled() ) {
//				color = FlatLaf.isLafDark()  ? ColorFunctions.shade( color, 0.5f )
//						: ColorFunctions.tint( color, 0.6f );
//			}
			g.setColor( color );
			g.fillRoundRect( 1, 1, width - 2, height - 2, 5, 5 );
		}
	}
}
