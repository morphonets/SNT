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

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import bdv.tools.boundingbox.BoxDisplayModePanel;
import bdv.tools.boundingbox.RealBoxSelectionPanel;
import bdv.tools.boundingbox.TransformedBoxEditor;
import bdv.tools.boundingbox.TransformedBoxEditor.BoxSourceType;
import bdv.tools.boundingbox.TransformedRealBoxModel;
import bdv.viewer.AbstractViewerPanel;
import bdv.viewer.ConverterSetups;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import net.imglib2.FinalRealInterval;
import net.imglib2.RealInterval;
import net.imglib2.realtransform.AffineTransform3D;

import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.TriggerBehaviourBindings;

import sc.fiji.snt.util.BoundingBox;
import sc.fiji.snt.util.PointInImage;

/**
 * Base of the {@link RegionSelector}s that rely on BDV's box editor: a draggable
 * box overlaid on the viewer, complemented by a panel of spinners/sliders. The
 * editor only needs an {@link AbstractViewerPanel}, which is the base of the
 * panels of both Bdv and Bvv, so subclasses just supply the viewer's own
 * plumbing. The editor is installed with {@link BoxSourceType#NONE}: BDV's stock
 * selection dialog adds a placeholder source to the viewer, forces the fused
 * display mode and makes the placeholder the current source. None of that is
 * wanted in a viewer that is also used for tracing
 *
 * @author Tiago Ferreira
 */
abstract class BoxRegionSelector implements RegionSelector {

	private final AbstractBigViewer viewer;

	// The selection dialog is kept so that a second request while one is
	// pending only brings the existing dialog to the front
	private JDialog pending;

	BoxRegionSelector(final AbstractBigViewer viewer) {
		this.viewer = viewer;
	}

	/** @return the viewer's panel, or null if unavailable */
	abstract AbstractViewerPanel viewerPanel();

	/** @return the viewer's converter setups, or null if unavailable */
	abstract ConverterSetups converterSetups();

	/** @return the viewer's trigger/behavior bindings, or null if unavailable */
	abstract TriggerBehaviourBindings triggerBindings();

	// If a config cannot be retrieved, BDV falls back to its own default triggers
	private static InputTriggerConfig inputConfig(final AbstractViewerPanel panel) {
		try {
			final InputTriggerConfig config = panel.getInputTriggerConfig();
			if (config != null) return config;
		} catch (final RuntimeException ignored) {
			// use the default below
		}
		return new InputTriggerConfig();
	}

	/** Viewer-specific setup of the editor, e.g., its projection */
	abstract void configureEditor(TransformedBoxEditor editor);

	/** Optional link between the box and what the viewer renders (see {@link BvvRegionSelector}) */
	interface DepthLimiter {
		/** @return the option to be added to the selection dialog */
		JCheckBox checkBox();

		/** Undoes whatever the option changed in the viewer */
		void dispose();
	}

	/** @return the viewer's depth limiter for the box, or null if the viewer has none */
	DepthLimiter depthLimiter(final TransformedRealBoxModel model) {
		return null;
	}

	@Override
	public void select(final BoundingBox initial, final Consumer<BoundingBox> onDone) {
		if (pending != null) {
			pending.toFront();
			return;
		}
		// Box handles and tracing share mouse input
		if (viewer.isPathInProgress()) {
			onDone.accept(null);
			return;
		}
		final AbstractViewerPanel viewerPanel = viewerPanel();
		final ConverterSetups setups = converterSetups();
		final TriggerBehaviourBindings bindings = triggerBindings();
		final SourceAndConverter<?> sac = (viewerPanel == null) ? null : currentSource(viewerPanel);
		if (sac == null || setups == null || bindings == null) {
			onDone.accept(null);
			return;
		}
		final RealInterval range = worldBounds(sac.getSpimSource());
		final RealInterval start = (initial == null) ? defaultBox(viewerPanel, range)
				: clip(toInterval(initial), range);

		final TransformedRealBoxModel model = new TransformedRealBoxModel(start, new AffineTransform3D());
		final TransformedBoxEditor editor = new TransformedBoxEditor(inputConfig(viewerPanel), viewerPanel, setups, 0,
				bindings, model, "selection", BoxSourceType.NONE);
		configureEditor(editor);
		editor.setEditable(true);
		editor.setFillIntersection(true);

		final DepthLimiter limiter = depthLimiter(model);
		final RealBoxSelectionPanel boxPanel = new RealBoxSelectionPanel(model, range);
		model.intervalChangedListeners().add(() -> {
			boxPanel.updateSliders(model.getInterval());
			viewerPanel.getDisplayComponent().repaint();
		});

		final Window owner = SwingUtilities.getWindowAncestor(viewerPanel);
		final Runnable[] cancelRef = { () -> {} }; // set once finish() exists
		final JDialog dialog = new JDialog(owner, "Select Region");
		dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		// Clicks must reach the box, not the tracer
		final Runnable resumeTracing = viewer.suspendTracing();
		final boolean[] finished = { false };
		// Viewer closed while selecting: nothing to select on anymore
		final WindowAdapter ownerListener = new WindowAdapter() {
			@Override
			public void windowClosing(final WindowEvent e) {
				cancelRef[0].run();
			}

			@Override
			public void windowClosed(final WindowEvent e) {
				cancelRef[0].run();
			}
		};
		final Consumer<Boolean> finish = accepted -> {
			if (finished[0]) return;
			finished[0] = true;
			final BoundingBox result = accepted ? toBoundingBox(model.getInterval()) : null;
			if (owner != null) owner.removeWindowListener(ownerListener);
			editor.uninstall();
			if (limiter != null) limiter.dispose();
			resumeTracing.run();
			pending = null;
			dialog.dispose();
			onDone.accept(result);
		};
		cancelRef[0] = () -> finish.accept(false);
		if (owner != null) owner.addWindowListener(ownerListener);

		final JButton ok = new JButton("OK");
		ok.addActionListener(e -> finish.accept(true));
		final JButton cancel = new JButton("Cancel");
		cancel.addActionListener(e -> finish.accept(false));
		dialog.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(final WindowEvent e) {
				finish.accept(false);
			}
		});

		final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 3));
		buttons.add(cancel);
		buttons.add(ok);
		final JPanel content = new JPanel(new BorderLayout(0, 4));
		content.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		content.add(boxPanel, BorderLayout.NORTH);
		final JPanel options = new JPanel(new BorderLayout(0, 2));
		options.add(new BoxDisplayModePanel(editor.boxDisplayMode()), BorderLayout.NORTH);
		if (limiter != null) options.add(limiter.checkBox(), BorderLayout.SOUTH);
		content.add(options, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);
		dialog.setContentPane(content);
		dialog.getRootPane().setDefaultButton(ok);
		dialog.pack();
		dialog.setLocationRelativeTo(owner);

		pending = dialog;
		editor.install();
		dialog.setVisible(true);
		viewerPanel.requestRepaint();
	}

	private static SourceAndConverter<?> currentSource(final AbstractViewerPanel viewerPanel) {
		final SourceAndConverter<?> current = viewerPanel.state().getCurrentSource();
		if (current != null) return current;
		final var sources = viewerPanel.state().getSources();
		return sources.isEmpty() ? null : sources.getFirst();
	}

	/** World-space bounds of the source (level 0, first time point) */
	private static RealInterval worldBounds(final Source<?> source) {
		final AffineTransform3D t = new AffineTransform3D();
		source.getSourceTransform(0, 0, t);
		final net.imglib2.Interval itv = source.getSource(0, 0);
		final double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		final double[] max = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		final double[] corner = new double[3];
		final double[] world = new double[3];
		for (int i = 0; i < 8; i++) {
			for (int d = 0; d < 3; d++)
				corner[d] = ((i >> d) & 1) == 0 ? itv.realMin(d) : itv.realMax(d);
			t.apply(corner, world);
			for (int d = 0; d < 3; d++) {
				min[d] = Math.min(min[d], world[d]);
				max[d] = Math.max(max[d], world[d]);
			}
		}
		return new FinalRealInterval(min, max);
	}

	/**
	 * Cubic box centered on the view, with sides of half the longest visible
	 * extent, limited to the source bounds
	 */
	private static RealInterval defaultBox(final AbstractViewerPanel viewerPanel, final RealInterval range) {
		final AffineTransform3D vt = viewerPanel.state().getViewerTransform();
		final double w = viewerPanel.getDisplayComponent().getWidth();
		final double h = viewerPanel.getDisplayComponent().getHeight();
		final double[] c0 = new double[3];
		final double[] c1 = new double[3];
		vt.applyInverse(c0, new double[] { 0, 0, 0 });
		vt.applyInverse(c1, new double[] { w, h, 0 });
		double visible = 0;
		final double[] center = new double[3];
		for (int d = 0; d < 3; d++) {
			visible = Math.max(visible, Math.abs(c1[d] - c0[d]));
			center[d] = (c0[d] + c1[d]) / 2;
		}
		final double half = visible / 4;
		final double[] min = new double[3];
		final double[] max = new double[3];
		for (int d = 0; d < 3; d++) {
			// A view centered outside the data: use the data's center instead
			if (center[d] < range.realMin(d) || center[d] > range.realMax(d))
				center[d] = (range.realMin(d) + range.realMax(d)) / 2;
			min[d] = center[d] - half;
			max[d] = center[d] + half;
		}
		return clip(new FinalRealInterval(min, max), range);
	}

	private static RealInterval clip(final RealInterval box, final RealInterval range) {
		final double[] min = new double[3];
		final double[] max = new double[3];
		for (int d = 0; d < 3; d++) {
			min[d] = Math.max(box.realMin(d), range.realMin(d));
			max[d] = Math.min(box.realMax(d), range.realMax(d));
			if (max[d] <= min[d]) { // no overlap on this axis: use the whole axis
				min[d] = range.realMin(d);
				max[d] = range.realMax(d);
			}
		}
		return new FinalRealInterval(min, max);
	}

	private static RealInterval toInterval(final BoundingBox box) {
		final PointInImage o = box.origin();
		final PointInImage e = box.originOpposite();
		return new FinalRealInterval(
				new double[] { Math.min(o.x, e.x), Math.min(o.y, e.y), Math.min(o.z, e.z) },
				new double[] { Math.max(o.x, e.x), Math.max(o.y, e.y), Math.max(o.z, e.z) });
	}

	private static BoundingBox toBoundingBox(final RealInterval itv) {
		final BoundingBox box = new BoundingBox();
		box.setOrigin(new PointInImage(itv.realMin(0), itv.realMin(1), itv.realMin(2)));
		box.setOriginOpposite(new PointInImage(itv.realMax(0), itv.realMax(1), itv.realMax(2)));
		return box;
	}

}
