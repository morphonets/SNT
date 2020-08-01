package sc.fiji.snt.viewer.geditor;

import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.*;

import com.mxgraph.model.mxGeometry;
import com.mxgraph.model.mxICell;
import com.mxgraph.swing.mxGraphComponent;
import com.mxgraph.swing.util.mxGraphActions;
import com.mxgraph.util.*;
import com.mxgraph.util.mxEventSource.mxIEventListener;
import com.mxgraph.view.mxGraph;
import com.mxgraph.view.mxGraphView;

import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.gui.IconFactory.GLYPH;

public class EditorToolBar extends JToolBar
{

	private static final long serialVersionUID = -8015443128436394471L;
	private boolean ignoreZoomChange = false;
	private final GraphEditor editor;


	public EditorToolBar(final GraphEditor editor, final int orientation)
	{
		super(orientation);
		this.editor = editor;
		setBorder(BorderFactory.createCompoundBorder(BorderFactory
				.createEmptyBorder(3, 3, 3, 3), getBorder()));
		//setFloatable(true);
		setFocusable(false);

		add(editor.bind("Open", new EditorActions.OpenAction(), IconFactory.getButtonIcon(GLYPH.OPEN_FOLDER, 1f)))
				.setToolTipText("Open");
		add(editor.bind("Save", new EditorActions.SaveAction(false), IconFactory.getButtonIcon(GLYPH.SAVE, 1f)))
				.setToolTipText("Save");
		addSeparator();
		add(Box.createHorizontalGlue());

		add(editor.bind("Cut", TransferHandler.getCutAction(),
				IconFactory.getButtonIcon(GLYPH.CUT, 1f))).setToolTipText("Cut");
		add(editor.bind("Copy", TransferHandler.getCopyAction(),
				IconFactory.getButtonIcon(GLYPH.COPY, 1f))).setToolTipText("Copy");
		add(editor.bind("Paste", TransferHandler.getPasteAction(),
				IconFactory.getButtonIcon(GLYPH.PASTE, 1f))).setToolTipText("Paste");
		addSeparator();

		add(editor.bind("Undo", new EditorActions.HistoryAction(true), IconFactory.getButtonIcon(GLYPH.UNDO, 1f)))
				.setToolTipText("Undo");
		add(editor.bind("Redo", new EditorActions.HistoryAction(false), IconFactory.getButtonIcon(GLYPH.REDO, 1f)))
				.setToolTipText("Redo");
		addSeparator();
		add(Box.createHorizontalGlue());

		// Zoom controls
		final mxGraphView view = editor.getGraphComponent().getGraph()
				.getView();
		final JComboBox<String> zoomCombo = new JComboBox<>(new String[] { "400%",
				"200%", "150%", "100%", "75%", "50%", mxResources.get("page"),
				mxResources.get("width"), mxResources.get("actualSize") });
		zoomCombo.setToolTipText("Zoom levels. Arbitry levels accepted.");
		zoomCombo.setEditable(true);
		zoomCombo.setMaximumRowCount(9);
		add(zoomCombo);

		// Sets the zoom in the zoom combo the current value
		final mxIEventListener scaleTracker = (sender, evt) -> {
			ignoreZoomChange = true;
			try {
				zoomCombo.setSelectedItem((int) Math.round(100 * view.getScale()) + "%");
			} finally {
				ignoreZoomChange = false;
			}
		};

		// Installs the scale tracker to update the value in the combo box
		// if the zoom is changed from outside the combo box
		view.getGraph().getView().addListener(mxEvent.SCALE, scaleTracker);
		view.getGraph().getView().addListener(mxEvent.SCALE_AND_TRANSLATE,
				scaleTracker);

		// Invokes once to sync with the actual zoom value
		scaleTracker.invoke(null, null);

		zoomCombo.addActionListener(e -> {
			final mxGraphComponent graphComponent = editor.getGraphComponent();

			// Zoomcombo is changed when the scale is changed in the diagram
			// but the change is ignored here
			if (!ignoreZoomChange) {
				String zoom = zoomCombo.getSelectedItem().toString();

				if (zoom.equals(mxResources.get("page"))) {
					graphComponent.setPageVisible(true);
					graphComponent.setZoomPolicy(mxGraphComponent.ZOOM_POLICY_PAGE);
				} else if (zoom.equals(mxResources.get("width"))) {
					graphComponent.setPageVisible(true);
					graphComponent.setZoomPolicy(mxGraphComponent.ZOOM_POLICY_WIDTH);
				} else if (zoom.equals(mxResources.get("actualSize"))) {
					graphComponent.zoomActual();
				} else {
					try {
						zoom = zoom.replace("%", "");
						final double scale = Math.min(16, Math.max(0.01, Double.parseDouble(zoom) / 100));
						graphComponent.zoomTo(scale, graphComponent.isCenterZoom());
					} catch (final Exception ex) {
						new GuiUtils(editor).error("Invalid zoom factor.");
						ignoreZoomChange = true;
						try {
							zoomCombo.setSelectedItem((int) Math.round(100 * view.getScale()) + "%");
						} finally {
							ignoreZoomChange = false;
						}
					}
				}
			}
		});

		final JButton zoomOutButton = new JButton(IconFactory.getButtonIcon(GLYPH.SEARCH_MINUS, 1f));
		zoomOutButton.setToolTipText("Zoom out");
		zoomOutButton.addActionListener(e -> editor.getGraphComponent().zoomOut());
		add(zoomOutButton);
		final JButton zoomInButton = new JButton(IconFactory.getButtonIcon(GLYPH.SEARCH_PLUS, 1f));
		zoomInButton.setToolTipText("Zoom in");
		zoomInButton.addActionListener(e -> editor.getGraphComponent().zoomIn());
		add(zoomInButton);
		addSeparator();
		add(Box.createHorizontalGlue());

		// Style controls
		add(editor.bind("Delete", mxGraphActions.getDeleteAction(),
				IconFactory.getButtonIcon(GLYPH.TRASH, 1f))).setToolTipText("Delete Selected cell(s)");
		add(editor.bind("Font", new EditorActions.ColorAction("Font",
				mxConstants.STYLE_FONTCOLOR),
				IconFactory.getButtonIcon(GLYPH.FONT, 1f))).setToolTipText("Font Color");
		add(editor.bind("Stroke", new EditorActions.ColorAction("Stroke",
				mxConstants.STYLE_STROKECOLOR),
				IconFactory.getButtonIcon(GLYPH.PEN, 1f))).setToolTipText("Stroke Color");
		add(editor.bind("Fill", new EditorActions.ColorAction("Fill",
				mxConstants.STYLE_FILLCOLOR),
				IconFactory.getButtonIcon(GLYPH.FILL, 1f))).setToolTipText("Fill Color");
		addSeparator();

		// Vertices size controls
		final JButton minusShapeSizeButton = new JButton(IconFactory.getButtonIcon(GLYPH.MINUS, 1f));
		minusShapeSizeButton.setToolTipText("Decrease size of selected vertices");
		minusShapeSizeButton.addActionListener(e -> {
			final mxGraphComponent graphComponent = editor.getGraphComponent();
			final mxGraph graph = graphComponent.getGraph();
			graph.setCellsResizable(true);
			final Object[] cells = graph.getSelectionCells();
			if (noCellsError(cells)) {
				return;
			}
			graph.getModel().beginUpdate();
			for (final Object cell : cells) {
				final mxICell mxc = (mxICell) cell;
				if (graph.getModel().isVertex(mxc)) {
					final mxGeometry geom = mxc.getGeometry();
					final double srcWidth = geom.getWidth();
					final double srcHeight = geom.getHeight();
					final double maxWidth = srcWidth * 0.833333333;
					final double maxHeight = srcHeight * 0.833333333;
					final double ratio = Math.min(maxWidth / srcWidth, maxHeight / srcHeight);
					graph.resizeCell(mxc, new mxRectangle(geom.getX(), geom.getY(), srcWidth*ratio, srcHeight*ratio));
				}
			}
			graph.getModel().endUpdate();
		});
		add(minusShapeSizeButton);
		final JButton plusShapeSizeButton = new JButton(IconFactory.getButtonIcon(GLYPH.PLUS, 1f));
		plusShapeSizeButton.setToolTipText("Increase size of selected vertices");
		plusShapeSizeButton.addActionListener(e -> {
			final mxGraphComponent graphComponent = editor.getGraphComponent();
			final mxGraph graph = graphComponent.getGraph();
			graph.setCellsResizable(true);
			final Object[] cells = graph.getSelectionCells();
			if (noCellsError(cells)) {
				return;
			}
			graph.getModel().beginUpdate();
			for (final Object cell : cells) {
				final mxICell mxc = (mxICell) cell;
				if (graph.getModel().isVertex(mxc)) {
					final mxGeometry geom = mxc.getGeometry();
					final double srcWidth = geom.getWidth();
					final double srcHeight = geom.getHeight();
					final double maxWidth = srcWidth * 1.20;
					final double maxHeight = srcHeight * 1.20;
					final double ratio = Math.min(maxWidth / srcWidth, maxHeight / srcHeight);
					graph.resizeCell(mxc, new mxRectangle(geom.getX(), geom.getY(), srcWidth*ratio, srcHeight*ratio));
				}
			}
			graph.getModel().endUpdate();
		});
		add(plusShapeSizeButton);
		addSeparator();
		add(Box.createHorizontalGlue());

		// Gets the list of available fonts from the local graphics environment
		// and adds some frequently used fonts at the beginning of the list
		final GraphicsEnvironment env = GraphicsEnvironment
				.getLocalGraphicsEnvironment();
		final List<String> fonts = new ArrayList<String>();
		fonts.addAll(Arrays.asList("Arial", "Helvetica", "Verdana",
				"Garamond", "Courier New"));
		fonts.addAll(Arrays.asList(env.getAvailableFontFamilyNames()));

		final JComboBox<String> fontCombo = new JComboBox<String>(fonts.toArray(new String[0]));
		fontCombo.setPrototypeDisplayValue("DejaVu Sans Condens");
		fontCombo.setEditable(false);
		fontCombo.setToolTipText("Labels Typeface & Size");
		add(fontCombo);
		fontCombo.addActionListener(e -> {
			final String font = fontCombo.getSelectedItem().toString();
			if (font != null) {
				final mxGraph graph = editor.getGraphComponent().getGraph();
				if (!noCellsError(graph.getSelectionCells()))
					graph.setCellStyles(mxConstants.STYLE_FONTFAMILY, font);
			}
		});

		final JComboBox<String> sizeCombo = new JComboBox<>(new String[] { "6pt", "8pt",
				"9pt", "10pt", "12pt", "14pt", "18pt", "24pt", "30pt", "36pt",
				"48pt", "60pt" });
		sizeCombo.setToolTipText("Labels Typeface & Size");
		sizeCombo.setEditable(false);
		add(sizeCombo);
		sizeCombo.addActionListener(e -> {
			final mxGraph graph = editor.getGraphComponent().getGraph();
			if (!noCellsError(graph.getSelectionCells())) {
				graph.setCellStyles(mxConstants.STYLE_FONTSIZE,
						sizeCombo.getSelectedItem().toString().replace("pt", ""));
			}
		});

		add(editor.bind("Bold", new EditorActions.FontStyleAction(true),
				IconFactory.getButtonIcon(GLYPH.BOLD, 1f))).setToolTipText("Bold");
		add(editor.bind("Italic", new EditorActions.FontStyleAction(false),
				IconFactory.getButtonIcon(GLYPH.ITALIC, 1f))).setToolTipText("Italic");
		addSeparator();

		add(editor.bind("Left", new EditorActions.KeyValueAction(mxConstants.STYLE_ALIGN,
				mxConstants.ALIGN_LEFT),
				IconFactory.getButtonIcon(GLYPH.ALIGN_LEFT, 1f))).setToolTipText("Align Left");
		add(editor.bind("Center", new EditorActions.KeyValueAction(mxConstants.STYLE_ALIGN,
				mxConstants.ALIGN_CENTER),
				IconFactory.getButtonIcon(GLYPH.ALIGN_CENTER, 1f))).setToolTipText("Align Center");
		add(editor.bind("Right", new EditorActions.KeyValueAction(mxConstants.STYLE_ALIGN,
				mxConstants.ALIGN_RIGHT),
				IconFactory.getButtonIcon(GLYPH.ALIGN_RIGHT, 1f))).setToolTipText("Align Right");

	}

	private boolean noCellsError(final Object[] cells) {
		final boolean noCells = cells == null || cells.length ==0;
		if (noCells) editor.status("No selection exists!", true);
		return noCells;
	}

}
