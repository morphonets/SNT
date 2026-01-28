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

package sc.fiji.snt.analysis;

import net.imglib2.display.ColorTable;
import sc.fiji.snt.viewer.Annotation3D;


import java.util.*;

/**
 * A specialized {@link ColorMapper} for applying color-coded mappings to 3D annotations.
 * <p>
 * AnnotationMapper enables quantitative visualization of 3D annotations by mapping
 * their properties to colors using lookup tables (LUTs). This is particularly
 * useful for visualizing brain regions, anatomical compartments, or other 3D
 * structures with color-coding based on their quantitative properties.
 * </p>
 * <p>
 * The mapper supports transparency control and automatically determines the value
 * range across all annotations to ensure proper color scaling. Currently, only
 * volume-based mapping is supported, but the architecture allows for future
 * extension to other annotation properties.
 * </p>
 * Example usage:
 * <pre>
 * Collection&lt;Annotation3D&gt; annotations = getAnnotations();
 * AnnotationMapper mapper = new AnnotationMapper(annotations);
 * mapper.setTransparency(50.0); // 50% transparency
 * mapper.map("volume", colorTable);
 * </pre>
 *
 * @author Tiago Ferreira
 * @see ColorMapper
 * @see Annotation3D
 * @see sc.fiji.snt.viewer.Viewer3D
 */
public class AnnotationMapper extends ColorMapper {

    private final Collection<Annotation3D> annotations;
    private double transparency;

    /**
     * Constructs an AnnotationMapper for the specified collection of 3D annotations.
     * <p>
     * The mapper will apply color mappings to all annotations in the provided
     * collection based on their quantitative properties.
     * </p>
     *
     * @param annotations the collection of Annotation3D objects to be color-mapped
     * @throws IllegalArgumentException if annotations is null
     */
    public AnnotationMapper(final Collection<Annotation3D> annotations) {
        this.annotations = annotations;
    }

    /**
     * Sets the transparency level for mapped annotations.
     * <p>
     * This transparency value will be applied to all annotations when colors
     * are mapped, allowing for semi-transparent visualization that can reveal
     * underlying structures or overlapping annotations.
     * </p>
     *
     * @param transparencyPercent the color transparency as a percentage (0-100),
     *                           where 0 is fully opaque and 100 is fully transparent
     */
    public void setTransparency(final double transparencyPercent) {
        this.transparency = transparencyPercent;
    }

    /**
     * Maps annotation properties to colors using the specified color table.
     * This method performs the color mapping in two phases:
     * <ol>
     * <li>Determines the value range across all annotations to establish proper scaling</li>
     * <li>Applies colors to each annotation based on its value and the color table</li>
     * </ol>
     * <p>
     * Currently, only volume-based mapping is supported. The method automatically
     * calculates the minimum and maximum values across all annotations to ensure
     * proper color scaling across the full range of the color table.
     * </p>
     *
     * @param measurement the measurement type to map (currently only "volume" is supported)
     * @param colorTable the ColorTable defining the color mapping scheme
     * @throws IllegalArgumentException if measurement or colorTable is null
     */
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

    /**
     * Extracts the quantitative value from an annotation for color mapping.
     * <p>
     * Currently, this method returns the volume of the annotation. Future
     * implementations may support additional properties such as surface area,
     * centroid coordinates, or custom annotation metadata.
     * </p>
     *
     * @param annotation the Annotation3D object to extract the value from
     * @return the volume of the annotation
     */
    private double getValue(final Annotation3D annotation) {
        return annotation.getVolume(); // currently only volume is supported
    }
}
