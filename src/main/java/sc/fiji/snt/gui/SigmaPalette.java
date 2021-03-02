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

import java.awt.Button;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Label;
import java.awt.Panel;
import java.awt.Scrollbar;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;

import features.HessianEvalueProcessor;
import features.TubenessProcessor;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.gui.GUI;
import ij.gui.ImageCanvas;
import ij.gui.Overlay;
import ij.gui.StackWindow;
import ij.gui.TextRoi;
import ij.process.FloatProcessor;
import sc.fiji.snt.SNT;
import stacks.ThreePaneCrop;
import sc.fiji.snt.HessianCaller;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.SNTUI;
import util.Limits;

/**
 * Implements SNT 'Sigma wizard'. It relies heavily on java.awt because it
 * extends IJ1's StackWindow. It was ported from {@link features.SigmaPalette}
 * now deprecated.
 */
public class SigmaPalette extends Thread {

	private double[] sigmaValues;
	private int croppedWidth;
	private int croppedHeight;
	private int croppedDepth;
	private PaletteStackWindow paletteWindow;
	private ImagePlus paletteImage;

	private int selectedSigmaIndex = 0;
	private int mouseMovedSigmaIndex = -1;
	private final double[] selectedMinMax = {Double.NaN, Double.NaN};
	private int x_min, x_max, y_min, y_max, z_min, z_max;
	private double defaultMin;
	private double defaultMax;
	private int sigmasAcross;
	private int sigmasDown;
	private int initial_z;

	private final ImagePlus image;
	private final SNT snt;
	private final HessianCaller hc;
	private final HessianEvalueProcessor hep;

	public SigmaPalette(final SNT snt, final HessianCaller caller) {
		this.snt = snt;
		hc = caller;
		image = hc.getImp();
		hep = new TubenessProcessor(true);
	}

	private class PaletteStackWindow extends StackWindow {

		private static final long serialVersionUID = 1L;
		private Button applyButton;
		private Scrollbar minValueScrollbar;
		private Scrollbar maxValueScrollbar;
		private Label minValueLabel;
		private Label maxValueLabel;
		private final double defaultMax;

		public PaletteStackWindow(final ImagePlus imp, final ImageCanvas ic,
			final double defaultMax)
		{
			super(imp, ic);
			setLocationRelativeTo(snt.getImagePlus().getWindow());
			this.defaultMax = defaultMax;
			ic.disablePopupMenu(true);
			ic.setShowCursorStatus(false);
			ic.setCursor(new Cursor(Cursor.HAND_CURSOR));
			final double scalingFactor = Prefs.getGuiScale();
			if (scalingFactor > 1) {
				ic.setMagnification(scalingFactor);
				ic.zoomIn(ic.getWidth() / 2, ic.getHeight() / 2);
			} else if (scalingFactor < 1) {
				ic.setMagnification(scalingFactor);
				ic.zoomOut(ic.getWidth() / 2, ic.getHeight() / 2);
			} else {
				ic.setMagnification(1);
				ic.unzoom();
			}
			//add(new Label("")); // spacer
			final Panel panel = new Panel();
			panel.setLayout(new GridBagLayout());
			final GridBagConstraints c = new GridBagConstraints();
			c.ipadx = 0;
			c.insets = new Insets(0, 0, 0, 0);
			c.ipadx = 0;
			c.fill = GridBagConstraints.HORIZONTAL;
			assembleScrollbars(panel, c);
			assembleButtonPanel(panel, c);
			GUI.scale(panel);
			updateLabels();
			add(panel);
			pack();
			repaint();
		}

		private void assembleButtonPanel(final Panel panel, final GridBagConstraints c) {
			final Button cButton = new Button("Cancel");
			cButton.addActionListener(e -> dismiss());
			applyButton = new Button("Apply s=###.##; max=###.##");
			applyButton.addActionListener(e -> apply());
			add(applyButton);
			add(cButton);
			final Panel buttonPanel = new Panel(new FlowLayout(FlowLayout.CENTER));
			buttonPanel.add(cButton);
			buttonPanel.add(applyButton);
			c.gridy++;
			c.gridx =0;
			c.weightx = 1;
			c.gridwidth= GridBagConstraints.REMAINDER;
			panel.add(buttonPanel, c);
		}

		private void assembleScrollbars(final Panel panel, final GridBagConstraints c) {
			final double[] limits = hc.impRange();
			final double multiplier = 100 / (limits[1] - limits[0]);
			minValueScrollbar = new Scrollbar(Scrollbar.HORIZONTAL, //
					(int) (defaultMin * multiplier), //
					1, //
					(int) (limits[0] * multiplier), (int) (limits[1]*multiplier));
			minValueScrollbar.setFocusable(false); // prevents scroll bar from flickering on windows!?
			minValueScrollbar.addAdjustmentListener(e -> {
				minMaxChanged(e.getValue()/multiplier, selectedMinMax[1]);
			});
			minValueLabel = new Label("###.##");
			final Button resetMin = new Button("Reset");
			resetMin.addActionListener(e -> {
				minValueScrollbar.setValue((int)(defaultMin*multiplier));
				minMaxChanged(defaultMin, selectedMinMax[1]);
			});

			c.gridy++;
			c.gridx =0;
			c.weightx = 0;
			panel.add(new Label("Min."), c);
			c.gridx++;
			c.weightx = 1;
			panel.add(minValueScrollbar, c);
			c.gridx++;
			c.weightx = 0;
			panel.add(minValueLabel, c);
			c.gridx++;
			panel.add(resetMin, c);
			c.gridx++;

			// Max scrollbar
			maxValueScrollbar = new Scrollbar(Scrollbar.HORIZONTAL, //
					(int) (defaultMax * multiplier), //
					1, //
					(int) ((limits[0]) * multiplier), (int) (limits[1]*multiplier));
			maxValueScrollbar.setFocusable(false); // prevents scroll bar from flickering on windows!?
			maxValueScrollbar.addAdjustmentListener(e -> {
				minMaxChanged(selectedMinMax[0], e.getValue()/multiplier);
			});
			maxValueLabel = new Label("###.##");
			final Button resetMax = new Button("Reset");
			resetMax.addActionListener(e -> {
				maxValueScrollbar.setValue((int) (defaultMax * multiplier));
				minMaxChanged(selectedMinMax[0], defaultMax);
			});

			c.gridy++;
			c.gridx =0;
			c.weightx = 0;
			panel.add(new Label("Max."), c);
			c.gridx++;
			c.weightx = 1;
			panel.add(maxValueScrollbar, c);
			c.gridx++;
			c.weightx = 0;
			panel.add(maxValueLabel, c);
			c.gridx++;
			panel.add(resetMax, c);
			c.gridx++;
		}

		private void updateLabels() {
			final String min = SNTUtils.formatDouble(selectedMinMax[0], 2);
			final String max = SNTUtils.formatDouble(selectedMinMax[1], 2);
			minValueLabel.setText(min);
			maxValueLabel.setText(max);
			applyButton.setLabel(
					"Apply \u03C3=" + SNTUtils.formatDouble(getSelectedSigma(), 2) + "; max=" + max);
		}

		private void minMaxChanged(final double newMin, final double newMax) {
			setMinMax(newMin, newMax);
			updateLabels();
		}

		@Override
		public void windowClosing(final WindowEvent e) {
			dismiss();
			super.windowClosing(e);
		}

		@Override
		public String createSubtitle() {
			String label = "Sigma preview grid: \u03C3=" + getMouseOverSigma();
			if (zSelector != null) label += "  z=" + SNTUtils.formatDouble(image.getCalibration().getZ(zSelector.getValue() - 1), 2);
			return label;
		}
	}

	private class PaletteCanvas extends ImageCanvas {

		private static final long serialVersionUID = 1L;
		private final int croppedWidth;
		private final int croppedHeight;
		private final int sigmasAcross;
		private final int sigmasDown;

		public PaletteCanvas(final ImagePlus imagePlus, final int croppedWidth,
			final int croppedHeight, final int sigmasAcross, final int sigmasDown)
		{
			super(imagePlus);
			this.croppedWidth = croppedWidth;
			this.croppedHeight = croppedHeight;
			this.sigmasAcross = sigmasAcross;
			this.sigmasDown = sigmasDown;
		}

		private int[] getTileXY(final MouseEvent e) {
			final int sx = e.getX();
			final int sy = e.getY();
			final int ox = offScreenX(sx);
			final int oy = offScreenY(sy);
			final int sigmaX = ox / (croppedWidth + 1);
			final int sigmaY = oy / (croppedHeight + 1);
			return new int[] { sigmaX, sigmaY };
		}

		private int sigmaIndexFromMouseEvent(final MouseEvent e) {
			final int[] sigmaXY = getTileXY(e);
			final int sigmaIndex = sigmaXY[1] * sigmasAcross + sigmaXY[0];
			if (sigmaIndex >= 0 && sigmaIndex < sigmaValues.length) return sigmaIndex;
			else return -1;
		}

		@Override
		public void mouseMoved(final MouseEvent e) {
			mouseMovedSigmaIndex = sigmaIndexFromMouseEvent(e);
			if (mouseMovedSigmaIndex >= 0) {
				if (paletteWindow != null) paletteWindow.repaint(); // call createSubtitle()
				setOverlayLabel(mouseMovedSigmaIndex, getTileXY(e));
			}
		}

		@Override
		public void mouseReleased(final MouseEvent e) {
			final int sigmaIndex = sigmaIndexFromMouseEvent(e);
			if (sigmaIndex >= 0) {
				setSelectedSigmaIndex(sigmaIndex);
				paletteWindow.repaint(); // call createSubtitle()
				setOverlayLabel(sigmaIndex, getTileXY(e));
			}
		}

		/* Keep another Graphics for double-buffering: */
		private int backBufferWidth;
		private int backBufferHeight;
		private Graphics backBufferGraphics;
		private Image backBufferImage;

		private void resetBackBuffer() {
			if (backBufferGraphics != null) {
				backBufferGraphics.dispose();
				backBufferGraphics = null;
			}
			if (backBufferImage != null) {
				backBufferImage.flush();
				backBufferImage = null;
			}
			backBufferWidth = getSize().width;
			backBufferHeight = getSize().height;
			backBufferImage = createImage(backBufferWidth, backBufferHeight);
			backBufferGraphics = backBufferImage.getGraphics();
		}

		@Override
		public void paint(final Graphics g) {

			if (backBufferWidth != getSize().width ||
				backBufferHeight != getSize().height || backBufferImage == null ||
				backBufferGraphics == null) resetBackBuffer();

			super.paint(backBufferGraphics);
			drawOverlayGrid(backBufferGraphics);
			g.drawImage(backBufferImage, 0, 0, this);
		}

		private void drawOverlayGrid(final Graphics g) {
			g.setColor(java.awt.Color.MAGENTA);
			final int width = imp.getWidth();
			final int height = imp.getHeight();

			// Draw the vertical lines:
			for (int i = 0; i <= sigmasAcross; ++i) {
				final int x = i * (croppedWidth + 1);
				final int screen_x = screenX(x);
				g.drawLine(screen_x, screenY(0), screen_x, screenY(height - 1));
			}

			// Draw the horizontal lines:
			for (int j = 0; j <= sigmasDown; ++j) {
				final int y = j * (croppedHeight + 1);
				final int screen_y = screenY(y);
				g.drawLine(screenX(0), screen_y, screenX(width - 1), screen_y);
			}

			// If there's a selected sigma, highlight that in green:
			final int selectedSigmaIndex = getSelectedSigmaIndex();

			if (selectedSigmaIndex >= 0 && selectedSigmaIndex < sigmaValues.length) {
				g.setColor(java.awt.Color.GREEN);
				final int sigmaY = selectedSigmaIndex / sigmasAcross;
				final int sigmaX = selectedSigmaIndex % sigmasAcross;
				final int leftX = screenX(sigmaX * (croppedWidth + 1));
				final int rightX = screenX((sigmaX + 1) * (croppedWidth + 1));
				final int topY = screenY(sigmaY * (croppedHeight + 1));
				final int bottomY = screenY((sigmaY + 1) * (croppedHeight + 1));
				g.drawLine(leftX, topY, rightX, topY);
				g.drawLine(leftX, topY, leftX, bottomY);
				g.drawLine(leftX, bottomY, rightX, bottomY);
				g.drawLine(rightX, bottomY, rightX, topY);
			}
		}
	}

	private void setMinMax(final double min, final double max) {
		selectedMinMax[0] = min;
		selectedMinMax[1] = max;
		paletteImage.getProcessor().setMinAndMax(min, max);
		paletteImage.updateAndDraw();
	}

	private int getSelectedSigmaIndex() {
		return selectedSigmaIndex;
	}

	private double getSelectedSigma() {
		if (selectedSigmaIndex > -1 && selectedSigmaIndex < sigmaValues.length)
			return sigmaValues[selectedSigmaIndex];
		return Double.NaN;
	}

	private String getMouseOverSigma() {
		if (mouseMovedSigmaIndex > -1 && mouseMovedSigmaIndex < sigmaValues.length)
			return SNTUtils.formatDouble(sigmaValues[mouseMovedSigmaIndex], 2);
		return "NaN";
	}

	private void setSelectedSigmaIndex(final int selectedSigmaIndex) {
		this.selectedSigmaIndex = selectedSigmaIndex;
		paletteWindow.updateLabels();
		paletteImage.updateAndDraw();
	}

	private void setOverlayLabel(final int sigmaIndex, final int[] xyTile) {
		final String label = "\u03C3=" + SNTUtils.formatDouble(sigmaValues[sigmaIndex],
			2);
		final TextRoi roi = new TextRoi(xyTile[0] * croppedWidth + 2, xyTile[1] *
			croppedHeight + 2, label);
		roi.setStrokeColor((getSelectedSigmaIndex() == sigmaIndex) ? Color.GREEN
			: Color.MAGENTA);
		roi.setAntialiased(true);
		paletteImage.setOverlay(new Overlay(roi));
	}

	/**
	 * Displays the Sigma wizard in a separate thread.
	 *
	 * @param x_min image boundary for choice grid
	 * @param x_max image boundary for choice grid
	 * @param y_min image boundary for choice grid
	 * @param y_max image boundary for choice grid
	 * @param z_min image boundary for choice grid
	 * @param z_max image boundary for choice grid
	 * @param sigmaValues the desired range of sigma values for choice grid
	 * @param sigmasAcross the number of columns in choice grid
	 * @param sigmasDown the number of rows in choice grid
	 * @param initial_z the default z-position
	 */
	public void makePalette(final int x_min, final int x_max, final int y_min, final int y_max,//
			final int z_min, final int z_max, final double[] sigmaValues, //
			final int sigmasAcross, final int sigmasDown, final int initial_z) {

		if (sigmaValues.length > sigmasAcross * sigmasDown) {
			throw new IllegalArgumentException("A " + sigmasAcross + "x" +
				sigmasDown + " layout is not large enough for " + (sigmaValues.length +1 ) +
				" images");
		}

		this.x_min = x_min;
		this.x_max = x_max;
		this.y_min = y_min;
		this.y_max = y_max;
		this.z_min = z_min;
		this.z_max = z_max;
		this.sigmaValues = sigmaValues;
		this.sigmasAcross = sigmasAcross;
		this.sigmasDown = sigmasDown;
		this.initial_z = initial_z;
		start();
	}

	private void flush() {
		paletteWindow.close();
		if (paletteImage != null) paletteImage.flush();
		snt.setCanvasLabelAllPanes(null);
	}

	public void dismiss() {
		flush();
		snt.changeUIState(SNTUI.READY);
	}

	private void apply() {
		//preprocess.setSelected(false);
		flush();
		hc.setSigmaAndMax(getSelectedSigma(), selectedMinMax[1]);
		if (hc.isGaussianComputed())
			snt.changeUIState(SNTUI.READY);
		else 
			hc.start();
	}

	private void copyIntoPalette(final ImagePlus smallImage,
		final ImagePlus paletteImage, final int offsetX, final int offsetY)
	{
		final int largerWidth = paletteImage.getWidth();
		final int depth = paletteImage.getStackSize();
		if (depth != smallImage.getStackSize()) throw new IllegalArgumentException(
			"In copyIntoPalette(), depths don't match");
		final int smallWidth = smallImage.getWidth();
		final int smallHeight = smallImage.getHeight();
		final ImageStack paletteStack = paletteImage.getStack();
		final ImageStack smallStack = smallImage.getStack();
		// Make sure the minimum and maximum are sensible in the small stack:
		for (int z = 0; z < depth; ++z) {
			final float[] smallPixels = (float[]) smallStack.getProcessor(z + 1)
				.getPixels();
			final float[] palettePixels = (float[]) paletteStack.getProcessor(z + 1)
				.getPixels();
			for (int y = 0; y < smallHeight; ++y) {
				final int smallIndex = y * smallWidth;
				System.arraycopy(smallPixels, smallIndex, palettePixels, (offsetY + y) *
					largerWidth + offsetX, smallWidth);
			}
		}
	}

	@Override
	public void run() {

		final ImagePlus cropped = ThreePaneCrop.performCrop(image, x_min, x_max,
			y_min, y_max, z_min, z_max, false);

		croppedWidth = (x_max - x_min) + 1;
		croppedHeight = (y_max - y_min) + 1;
		croppedDepth = (z_max - z_min) + 1;

		final int paletteWidth = croppedWidth * sigmasAcross + (sigmasAcross + 1);
		final int paletteHeight = croppedHeight * sigmasDown + (sigmasDown + 1);

		final ImageStack newStack = new ImageStack(paletteWidth, paletteHeight);
		for (int z = 0; z < croppedDepth; ++z) {
			final FloatProcessor fp = new FloatProcessor(paletteWidth, paletteHeight);
			newStack.addSlice("", fp);
		}
		paletteImage = new ImagePlus("Pick Sigma & Max", newStack);
		paletteImage.setZ(initial_z - z_min + 1);
		setOverlayLabel(0, new int[] { 0, 0 });
		for (int sigmaIndex = 0; sigmaIndex < sigmaValues.length; ++sigmaIndex) {
			final int sigmaY = sigmaIndex / sigmasAcross;
			final int sigmaX = sigmaIndex % sigmasAcross;
			final int offsetX = sigmaX * (croppedWidth + 1) + 1;
			final int offsetY = sigmaY * (croppedHeight + 1) + 1;
			final double sigma = sigmaValues[sigmaIndex];
			hep.setSigma(sigma);
			final ImagePlus processed = hep.generateImage(cropped);
			final float[] limits = Limits.getStackLimits(processed);
			defaultMin = limits[0];
			defaultMax = limits[1];
			copyIntoPalette(processed, paletteImage, offsetX, offsetY);
			setMinMax(defaultMin, defaultMax);
		}

		final PaletteCanvas paletteCanvas = new PaletteCanvas(paletteImage, croppedWidth, croppedHeight,
			sigmasAcross, sigmasDown);
		paletteWindow = new PaletteStackWindow(paletteImage, paletteCanvas, defaultMax);
		paletteCanvas.requestFocusInWindow(); // required to trigger keylistener events
	}

}
