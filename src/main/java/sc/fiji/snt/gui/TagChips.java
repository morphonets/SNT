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

import org.scijava.util.ColorRGB;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Renders tags as "chips" next to the text of a {@link JLabel}-based cell
 * renderer: custom tags ({@code {tag1, tag2}}) and metadata tags
 * ({@code [Ch:1]}). Tags that are HTML colors are rendered as color swatches.
 * Meant to be used by composition: renderers call {@link #setChips} when
 * configuring the cell and delegate {@link #extraWidth} and {@link #paint}
 * from {@code getPreferredSize()} and {@code paintComponent()}.
 *
 * @author Tiago Ferreira
 */
public final class TagChips {

	private static final int CHIP_GAP = 4; // space before first chip and between chips
	private static final int CHIP_HPAD = 4; // horizontal padding inside each chip
	private static final int CHIP_VPAD = 1; // vertical padding inside each chip
	private static final float SWATCH_SCALE = .8f; // swatch size relative to chip height
	private static final Pattern SPLIT = Pattern.compile(",\\s*");
	private static final Pattern META = Pattern.compile("\\[[^]]+]");

	// Tags are re-parsed every time a cell is rendered: cache the color lookups
	private static final Map<String, Optional<Color>> COLORS = new ConcurrentHashMap<>();
	private static final int MAX_CACHED_COLORS = 1024;

	private record Chip(String text, Color color) {}

	private static Color colorOf(final String tag) {
		if (COLORS.size() > MAX_CACHED_COLORS) COLORS.clear();
		return COLORS.computeIfAbsent(tag, t -> {
			final ColorRGB c = ColorRGB.fromHTMLColor(t);
			return Optional.ofNullable((c == null) ? null : new Color(c.getRed(), c.getGreen(), c.getBlue()));
		}).orElse(null);
	}

	private final List<Chip> chips = new ArrayList<>();
	private Font baseFont;
	private Font chipFont;

	/**
	 * Custom tags of a name: content inside {} split by comma (empties skipped).
	 * The opening brace may be anywhere (e.g., Path names)
	 *
	 * @return the tags, or null if none
	 */
	public static String[] parseCustom(final String name) {
		return parseCustom(name, false);
	}

	/**
	 * Custom tags: content inside {} split by comma (empties skipped)
	 *
	 * @param spaceDelimited if true the opening brace must be preceded by a space (i.e., the " {" delimiter used
	 *                       by {@link TagUtils}, e.g., list entries)
	 * @return the tags, or null if none
	 */
	public static String[] parseCustom(final String name, final boolean spaceDelimited) {
		final String opening = (spaceDelimited) ? " {" : "{";
		final int open = name.indexOf(opening);
		final int close = name.lastIndexOf('}');
		if (open < 0 || close <= open) return null;
		final List<String> list = new ArrayList<>();
		for (final String s : SPLIT.split(name.substring(open + opening.length(), close))) {
			if (!s.isEmpty()) list.add(s);
		}
		return list.isEmpty() ? null : list.toArray(new String[0]);
	}

	/** Metadata tags: [] blocks of the text, without brackets; null if none */
	public static String[] parseMeta(final String text) {
		final var m = META.matcher(text);
		final List<String> list = new ArrayList<>();
		while (m.find()) {
			final String match = m.group();
			list.add(match.substring(1, match.length() - 1));
		}
		return list.isEmpty() ? null : list.toArray(new String[0]);
	}

	/** Removes all [] blocks from the text */
	public static String stripMeta(final String text) {
		return META.matcher(text).replaceAll("").trim();
	}

	public void clear() {
		chips.clear();
	}

	public boolean isEmpty() {
		return chips.isEmpty();
	}

	/** Replaces current chips. Either argument may be null */
	public void setChips(final String[] custom, final String[] meta) {
		chips.clear();
		if (custom != null) {
			for (final String tag : custom) {
				chips.add(new Chip(tag, colorOf(tag)));
			}
		}
		if (meta != null) {
			for (final String tag : meta)
				chips.add(new Chip(tag, null));
		}
	}

	private FontMetrics metrics(final JLabel label) {
		final Font f = label.getFont();
		if (chipFont == null || f != baseFont) { // L&F or font changes
			baseFont = f;
			chipFont = f.deriveFont(Font.PLAIN, f.getSize2D() * .85f);
		}
		return label.getFontMetrics(chipFont);
	}

	private static int height(final FontMetrics fm) {
		return fm.getAscent() + fm.getDescent() + CHIP_VPAD * 2;
	}

	// Color swatches are smaller than text chips so that they do not overshadow the label
	private static int swatchSize(final FontMetrics fm) {
		return Math.max(6, Math.round(height(fm) * SWATCH_SCALE));
	}

	private static int width(final Chip chip, final FontMetrics fm) {
		return (chip.color() != null) ? swatchSize(fm) : fm.stringWidth(chip.text()) + CHIP_HPAD * 2;
	}

	/** Extra width needed by the chips (to be added to the label's preferred width) */
	public int extraWidth(final JLabel label) {
		if (chips.isEmpty()) return 0;
		final FontMetrics fm = metrics(label);
		int w = CHIP_GAP;
		for (final Chip chip : chips)
			w += width(chip, fm) + CHIP_GAP;
		return w;
	}

	/** Paints the chips right after the label's text. Call after super.paintComponent() */
	public void paint(final Graphics g, final JLabel label) {
		if (chips.isEmpty()) return;
		final Graphics2D g2 = (Graphics2D) g.create();
		GuiUtils.setRenderingHints(g2);
		final Color fg = label.getForeground();
		final Color border = new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 50);
		final Color fill = new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 8);
		final int arcProp = UIManager.getInt("Component.arc");
		final int arc = (arcProp > 0) ? Math.min(arcProp, 8) : 4;

		int x = label.getInsets().left;
		final Icon icon = label.getIcon();
		if (icon != null) x += icon.getIconWidth() + label.getIconTextGap();
		final String text = label.getText();
		if (text != null && !text.isEmpty())
			x += label.getFontMetrics(label.getFont()).stringWidth(text);
		x += CHIP_GAP;

		final FontMetrics fm = metrics(label); // also (re)sets chipFont
		g2.setFont(chipFont);
		final int h = height(fm);
		final int y = (label.getHeight() - h) / 2;
		for (final Chip chip : chips) {
			final int w = width(chip, fm);
			if (chip.color() != null) {
				final int sy = y + (h - w) / 2; // swatches are square and vertically centered
				IconFactory.paintSwatch(g2, x, sy, w, w, IconFactory.swatchArc(w), chip.color(), border);
			} else {
				IconFactory.paintSwatch(g2, x, y, w + 1, h + 1, arc, fill, border);
				g2.setColor(fg);
				g2.drawString(chip.text(), x + CHIP_HPAD, y + fm.getAscent() + CHIP_VPAD);
			}
			x += w + CHIP_GAP;
		}
		g2.dispose();
	}

}
