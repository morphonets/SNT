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
