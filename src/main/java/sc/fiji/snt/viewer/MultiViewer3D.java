/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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

import org.jzy3d.plot3d.rendering.canvas.CanvasAWT;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.io.MouseLightLoader;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Class for rendering individual {@link Viewer3D}s as a multi-panel montage.
 *
 * @author Tiago Ferreira
 */
public class MultiViewer3D {

	private static final Color BKG_LIGHT = new Color(242, 242, 242);
	private static final Color BKG_DARK = new Color(70, 73, 75);

	private final List<Viewer3D> viewers;
	private int gridCols;
	private int gridRows;
	private String label;
	private int gap;

	/**
	 * Assembles a multi-panel viewer from a list of viewers (1 per panel)
	 * @param viewers the list of Viewers
	 */
	public MultiViewer3D(final List<Viewer3D> viewers) {
		if (viewers == null || viewers.isEmpty())
			throw new IllegalArgumentException("Cannot instantiate a grid from a null/empty list of viewers");
		this.viewers = viewers;
		guessLayout();
		setGap(10);
	}

	/**
	 * Assembles a multi-panel viewer from a list of trees (1 per panel)
	 * @param trees the group of trees
	 */
	public MultiViewer3D(final Collection<Tree> trees) {
		if (trees == null || trees.isEmpty())
			throw new IllegalArgumentException("Cannot instantiate a grid from a null/empty list of trees");
		viewers = new ArrayList<>();
		trees.forEach(tree -> {
			final Viewer3D v = new Viewer3D();
			v.add(tree);
			v.addLabel(tree.getLabel());
			v.setEnableDebugMode(false);
			viewers.add(v);
		});
		guessLayout();
		setGap(10);
	}

	private void guessLayout() {
		if (viewers.size() < 10) {
			gridCols = viewers.size();
			gridRows = 1;
		} else {
			gridCols = viewers.size() / 2;
			adjustGridRows();
		}
	}

	private void adjustGridRows() {
		if (gridCols >= viewers.size())
			gridRows = 1;
		else {
			gridRows = 0;
			for (int start = 0; start < viewers.size(); start += gridCols) {
				gridRows++;
			}
		}
	}

	public List<Viewer3D> viewers() {
		return viewers;
	}

	public void setAnimationEnabled(final boolean enabled) {
		viewers.forEach(v -> v.setAnimationEnabled(enabled));
	}

	public void setViewMode(final Viewer3D.ViewMode view) {
		viewers.forEach(v -> v.setViewMode(view));
	}

	public void setLabels(final List<String> labels) {
		if (labels == null) {
			viewers.forEach(v -> v.addLabel(null));
		} else {
			for (int i = 0; i < viewers.size(); i++)
				viewers.get(i).addLabel(labels.get(i));
		}
	}

	public void setLayoutColumns(final int cols) {
		if (cols <= 0) {
			guessLayout();
		} else {
			gridCols = cols;
			adjustGridRows();
		}
	}

	public JFrame show() {
		final JFrame frame = getJFrame();
		frame.setTitle((label == null) ? "Multi-Pane Reconstruction Viewer" : label);
		frame.setVisible(true);
		return frame;
	}

	private int initialViewerSize() {
		final int grid = (gridCols + gridRows / 2);
		return 1200 / grid - (gap /2 * grid);
	}

	private JFrame getJFrame() {

		final JFrame frame = new JFrame();
		final GridLayout layout = new GridLayout(gridRows, gridCols);
		layout.setHgap(gap/2);
		layout.setVgap(gap/2);
		frame.setLayout(layout);
		frame.getContentPane().setBackground(BKG_DARK); // by default individual viewers have dark background

		final List<CanvasAWT> canvases = new ArrayList<>(viewers.size());
		final int initialSize = initialViewerSize();
		for (final Viewer3D viewer3D : viewers) {
			final Frame viewerFrame = viewer3D.getFrame(false);
			for (final Component c : viewerFrame.getComponents()) {
				if (c instanceof CanvasAWT) {
					c.setPreferredSize(new Dimension(initialSize, initialSize));
					frame.add(c);
					canvases.add((CanvasAWT) c);
					viewerFrame.dispose();
					break;
				}
			}
		}
		if (canvases.isEmpty()) {
			throw new IllegalArgumentException("Only CanvasAWT-based viewers supported");
		}
		if (canvases.size() != viewers.size()) {
			SNTUtils.log("Some viewers do not extend CanvasAWT and were not considered");
		}
		frame.pack();
		frame.addComponentListener(new ComponentAdapter() {
			public void componentResized(final ComponentEvent componentEvent) {
				final int w = Math.round((float) frame.getWidth() / gridCols) - (gap / 2 * gridCols);
				final int h = Math.round((float) frame.getHeight() / gridRows) - (gap / 2 * gridRows);
				canvases.forEach(canvas -> canvas.setPreferredSize(new Dimension(w, h)));
			}
		});
		canvases.forEach(canvas ->
				canvas.addKeyListener(new KeyAdapter() {
					@Override
					public void keyPressed(final KeyEvent e) {
						if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
							frame.requestFocusInWindow();
						}
					}
				})
		);
		frame.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(final KeyEvent e) {
				switch (e.getKeyChar()) {
					case 'D':
					case 'd':
						if (frame.getContentPane().getBackground().equals(BKG_DARK))
							frame.getContentPane().setBackground(BKG_LIGHT);
						else
							frame.getContentPane().setBackground(BKG_DARK);
						break;
					default:
						super.keyPressed(e);
						break;
				}
			}
		});
		frame.addMouseWheelListener(new MouseWheelListener() {
			@Override
			public void mouseWheelMoved(final MouseWheelEvent e) {
				canvases.forEach(canvas -> canvas.triggerMouseWheelEvent(e));
			}
		});
		frame.addMouseMotionListener(new MouseMotionListener() {
			@Override
			public void mouseDragged(final MouseEvent e) {
				canvases.forEach(canvas -> canvas.triggerMouseMotionEvent(e));
			}

			@Override
			public void mouseMoved(final MouseEvent e) {
				canvases.forEach(canvas -> canvas.triggerMouseMotionEvent(e));
			}
		});
		frame.addMouseListener(new MouseListener() {
			@Override
			public void mouseClicked(final MouseEvent e) {
				canvases.forEach(canvas -> canvas.triggerMouseEvent(e));
			}

			@Override
			public void mousePressed(final MouseEvent e) {
				canvases.forEach(canvas -> canvas.triggerMouseEvent(e));
			}

			@Override
			public void mouseReleased(final MouseEvent e) {
				canvases.forEach(canvas -> canvas.triggerMouseEvent(e));
			}

			@Override
			public void mouseEntered(final MouseEvent e) {
				canvases.forEach(canvas -> canvas.triggerMouseEvent(e));
			}

			@Override
			public void mouseExited(final MouseEvent e) {
				canvases.forEach(canvas -> canvas.triggerMouseEvent(e));
			}
		});
		canvases.forEach(canvas -> {
			for (KeyListener kl : canvas.getKeyListeners())
				frame.addKeyListener(kl);
		});
		// If needed, add an extra space at the bottom to facilitate interaction w/ all viewers
		if (gridRows * gridCols <= viewers.size())
			((JComponent)frame.getContentPane()).setBorder(BorderFactory.createEmptyBorder(0, 0,
					(gap>0) ? gap : GuiUtils.getMenuItemHeight(), 0));
		return frame;
	}

	public void setGap(final int gap) {
		this.gap = gap;
	}

	/* IDE debug method */
	public static void main(final String... args) {
		final List<Tree> trees = MouseLightLoader.demoTrees();
		assert trees != null;
		final MultiViewer3D multi = new MultiViewer3D(trees);
		multi.setGap(10);
		multi.setLayoutColumns(2);
	//	multi.setAnimationEnabled(true);
		multi.show();
	}

}
