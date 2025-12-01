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
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * Creates icons from Font Awesome Glyphs: High-quality icons that render well
 * at any screen resolution. Based on code from <a href=
 * "http://icedtea.classpath.org/people/neugens/SwingUIPatterns/file/tip/src/main/java/org/icedtea/ui/patterns/swing/images/FontAwesomeIcon.java">Mario
 * Torre</a> (GPL-2) and <a href=
 * "https://github.com/griffon-plugins/griffon-fontawesome-plugin/blob/master/subprojects/griffon-fontawesome-javafx/src/main/java/griffon/javafx/support/fontawesome/FontAwesomeIcon.java">
 * Andres Almiray</a> (Apache-2.0)
 *
 * @author Tiago Ferreira
 */
class FADerivedIcon implements Icon {

	private static final String FA_VERSION = "6.7.2"; // could be read from pom
	private static final Font fontRegular = loadFont("fa-regular-400.ttf");
	private static final Font fontSolid = loadFont("fa-solid-900.ttf");
	private static int DEF_SIZE;

	private final Paint color;
	private final Font font;
	private final String symbol;
	private int w;

	FADerivedIcon(final char iconID, final float size, final Paint color, final boolean solid) {
		this.color = color;
		font = getFont(solid, size);
		symbol = String.valueOf(iconID);
	}

	private static Font loadFont(final String fontName) {
		Font font;
		try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            classLoader = (classLoader != null) ? classLoader : FADerivedIcon.class.getClassLoader();
			final InputStream is = classLoader
				.getResourceAsStream(String.format("META-INF/resources/webjars/font-awesome/%s/webfonts/%s", FA_VERSION, fontName));
			assert is != null;
			font = Font.createFont(Font.TRUETYPE_FONT, is).deriveFont((float)defSize());
		}
		catch (final Exception ignored) {
			font = UIManager.getFont("Label.font"); // desperate fallback
		}
		return font;
	}

	@Override
	public synchronized void paintIcon(final Component ignored, final Graphics g, final int x, final int y) {
		final Graphics2D graphics = (Graphics2D) g;
		GuiUtils.setRenderingHints(graphics);
		final Font previousFont = graphics.getFont();
		graphics.setFont(font);
		graphics.setPaint(color);
		graphics.drawString(symbol, x, y + graphics.getFontMetrics(font).getAscent());
		graphics.setFont(previousFont);
	}

	protected ImageIcon asImage() {
		final BufferedImage image = new BufferedImage(getIconWidth(), getIconHeight(), BufferedImage.TYPE_INT_ARGB);
		final Graphics g = image.createGraphics();
		try {
			paintIcon(null, g, 0, 0);
			return new ImageIcon(image);
		} finally {
			g.dispose();
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see javax.swing.Icon#getIconHeight()
	 */
	@Override
	public int getIconHeight() {
		return font.getSize() + 1;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see javax.swing.Icon#getIconWidth()
	 */
	@Override
	public int getIconWidth() {
		if (w == 0)
			w = new JLabel().getFontMetrics(font).stringWidth(symbol);
		return w;
	}

	static Font getFont(final boolean solid) {
		return (solid) ? fontSolid : fontRegular;
	}

	static Font getFont(final boolean solid, final float size) {
		return (solid) ? fontSolid.deriveFont(size) : fontRegular.deriveFont(size);
	}

	static int defSize() {
		if (DEF_SIZE == 0) {
			DEF_SIZE = (int) GuiUtils.uiFontSize();
		}
		return DEF_SIZE;
	}
}
