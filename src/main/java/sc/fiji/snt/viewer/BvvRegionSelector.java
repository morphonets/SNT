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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt.viewer;

import javax.swing.JCheckBox;
import javax.swing.Timer;

import bdv.tools.boundingbox.TransformedBoxEditor;
import bdv.tools.boundingbox.TransformedRealBoxModel;
import bdv.viewer.AbstractViewerPanel;
import bdv.viewer.ConverterSetups;
import bvv.core.VolumeViewerFrame;
import bvv.core.VolumeViewerPanel;
import net.imglib2.RealInterval;

import org.scijava.ui.behaviour.util.TriggerBehaviourBindings;

/**
 * {@link RegionSelector} for {@link Bvv}: a draggable box overlaid on the
 * viewer, see {@link BoxRegionSelector}. BVV cannot clip its rendering to a box
 * (only along the viewing direction, see {@link Bvv#setSlab(double, double)}),
 * so the box is an overlay only. The overlay uses the camera distance of the
 * viewer so that it is drawn with the same perspective as the volume
 *
 * @author Tiago Ferreira
 */
public class BvvRegionSelector extends BoxRegionSelector {

	private static final double DEFAULT_CAMERA_DISTANCE = 1000;
	// Dragging a box fires many changes: the slab is only updated once they settle
	private static final int DEBOUNCE_MS = 200;

	private final Bvv bvv;

	BvvRegionSelector(final Bvv bvv) {
		super(bvv);
		this.bvv = bvv;
	}

	@Override
	AbstractViewerPanel viewerPanel() {
		return bvv.getViewerPanel();
	}

	@Override
	ConverterSetups converterSetups() {
		final VolumeViewerPanel panel = bvv.getViewerPanel();
		return (panel == null) ? null : panel.getConverterSetups();
	}

	@Override
	TriggerBehaviourBindings triggerBindings() {
		final VolumeViewerFrame frame = bvv.getViewerFrame();
		return (frame == null) ? null : frame.getTriggerbindings();
	}

	@Override
	void configureEditor(final TransformedBoxEditor editor) {
		final double[] cam = bvv.getCamParams();
		final double dCam = (cam != null && cam.length > 0 && cam[0] > 0) ? cam[0] : DEFAULT_CAMERA_DISTANCE;
		editor.setPerspective(1, dCam); // eye at dCam from the screen plane, as in BVV's own projection
	}

	// Slab rendering along the viewing axis is the only clipping BVV supports, so the box depth is
	// mapped to a slab (canonical XY, XZ and ZY views only). The prior slab is restored on exit
	@Override
	DepthLimiter depthLimiter(final TransformedRealBoxModel model) {
		final double[] prior = bvv.getSlab();
		final int priorAxis = bvv.getSlabAxis();
		final boolean[] applied = { false };
		final JCheckBox check = new JCheckBox("Limit view to box depth", false);
		check.setToolTipText("<HTML>Renders only the depth range of the box. Requires a view aligned to the<br>"
				+ "XY, XZ or ZY plane. The previous view is restored when the selection ends");
		final Runnable restore = () -> {
			if (!applied[0]) return;
			applied[0] = false;
			try {
				if (prior != null && priorAxis >= 0 && bvv.getSlabAxis() == priorAxis)
					bvv.setSlab((prior[0] + prior[1]) / 2, prior[1] - prior[0]);
				else
					bvv.clearSlab();
			} catch (final RuntimeException ex) {
				bvv.clearSlab();
			}
		};
		final Runnable apply = () -> {
			final int axis = bvv.getSlabAxis();
			if (axis < 0) return; // view was rotated away from a canonical plane
			final RealInterval box = model.getInterval();
			try {
				bvv.setSlab((box.realMin(axis) + box.realMax(axis)) / 2, box.realMax(axis) - box.realMin(axis));
				applied[0] = true;
			} catch (final RuntimeException ignored) {
				// degenerate box or view changed meanwhile: keep the current slab
			}
		};
		final Timer debounce = new Timer(DEBOUNCE_MS, e -> apply.run());
		debounce.setRepeats(false);
		model.intervalChangedListeners().add(() -> {
			if (check.isSelected()) debounce.restart();
		});
		check.addActionListener(e -> {
			if (!check.isSelected()) {
				debounce.stop();
				restore.run();
			} else if (bvv.getSlabAxis() < 0) {
				check.setSelected(false);
				bvv.getGuiUtils().error("The view must be aligned to the XY, XZ or ZY plane.");
			} else {
				apply.run();
			}
		});
		return new DepthLimiter() {
			@Override
			public JCheckBox checkBox() {
				return check;
			}

			@Override
			public void dispose() {
				debounce.stop();
				restore.run();
			}
		};
	}

}
