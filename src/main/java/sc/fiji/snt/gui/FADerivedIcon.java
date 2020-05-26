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

import java.awt.Component;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import javax.swing.Icon;
import javax.swing.UIManager;

import sc.fiji.snt.SNTUtils;

/**
 * Creates icons from Font Awesome Glyphs: High-quality icons that render well
 * at any screen resolution. Based on code from <a href=
 * "http://icedtea.classpath.org/people/neugens/SwingUIPatterns/file/tip/src/main/java/org/icedtea/ui/patterns/swing/images/FontAwesomeIcon.java">Mario
 * Torre</a> (GPL-2) and <a href=
 * "https://github.com/griffon-plugins/griffon-fontawesome-plugin/blob/master/subprojects/griffon-fontawesome-javafx/src/main/java/griffon/javafx/support/fontawesome/FontAwesomeIcon.java">
 * Andres Almiray</a> (Apache-2.0), with tweaks and updates for font awesome
 * 5.0.
 *
 * @author Tiago Ferreira
 */
public class FADerivedIcon implements Icon {

	private static final String AWESOME_REGULAR =
		"META-INF/resources/webjars/font-awesome/5.13.0/webfonts/fa-regular-400.ttf";
	private static final String AWESOME_SOLID =
		"META-INF/resources/webjars/font-awesome/5.13.0/webfonts/fa-solid-900.ttf";
	private final Font font;
	private final float size;
	private final Paint color;
	private final char iconID;
	private BufferedImage buffer;


	protected FADerivedIcon(final char iconID, final float size,
		final Paint color, final boolean solid)
	{
		this.iconID = iconID;
		this.size = size;
		font = getFont(solid);
		this.color = color;
	}

	private Font getFont(final boolean solid) {
		Font font;
		try {
			final InputStream is = Thread.currentThread().getContextClassLoader()
				.getResourceAsStream((solid) ? AWESOME_SOLID : AWESOME_REGULAR);
			assert is != null;
			font = Font.createFont(Font.TRUETYPE_FONT, is);
		}
		catch (FontFormatException | IOException ex) {
			font = UIManager.getFont("Label.font"); // desperate fallback
			SNTUtils.error("Could not load fonts", ex);
		}
		return font.deriveFont(size);
	}

	@Override
	public synchronized void paintIcon(final Component c, final Graphics g,
		final int x, final int y)
	{

		if (buffer == null) {
			buffer = new BufferedImage(getIconWidth(), getIconHeight(),
				BufferedImage.TYPE_INT_ARGB);

			final Graphics2D graphics = (Graphics2D) buffer.getGraphics();
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
				RenderingHints.VALUE_RENDER_QUALITY);

			graphics.setFont(font);
			graphics.setPaint(color);

			final String str = String.valueOf(iconID);
			final FontMetrics metrics = graphics.getFontMetrics(font);
			// Calculate position of the icon NB: In Java2D 0 is top of
			final float xx = (size - metrics.stringWidth(str)) / 2;
			final float yy = ((size - metrics.getHeight()) / 2) + metrics.getAscent();
			graphics.drawString(str, xx, yy);
			graphics.dispose();
		}

		g.drawImage(buffer, x, y, null);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see javax.swing.Icon#getIconHeight()
	 */
	@Override
	public int getIconHeight() {
		return (int) size;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see javax.swing.Icon#getIconWidth()
	 */
	@Override
	public int getIconWidth() {
		return (int) size;
	}
}
