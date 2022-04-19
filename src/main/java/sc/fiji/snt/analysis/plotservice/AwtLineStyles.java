/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2022 Fiji developers.
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

package sc.fiji.snt.analysis.plotservice;

import org.scijava.plot.LineStyle;

import java.awt.*;

/**
 * @author Matthias Arzt
 */

class AwtLineStyles {

	private final boolean visible;

	private final BasicStroke stroke;

	private AwtLineStyles(boolean visible, BasicStroke stroke) {
		this.visible = visible;
		this.stroke = stroke;
	}

	public boolean isVisible() {
		return visible;
	}

	public BasicStroke getStroke() {
		return stroke;
	}

	public static AwtLineStyles getInstance(LineStyle style) {
		if(style != null)
			switch (style) {
				case SOLID:
					return solid;
				case DASH:
					return dash;
				case DOT:
					return dot;
				case NONE:
					return none;
			}
		return solid;
	}

	// --- Helper Constants ---

	private static AwtLineStyles solid = new AwtLineStyles(true, Strokes.solid);

	private static AwtLineStyles dash = new AwtLineStyles(true, Strokes.dash);

	private static AwtLineStyles dot = new AwtLineStyles(true, Strokes.dot);

	private static AwtLineStyles none = new AwtLineStyles(false, Strokes.none);

	static class Strokes {

		private static BasicStroke solid = new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

		private static BasicStroke dash = new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
				.0f, new float[]{6.0f, 6.0f}, 0.0f);

		private static BasicStroke dot = new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
				.0f, new float[]{0.6f, 4.0f}, 0.0f);

		private static BasicStroke none = new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
				.0f, new float[]{0.0f, 100.0f}, 0.0f);
	}

}