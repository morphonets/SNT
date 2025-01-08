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

package sc.fiji.snt.analysis;

import net.imglib2.display.ColorTable;
import sc.fiji.snt.viewer.Annotation3D;


import java.util.*;

public class AnnotationMapper extends ColorMapper {

    private final Collection<Annotation3D> annotations;
    private double transparency;

    public AnnotationMapper(final Collection<Annotation3D> annotations) {
        this.annotations = annotations;
    }

    /**
     * @param transparencyPercent the color transparency (in percentage) of mapped annotations
     */
    public void setTransparency(final double transparencyPercent) {
        this.transparency = transparencyPercent;
    }

    @Override
    public void map(final String measurement, final ColorTable colorTable) {
        super.map(measurement, colorTable);
        for (final Annotation3D annotation : annotations) {
            final double v = getValue(annotation);
            if (v < min) min = v;
            if (v > max) max = v;
        }
        for (final Annotation3D annotation : annotations) {
            annotation.setColor(getColorRGB(getValue(annotation)), transparency);
        }
    }

    private double getValue(final Annotation3D annotation) {
        return annotation.getVolume(); // currently only volume is supported
    }
}
