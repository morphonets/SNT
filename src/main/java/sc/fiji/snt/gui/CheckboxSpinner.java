/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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

import java.awt.Color;
import java.awt.FlowLayout;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

public class CheckboxSpinner extends JPanel {

	private static final long serialVersionUID = 937359242995500492L;
	private final JCheckBox checkbox;
	private final JSpinner spinner;
	private JLabel label;

	public CheckboxSpinner(final JCheckBox checkbox, final JSpinner numericSpinner) {
		super(new FlowLayout(FlowLayout.LEFT, 0, 0));
		this.checkbox = checkbox;
		this.spinner = numericSpinner;
		add(checkbox);
		add(numericSpinner);
	}

	public void appendLabel(final String trailingLabel) {
		label = new JLabel(" " + trailingLabel);
		add(label);
	}

	public void setSpinnerMinMax(final int min, final int max) {
		((SpinnerNumberModel) spinner.getModel()).setMinimum(min);
		((SpinnerNumberModel) spinner.getModel()).setMaximum(max);
		final int value = ((Number) getValue()).intValue(); 
		if (value < min)
			spinner.setValue(min);
		else if (value > max)
			spinner.setValue(max);
	}

	public void setSelected(boolean b) {
		getCheckBox().setSelected(b);
		//getSpinner().setEnabled(!b);
	}

	public JCheckBox getCheckBox() {
		return checkbox;
	}

	public JSpinner getSpinner() {
		return spinner;
	}

	public boolean isSelected() {
		return getCheckBox().isSelected();
	}

	public Object getValue() {
		return getSpinner().getValue();
	}

	@Override
	public void setEnabled(final boolean b) {
		getCheckBox().setEnabled(b);
		getSpinner().setEnabled(b);
		if (label != null) {
			final Color fg = (b) ? getCheckBox().getForeground() : GuiUtils.getDisabledComponentColor();
			label.setForeground(fg);
		}
	}

	@Override
	public boolean isEnabled() {
		return getCheckBox().isEnabled();
	}

	@Override
	public void setToolTipText(final String text) {
		GuiUtils.addTooltip(this, text);
	}

}
