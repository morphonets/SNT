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

package sc.fiji.snt.seed;

import sc.fiji.snt.gui.GuiUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Modal seed editor with two modes:
 * <ul>
 *   <li><b>Single</b>: One seed, all nine fields (X, Y, Z, confidence, radius,
 *       channel, frame, type, source) are shown and editable; Save rebuilds an
 *       immutable {@link SeedPoint} and applies it via
 *       {@link SeedOverlay#replaceAt(int, SeedPoint)}; Delete removes the seed.
 *   <li><b>Bulk</b>: Rwo or more seeds (or "all" when no selection): coordinate
 *       fields (X, Y, Z) are hidden. Remaining fields are pre-filled from the
 *       first seed but only those the user actually edits are applied to every
 *       seed in the batch (others are preserved per seed). Delete removes all
 *       seeds in the batch after a confirmation.
 * </ul>
 * <p>
 * Entry points: {@link #editAt(Component, SeedOverlay, int)} for single mode,
 * {@link #editBulk(Component, SeedOverlay, List)} for bulk mode. Both
 * defensively re-resolve each seed by reference (via
 * {@link SeedOverlay#indexOf(SeedPoint)}) before mutation, in case the list
 * has shifted while the modal was open.
 *
 * @author Tiago Ferreira
 * @see SeedOverlay
 */
public class SeedPointEditDialog {

    private final SeedOverlay overlay;
    private final List<SeedPoint> originals; //Captured seeds at open time. Size 1: single mode; ≥2: bulk mode
    private final boolean bulk;
    private final JDialog dialog;

    // X/Y/Z are null in bulk mode (hidden from layout).
    private final JTextField xField;
    private final JTextField yField;
    private final JTextField zField;

    // Always present
    private final JTextField confField;
    private final JTextField radiusField;
    private final JTextField channelField;
    private final JTextField frameField;
    private final JTextField typeField;
    private final JTextField sourceField;

    /**
     * Bulk-mode only: fields the user has edited (via DocumentListener). On
     * Save, we additionally check the current text against {@link #initialTexts}
     * so a field that was edited and then reverted is treated as unchanged.
     */
    private final Set<JTextField> touched = new HashSet<>();
    /**
     * Bulk-mode only: initial text captured per editable field at construction
     * time. {@link #isFieldChanged(JTextField)} uses this to distinguish
     * "modified and committed" from "modified then reverted".
     */
    private final Map<JTextField, String> initialTexts = new HashMap<>();

    private SeedPointEditDialog(final Component parent, final SeedOverlay overlay, final List<Integer> indices) {
        this.overlay = overlay;
        this.bulk = indices.size() != 1;
        this.originals = new ArrayList<>(indices.size());
        for (final int i : indices) originals.add(overlay.get(i));

        final Window owner = (parent == null) ? null : SwingUtilities.getWindowAncestor(parent);
        final String title = bulk
                ? "Edit " + originals.size() + " Seed Points"
                : "Edit Seed Point #" + (indices.getFirst() + 1);
        dialog = new JDialog((Frame) (owner instanceof Frame ? owner : null), title, true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        final JPanel root = new JPanel(new GridBagLayout());
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        final GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.LINE_START;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.gridwidth = 1;
        g.gridx = 0;
        g.gridy = 0;

        final int SPACER = (int) GuiUtils.uiFontSize();
        if (bulk) {
            // Info banner spans both columns.
            g.gridwidth = 2;
            g.weightx = 1;
            g.insets.bottom += SPACER/2;
            final JLabel note = new JLabel("<html>Only fields you modify will be applied to all "
                    + originals.size() + " selected<br>seeds. Untouched fields are preserved per seed.</html>");
            root.add(note, g);
            g.gridy++;
            g.gridwidth = 1;
            g.insets.bottom -= SPACER/2;
        }

        final SeedPoint o = originals.getFirst(); // representative for pre-fill
        if (!bulk) {
            xField = addField(root, g, "X (physical):", String.format("%.6f", o.x));
            yField = addField(root, g, "Y (physical):", String.format("%.6f", o.y));
            zField = addField(root, g, "Z (physical):", String.format("%.6f", o.z));
        } else {
            xField = null;
            yField = null;
            zField = null;
        }
        confField = addField(root, g, "Confidence [0–1]:", String.format("%.4f", o.confidence));
        radiusField = addField(root, g, "Radius (physical, ≥0):", String.format("%.4f", o.radius));
        channelField = addField(root, g, "Channel (-1 = unset):", Integer.toString(o.channel));
        frameField = addField(root, g, "Frame (-1 = unset):", Integer.toString(o.frame));
        typeField = addField(root, g, "Type:", o.type);
        sourceField = addField(root, g, "Source:", o.source);

        if (bulk) {
            // Capture each editable field's initial text and attach the touch listener
            //  AFTER pre-fill so the listener doesn't fire on the  initial setText
            for (final JTextField f : Arrays.asList(confField, radiusField,
                    channelField, frameField, typeField, sourceField)) {
                initialTexts.put(f, f.getText());
                attachTouchListener(f);
            }
        }

        // Button row spans both columns
        g.gridx = 0;
        g.gridwidth = 2;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets.top += SPACER/2;
        root.add(buildButtonRow(), g);
        dialog.setContentPane(root);
        dialog.pack();
        if (owner != null) dialog.setLocationRelativeTo(owner);
    }

    /**
     * Opens a single-seed edit dialog for the seed at the given model-row
     * index of {@code overlay}. No-op if the index is out of range.
     */
    public static void editAt(final Component parent, final SeedOverlay overlay, final int index) {
        if (overlay == null || index < 0 || index >= overlay.size()) return;
        new SeedPointEditDialog(parent, overlay, List.of(index)).show();
    }

    /**
     * Opens a bulk edit dialog for all seeds at the given indices. If
     * {@code indices.size() == 1}, behaves like {@link #editAt}. No-op if no
     * valid indices remain after bounds-checking.
     */
    public static void editBulk(final Component parent, final SeedOverlay overlay, final List<Integer> indices) {
        if (overlay == null || indices == null || indices.isEmpty()) return;
        final List<Integer> valid = new ArrayList<>(indices.size());
        for (final int i : indices) {
            if (i >= 0 && i < overlay.size()) valid.add(i);
        }
        if (valid.isEmpty()) return;
        new SeedPointEditDialog(parent, overlay, valid).show();
    }

    private void show() {
        dialog.setVisible(true);
    }

    private static JTextField addField(final JPanel root, final GridBagConstraints g,
                                       final String labelText, final String initial) {
        g.gridx = 0;
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        final JLabel label = new JLabel(labelText);
        label.setHorizontalAlignment(SwingConstants.RIGHT);
        root.add(label, g);
        g.gridx = 1;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        final JTextField field = new JTextField(initial, 10);
        GuiUtils.addClearButton(field);
        root.add(field, g);
        g.gridy++;
        return field;
    }

    private void attachTouchListener(final JTextField f) {
        f.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(final DocumentEvent e) {
                touched.add(f);
            }

            @Override
            public void removeUpdate(final DocumentEvent e) {
                touched.add(f);
            }

            @Override
            public void changedUpdate(final DocumentEvent e) {
                touched.add(f);
            }
        });
    }

    private JPanel buildButtonRow() {
        final JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        final JButton deleteBtn = new JButton(bulk ? "Delete All" : "Delete");
        deleteBtn.setToolTipText(bulk
                ? "Remove all " + originals.size() + " seeds from the overlay"
                : "Remove this seed from the overlay");
        deleteBtn.addActionListener(e -> onDelete());
        p.add(deleteBtn);
        final JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dialog.dispose());
        final JButton saveBtn = new JButton("Apply");
        saveBtn.addActionListener(e -> apply());
        dialog.getRootPane().setDefaultButton(saveBtn);
        p.add(cancelBtn);
        p.add(saveBtn);
        return p;
    }

    // Actions
    private void onDelete() {
        final GuiUtils gu = new GuiUtils(dialog);
        final String msg = bulk ? "Delete " + originals.size() + " selected seed(s)?" : "Delete this seed?";
        final String title = bulk ? "Delete " + originals.size() + " Seeds?" : "Delete Seed?";
        if (!gu.getConfirmation(msg, title)) return;
        for (final SeedPoint o : originals) {
            // Defensive: re-resolve by reference, since the list may have shifted while the modal was open
            final int now = overlay.indexOf(o);
            if (now >= 0) overlay.removeAt(now);
            else overlay.remove(o);
        }
        dialog.dispose();
    }

    private void apply() {
        if (bulk) {
            applyBulk();
        } else {
            applySingle();
        }
    }

    private void applySingle() {
        final double x, y, z;
        final double conf, radius;
        final int channel, frame;
        try {
            x = GuiUtils.extractDouble(xField);
            y = GuiUtils.extractDouble(yField);
            z = GuiUtils.extractDouble(zField);
            conf = clamp01(GuiUtils.extractDouble(confField));
            radius = Math.max(0.0, GuiUtils.extractDouble(radiusField));
            channel = parseInt(channelField);
            frame = parseInt(frameField);
        } catch (final Exception ex) {
            new GuiUtils(dialog).error("Invalid input(s).");
            return;
        }
        final String type = typeField.getText().trim();
        final String source = sourceField.getText().trim();
        final SeedPoint original = originals.getFirst();
        final SeedPoint updated = new SeedPoint(x, y, z, conf, radius, channel, frame, type, source);
        final int now = overlay.indexOf(original);
        if (now >= 0) {
            overlay.replaceAt(now, updated);
        } else {
            // Seed no longer in the overlay (e.g., removed elsewhere): treat
            // save as a fresh add so the user doesn't lose their edits.
            overlay.add(updated);
        }
        dialog.dispose();
    }

    /**
     * Bulk save. Each field is "applied" only when its current text differs
     * from the initial text captured at construction time.
     * <p>
     * Updates are coalesced: a single {@link SeedOverlay#replaceAllAt} fires
     * the overlay listener once for the in-place replacements, and a single
     * {@link SeedOverlay#addAll} handles orphaned originals (seeds that were
     * removed elsewhere while the modal was open).
     */
    private void applyBulk() {
        // Quick exit: if no editable field actually changed value, there's nothing to do.
        final boolean cfChanged = isFieldChanged(confField);
        final boolean rdChanged = isFieldChanged(radiusField);
        final boolean chChanged = isFieldChanged(channelField);
        final boolean frChanged = isFieldChanged(frameField);
        final boolean tyChanged = isFieldChanged(typeField);
        final boolean srChanged = isFieldChanged(sourceField);
        if (!(cfChanged || rdChanged || chChanged || frChanged || tyChanged || srChanged)) {
            dialog.dispose();
            return;
        }
        // Parse all changed fields up front so invalid input doesn't leave a half-applied batch
        final Double newConf, newRadius;
        final Integer newChannel, newFrame;
        try {
            newConf = cfChanged ? clamp01(GuiUtils.extractDouble(confField)) : null;
            newRadius = rdChanged ? Math.max(0.0, GuiUtils.extractDouble(radiusField)) : null;
            newChannel = chChanged ? parseInt(channelField) : null;
            newFrame = frChanged ? parseInt(frameField) : null;
        } catch (final Exception ex) {
            new GuiUtils(dialog).error("Invalid input(s).");
            return;
        }
        final String newType = tyChanged ? typeField.getText().trim() : null;
        final String newSource = srChanged ? sourceField.getText().trim() : null;

        final List<SeedPoint> snapshot = overlay.list();
        final Map<SeedPoint, Integer> positions = new HashMap<>(snapshot.size() * 2);
        for (int i = 0; i < snapshot.size(); i++) positions.put(snapshot.get(i), i);

        final Map<Integer, SeedPoint> replacements = new HashMap<>(originals.size() * 2);
        final List<SeedPoint> orphanReplacements = new ArrayList<>();
        for (final SeedPoint o : originals) {
            final SeedPoint updated = new SeedPoint(
                    o.x, o.y, o.z,
                    newConf != null ? newConf : o.confidence,
                    newRadius != null ? newRadius : o.radius,
                    newChannel != null ? newChannel : o.channel,
                    newFrame != null ? newFrame : o.frame,
                    newType != null ? newType : o.type,
                    newSource != null ? newSource : o.source);
            final Integer pos = positions.get(o);
            if (pos != null) replacements.put(pos, updated);
            else orphanReplacements.add(updated); // seed removed elsewhere -> re-add
        }
        if (!replacements.isEmpty()) overlay.replaceAllAt(replacements);
        if (!orphanReplacements.isEmpty()) overlay.addAll(orphanReplacements);
        dialog.dispose();
    }

    /**
     * @return {@code true} iff bulk mode is active, the user has typed in
     *         {@code f}, and the current text differs from the value the
     *         dialog opened with. A field that was edited and then reverted
     *         to the original is treated as unchanged.
     */
    private boolean isFieldChanged(final JTextField f) {
        if (f == null || !touched.contains(f)) return false;
        final String init = initialTexts.get(f);
        return init == null || !init.equals(f.getText());
    }

    private static int parseInt(final JTextField field) {
       final double parsed = GuiUtils.extractDouble(field);
       return (Double.isNaN(parsed)) ? -1 : (int)parsed;
    }

    private static double clamp01(final double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
