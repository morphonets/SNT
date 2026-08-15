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

import ij.measure.Calibration;
import sc.fiji.snt.Path;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.Tree;
import sc.fiji.snt.util.BoundingBox;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTPoint;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An interactive prompt for {@link SNTUI}'s "Materialize Region" command (Stream mode only): lets the user define the
 * crop's size, center, and padding, with a live dimension/RAM estimate, then resolves the whole configuration into one
 * {@link BoundingBox} for  {@link SNT#materializeDisplayCanvas(BoundingBox)}.
 */
public class MaterializeRegionDialog extends JDialog {

	private static final long serialVersionUID = 1L;

	// Used to warn that materialization of a streamed crop may take a while
	public static final long LARGE_MATERIALIZATION_WARN_BYTES = 500_000_000L; // 500 MB
	public static final String LARGE_MATERIALIZATION_WARN_BYTES_STRING = "500MB";

	private static final String SIZE_AUTO_ALL = "Auto-bounds: All paths";
	private static final String SIZE_AUTO_SELECTED = "Auto-bounds: Selected paths";
	private static final String SIZE_OTHER = "Other...";

	private static final String SCOPE_MAIN = "Main image (CT position being traced)";
	private static final String SCOPE_SECONDARY = "Secondary layer";

	// To add a preset: Add one entry here
	private static final Map<String, int[]> SIZE_PRESETS = new LinkedHashMap<>();
	static {
		SIZE_PRESETS.put("Preset: 1024x1024x512 px", new int[] { 1024, 1024, 512 });
		SIZE_PRESETS.put("Preset: 2048x2048x512 px", new int[] { 2048, 2048, 512 });
		SIZE_PRESETS.put("Preset: 1024x1024x1024 px", new int[] { 1024, 1024, 1024 });
		SIZE_PRESETS.put("Preset: 2048x2048x1024 px", new int[] { 2048, 2048, 1024 });
	}

	private static final String CENTER_AUTO = "Auto";
	private static final String CENTER_SCENE = "Center of scene (not yet implemented; uses Auto)";
	private static final String CENTER_OTHER = "Other...";

	// Wide enough for this dialog's own content (three X/Y/Z fields plus labels) without forcing the
	// window wider just to fit a one-line status message: long messages wrap onto further lines
	// instead of being clipped or growing the dialog every time updateEstimate() runs
	private static final int STATUS_LABEL_MAX_WIDTH = 420;

	// Below this, in XY only, the resolved crop is unlikely to provide useful editing context (e.g.
	// a single-node selection with no padding resolves to a valid but 1-2 px cube). Z is deliberately
	// excluded: a thin/2D slab (e.g. depth 1) is a legitimate, intentional choice
	private static final long MIN_USEFUL_XY_PX = 100;

	private final SNT plugin;
	private boolean succeeded;
	private BoundingBox resolvedBox;
	// The last successfully resolved estimate from updateEstimate(), i.e. exactly what will be read
	// if "Materialize" is pressed right now: -1 if not yet resolved to a valid estimate
	private long estimatedBytes = -1;
	// Guards against re-entrant calls: updateEstimate() writes resolved values back into read-only preview fields
	// via XYZFieldsPanel#setValue(...), which itself fires the very same change listener that calls updateEstimate()
	// without this guard that would recurse.
	private boolean updatingEstimate;

	// Lazily-computed bounding boxes of "all paths"/"selected paths", in raw (uncalibrated-spacing) world units, i.e.
	// before setSpacing()/expand() are applied by callers. This dialog is modal, so the path selection cannot change
	// while it is open recomputing. Computed once, reused for the rest of this dialog's lifetime. null means
	// "no such paths" (not "not yet computed")
	private BoundingBox allPathsBox;
	private BoundingBox selectedPathsBox;

	private final JComboBox<String> scopeCombo;
	private final JComboBox<String> sizeCombo;
	private final XYZFieldsPanel sizeFields; // pixel units
	private final JComboBox<String> centerCombo;
	private final XYZFieldsPanel centerFields; // calibrated units
	private final XYZFieldsPanel paddingFields; // calibrated units
	private final JLabel statusLabel;
	private final JCheckBox fastMaterializationCheckBox;
	private final JButton okButton;
	private final JButton cancelButton;

	public MaterializeRegionDialog(final SNTUI ui, final SNT plugin) {
		super(ui, "Materialize Region...", true);
		this.plugin = plugin;

		final List<String> scopeItems = new ArrayList<>(List.of(SCOPE_MAIN));
		if (plugin.isSecondaryDataAvailable()) scopeItems.add(SCOPE_SECONDARY);
		scopeCombo = new JComboBox<>(scopeItems.toArray(new String[0]));
		scopeCombo.setToolTipText("<HTML><div WIDTH=500>Which data source to crop pixel data from. " +
				"To materialize a different channel, frame, or dataset, first make it the one being traced " +
				"(i.e., switch the active source in BDV/BVV and start a path on it), then reopen this dialog.");

		final List<String> sizeItems = new ArrayList<>(List.of(SIZE_AUTO_ALL, SIZE_AUTO_SELECTED));
		sizeItems.addAll(SIZE_PRESETS.keySet());
		sizeItems.add(SIZE_OTHER);
		sizeCombo = new JComboBox<>(sizeItems.toArray(new String[0]));
		sizeFields = new XYZFieldsPanel("X (px)", "Y (px)", "Z (px)");
		centerCombo = new JComboBox<>(new String[] { CENTER_AUTO, CENTER_SCENE, CENTER_OTHER });
		final String unit = plugin.getSpacingUnits();
		centerFields = new XYZFieldsPanel("X (" + unit + ")", "Y (" + unit + ")", "Z (" + unit + ")");
		paddingFields = new XYZFieldsPanel("X (" + unit + ")", "Y (" + unit + ")", "Z (" + unit + ")");
		statusLabel = new JLabel();
		fastMaterializationCheckBox = new JCheckBox("Fast materialization");
		fastMaterializationCheckBox.setToolTipText("<HTML><div WIDTH=500>Copies the crop's pixel data in a single, " +
				"multithreaded pass, instead of the slower (but more thoroughly tested) two-pass copy used otherwise." +
				"<br>" +
				"Falls back to the slow copy automatically if the fast one fails for any reason. Uncheck if a " +
				"materialized crop is ever suspected of being wrong.");
		loadPrefs(ui.getPrefs());

		if (pathAndFillManager().getSelectedPaths().isEmpty()) {
			sizeCombo.removeItem(SIZE_AUTO_SELECTED);
		}

		okButton = new JButton("Materialize");
		okButton.addActionListener(e -> {
			final StringBuilder error = new StringBuilder();
			resolvedBox = resolveBoundingBox(error);
			if (resolvedBox == null) {
				new GuiUtils(this).error(error.toString(), "Invalid Region");
				return;
			}
			succeeded = true;
			savePrefs(ui.getPrefs());
			dispose();
		});
		cancelButton = new JButton("Cancel");
		cancelButton.addActionListener(e -> {
			succeeded = false;
			dispose();
		});

		scopeCombo.addActionListener(e -> updateEstimate());
		sizeCombo.addActionListener(e -> sizeChoiceChanged());
		centerCombo.addActionListener(e -> centerChoiceChanged());
		sizeFields.addChangeListener(this::updateEstimate);
		centerFields.addChangeListener(this::updateEstimate);
		paddingFields.addChangeListener(this::updateEstimate);

		assembleDialog();
		sizeChoiceChanged(); // also triggers the first updateEstimate()
		setLocationRelativeTo(ui);
		setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		pack();
		setVisible(true);
	}

	private PathAndFillManager pathAndFillManager() {
		return plugin.getPathAndFillManager();
	}

	/**
	 * @return the bounding box of every loaded path, or {@code null} if there are none
	 */
	private BoundingBox allPathsBox() {
		if (allPathsBox == null) {
			final Collection<Path> paths = pathAndFillManager().getPaths();
			if (!paths.isEmpty()) allPathsBox = new Tree(new ArrayList<>(paths)).getBoundingBox();
		}
		return allPathsBox;
	}

	/**
	 * @return the bounding box of the currently selected paths, or {@code null} if none are selected
	 */
	private BoundingBox selectedPathsBox() {
		if (selectedPathsBox == null) {
			final Collection<Path> paths = pathAndFillManager().getSelectedPaths();
			if (paths != null && !paths.isEmpty()) selectedPathsBox = new Tree(new ArrayList<>(paths)).getBoundingBox();
		}
		return selectedPathsBox;
	}

	private void assembleDialog() {
		final JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 3));
		buttonsPanel.add(cancelButton);
		buttonsPanel.add(okButton);

		setLayout(new GridBagLayout());
		final GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 10, 4, 10);
		c.gridx = 0;
		c.gridy = 0;
		c.anchor = GridBagConstraints.LINE_START;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;

		add(section("Bounding Box Dimensions", sizeCombo, sizeFields), c);
		c.gridy++;
		add(section("Bounding Box Center", centerCombo, centerFields), c);
		c.gridy++;
		add(section("Padding", null, paddingFields), c);
		c.gridy++;
		add(section("Scope", scopeCombo, null), c);
		c.gridy++;
		add(fastMaterializationCheckBox, c);
		c.gridy++;
		c.insets.top = 10;
		add(statusLabel, c);
		c.gridy++;
		c.insets.top = 14;
		add(buttonsPanel, c);
	}

	private JPanel section(final String title, final JComboBox<String> combo, final XYZFieldsPanel fields) {
		final JPanel panel = new JPanel(new BorderLayout(0, 4));
		panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title),
				BorderFactory.createEmptyBorder(2, 2, 2, 2)));
		if (combo != null) panel.add(combo, BorderLayout.NORTH);
		if (fields != null) panel.add(fields, BorderLayout.CENTER);
		return panel;
	}

	private void sizeChoiceChanged() {
		final String choice = (String) sizeCombo.getSelectedItem();
		final boolean isOther = SIZE_OTHER.equals(choice);
		sizeFields.setFieldsEnabled(isOther);
		final int[] preset = SIZE_PRESETS.get(choice);
		if (preset != null) {
			sizeFields.setValue(0, preset[0]);
			sizeFields.setValue(1, preset[1]);
			sizeFields.setValue(2, preset[2]);
		}
		final boolean autoBounds = SIZE_AUTO_ALL.equals(choice) || SIZE_AUTO_SELECTED.equals(choice);
		centerCombo.setEnabled(!autoBounds);
		// setSelectedItem(...) only fires centerCombo's own listener (which itself calls
		// centerChoiceChanged()) if the selection actually changes; call it explicitly only in the
		// cases where that won't happen, so a single choice change never triggers two
		// updateEstimate() passes (each a full bounding-box resolution)
		if (autoBounds && !CENTER_AUTO.equals(centerCombo.getSelectedItem())) {
			centerCombo.setSelectedItem(CENTER_AUTO);
		} else {
			centerChoiceChanged();
		}
	}

	private void centerChoiceChanged() {
		final String choice = (String) centerCombo.getSelectedItem();
		centerFields.setFieldsEnabled(centerCombo.isEnabled() && CENTER_OTHER.equals(choice));
		updateEstimate();
	}

	private void updateEstimate() {
		if (updatingEstimate) return; // see field javadoc: setValue(...) below re-fires this listener
		updatingEstimate = true;
		try {
			final StringBuilder error = new StringBuilder();
			final BoundingBox box = resolveBoundingBox(error);
			if (box == null) {
				setStatus(error.toString(), GuiUtils.errorColor());
				okButton.setEnabled(false);
				return;
			}
			// Resolve against the loaded source's own bounds (not just the requested region) so the
			// estimate always reflects what will actually be produced: a region that partially exceeds
			// the source is silently trimmed by materializeDisplayCanvas() itself (independently per
			// axis/side), and one that misses the source entirely is rejected outright. Without this,
			// the dialog could show/allow a region that either overstates the real result (partial
			// overlap - the estimate here would show the full requested size, not the smaller actual
			// one) or fails only after the user commits (no overlap at all)
			final Calibration cal = plugin.getCalibration();
			final SNT.VoxelBounds voxelBounds;
			try {
				voxelBounds = plugin.resolveVoxelBounds(box, cal, isSecondaryLayerScope());
			} catch (final IllegalStateException | IllegalArgumentException ex) {
				setStatus(ex.getMessage(), GuiUtils.errorColor());
				okButton.setEnabled(false);
				return;
			}
			final long width = voxelBounds.max()[0] - voxelBounds.min()[0] + 1;
			final long height = voxelBounds.max()[1] - voxelBounds.min()[1] + 1;
			final long depth = voxelBounds.max()[2] - voxelBounds.min()[2] + 1;
			// Reflect the resolved size back into sizeFields ONLY for the Auto-bounds choices, where
			// sizeFields is a pure read-only preview never consulted by resolveBoundingBox(). For
			// Preset/Other, sizeFields is resolveBoundingBox()'s own INPUT (the pre-padding size)
			// "dims" here already has padding baked in (box.expand(...) was applied above), so writing
			// it back would feed the padded size into the next pass as if it were the new input,
			// adding padding again on top of padding, forever!!
			final String sizeChoice = (String) sizeCombo.getSelectedItem();
			final boolean autoBoundsSize = SIZE_AUTO_ALL.equals(sizeChoice) || SIZE_AUTO_SELECTED.equals(sizeChoice);
			if (autoBoundsSize) {
				sizeFields.setValue(0, width);
				sizeFields.setValue(1, height);
				sizeFields.setValue(2, depth);
			}
			if (!(centerCombo.isEnabled() && CENTER_OTHER.equals(centerCombo.getSelectedItem()))) {
				final SNTPoint centroid = box.getCentroid();
				centerFields.setValue(0, centroid.getX());
				centerFields.setValue(1, centroid.getY());
				centerFields.setValue(2, centroid.getZ());
			}
			final long bytesNeeded = plugin.estimateMaterializationBytes(width, height, depth);
			estimatedBytes = bytesNeeded;
			final long bytesAvailable = plugin.getMaterializationMemoryBudget();
			// Neither condition below is an error, both describe a valid, resolvable crop the user may still genuinely
			// want - so they only ever affect the message/color, never okButton.
			// voxelBounds.clamped(): the requested region (size/center/padding combined) reaches past
			// the loaded source on at least one side; the numbers above are already the real, trimmed
			// result, not the requested one, but the user should still know their request didn't fully
			// fit. Small XY: e.g. a single-node selection with no padding resolves to a valid but 1-2 px
			// cube - technically correct but unlikely to provide useful editing context. Z is deliberately not checked
			final List<String> notes = new ArrayList<>();
			if (voxelBounds.clamped()) notes.add("reached the loaded data's edge");
			if (width < MIN_USEFUL_XY_PX || height < MIN_USEFUL_XY_PX) {
				notes.add("XY smaller than " + MIN_USEFUL_XY_PX + " px; may be too small for useful editing context");
			}
			final String dimsMsg = String.format("Estimated crop: %dx%dx%d px%s", width, height, depth,
					notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")");
			final Color normalColor = notes.isEmpty() ? getForeground() : GuiUtils.linkColor();
			if (bytesNeeded < 0) {
				setStatus(dimsMsg + " (RAM estimate unavailable)", normalColor);
				okButton.setEnabled(true);
			} else if (bytesNeeded > bytesAvailable) {
				setStatus(String.format("%s, ~%.2f GB: exceeds the %.2f GB available. Reduce size/padding.",
						dimsMsg, bytesNeeded / 1e9, bytesAvailable / 1e9), GuiUtils.errorColor());
				okButton.setEnabled(false);
			} else {
				setStatus(String.format("%s, ~%.2f GB. Pixel data will be copied into memory.", dimsMsg,
						bytesNeeded / 1e9), normalColor);
				okButton.setEnabled(true);
			}
		} finally {
			updatingEstimate = false;
		}
	}

	private void setStatus(final String text, final Color color) {
		statusLabel.setForeground(color);
		statusLabel.setText(GuiUtils.getWrappedText(statusLabel, text, STATUS_LABEL_MAX_WIDTH));
	}

	/**
	 * Resolves the current dialog configuration into a single {@link BoundingBox}, in world/calibrated
	 * units, ready for {@link SNT#materializeDisplayCanvas(BoundingBox)}. Used by both the live status
	 * estimate and the OK button, so they can never disagree.
	 *
	 * @param errorOut populated with a user-facing message if resolution fails
	 * @return the resolved box, or {@code null} if the current configuration is invalid
	 */
	private BoundingBox resolveBoundingBox(final StringBuilder errorOut) {
		final Calibration cal = plugin.getCalibration();
		final String sizeChoice = (String) sizeCombo.getSelectedItem();
		final BoundingBox box;
		if (SIZE_AUTO_ALL.equals(sizeChoice) || SIZE_AUTO_SELECTED.equals(sizeChoice)) {
			final boolean fromSelection = SIZE_AUTO_SELECTED.equals(sizeChoice);
			final BoundingBox cached = fromSelection ? selectedPathsBox() : allPathsBox();
			if (cached == null) {
				errorOut.append(fromSelection ? "No paths are currently selected."
						: "No paths exist to compute a bounding box from.");
				return null;
			}
			box = cached.clone(); // never mutate the cache: setSpacing()/expand() below are in-place
			box.setSpacing(cal.pixelWidth, cal.pixelHeight, cal.pixelDepth, cal.getUnit());
		} else {
			final double sizeXPx = sizeFields.getValue(0, 0);
			final double sizeYPx = sizeFields.getValue(1, 0);
			final double sizeZPx = sizeFields.getValue(2, 0);
			if (sizeXPx <= 0 || sizeYPx <= 0 || sizeZPx <= 0) {
				errorOut.append("Size must be a positive number of pixels along each axis.");
				return null;
			}
			final SNTPoint center = resolveCenter(errorOut);
			if (center == null) return null;
			final double halfWidth = sizeXPx * cal.pixelWidth / 2;
			final double halfHeight = sizeYPx * cal.pixelHeight / 2;
			final double halfDepth = sizeZPx * cal.pixelDepth / 2;
			box = new BoundingBox();
			box.setSpacing(cal.pixelWidth, cal.pixelHeight, cal.pixelDepth, cal.getUnit());
			box.setOrigin(new PointInImage(center.getX() - halfWidth, center.getY() - halfHeight,
					center.getZ() - halfDepth));
			box.setOriginOpposite(new PointInImage(center.getX() + halfWidth, center.getY() + halfHeight,
					center.getZ() + halfDepth));
		}
		final double padX = paddingFields.getValue(0, 0);
		final double padY = paddingFields.getValue(1, 0);
		final double padZ = paddingFields.getValue(2, 0);
		if (padX < 0 || padY < 0 || padZ < 0) {
			errorOut.append("Padding must be a non-negative number.");
			return null;
		}
		box.expand(padX, padY, padZ);
		return box;
	}

	/**
	 * @return the center to use for a fixed-size (Preset/Other) region, in world/calibrated units, or
	 *         {@code null} if it cannot be resolved (error message appended to {@code errorOut})
	 */
	private SNTPoint resolveCenter(final StringBuilder errorOut) {
		final String choice = (String) centerCombo.getSelectedItem();
		if (CENTER_OTHER.equals(choice)) {
			return new PointInImage(centerFields.getValue(0, 0), centerFields.getValue(1, 0),
					centerFields.getValue(2, 0));
		}
		// CENTER_AUTO and the still-stubbed CENTER_SCENE (falls back to Auto until viewport-center math
		// is implemented) both resolve to the centroid of the selected paths if any are
		// selected, else every loaded path, mirroring materializeDisplayCanvas()'s own selected-else-all
		// fallback. Uses the same memoized boxes as the size section, so this never re-traverses paths
		final BoundingBox box = selectedPathsBox() != null ? selectedPathsBox() : allPathsBox();
		if (box == null) {
			errorOut.append("No paths exist to center the region on.");
			return null;
		}
		return box.getCentroid();
	}

	private void loadPrefs(final SNTPrefs prefs) {
		paddingFields.setValue(0, Double.parseDouble(prefs.get("mrd.xpad", "10")));
		paddingFields.setValue(1, Double.parseDouble(prefs.get("mrd.ypad", "10")));
		paddingFields.setValue(2, Double.parseDouble(prefs.get("mrd.zpad", "10")));
		fastMaterializationCheckBox.setSelected(prefs.isFastCropMaterializationEnabled());
	}

	private void savePrefs(final SNTPrefs prefs) {
		prefs.set("mrd.xpad", "" + paddingFields.getValue(0, 10));
		prefs.set("mrd.ypad", "" + paddingFields.getValue(1, 10));
		prefs.set("mrd.zpad", "" + paddingFields.getValue(2, 10));
		prefs.setFastCropMaterialization(fastMaterializationCheckBox.isSelected());
	}

	/**
	 * @return {@code true} if the user confirmed the dialog (clicked "Materialize" with a valid
	 *         configuration), {@code false} if canceled/dismissed
	 */
	public boolean succeeded() {
		return succeeded;
	}

	/**
	 * @return the resolved region, in world/calibrated units, ready for
	 *         {@link SNT#materializeDisplayCanvas(BoundingBox)}. Only valid if {@link #succeeded()}.
	 */
	public BoundingBox getResolvedBoundingBox() {
		return resolvedBox;
	}

	/**
	 * @return true if "Scope" is set to the secondary (filtered) image, false for the main streamed
	 *         source (the default, and the only option when no secondary image is loaded).
	 */
	public boolean isSecondaryLayerScope() {
		return SCOPE_SECONDARY.equals(scopeCombo.getSelectedItem());
	}

	/**
	 * @return the estimated size, in bytes, of the region returned by {@link #getResolvedBoundingBox()}
	 *         - i.e. exactly what {@link SNT#estimateMaterializationBytes(long, long, long)} reported
	 *         for it the moment "Materialize" was pressed - or -1 if unavailable. Only valid if
	 *         {@link #succeeded()}. Callers wanting to warn about a long-running materialization
	 *         should use this rather than recomputing the estimate themselves.
	 */
	public long getEstimatedBytes() {
		return estimatedBytes;
	}
}
