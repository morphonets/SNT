/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2019 Fiji developers.
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

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Map;

import javax.swing.*;

import org.scijava.Context;

import com.mxgraph.layout.mxCompactTreeLayout;

import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.plugin.GraphAdapterMapperCmd;


class TreeGraphComponent extends SNTGraphComponent {

	private static final long serialVersionUID = 1L;
	private final mxCompactTreeLayout layout;
	private JButton flipButton;

	protected TreeGraphComponent(final TreeGraphAdapter adapter, Context context) {
		super(adapter, context);
		layout = new mxCompactTreeLayout(adapter);
		layout.execute(adapter.getDefaultParent());
	}

	@Override
	protected Component getJSplitPane() {
		// Default dimensions are exaggerated. Curb them a bit
		setPreferredSize(getPreferredSize());
		assignPopupMenu(this);
		centerGraph();
		requestFocusInWindow();
		return new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, getControlPanel(), this);
	}

	@Override
	protected JComponent getControlPanel() {
		final JPanel buttonPanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gbc = new GridBagConstraints();
		gbc.weightx = 1;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.gridwidth = GridBagConstraints.REMAINDER;

		GuiUtils.addSeparator(buttonPanel, "Navigation:", true, gbc);
		JButton button = new JButton("Zoom In");
		button.setToolTipText("[+] or Shift + Mouse Wheel");
		button.addActionListener(e -> { zoomIn(); });
		buttonPanel.add(button, gbc);
		button = new JButton("Zoom Out");
		button.setToolTipText("[-] or Shift + Mouse Wheel");
		button.addActionListener(e -> { zoomOut(); });
		buttonPanel.add(button, gbc);
		button = new JButton("Reset Zoom");
		button.addActionListener(e -> {zoomActual(); zoomAndCenter();});
		buttonPanel.add(button, gbc);
		button = new JButton("Center");
		button.addActionListener(e -> {centerGraph();});
		buttonPanel.add(button, gbc);
//		panMenuItem.addActionListener(e -> getPanningHandler().setEnabled(panMenuItem.isSelected()));
//		buttonPanel.add(panMenuItem, gbc);

		GuiUtils.addSeparator(buttonPanel, "Layout:", true, gbc);
		flipButton = new JButton((layout.isHorizontal()?"Vertical":"Horizontal"));
		flipButton.addActionListener(e -> flipGraphToHorizontal(!layout.isHorizontal()));
		buttonPanel.add(flipButton, gbc);
		button = new JButton("Reset");
		button.addActionListener(e -> {
			zoomActual();
			zoomAndCenter();
			flipGraphToHorizontal(true);
		});
		buttonPanel.add(button, gbc);
		final JButton labelsButton = new JButton("Labels");
		final JPopupMenu lPopup = new JPopupMenu();
		final JCheckBox vCheckbox = new JCheckBox("Vertices (Node ID)", adapter.isVertexLabelsEnabled());
		vCheckbox.addActionListener( e -> {
			adapter.setEnableVertexLabels(vCheckbox.isSelected());
		});
		lPopup.add(vCheckbox);
		final JCheckBox eCheckbox = new JCheckBox("Edges (Inter-node distance)", adapter.isEdgeLabelsEnabled());
		eCheckbox.addActionListener( e -> {
			adapter.setEnableEdgeLabels(eCheckbox.isSelected());
		});
		lPopup.add(eCheckbox);
		labelsButton.addMouseListener(new MouseAdapter() {
			public void mousePressed(final MouseEvent e) {
				lPopup.show(labelsButton, e.getX(), e.getY());
			}
		});
		buttonPanel.add(labelsButton, gbc);

		GuiUtils.addSeparator(buttonPanel, "Color coding", true, gbc);
		final JButton colorCodingButton = new JButton("Color code");
		colorCodingButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				final Map<String, Object> input = new HashMap<>();
				input.put("adapter", adapter);
				cmdService.run(GraphAdapterMapperCmd.class, true, input);
			}
		});
		buttonPanel.add(colorCodingButton, gbc);

		GuiUtils.addSeparator(buttonPanel, "Export:", true, gbc);
		final JButton ioButton = new JButton("Save As");
		final JPopupMenu popup = new JPopupMenu();
		popup.add(saveAsMenuItem("HTML...", ".html"));
		popup.add(saveAsMenuItem("PNG...", ".png"));
		popup.add(saveAsMenuItem("SVG...", ".svg"));
		ioButton.addMouseListener(new MouseAdapter() {
			public void mousePressed(final MouseEvent e) {
				popup.show(ioButton, e.getX(), e.getY());
			}
		});
		buttonPanel.add(ioButton, gbc);
		final JPanel holder = new JPanel(new BorderLayout());
		holder.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));
		holder.add(buttonPanel, BorderLayout.CENTER);
		return new JScrollPane(holder);
	}

	@Override
	protected void assignPopupMenu(final JComponent component) {
		final JPopupMenu popup = new JPopupMenu();
		component.setComponentPopupMenu(popup);
		JMenuItem mItem = new JMenuItem("Zoom to Selection (Alt + Click & Drag)");
		mItem.addActionListener(e -> {
			new GuiUtils(this).error("Please draw a rectangular selection while holding \"Alt\".");
		});
		popup.add(mItem);
		mItem = new JMenuItem("Zoom In ([+] or Shift + Mouse Wheel)");
		mItem.addActionListener(e -> zoomIn());
		popup.add(mItem);
		mItem = new JMenuItem("Zoom Out ([-] or Shift + Mouse Wheel)");
		mItem.addActionListener(e -> zoomOut());
		popup.add(mItem);
		mItem = new JMenuItem("Reset Zoom");
		mItem.addActionListener(e -> {zoomActual(); zoomAndCenter(); centerGraph();});
		popup.add(mItem);
		popup.addSeparator();
		mItem = new JMenuItem("Available Shortcuts...");
//		mItem.addActionListener(e -> keyboardHandler.displayKeyMap());
		popup.add(mItem);

		getGraphControl().addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(final MouseEvent e) {
				handleMouseEvent(e);
			}

			@Override
			public void mouseReleased(final MouseEvent e) {
				handleMouseEvent(e);
			}

			private void handleMouseEvent(final MouseEvent e) {
				if (e.isConsumed())
					return;
				if (e.isPopupTrigger()) {
					popup.show(getGraphControl(), e.getX(), e.getY());
				}
				e.consume();
			}
		});
	}

	private void flipGraphToHorizontal(final boolean horizontal) {
		layout.setHorizontal(horizontal);
		layout.execute(adapter.getDefaultParent());
		centerGraph();
		if (flipButton != null)
			flipButton.setText((layout.isHorizontal())?"Vertical":"Horizontal");
	}

}