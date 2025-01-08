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
package sc.fiji.snt.viewer;

import java.awt.GridLayout;
import javax.swing.JPanel;
import javax.swing.JSlider;

import org.jzy3d.chart.Chart;
import org.jzy3d.colors.Color;
import org.jzy3d.plot3d.primitives.enlightables.AbstractEnlightable;
import org.jzy3d.ui.editors.ColorEditor;

/**
 * Copy of org.jzy3d.ui.editors.MaterialEditor that does not interfere with
 * LookandFee
 */
class MaterialEditor extends JPanel {

	private static final long serialVersionUID = 4903947408608903517L;
	protected ColorEditor ambiantColorControl;
	protected ColorEditor diffuseColorControl;
	protected ColorEditor specularColorControl;
	protected Chart chart;
	protected AbstractEnlightable enlightable;

	protected MaterialEditor(final Chart chart) {
		this.chart = chart;
		ambiantColorControl = new ColorEditor("Ambiant");
		diffuseColorControl = new ColorEditor("Diffuse");
		specularColorControl = new ColorEditor("Specular");
		setLayout(new GridLayout(3, 1));
		add(ambiantColorControl);
		add(diffuseColorControl);
		add(specularColorControl);
	}

	protected void registerColorControl(final ColorEditor colorControl, final Color color) {
		final JSlider slider0 = colorControl.getSlider(0);
		slider0.setValue((int) color.r * slider0.getMaximum());
		slider0.addChangeListener(e -> {
			color.r = getPercent(slider0);
			chart.render();
		});
		final JSlider slider1 = colorControl.getSlider(1);
		slider1.setValue((int) color.g * slider1.getMaximum());
		slider1.addChangeListener(e -> {
			color.g = getPercent(slider1);
			chart.render();
		});
		final JSlider slider2 = colorControl.getSlider(2);
		slider2.setValue((int) color.b * slider2.getMaximum());
		slider2.addChangeListener(e -> {
			color.b = getPercent(slider2);
			chart.render();
		});
	}

	protected float getPercent(final JSlider slider) {
		return ((float) slider.getValue()) / ((float) slider.getMaximum());
	}

	protected void setTarget(final AbstractEnlightable enlightable) {
		this.enlightable = enlightable;
		registerColorControl(ambiantColorControl, enlightable.getMaterialAmbiantReflection());
		registerColorControl(diffuseColorControl, enlightable.getMaterialDiffuseReflection());
		registerColorControl(specularColorControl, enlightable.getMaterialSpecularReflection());
	}

}
