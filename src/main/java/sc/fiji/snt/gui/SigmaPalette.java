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

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.*;
import ij.measure.Calibration;
import ij.process.FloatProcessor;
import net.imagej.ops.OpService;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.neighborhood.DiamondShape;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import org.jspecify.annotations.NonNull;
import org.scijava.plugin.Parameter;
import org.scijava.ui.awt.AWTWindows;
import sc.fiji.snt.NearPoint;
import sc.fiji.snt.Path;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.CandidatePathMetrics;
import sc.fiji.snt.analysis.CandidatePathMetrics.Metric;
import sc.fiji.snt.filter.Frangi;
import sc.fiji.snt.util.PointInCanvas;
import sc.fiji.snt.filter.Tubeness;

import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Implements SNT 'Sigma wizard'. It relies heavily on java.awt because it
 * extends IJ1's StackWindow. It was ported from {@code features.SigmaPalette}
 * now deprecated.
 */
public class SigmaPalette extends Thread {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	@Parameter
	OpService opService;

	private static final Color COLOR_DEFAULT = Color.CYAN;
	private static final Color COLOR_MOUSEOVER = Color.MAGENTA;
	private static final Color COLOR_SELECTED = Color.GREEN;

	private double[] sigmaValues;
    private PaletteStackWindow paletteWindow;
	private ImagePlus paletteImage;
	private PaletteOptions paletteOptions;

	private int selectedSigmaIndex = 0;
	private int mouseMovedSigmaIndex = -1;
	private double selectedMax = Double.NaN;
	private int mouseMovedAcceptedSigmaIndex = -1;

	private int selectedScale = 1;
	private int x_min, x_max, y_min, y_max, z_min, z_max;
	private double suggestedMax = -Double.MAX_VALUE;
	private int sigmasAcross;
	private int sigmasDown;
	private int initial_z;

	private final ImagePlus image;
	private final SNT snt;
	// NB: Voxel size comes from SNT, not from image: in Stream mode image can be a bare wrap that does not carry it
	private final Calibration cal;
	// Offset (in voxels) of the world origin in the grid of image: voxel = world / spacing + gridOffset
	private final PointInCanvas gridOffset;
	private final SNT.FilterType analysisType;
	private List<Double> scaleSettings; // ordered: scales are addressed by position. Duplicates are rejected in setSettingsForSelectedScale()
	private final List<SigmaPaletteListener> listeners;
	private Window parent;

	// Path metrics: the traced path next to the click (if any) is re-traced on every tile with the current cost
	// function. The results are judged on the raw data, and listed in a table
	private static final double MAX_SEED_PATH_DISTANCE_VOXELS = 20; // click to nearest path node
	private static final double TRIM_LENGTH_UM = 50; // max length of the evaluated segment on each side of the click
	private static final int MIN_SEGMENT_VOXELS = 10; // below this endpoint separation metrics are noise
	private static final int MIN_INSET_XY = 5; // keeps endpoints away from the crop border (filter edge effects)
	private static final int INSET_Z = 2;
	private static final int TRACE_TIMEOUT_SECONDS = 10;
	private boolean hasSeed;
	private int seedX, seedY, seedZ; // in image's grid. seedZ is 1-based
	private Segment segment;
	private String metricsNote; // why path metrics are not available, if relevant
	private WizardSupport.TraceResult[] traces; // one per sigma
	private double[][] metrics; // [sigma rows + 1 reference row][Metric ordinal]
	private double[][] sigmaMetrics; // as above, without the reference row

	/**
	 * Segment of an existing path, in the crop's own voxel grid
	 */
	private record Segment(Path reference, int[] start, int[] end) {
	}

	/**
	 * @param snt        the SNT instance
	 * @param fullSource if true, previews the full, crop-independent source data (see
	 *                   {@link SNT#getFullSourceDataAsImp()}) instead of the data currently loaded in the classic
	 *                   canvas. This is what a BDV/BVV click refers to, regardless of any materialized crop. The
	 *                   seed and bounds passed to this palette must then be voxel indices of that data
	 */
	public SigmaPalette(final SNT snt, final boolean fullSource) {
		snt.getContext().inject(this);
		this.snt = snt;
		analysisType = snt.getFilterType();
		image = (fullSource) ? snt.getFullSourceDataAsImp() : snt.getLoadedDataAsImp();
		final PointInCanvas offset = (fullSource) ? snt.getDefaultCanvasPixelOffset() : snt.getActiveCanvasPixelOffset();
		gridOffset = new PointInCanvas(offset.x, offset.y, offset.z);
		cal = snt.getCalibration();
		listeners = new ArrayList<>();
	}

	public void setParent(final Window parent) {
		this.parent = parent;
	}

	/**
	 * Sets the position the palette was seeded from. If a path exists near this point, the palette will list quality
	 * metrics of the path segment retraced with each sigma. Must be called before {@link #makePalette}
	 *
	 * @param x pixel X position (0-based) in the grid of the data being previewed
	 * @param y pixel Y position (0-based)
	 * @param z 1-based Z-slice index
	 */
	public void setSeed(final int x, final int y, final int z) {
		seedX = x;
		seedY = y;
		seedZ = z;
		hasSeed = true;
	}

	private int getMaxScales() {
        return switch (analysisType) {
            case GAUSS, MEDIAN -> 1;
            default -> sigmaValues.length;
        };
	}

	private class PaletteStackWindow extends StackWindow {

		private static final long serialVersionUID = 1L;
        private Button commitButton;
		private Scrollbar maxValueScrollbar;
		private Scrollbar scalesScrollbar;
		private Label maxValueLabel;
		private Label selectedScaleLabel;
		private JTable metricsTable; // null if no metrics
		private final double defaultMax;
		private final GuiUtils guiUtils;

		public PaletteStackWindow(final ImagePlus imp, final ImageCanvas ic,
			final double defaultMax)
		{
			super(imp, ic);
			this.defaultMax = defaultMax;
			guiUtils = new GuiUtils(this);
			ic.disablePopupMenu(true);
			ic.setShowCursorStatus(false);
			ic.setCursor(new Cursor(Cursor.HAND_CURSOR));
			add(new Label("")); // spacer
			final Panel panel = new Panel();
			panel.setLayout(new GridBagLayout());
			final GridBagConstraints c = new GridBagConstraints();
			c.ipadx = 0;
			c.insets = new Insets(0, 0, 0, 0);
			c.fill = GridBagConstraints.HORIZONTAL;
			assembleScrollbars(panel, c);
			assembleMetricsPanel(panel, c);
			assembleButtonPanel(panel, c);

			// shortcuts
			removeKeyListener(IJ.getInstance());
			getCanvas().removeKeyListener(IJ.getInstance());
			final KeyAdapter keyHandler = new KeyAdapter() {
				@Override
				public void keyPressed(final KeyEvent e) {
					handleKey(e);
				}
			};
			addKeyListener(keyHandler);
			getCanvas().addKeyListener(keyHandler);

			// final rendering
			updateSliceSelector();
			GUI.scale(panel);
			// Leave room for sliders and buttons, but make sure the canvas is not narrower than the metrics table
			GuiUtils.zoomToScreenFraction(ic, 0.55, 8,
					(metricsTable == null) ? 0 : metricsTable.getPreferredSize().width);
			updateLabels();
			add(panel);
			pack();
			if (parent == null)
				setLocationRelativeTo((snt.getImagePlus() == null) ? null : snt.getImagePlus().getWindow()); // after pack()
			else
				AWTWindows.centerWindow(parent, this);
			repaint(); // is this really needed?
			guiUtils.tempMsg("Press 'H' or 'F1' for help", 3000);
		}

		// IJ's key listener is removed above, so zoom and help shortcuts are handled here
		private void handleKey(final KeyEvent e) {
			final ImageCanvas ic = getCanvas();
			final char c = e.getKeyChar();
			final int code = e.getKeyCode();
			if (c == '+' || c == '=' || code == KeyEvent.VK_ADD) {
				final Point p = ic.getMousePosition();
				ic.zoomIn((p != null) ? p.x : ic.getWidth() / 2, (p != null) ? p.y : ic.getHeight() / 2);
			} else if (c == '-' || c == '_' || code == KeyEvent.VK_SUBTRACT) {
				final Point p = ic.getMousePosition();
				ic.zoomOut((p != null) ? p.x : ic.getWidth() / 2, (p != null) ? p.y : ic.getHeight() / 2);
			} else if (code == KeyEvent.VK_H || code == KeyEvent.VK_F1) {
				paletteOptions.showHelp(helpHtml(), this);
			} else {
				return;
			}
			e.consume();
		}

		private void assembleMetricsPanel(final Panel panel, final GridBagConstraints c) {
			final Component content;
			if (metrics != null) {
				content = assembleMetricsTable();
			} else if (metricsNote != null) {
				content = new Label(metricsNote);
			} else {
				return;
			}
			c.gridy++;
			c.gridx = 0;
			c.weightx = 1;
			c.gridwidth = GridBagConstraints.REMAINDER;
			panel.add(content, c);
		}

		private JScrollPane assembleMetricsTable() {
			final int n = sigmaValues.length;
			final var nameRenderer = new DefaultTableCellRenderer() {
				private static final long serialVersionUID = 1L;

				@Override
				public Component getTableCellRendererComponent(final JTable t, final Object value,
															   final boolean isSel, final boolean hasFocus,
															   final int row, final int col) {
					super.getTableCellRendererComponent(t, value, false, false, row, col);
					final boolean current = row == selectedSigmaIndex;
					setFont(t.getFont().deriveFont(current ? Font.BOLD : (row >= n) ? Font.ITALIC : Font.PLAIN));
					setHorizontalAlignment(SwingConstants.LEFT);
					return this;
				}
			};
			final WizardSupport.MetricsTable mt = WizardSupport.metricsTable(getTableModel(n, Metric.values()),
					nameRenderer,
					"Click a row to select its tile. Double-click to set it",
					"<html><div width=300>Segment next to the clicked point, retraced on each filtered tile with the "
							+ "current cost function. Metrics are measured on the original (unfiltered) image. "
							+ "'Traced' is the original segment",
					"<html><div width=300>Compute time of the search on the filtered tile. Tiles are processed in "
							+ "parallel, so timings are indicative only. Speed is not a measure of path quality",
					true,
					(row, m) -> row < n && traces[row] != null && traces[row].ok()
							&& CandidatePathMetrics.isBest(sigmaMetrics, m, row),
					(row, m) -> row < n && worseThanTraced(row, m),
					(row, clicks) -> {
						if (row >= n) return; // the reference
						setSelectedSigmaIndex(row);
						((PaletteCanvas) getCanvas()).updateOverlayLabels();
						if (clicks == 2) setSettingsForSelectedScale();
						updateLabels();
						repaint();
						getCanvas().requestFocusInWindow(); // so that keyboard shortcuts (zoom, etc.) keep working
					},
					Math.min(n + 1, 6));
			metricsTable = mt.table();
			return mt.pane();
		}

		private void assembleButtonPanel(final Panel panel, final GridBagConstraints c) {
			commitButton = new Button("Commit \u03C3 Values [16 Scale(s)]");
			commitButton.addActionListener(e -> apply());
			final Button cancelButton = new Button("B&C");
			cancelButton.addActionListener(e -> IJ.doCommand("Brightness/Contrast..."));
			final Panel buttonPanel = new Panel(new FlowLayout(FlowLayout.CENTER));
			buttonPanel.add(commitButton);
			buttonPanel.add(cancelButton);
			buttonPanel.add(assembleOptionsButton());
			c.gridy++;
			c.gridx = 0;
			c.weightx = 1;
			c.gridwidth = GridBagConstraints.REMAINDER;
			panel.add(buttonPanel, c);
		}

		private Button assembleOptionsButton() {
			// Standard items (help / LUT / MIP / snapshot / dismiss) come from
			// PaletteOptions; the sigma-specific "Reset All Scales" item is
			// inserted via the extras callback.
			return paletteOptions.buildMoreButton(this, helpHtml(), pm -> {
				final MenuItem reset = new MenuItem("Reset All Scales");
				reset.setEnabled(getMaxScales() > 1);
				reset.addActionListener(e -> {
					if (scaleSettings != null && !scaleSettings.isEmpty()) {
						scaleSettings.clear();
						advanceToFirstScale();
						((PaletteCanvas) getCanvas()).updateOverlayLabels();
						updateLabels();
					}
				});
				pm.add(reset);
			});
		}

		private String helpHtml() {
			return "<p>"
					+ "Each tile in the grid previews the effect of the <i>Sigma parameter</i> (&sigma;) &ndash; an "
					+ "estimate of the radii of the structures being traced &ndash; on the the image processing filter:"
					+ "</p>"
					+ "<p><b>How to Use the <i>Sigma Preview Palette</i>:</b></p>"
					+ "<ul>"
					+ "<li>You can select has many values (scales) as relevant: Some filters (e.g., Tubeness, Frangi)"
					+ "    allow multiple scales, while others (e.g., Gaussian, Median) only a single scale</li>"
					+ "<li>With 3D images, use the main slider to navigate Z-planes</li>"
					+ "<li>Setting multiple scales:</li>"
					+ "<ul>"
					+ "<li>To select a &sigma; value, click on its tile while holding <kbd>Shift</kbd> (or double-click on it)</li>"
					+ "<li>To un-select a &sigma; value, click on its tile while holding <kbd>Alt</kbd></li>"
					+ "<li>You can also use the <i>Scale</i> slider and its <i>Set</i> button to "
					+ "set/replace a value"
					+ "</ul>"
					+ "<li>Setting a single scale:</li>"
					+ "<ul>"
					+ "<li>Simply click on a tile to select its &sigma; value</li>"
					+ "</ul>"
					+ "</ul>"
					+ "<p><b>Path metrics:</b> If a traced path lies near the point you clicked, the table lists quality "
					+ "metrics of the segment retraced on each tile with the current cost function (measured on the "
					+ "original image), alongside the original segment (<i>Traced</i>). The best value of each column "
					+ "is highlighted, and values worse than the original segment are shown in red. Click a row to "
					+ "select its tile. Note that results depend on the cost function in use: Retracing is not "
					+ "performed if the path is too short or too close to the edge of the preview."
					+ "</p>"
					+ "<p>"
					+ "Use the B&amp;C button to adjust the Brightness/Contrast of the grid. Have a look at the "
					+ "<i>More &raquo;</i> menu for further options."
					+ "</p>";
		}

		private void assembleScrollbars(final Panel panel, final GridBagConstraints c) {

			scalesScrollbar = new RangeScrollbar(1, 1, getMaxScales() + 1);
			selectedScaleLabel = new Label("1  ");
            final Button setButton = new Button("Set");
			setButton.addActionListener(e -> setSettingsForSelectedScale());

			scalesScrollbar.addAdjustmentListener(e -> selectedScaleChanged(scalesScrollbar.getValue()));
			maxValueScrollbar = new RangeScrollbar(defaultMax);
			maxValueScrollbar.addAdjustmentListener(e -> {
				final double fraction = (double) maxValueScrollbar.getValue()
						/ (double) (maxValueScrollbar.getMaximum() - maxValueScrollbar.getMinimum() + 1);
				maxChanged(fraction * (image.getDisplayRangeMax() - image.getDisplayRangeMin() + 1)
						+ image.getDisplayRangeMin());
			});
			maxValueLabel = new Label("###.##");
			final Button resetMax = new Button("Auto");
			resetMax.addActionListener(e -> {
				maxValueScrollbar.setValue( (int)Math.round(defaultMax));
				maxChanged(defaultMax);
			});

			if (getMaxScales() > 1) {
				c.gridx =0;
				c.weightx = 0;
				panel.add(new Label("Scale"), c);
				c.gridx++;
				c.weightx = 1;
				panel.add(scalesScrollbar, c);
				c.gridx++;
				c.weightx = 0;
				panel.add(selectedScaleLabel, c);
				c.gridx++;
				panel.add(setButton, c);
				c.gridy++;
			}

		}

		private void advanceToNextScale() {
			final int currentValue = scalesScrollbar.getValue();
			scalesScrollbar.setValue(Math.min(scalesScrollbar.getMaximum(), currentValue + 1));
			if (currentValue != scalesScrollbar.getValue()) {
				selectedScaleChanged(scalesScrollbar.getValue());
				//guiUtils.tempMsg(String.format("Scale %d successfully set.", currentValue));
			}
		}

		private void advanceToFirstScale() {
			if (scalesScrollbar.getValue() != 1) {
				scalesScrollbar.setValue(1);
				selectedScaleChanged(1);
			}
		}

		private void advanceToScale(final int scaleIndex) {
			if (scaleIndex >= scalesScrollbar.getMinimum() && scaleIndex <= scalesScrollbar.getMaximum()) {
				scalesScrollbar.setValue(scaleIndex);
				selectedScaleChanged(scaleIndex);
			}
		}
		private void updateLabels() {
			final String max = SNTUtils.formatDouble(selectedMax, 1);
			maxValueLabel.setText(max);
			final int nScales = (scaleSettings == null) ? 0 : scaleSettings.size();
			if (getMaxScales() == 1)
				commitButton.setLabel("Commit \u03C3 Value");
			else
				commitButton.setLabel(String.format("Commit \u03C3 Values [%d Scale(s)]", nScales));
		}

		private void selectedScaleChanged(final int newScaleIndex) {
			selectedScale = newScaleIndex;
			selectedScaleLabel.setText(selectedScale + "  "); // accommodate double digits
			if (scaleSettings != null && selectedScale <= scaleSettings.size() && scaleSettings.get(selectedScale -1) != null) {
				final double sigma = scaleSettings.get(selectedScale -1);
				final int sigmaIndex = getSigmaIndexFromValue(sigma);
				setSelectedSigmaIndex(sigmaIndex);
//				maxValueScrollbar.setValue((int) Math.round(settings[1]));
			} else {
				updateLabels();
			}
			repaint(); // will call drawInfo(getGraphics()) to update subtitle
		}

		private void maxChanged(final double newMax) {
			setMax(newMax);
			updateLabels();
		}

		@Override
		public void windowClosing(final WindowEvent e) {
			dismiss();
			super.windowClosing(e);
		}

		@Override
		public String createSubtitle() {
			final StringBuilder sb = new StringBuilder(analysisType.toString());
			sb.append(" Preview: Setting Scale ").append(selectedScale);
			sb.append(" \u03C3=").append(getMouseOverSigma());
			if (zSelector != null) {
				sb.append("  z=").append(SNTUtils.formatDouble(cal.getZ(zSelector.getValue() - 1), 2));
			}
			return sb.toString();
		}
	}

	private @NonNull AbstractTableModel getTableModel(int n, Metric[] ms) {
		final String units = snt.getSpacingUnits();
        return new AbstractTableModel() {
            private static final long serialVersionUID = 1L;

            @Override
            public int getRowCount() {
                return n + 1; // sigmas + reference
            }

            @Override
            public int getColumnCount() {
                return ms.length + 2; // name, time, metrics
            }

            @Override
            public String getColumnName(final int col) {
                if (col == 0) return "\u03C3";
                if (col == 1) return "Time (ms)";
                return ms[col - 2].columnName(units);
            }

            @Override
            public Object getValueAt(final int row, final int col) {
                if (col == 0) return (row < n) ? SNTUtils.formatDouble(sigmaValues[row], 2) : "Traced";
                if (row < n && (traces[row] == null || !traces[row].ok())) return "n/a";
                if (col == 1) return (row < n) ? String.valueOf(traces[row].elapsedMs()) : "";
                return ms[col - 2].format(metrics[row][col - 2]);
            }
        };
	}

	private class RangeScrollbar extends Scrollbar {

		private static final long serialVersionUID = 1L;
		private final ScrollbarWithLabel REF = new ScrollbarWithLabel(null, 1, 0, 1, 0, '\u0000');

		public RangeScrollbar(final double value) {
			this(value, (int) Math.round(image.getDisplayRangeMin()),
					(int) Math.round(image.getDisplayRangeMax()));
		}

		public RangeScrollbar(final double value, final int min, final int max) {
			super(Scrollbar.HORIZONTAL, (int) (value), 1, min, max);
			setFocusable(false); // prevents scroll bar from flickering on windows!?
			GUI.fixScrollbar(this);
		}

		@Override
		public Dimension getPreferredSize() {
			return REF.getPreferredSize();
		}

		@Override
		public Dimension getMinimumSize() {
			return REF.getMinimumSize();
		}
	}

	private class PaletteCanvas extends ImageCanvas {

		private static final long serialVersionUID = 1L;
		private final int croppedWidth;
		private final int croppedHeight;
		private final int sigmasAcross;
		private final int sigmasDown;
		private Overlay overlay;

		public PaletteCanvas(final ImagePlus imagePlus, final int croppedWidth,
			final int croppedHeight, final int sigmasAcross, final int sigmasDown)
		{
			super(imagePlus);
			this.croppedWidth = croppedWidth;
			this.croppedHeight = croppedHeight;
			this.sigmasAcross = sigmasAcross;
			this.sigmasDown = sigmasDown;
			initOverlay();
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

		private void initOverlay() {
			overlay = new Overlay();
			overlay.selectable(false);
			getImage().setHideOverlay(false);
			for (int i = 0; i < sigmasAcross; i++) {
				for (int j = 0; j < sigmasDown; j++) {
					overlay.add(getSigmaLabel(new int[] { i, j }, COLOR_DEFAULT));
				}
			}
			getImage().setOverlay(overlay);
		}

		private TextRoi getSigmaLabel(final int[] xyTile, final Color color) {
			final int sigmaIndex = sigmaIndexFromTilePosition(xyTile);
			final String label = "\u03C3=" + SNTUtils.formatDouble(sigmaValues[sigmaIndex], 2);
			final TextRoi roi = new TextRoi(xyTile[0] * croppedWidth + 2, xyTile[1] * croppedHeight + 2, label);
			roi.setStrokeColor(color);
			roi.setAntialiased(true);
			final String name = "label" + Arrays.toString(xyTile);
			roi.setName(name);
			return roi;
		}

		private TextRoi getSetSigmaLabel(final int sigmaIndex, final int[] xyTile) {
			final int sigmaSetIndex = indexOfSigmaInScaleSettings(sigmaValues[sigmaIndex]);
			final String label = (sigmaSetIndex == -1) ? "" : "Scale " + (sigmaSetIndex + 1);
			final TextRoi roi = new TextRoi(xyTile[0] * croppedWidth + 2,
					xyTile[1] * croppedHeight + TextRoi.getDefaultFontSize() - 2, label);
			final boolean selected = getSelectedSigmaIndex() == sigmaIndex;
			setTextRoiColor(roi, selected, mouseMovedSigmaIndex == sigmaIndex);
			roi.setAntialiased(true);
			roi.setFontSize(roi.getCurrentFont().getSize() - 2);
			roi.setName("set" + Arrays.toString(xyTile));
			return roi;
		}

		private void setTextRoiColor(final Roi roi, final boolean isSelected, final boolean isMouseOver) {
			if (roi != null) {
				if (isSelected)
					roi.setStrokeColor(COLOR_SELECTED);
				else if (isMouseOver)
					roi.setStrokeColor(COLOR_MOUSEOVER);
				else
					roi.setStrokeColor(COLOR_DEFAULT);
			}
		}

		private void updateOverlayLabels() {
			for (int i = 0; i < sigmasAcross; i++) {
				for (int j = 0; j < sigmasDown; j++) {
					final int[] tilePosition = new int[] { i, j };
					final String nameSeed = Arrays.toString(tilePosition);
					final int sigmaIndex = sigmaIndexFromTilePosition(tilePosition);
					final boolean selected = getSelectedSigmaIndex() == sigmaIndex;
					final Roi roi = overlay.get("label" + nameSeed);
					setTextRoiColor(roi, selected, mouseMovedSigmaIndex == sigmaIndex);
					overlay.remove("set" + nameSeed);
					overlay.add(getSetSigmaLabel(sigmaIndex, tilePosition));
				}
			}
			repaint();
		}

		private int sigmaIndexFromTilePosition(final int[] tilePosition) {
			final int sigmaIndex = tilePosition[1] * sigmasAcross + tilePosition[0];
			if (sigmaIndex >= 0 && sigmaIndex < sigmaValues.length) {
				return sigmaIndex;
			} else {
				return -1;
			}
		}

		private void updateSigmaFromMouseEvent(final MouseEvent e) {
			final int[] tilePosition = getTileXY(e);
			mouseMovedSigmaIndex = sigmaIndexFromTilePosition(tilePosition);
			if (mouseMovedSigmaIndex > -1 && mouseMovedAcceptedSigmaIndex != mouseMovedSigmaIndex) {
				if (e.getClickCount() > 0) {
					setSelectedSigmaIndex(mouseMovedSigmaIndex);
					final int indexInScaleSettings = indexOfSigmaInScaleSettings(sigmaValues[mouseMovedSigmaIndex]);
					if (indexInScaleSettings > -1) {
						//  user clicked on a previously set tile
						if (e.isAltDown()) { // remove this scale
							scaleSettings.remove(indexInScaleSettings);
						} else { // select it
							paletteWindow.advanceToScale(indexInScaleSettings + 1); // 1-based index
						}
					} else if (e.isShiftDown() || getMaxScales() == 1 || e.getClickCount() == 2) { // commit selection immediately
						setSettingsForSelectedScale();
						mouseMovedAcceptedSigmaIndex = mouseMovedSigmaIndex;
						return;
					}
				}
				updateOverlayLabels();
				if (paletteWindow != null)
					paletteWindow.repaint(); // call createSubtitle()
				mouseMovedAcceptedSigmaIndex = -1;
			}
		}

		@Override
		public void mouseClicked(final MouseEvent e) {
			super.mouseClicked(e); // change cursor, etc.
			updateSigmaFromMouseEvent(e);
			paletteWindow.updateLabels();
		}

		@Override
		public void mousePressed(final MouseEvent e) {
			updateSigmaFromMouseEvent(e);
			paletteWindow.updateLabels();
		}

		@Override
		public void mouseMoved(final MouseEvent e) {
			super.mouseMoved(e);
			updateSigmaFromMouseEvent(e);
		}

		@Override
		public void mouseReleased(final MouseEvent e) {
			updateSigmaFromMouseEvent(e);
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
			g.setColor(COLOR_DEFAULT);
			((Graphics2D)g).setStroke(new BasicStroke(1));
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

			// If there's a selected sigma, highlight that
			final int selectedSigmaIndex = getSelectedSigmaIndex();

			if (selectedSigmaIndex >= 0 && selectedSigmaIndex < sigmaValues.length) {
				g.setColor(COLOR_SELECTED);
				((Graphics2D)g).setStroke(new BasicStroke(3));
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

	private void setMax(final double max) {
		selectedMax = max;
		paletteImage.getProcessor().setMinAndMax(paletteImage.getProcessor().getMin(), max);
		paletteImage.updateAndDraw();
		if (paletteOptions != null) paletteOptions.updateMip();
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

	private int getSigmaIndexFromValue(final double sigma) {
		for (int i = 0; i < sigmaValues.length; i++) {
			if (sigma == sigmaValues[i]) return i;
		}
		return -1;
	}

	private void setSelectedSigmaIndex(final int selectedSigmaIndex) {
		this.selectedSigmaIndex = selectedSigmaIndex;
		paletteWindow.updateLabels();
		paletteImage.getCanvas().repaint();
		if (paletteWindow.metricsTable != null) paletteWindow.metricsTable.repaint();
	}

	/**
	 * Displays the Sigma wizard in a separate thread.
	 *
	 * @param x_min image boundary for choice grid
	 * @param x_max image boundary for choice grid
	 * @param y_min image boundary for choice grid
	 * @param y_max image boundary for choice grid
	 * @param z_min image boundary for choice grid (1-based index)
	 * @param z_max image boundary for choice grid (1-based index)
	 * @param sigmaValues the desired range of sigma values for choice grid
	 * @param sigmasAcross the number of columns in choice grid
	 * @param sigmasDown the number of rows in choice grid
	 * @param initial_z the default z-position
	 */
	public void makePalette(final int x_min, final int x_max, final int y_min, final int y_max,//
			final int z_min, final int z_max, final double[] sigmaValues, //
			final int sigmasAcross, final int sigmasDown, final int initial_z) {

		if (sigmaValues.length != sigmasAcross * sigmasDown) {
			throw new IllegalArgumentException(sigmasAcross + "x" + sigmasDown + "must equal the number of sigmas");
		}

		double[] sigmas = sigmaValues;
		int across = sigmasAcross;
		int down = sigmasDown;
		if (analysisType == SNT.FilterType.MEDIAN) {
			// Median is defined by an integer radius: several sigmas map to the same radius and would give
			// identical tiles. Keep one sigma per radius, and keep the grid full (tiles are addressed by position)
			sigmas = distinctMedianSigmas(sigmaValues);
			if (sigmas.length != sigmaValues.length) {
				across = gridColumns(sigmas.length, sigmasAcross);
				down = sigmas.length / across;
			}
		}

		this.x_min = x_min;
		this.x_max = x_max;
		this.y_min = y_min;
		this.y_max = y_max;
		this.z_min = z_min; //1-based index
		this.z_max = z_max; //1-based index
		this.sigmaValues = sigmas;
		this.sigmasAcross = across;
		this.sigmasDown = down;
		this.initial_z = initial_z;
		start();
	}

	public void dismiss() {
		new ArrayList<>(listeners).forEach(SigmaPaletteListener::paletteDismissed); // avoid ConcurrentModificationException
		if (paletteWindow != null) paletteWindow.close();
		if (paletteImage != null) paletteImage.flush();
		listeners.clear();
	}

	public List<Double> getMultiScaleSettings() {
		if (scaleSettings == null)
			throw new IllegalArgumentException("getMultiScaleSettings() called before any setting being defined");
		return scaleSettings;
	}

	private int indexOfSigmaInScaleSettings(final double sigma) {
		if (scaleSettings == null)
			return -1;
		return scaleSettings.indexOf(sigma);
	}

	private void setSettingsForSelectedScale() {
		if (selectedScale < 0 || selectedScale > getMaxScales())
			throw new IllegalArgumentException("BUG: selectedScale index out-of-range");
		if (scaleSettings == null)
			scaleSettings = new ArrayList<>();
		boolean autoAdvance = true;
		if (selectedScale > 1 && selectedScale > scaleSettings.size() + 1) {
			if (paletteWindow.guiUtils
					.getConfirmation("You are adjusting scale " + selectedScale + " but previous scale positions are unset."
									+ " Adjust scale " + (scaleSettings.size() + 1) + " instead?", "Non-contiguous Scale Positions")) {
				paletteWindow.scalesScrollbar.setValue(scaleSettings.size()+1);
				paletteWindow.selectedScaleChanged(scaleSettings.size()+1);
				autoAdvance = false;
			} else {
				return;
			}
		}
		final double sigma = getSelectedSigma();
		final int existingIdx = indexOfSigmaInScaleSettings(sigma);
		if (existingIdx > -1 && existingIdx != selectedScale -1) {
			paletteWindow.guiUtils
					.error(String.format("This value of sigma has already been set for scale %d.", (existingIdx + 1)));
			return;
		}
		if (scaleSettings.isEmpty() || selectedScale -1 == scaleSettings.size()) {
			scaleSettings.add(sigma);
		} else {
			scaleSettings.set(selectedScale -1, sigma);
		}
		((PaletteCanvas)paletteWindow.getCanvas()).updateOverlayLabels();
		if (autoAdvance) {
			paletteWindow.advanceToNextScale();
		} else
			SNTUtils.log(String.format("Scale %d set: sigma=%.1f", selectedScale, sigma));
	}

	private void apply() {
		if (listeners == null || listeners.isEmpty() || listeners.stream().noneMatch(Objects::nonNull)) {
			paletteWindow.guiUtils.error("Prompt has been closed. Settings cannot be applied.");
			return;
		}
		if (scaleSettings == null || scaleSettings.isEmpty() || Double.isNaN(getSelectedSigma())) {
			if (getMaxScales() > 1) {
				paletteWindow.guiUtils.error("You must specify settings for at least one scale.");
				return;
			} else { // just set it ourselves
				scaleSettings = new ArrayList<>();
				scaleSettings.add(getSelectedSigma());
			}
		} else if (getMaxScales() > 1) {
			final StringBuilder sb = new StringBuilder("Commit the following settings across ");
			sb.append(scaleSettings.size()).append(" scale(s)? <ol>");
			for (final double setting : scaleSettings) {
				sb.append("<li>\u03C3= ").append(SNTUtils.formatDouble(setting, 2));
				sb.append("</li>");
			}
			sb.append("</ol>");
			if (!paletteWindow.guiUtils.getConfirmation(sb.toString(), "Commit Settings?"))
				return;
		}
		listeners.forEach( listener -> listener.setSigmas(getMultiScaleSettings()));
		dismiss();
	}

	// Looks for a path near the seed and, if found, extracts the part of it that can be evaluated: at most
	// TRIM_LENGTH_UM on either side of the point nearest to the seed, and inside the crop (away from its border).
	// Sets segment, or metricsNote if there is something to tell the user
	private void findSegment() {
		segment = null;
		metricsNote = null;
		final PathAndFillManager pafm = snt.getPathAndFillManager();
		if (!hasSeed || pafm == null || pafm.size() == 0) return; // nothing to evaluate
		final double[] sp = {cal.pixelWidth, cal.pixelHeight, cal.pixelDepth};
		final double maxDist = MAX_SEED_PATH_DISTANCE_VOXELS * Math.min(sp[0], Math.min(sp[1], sp[2]));
		// NB: Path nodes are true-world coordinates, whereas the seed (and the crop) are voxel indices of the data being
		// previewed, related by gridOffset (world origin offset and/or materialized crop). Do not use Path's
		// getXUnscaledDouble() and friends here: they add the canvasOffset of the path, which is not necessarily
		// the one in effect for that data (e.g., paths created in BDV/BVV). The lookup skips inactive path versions
		// and rejects clicks beyond the tip of a path
		final PointInCanvas off = gridOffset;
		final NearPoint np = pafm.nearestPointOnAnyPath((seedX - off.x) * sp[0], (seedY - off.y) * sp[1],
				(seedZ - 1 - off.z) * sp[2], maxDist);
		if (np == null) {
			SNTUtils.log("Sigma palette: no path within " + SNTUtils.formatDouble(maxDist, 2) + " " + snt.getSpacingUnits()
					+ " of seed (voxel " + seedX + "," + seedY + "," + seedZ + "; world "
					+ SNTUtils.formatDouble((seedX - off.x) * sp[0], 2) + ","
					+ SNTUtils.formatDouble((seedY - off.y) * sp[1], 2) + ","
					+ SNTUtils.formatDouble((seedZ - 1 - off.z) * sp[2], 2) + "; pixel offset " + off.x + ","
					+ off.y + "," + off.z + ")");
			metricsNote = "No path near the clicked point: path metrics are unavailable";
			return;
		}
		final Path best = np.getPath();
		final int bestIdx = np.indexInPath;
		// Crop bounds in the image's grid (z is 0-based here), kept away from the border when the crop allows it
		// The sampling disks of the metrics must fit inside the crop too
		final int insetXY = Math.max(MIN_INSET_XY, WizardSupport.metricsRadius() + 1);
		final double x0 = x_min + ((x_max - x_min > 2 * insetXY) ? insetXY : 0);
		final double x1 = x_max - ((x_max - x_min > 2 * insetXY) ? insetXY : 0);
		final double y0 = y_min + ((y_max - y_min > 2 * insetXY) ? insetXY : 0);
		final double y1 = y_max - ((y_max - y_min > 2 * insetXY) ? insetXY : 0);
		final double z0 = (z_min - 1) + ((z_max - z_min > 2 * INSET_Z) ? INSET_Z : 0);
		final double z1 = (z_max - 1) - ((z_max - z_min > 2 * INSET_Z) ? INSET_Z : 0);
		final double cap = trimLength();
		final Path path = best;
		final java.util.function.IntPredicate inside = i -> {
			final double x = gridX(path, i, off);
			final double y = gridY(path, i, off);
			final double z = gridZ(path, i, off);
			return x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1;
		};
		final java.util.function.IntToDoubleFunction step = i -> { // length of the segment between nodes i and i+1
			final double dx = (gridX(path, i + 1, off) - gridX(path, i, off)) * sp[0];
			final double dy = (gridY(path, i + 1, off) - gridY(path, i, off)) * sp[1];
			final double dz = (gridZ(path, i + 1, off) - gridZ(path, i, off)) * sp[2];
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		};
		if (!inside.test(bestIdx)) {
			metricsNote = "Path too close to the edge of the preview: path metrics are unavailable";
			return;
		}
		int lo = bestIdx;
		int hi = bestIdx;
		double len = 0;
		while (lo > 0 && inside.test(lo - 1) && len + step.applyAsDouble(lo - 1) <= cap) {
			len += step.applyAsDouble(lo - 1);
			lo--;
		}
		len = 0;
		while (hi < path.size() - 1 && inside.test(hi + 1) && len + step.applyAsDouble(hi) <= cap) {
			len += step.applyAsDouble(hi);
			hi++;
		}
		final int[] start = toCropGrid(path, lo, off);
		final int[] end = toCropGrid(path, hi, off);
		final int span = Math.max(Math.abs(start[0] - end[0]), Math.max(Math.abs(start[1] - end[1]),
				Math.abs(start[2] - end[2])));
		if (span < MIN_SEGMENT_VOXELS) {
			metricsNote = "Path segment near the clicked point is too short: path metrics are unavailable";
			return;
		}
		// Reference: the original segment, in the crop's own physical coordinates
		final Path reference = new Path(sp[0], sp[1], sp[2], cal.getUnit());
		for (int i = lo; i <= hi; i++) {
			reference.addPointDouble((gridX(path, i, off) - x_min) * sp[0],
					(gridY(path, i, off) - y_min) * sp[1],
					(gridZ(path, i, off) - (z_min - 1)) * sp[2]);
		}
		segment = new Segment(reference, start, end);
	}

	// True if the retraced segment scores worse than the original one in this metric. Values that look the same once
	// formatted are not considered different
	private boolean worseThanTraced(final int row, final Metric m) {
		if (metrics == null || traces[row] == null || !traces[row].ok()) return false;
		final int j = m.ordinal();
		final double v = metrics[row][j];
		final double ref = metrics[metrics.length - 1][j];
		if (Double.isNaN(v) || Double.isNaN(ref) || m.format(v).equals(m.format(ref))) return false;
		return m.lowerIsBetter() ? v > ref : v < ref;
	}

	// Voxel position of a path node in the grid of the data being previewed
	private double gridX(final Path p, final int i, final PointInCanvas off) {
		return p.getNode(i).x / cal.pixelWidth + off.x;
	}

	private double gridY(final Path p, final int i, final PointInCanvas off) {
		return p.getNode(i).y / cal.pixelHeight + off.y;
	}

	private double gridZ(final Path p, final int i, final PointInCanvas off) {
		return p.getNode(i).z / cal.pixelDepth + off.z;
	}

	// Crop-local voxel of a path node, clamped to the crop
	private int[] toCropGrid(final Path p, final int i, final PointInCanvas off) {
		final int w = x_max - x_min;
		final int h = y_max - y_min;
		final int d = z_max - z_min;
		return new int[]{
				Math.clamp(Math.round(gridX(p, i, off) - x_min), 0, w),
				Math.clamp(Math.round(gridY(p, i, off) - y_min), 0, h),
				Math.clamp(Math.round(gridZ(p, i, off) - (z_min - 1)), 0, d)};
	}

	// TRIM_LENGTH_UM expressed in the spacing units of the data
	private double trimLength() {
		final String unit = snt.getSpacingUnits();
		if ("mm".equalsIgnoreCase(unit)) return TRIM_LENGTH_UM / 1000d;
		if ("nm".equalsIgnoreCase(unit)) return TRIM_LENGTH_UM * 1000d;
		return TRIM_LENGTH_UM;
	}

	// Metrics of every retraced segment, plus the original one, judged on the unfiltered crop (the filtered tiles are
	// what the cost function optimizes, so they cannot be used to judge its result)
	private void computeMetrics(final RandomAccessibleInterval<FloatType> raw) {
		try {
			final int n = sigmaValues.length;
			final Path[] paths = new Path[n + 1];
			for (int i = 0; i < n; i++) paths[i] = (traces[i] != null && traces[i].ok()) ? traces[i].path() : null;
			paths[n] = segment.reference();
			metrics = CandidatePathMetrics.compute(paths, WizardSupport.as3D(raw), WizardSupport.metricsRadius(),
					() -> false);
			sigmaMetrics = Arrays.copyOf(metrics, n); // the reference row does not compete for 'best'
		} catch (final RuntimeException ex) {
			SNTUtils.log("Sigma palette: path metrics could not be computed: " + ex.getMessage());
			metrics = null;
			metricsNote = "Path metrics could not be computed";
		}
	}

	// Radius of the median filter for a given sigma. NB: Must match the conversion used when the filter is
	// actually applied (see ComputeSecondaryImg), so that the preview is what the user gets
	private int medianRadius(final double sigma) {
		final double r = (image.getNSlices() == 1)
				? (sigma / cal.pixelWidth + sigma / cal.pixelHeight) / 2d
				: (sigma / cal.pixelWidth + sigma / cal.pixelHeight + sigma / cal.pixelDepth) / 3d;
		return (int) Math.round(r);
	}

	// First sigma yielding each distinct (non-zero) median radius, in the original order. A zero radius is a no-op
	private double[] distinctMedianSigmas(final double[] sigmas) {
		final java.util.Map<Integer, Double> byRadius = new java.util.LinkedHashMap<>();
		for (final double sigma : sigmas) {
			final int radius = medianRadius(sigma);
			if (radius > 0) byRadius.putIfAbsent(radius, sigma);
		}
		if (byRadius.isEmpty()) return new double[]{sigmas[sigmas.length - 1]};
		return byRadius.values().stream().mapToDouble(Double::doubleValue).toArray();
	}

	// Number of columns for a full grid of n tiles: the largest divisor of n that is <= the preferred number of
	// columns, or a single row if there is none
	private static int gridColumns(final int n, final int preferred) {
		for (int cols = Math.min(preferred, n); cols > 1; cols--) {
			if (n % cols == 0) return cols;
		}
		return Math.max(1, n);
	}

	// Number of tiles to filter concurrently: bounded by cores and by a rough estimate of memory per tile (result
	// plus filter intermediates), to avoid exhausting the heap on large crops
	private int tileParallelism(final long voxels) {
		final long perTask = Math.max(1, voxels * Float.BYTES * 10);
		final int byMemory = (int) Math.max(1, (Runtime.getRuntime().maxMemory() / 2) / perTask);
		return Math.max(1, Math.min(Math.min(Runtime.getRuntime().availableProcessors(), 8), byMemory));
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

	// Filters the whole cropped input with a single sigma. Safe to call concurrently
	private RandomAccessibleInterval<FloatType> filterTile(final RandomAccessibleInterval<FloatType> input,
														   final ImagePlus cropped,
								 final double sigma, final double[] spacing, final double stackMax) {
		// NB: singleton dimensions are dropped so that result has the same dimensionality as input
		final RandomAccessibleInterval<FloatType> result = Views.dropSingletonDimensions(
				ArrayImgs.floats(cropped.getWidth(), cropped.getHeight(), cropped.getStackSize()));
		switch (analysisType) {
			case TUBENESS -> new Tubeness<FloatType, FloatType>(new double[]{sigma}, spacing).compute(input, result);
			case FRANGI -> new Frangi<FloatType, FloatType>(new double[]{sigma}, spacing, stackMax)
					.compute(input, result);
			case GAUSS -> opService.filter().gauss(result, input, sigma / spacing[0], sigma / spacing[1],
					sigma / spacing[2]);
			case MEDIAN -> opService.filter().median(result, input, new DiamondShape(medianRadius(sigma)));
			default -> throw new IllegalArgumentException("Unknown filter type");
		}
		return result;
	}

	@Override
	public void run() {
		WizardSupport.busy(snt, this::build);
	}

	private void build() {
		WizardSupport.status(snt, "Sigma wizard: reading region...");

        final int croppedWidth = (x_max - x_min) + 1;
        final int croppedHeight = (y_max - y_min) + 1;
		final int croppedDepth = (z_max - z_min) + 1;

		final Roi existingRoi = image.getRoi();
		image.setRoi(x_min, y_min, croppedWidth, croppedHeight);
		final ImagePlus cropped = image.crop(z_min + "-" + z_max);
		image.setRoi(existingRoi);

		final double[] spacing = new double[]{cal.pixelWidth, cal.pixelHeight, cal.pixelDepth};
		// Stream mode: avoid a whole-image stats pass by using the crop's own range
		double frangiMax = Double.NaN;
		if (analysisType == SNT.FilterType.FRANGI) {
			snt.seedStatsFromSubVolume(cropped);
			frangiMax = snt.getStats().max;
			if (frangiMax == 0) { // check once, before doing any work
				new GuiUtils().error("Statistics for the main image have not been computed yet. "
						+ "Please trace a small path over a relevant feature to compute them. "
						+ "This will allow SNT to better understand the dynamic range of the image.");
				dismiss(); // notifies listeners, so that the caller does not wait for a palette that will not show
				return;
			}
		}
		final double stackMax = frangiMax;
		findSegment();
		final Segment seg = segment;
		final SNT.CostType costType = snt.getCostType();

		final int paletteWidth = croppedWidth * sigmasAcross + (sigmasAcross + 1);
		final int paletteHeight = croppedHeight * sigmasDown + (sigmasDown + 1);

		final ImageStack newStack = new ImageStack(paletteWidth, paletteHeight);
		for (int z = 0; z < croppedDepth; ++z) {
			final FloatProcessor fp = new FloatProcessor(paletteWidth, paletteHeight);
			newStack.addSlice("", fp);
		}
		paletteImage = new ImagePlus("Pick Sigmas", newStack);
		paletteImage.setZ(initial_z - z_min + 1);
		// Helper bundles LUT / MIP / snapshot / dismiss menu state. Created
		// before the window so we can apply the "reset" LUT here and reuse
		// the same instance from PaletteStackWindow's More » button later.
		paletteOptions = new PaletteOptions(paletteImage, image, "Sigmas_Snapshot", this::dismiss);
		paletteOptions.applyLut("reset");

		// Tiles are independent: filter them concurrently, and copy each result into the palette (disjoint regions)
		// as soon as it is available, so that only a few results are alive at any time. The input is converted once
		final RandomAccessibleInterval<FloatType> input = ImageJFunctions.convertFloat(cropped);
		WizardSupport.status(snt, "Sigma wizard: filtering " + sigmaValues.length + " scales"
				+ ((seg != null) ? " and tracing..." : "..."));
		final int nThreads = tileParallelism((long) croppedWidth * croppedHeight * croppedDepth);
		final ExecutorService pool = Executors.newFixedThreadPool(nThreads);
		try {
			final CompletionService<Integer> completion = new ExecutorCompletionService<>(pool);
			final double[] tileMax = new double[sigmaValues.length];
			final ImagePlus[] results = new ImagePlus[sigmaValues.length];
			if (seg != null) traces = new WizardSupport.TraceResult[sigmaValues.length];
			for (int sigmaIndex = 0; sigmaIndex < sigmaValues.length; ++sigmaIndex) {
				final int idx = sigmaIndex;
				completion.submit(() -> {
					final RandomAccessibleInterval<FloatType> result = filterTile(input, cropped, sigmaValues[idx],
							spacing, stackMax);
					final ImagePlus processed = ImageJFunctions.wrap(result, "");
					tileMax[idx] = processed.getStatistics(ImagePlus.MIN_MAX).max;
					results[idx] = processed;
					// Retrace the segment on this tile, while the filtered data is still around
					// NB: 2D tiles have no singleton z, but the tracer works on 3D data
					if (seg != null) traces[idx] = WizardSupport.trace(snt,
							WizardSupport.as3D(result), cal, costType,
							seg.start(), seg.end(), TRACE_TIMEOUT_SECONDS);
					return idx;
				});
			}
			for (int done = 0; done < sigmaValues.length; ++done) {
				final int idx = completion.take().get(); // rethrows a failed tile
				final int offsetX = (idx % sigmasAcross) * (croppedWidth + 1) + 1;
				final int offsetY = (idx / sigmasAcross) * (croppedHeight + 1) + 1;
				copyIntoPalette(results[idx], paletteImage, offsetX, offsetY);
				results[idx] = null; // release
				suggestedMax = Math.max(tileMax[idx], suggestedMax);
			}
		} catch (final InterruptedException | ExecutionException ex) {
			if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
			SNTUtils.error("Sigma palette: filtering failed", (ex.getCause() != null) ? ex.getCause() : ex);
			dismiss();
			return;
		} finally {
			pool.shutdownNow();
		}
		setMax(suggestedMax); // once, instead of after every tile
		if (seg != null) {
			WizardSupport.status(snt, "Sigma wizard: computing metrics...");
			computeMetrics(input);
		}
		listeners.forEach(SigmaPaletteListener::paletteDisplayed);
		final PaletteCanvas paletteCanvas = new PaletteCanvas(paletteImage, croppedWidth, croppedHeight,
			sigmasAcross, sigmasDown);
		paletteWindow = new PaletteStackWindow(paletteImage, paletteCanvas, suggestedMax);
		paletteCanvas.requestFocusInWindow(); // required to trigger keylistener events
	}

	// createMIP / disposeMIP / updateMIP / applyLUT were moved to PaletteOptions
	// so the same logic could be reused by the CostPalette wizard. Internal
	// callers (setMax → updateMip, run → applyLut("reset")) now delegate via
	// the paletteOptions field.

	public void addListener(final SigmaPaletteListener listener) {
		if (listener != null) listeners.add(listener);
	}

	public void removeListener(final SigmaPaletteListener listener) {
		listeners.remove(listener);
		if (listeners.isEmpty()) dismiss();
	}

}
