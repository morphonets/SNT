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

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A row of labeled numeric fields, e.g. "X: [ ] Y: [ ] Z: [ ]".
 */
public class XYZFieldsPanel extends JPanel {

	private static final long serialVersionUID = 1L;
	private final JFormattedTextField[] fields;
	private final List<Runnable> changeListeners = new ArrayList<>();

	/**
	 * @param labels one label per field, e.g. {@code "X", "Y", "Z"}. Wraps to a new row after every 3 fields.
	 */
	public XYZFieldsPanel(final String... labels) {
		fields = new JFormattedTextField[labels.length];
		setLayout(new GridBagLayout());
		final GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = 0;
		c.weighty = 0;
		final int[] pos = { 0 };
		Arrays.stream(labels).forEach(label -> {
			if (pos[0] > 2) {
				c.gridx = 0;
				c.gridy++;
				c.insets.top = 10;
			}
			c.gridx++;
			c.weightx = 0;
			add(new JLabel(label + " "), c);
			c.gridx++;
			c.weightx = 0.5;
			fields[pos[0]] = createField();
			add(fields[pos[0]], c);
			pos[0]++;
		});
	}

	private JFormattedTextField createField() {
		// Locale-independent by construction (fixed '.' decimal point, no grouping separator), matching
		// getValue()/setValue() below (Double.parseDouble/String.valueOf, also always locale-independent).
		// NumberFormat.getNumberInstance() was tried first, but it is locale-dependent: Swing reformats the field's
		// text through it at commit/focus time, so on a  US-style locale "1024.0" could silently become "1,024"
		// (breaking Double.parseDouble, which does not accept grouping separators)
		final DecimalFormat format = new DecimalFormat("0.###", DecimalFormatSymbols.getInstance(Locale.US));
		format.setGroupingUsed(false);
		final JFormattedTextField field = new JFormattedTextField(format);
		field.setColumns(8);
		field.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(final DocumentEvent e) { fireChange(); }
			@Override
			public void removeUpdate(final DocumentEvent e) { fireChange(); }
			@Override
			public void changedUpdate(final DocumentEvent e) { fireChange(); }
		});
		return field;
	}

	private boolean fireScheduled;

	private void fireChange() {
		// Deferred via invokeLater, not run synchronously: this is called from inside DocumentListener callbacks, which
		// fire while the field's own Document already holds its write lock. A listener that then calls setValue(...)
		// (on this field or another one) would  try to mutate a Document while a mutation notification is in progress,
		// which AbstractDocument rejects (IllegalStateException). Deferring to the next EDT cycle runs listeners after
		// the triggering notification has fully unwound, so any resulting setValue(...) calls are safe.
		//
		// This panel has one Document/listener per field (X, Y, Z), all sharing the same changeListeners list. Setting
		// all three fields in a row (e.g. applying a preset) would otherwise queue one invokeLater per field. The flag
		// collapses any number of fireChange() calls made before the pending run fires into a single  listener pass
		if (fireScheduled) return;
		fireScheduled = true;
		SwingUtilities.invokeLater(() -> {
			fireScheduled = false;
			changeListeners.forEach(Runnable::run);
		});
	}

	/**
	 * Registers a listener to be run every time any field's text changes (every keystroke, not just on
	 * commit/focus-loss) - e.g. to recompute a live estimate shown elsewhere in the same dialog.
	 *
	 * @param listener the listener. Never receives the changed value directly; callers should re-read
	 *          whichever field(s) they care about via {@link #getValue(int, double)}.
	 */
	public void addChangeListener(final Runnable listener) {
		changeListeners.add(listener);
	}

	/**
	 * @return the number of fields in this panel
	 */
	public int nFields() {
		return fields.length;
	}

	/**
	 * @param field the field index
	 * @param defaultValue the value to return if the field is blank or unparsable
	 * @return the field's current numeric value
	 */
	public double getValue(final int field, final double defaultValue) {
		final String text = fields[field].getText();
		if (text == null || text.isBlank()) return defaultValue;
		try {
			return Double.parseDouble(text);
		} catch (final NumberFormatException ex) {
			return defaultValue;
		}
	}

	/**
	 * Sets a field's displayed value. A no-op if the field already displays this exact value
	 * (comparing text, not parsed value): since {@link #fireChange()} always re-fires listeners
	 * regardless of whether the new text actually differs, skipping a redundant
	 * {@code setText(...)} here is what stops a listener that calls this method (e.g. to reflect a
	 * recomputed value back into a read-only preview field) from looping forever.
	 *
	 * @param field the field index
	 * @param value the value to display
	 */
	public void setValue(final int field, final double value) {
		final String text = String.valueOf(value);
		if (!text.equals(fields[field].getText())) {
			fields[field].setText(text);
		}
	}

	/**
	 * Enables/disables every field in this panel (the labels stay visible either way, only the editable
	 * fields are toggled).
	 */
	public void setFieldsEnabled(final boolean enabled) {
		for (final JFormattedTextField field : fields) {
			field.setEnabled(enabled);
		}
	}

	/**
	 * @param field the field index
	 * @return the underlying Swing field, for callers needing direct access (tooltips, custom formatting, etc.)
	 */
	public JFormattedTextField getField(final int field) {
		return fields[field];
	}
}
