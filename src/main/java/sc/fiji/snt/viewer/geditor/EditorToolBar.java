package sc.fiji.snt.viewer.geditor;

import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
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

import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.gui.IconFactory.GLYPH;

public class EditorToolBar extends JToolBar
{

	/**
	 * 
	 */
	private static final long serialVersionUID = -8015443128436394471L;

	/**
	 * 
	 * @param frame
	 * @param orientation
	 */
	private boolean ignoreZoomChange = false;

	/**
	 * 
	 */
	@SuppressWarnings("rawtypes")
	public EditorToolBar(final BasicGraphEditor editor, final int orientation)
	{
		super(orientation);
		setBorder(BorderFactory.createCompoundBorder(BorderFactory
				.createEmptyBorder(3, 3, 3, 3), getBorder()));
		setFloatable(true);
		setFocusable(false);

		add(editor.bind("Open", new EditorActions.OpenAction(), IconFactory.getButtonIcon(GLYPH.OPEN_FOLDER, 1f)))
				.setToolTipText("Open");
		add(editor.bind("Save", new EditorActions.SaveAction(false), IconFactory.getButtonIcon(GLYPH.SAVE, 1f)))
				.setToolTipText("Save");
		addSeparator();

		add(editor.bind("Cut", TransferHandler.getCutAction(),
                "/mx_shape_images/cut.gif"));
		add(editor.bind("Copy", TransferHandler.getCopyAction(),
                "/mx_shape_images/copy.gif"));
		add(editor.bind("Paste", TransferHandler.getPasteAction(),
                "/mx_shape_images/paste.gif"));

		addSeparator();

		add(editor.bind("Delete", mxGraphActions.getDeleteAction(),
			IconFactory.getButtonIcon(GLYPH.DELETE, 1f))).setToolTipText("Delete Selected Object(s)");

		addSeparator();
		add(editor.bind("Undo", new EditorActions.HistoryAction(true), IconFactory.getButtonIcon(GLYPH.UNDO, 1f)))
				.setToolTipText("Undo");
		add(editor.bind("Redo", new EditorActions.HistoryAction(false), IconFactory.getButtonIcon(GLYPH.REDO, 1f)))
				.setToolTipText("Redo");
		addSeparator();

		// Gets the list of available fonts from the local graphics environment
		// and adds some frequently used fonts at the beginning of the list
		final GraphicsEnvironment env = GraphicsEnvironment
				.getLocalGraphicsEnvironment();
		final List<String> fonts = new ArrayList<String>();
		fonts.addAll(Arrays.asList(new String[] { "Helvetica", "Verdana",
				"Times New Roman", "Garamond", "Courier New", "-" }));
		fonts.addAll(Arrays.asList(env.getAvailableFontFamilyNames()));

		@SuppressWarnings("unchecked")
		final JComboBox fontCombo = new JComboBox(fonts.toArray());
		fontCombo.setEditable(false);
		fontCombo.setToolTipText("Labels Typeface & Size");
		add(fontCombo);
		fontCombo.addActionListener(e -> {
			final String font = fontCombo.getSelectedItem().toString();
			if (font != null && !font.equals("-")) {
				final mxGraph graph = editor.getGraphComponent().getGraph();
				graph.setCellStyles(mxConstants.STYLE_FONTFAMILY, font);
			}
		});

		@SuppressWarnings("unchecked")
		final JComboBox sizeCombo = new JComboBox(new Object[] { "6pt", "8pt",
				"9pt", "10pt", "12pt", "14pt", "18pt", "24pt", "30pt", "36pt",
				"48pt", "60pt" });
		sizeCombo.setToolTipText("Labels Typeface & Size");
		sizeCombo.setEditable(false);
		add(sizeCombo);
		sizeCombo.addActionListener(e -> {
			final mxGraph graph = editor.getGraphComponent().getGraph();
			graph.setCellStyles(mxConstants.STYLE_FONTSIZE, sizeCombo
					.getSelectedItem().toString().replace("pt", ""));
		});

		addSeparator();

		add(editor.bind("Bold", new EditorActions.FontStyleAction(true),
                "/mx_shape_images/bold.gif"));
		add(editor.bind("Italic", new EditorActions.FontStyleAction(false),
                "/mx_shape_images/italic.gif"));

		addSeparator();

		add(editor.bind("Left", new EditorActions.KeyValueAction(mxConstants.STYLE_ALIGN,
				mxConstants.ALIGN_LEFT),
                "/mx_shape_images/left.gif"));
		add(editor.bind("Center", new EditorActions.KeyValueAction(mxConstants.STYLE_ALIGN,
				mxConstants.ALIGN_CENTER),
                "/mx_shape_images/center.gif"));
		add(editor.bind("Right", new EditorActions.KeyValueAction(mxConstants.STYLE_ALIGN,
				mxConstants.ALIGN_RIGHT),
                "/mx_shape_images/right.gif"));

		addSeparator();

		add(editor.bind("Font", new EditorActions.ColorAction("Font",
				mxConstants.STYLE_FONTCOLOR),
                "/mx_shape_images/fontcolor.gif"));
		add(editor.bind("Stroke", new EditorActions.ColorAction("Stroke",
				mxConstants.STYLE_STROKECOLOR),
                "/mx_shape_images/linecolor.gif"));
		add(editor.bind("Fill", new EditorActions.ColorAction("Fill",
				mxConstants.STYLE_FILLCOLOR),
                "/mx_shape_images/fillcolor.gif"));

		addSeparator();

		final mxGraphView view = editor.getGraphComponent().getGraph()
				.getView();
		@SuppressWarnings("unchecked")
		final JComboBox zoomCombo = new JComboBox(new Object[] { "400%",
				"200%", "150%", "100%", "75%", "50%", mxResources.get("page"),
				mxResources.get("width"), mxResources.get("actualSize") });
		zoomCombo.setEditable(true);
		zoomCombo.setMaximumRowCount(9);
		add(zoomCombo);

		// Sets the zoom in the zoom combo the current value
		final mxIEventListener scaleTracker = new mxIEventListener()
		{
			/**
			 * 
			 */
			public void invoke(final Object sender, final mxEventObject evt)
			{
				ignoreZoomChange = true;

				try
				{
					zoomCombo.setSelectedItem((int) Math.round(100 * view
							.getScale())
							+ "%");
				}
				finally
				{
					ignoreZoomChange = false;
				}
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
			if (!ignoreZoomChange)
			{
				String zoom = zoomCombo.getSelectedItem().toString();

				if (zoom.equals(mxResources.get("page")))
				{
					graphComponent.setPageVisible(true);
					graphComponent
							.setZoomPolicy(mxGraphComponent.ZOOM_POLICY_PAGE);
				}
				else if (zoom.equals(mxResources.get("width")))
				{
					graphComponent.setPageVisible(true);
					graphComponent
							.setZoomPolicy(mxGraphComponent.ZOOM_POLICY_WIDTH);
				}
				else if (zoom.equals(mxResources.get("actualSize")))
				{
					graphComponent.zoomActual();
				}
				else
				{
					try
					{
						zoom = zoom.replace("%", "");
						final double scale = Math.min(16, Math.max(0.01,
								Double.parseDouble(zoom) / 100));
						graphComponent.zoomTo(scale, graphComponent
								.isCenterZoom());
					}
					catch (final Exception ex)
					{
						JOptionPane.showMessageDialog(editor, ex
								.getMessage());
					}
				}
			}
		});

		final JButton plusShapeSizeButton = new JButton("+");
		plusShapeSizeButton.setPreferredSize(new Dimension(10, 10));
		plusShapeSizeButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent e) {
				final mxGraphComponent graphComponent = editor.getGraphComponent();
				final mxGraph graph = graphComponent.getGraph();
				graph.setCellsResizable(true);
				final Object[] cells = graph.getSelectionCells();
				if (cells == null || cells.length ==0) {
					return;
				}
				for (final Object cell : cells) {
					final mxICell mxc = (mxICell) cell;
					if (graph.getModel().isVertex(mxc)) {
						final mxGeometry geom = mxc.getGeometry();
						final double srcWidth = geom.getWidth();
						final double srcHeight = geom.getHeight();
						final double maxWidth = srcWidth * 1.25;
						final double maxHeight = srcHeight * 1.25;
						final double ratio = Math.min(maxWidth / srcWidth, maxHeight / srcHeight);
						graph.resizeCell(mxc, new mxRectangle(geom.getX(), geom.getY(), srcWidth*ratio, srcHeight*ratio));
					}
				}
			}
		});
		add(plusShapeSizeButton);
		final JButton minusShapeSizeButton = new JButton("-");
		minusShapeSizeButton.setPreferredSize(new Dimension(10, 10));
		minusShapeSizeButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(final ActionEvent e) {
				final mxGraphComponent graphComponent = editor.getGraphComponent();
				final mxGraph graph = graphComponent.getGraph();
				graph.setCellsResizable(true);
				final Object[] cells = graph.getSelectionCells();
				if (cells == null || cells.length ==0) {
					return;
				}
				for (final Object cell : cells) {
					final mxICell mxc = (mxICell) cell;
					if (graph.getModel().isVertex(mxc)) {
						final mxGeometry geom = mxc.getGeometry();
						final double srcWidth = geom.getWidth();
						final double srcHeight = geom.getHeight();
						final double maxWidth = srcWidth * 0.75;
						final double maxHeight = srcHeight * 0.75;
						final double ratio = Math.min(maxWidth / srcWidth, maxHeight / srcHeight);
						graph.resizeCell(mxc, new mxRectangle(geom.getX(), geom.getY(), srcWidth*ratio, srcHeight*ratio));
					}
				}
			}
		});
		add(minusShapeSizeButton);
	}
}
