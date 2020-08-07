package sc.fiji.snt.viewer.geditor;

import com.mxgraph.model.mxGeometry;
import com.mxgraph.swing.mxGraphComponent;
import com.mxgraph.swing.view.mxCellEditor;
import com.mxgraph.util.mxCellRenderer;
import com.mxgraph.view.mxGraphView;

import org.scijava.Context;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;
import org.w3c.dom.Document;
import sc.fiji.snt.gui.GuiUtils;

import javax.imageio.ImageIO;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Collections;

public class SNTGraphComponent extends mxGraphComponent {

	private static final long serialVersionUID = -341155071963647372L;

	@Parameter
    protected CommandService cmdService;
    protected final SNTGraphAdapter<?, ?> adapter;
    protected File saveDir;
	private boolean spaceDown = false;

	private GraphEditor editor;

    protected SNTGraphComponent(SNTGraphAdapter<?, ?> adapter, Context context) {
        super(adapter);
        context.inject(this);
        this.adapter = adapter;
   
        // Zoom into the center of diagram rather than top left;
        setCenterZoom(true);
        // If selected cells exist, include them in viewport after zoom?
        setKeepSelectionVisibleOnZoom(true);

        // disable page layout
        setPageVisible(false);
        // If page is visible, center it in view port?
        setCenterPage(true);

        //setPreferPageSize(true);
        setEscapeEnabled(true);
        //setFoldingEnabled(true);
        setToolTips(true);

        // rendering and performance
        setTripleBuffered(false);
        setAntiAlias(true);
        setTextAntiAlias(true);

        // viewport is not opaque by default. this reduces contrast on dark themes 
		getViewport().setOpaque(true);
		getViewport().setBackground(Color.WHITE);

		// cell editing
		((mxCellEditor) getCellEditor()).setShiftEnterSubmitsText(true);
		setEnterStopsCellEditing(false);

        // By default disable editing of diagram. These can be toggled in GraphEditor's
        // GUI if the user is really willing to  change the graph's connectivity
        setDragEnabled(false);
        setConnectable(false);
        getConnectionHandler().setCreateTarget(false);

        // Use space key for panning (from TrackSchemeGraphComponent)
        getPanningHandler().setEnabled(true);
		addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(final KeyEvent e) {
				if (e.getKeyCode() == 32)
					spaceDown = false;
			}

			@Override
			public void keyPressed(final KeyEvent e) {
				if (e.getKeyCode() == 32 && !spaceDown)
					spaceDown = true;
			}
		});

    }

    @Override
    public boolean isPanningEvent(MouseEvent event) {
    	return (event != null) ? spaceDown : false;
    }

    @SuppressWarnings("unused")
	private void zoomToFitHorizontal() {
        // See https://www.javatips.net/api/bundlemaker-master/bundlemaker.incubator/
        // org.bundlemaker.core.ui.editor.dependencyviewer/src/org/bundlemaker/core/ui/
        // editor/dependencyviewer/graph/DependencyViewerGraph.java
        mxGraphView view = graph.getView();
        int compLen = getWidth();
        int viewLen = (int) getViewportBorderBounds().getWidth();

        if (compLen == 0 || viewLen == 0) {
            return;
        }
        double scale = (double) compLen / viewLen * view.getScale();
        if (scale > 1) {
            zoomActual();
        } else {
            view.setScale(scale);
        }
    }

    @Override
    public Dimension getPreferredSize() {
        final double width = graph.getGraphBounds().getWidth();
        final double height = graph.getGraphBounds().getHeight();
        return new Dimension((int)Math.min(600, width), (int)Math.min(400, height));
    }

    protected void centerGraph() {
        // https://stackoverflow.com/a/36947526
        final double widthLayout = getViewportBorderBounds().getWidth();
        final double heightLayout = getViewportBorderBounds().getHeight();
        final double width = adapter.getGraphBounds().getWidth();
        final double height = adapter.getGraphBounds().getHeight();
        adapter.getModel().setGeometry(adapter.getDefaultParent(),
                new mxGeometry((widthLayout - width) / 2, (heightLayout - height) / 2, widthLayout, heightLayout));
    }

    protected void export(final String extension) {
        final GuiUtils guiUtils = new GuiUtils(this.getParent());
        final File file = new File(getSaveDir(), "exported-graph" + extension);
        final File saveFile = guiUtils.saveFile("Export Graph...", file, Collections.singletonList(extension));
        if (saveFile == null)
            return; // user pressed cancel;
        saveDir = saveFile.getParentFile();
        try {
            switch (extension) {
                case ".png":
                    final BufferedImage image = mxCellRenderer.createBufferedImage(adapter, null, 1, getBackground(), true,
                            null);
                    ImageIO.write(image, "PNG", saveFile);
                    break;
                case ".svg":
                    final Document svgDoc = mxCellRenderer.createSvgDocument(adapter, null, 1, getBackground(), null);
                    exportDocument(svgDoc, saveFile);
                    break;
                case ".html":
                    final Document htmlDoc = mxCellRenderer.createHtmlDocument(adapter, null, 1, getBackground(), null);
                    exportDocument(htmlDoc, saveFile);
                    break;
                default:
                    throw new IllegalArgumentException("Unrecognized extension");
            }
            guiUtils.tempMsg(file.getAbsolutePath() + " saved");

        } catch (IOException | TransformerException e) {
            guiUtils.error("An exception occured while saving file. See Console for details");
            e.printStackTrace();
        }
    }

    protected File getSaveDir() {
        if (saveDir == null)
            return new File(System.getProperty("user.home"));
        return saveDir;
    }

    protected void exportDocument(final Document doc, final File file) throws TransformerException {
        final Transformer transformer = TransformerFactory.newInstance().newTransformer();
        final Result output = new StreamResult(file);
        final Source input = new DOMSource(doc);
        transformer.transform(input, output);
    }

	/**
	 * Assigns an editor. Once and editor has been assigned calling
	 * {@link #refresh()} will also refresh the editor.
	 * 
	 * @param editor the assigned editor
	 */
    protected void assignEditor(GraphEditor editor) {
    	this.editor = editor;
    }
 
	public void replaceGraph(final AnnotationGraphAdapter adapter, final boolean restoreParameters) {
		if (restoreParameters) {
			adapter.setStylesheet(getGraph().getStylesheet());
			final double existingScale = getGraph().getView().getScale();
			setGraph(adapter);
			zoomTo(existingScale, isCenterZoom());
		} else {
			setGraph(adapter);
		}
		refresh();
		if (editor != null) editor.status("Graph updated.. ");
	}

    @Override
	public void refresh() {
    	// If and editor exists, notify it of graph changes for GUI updates
		if (editor != null) editor.refresh();
		super.refresh();
	}
}
