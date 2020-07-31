package sc.fiji.snt.viewer;

import com.mxgraph.model.mxGeometry;
import com.mxgraph.swing.handler.mxKeyboardHandler;
import com.mxgraph.swing.mxGraphComponent;
import com.mxgraph.swing.mxGraphOutline;
import com.mxgraph.swing.view.mxCellEditor;
import com.mxgraph.util.mxCellRenderer;
import com.mxgraph.view.mxGraphView;
import org.scijava.Context;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;
import org.w3c.dom.Document;
import sc.fiji.snt.gui.GuiUtils;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

@SuppressWarnings("serial")
public class SNTGraphComponent extends mxGraphComponent {

    @Parameter
    protected CommandService cmdService;
    protected final SNTGraphAdapter adapter;
    protected File saveDir;
	private boolean spaceDown = false;

    protected SNTGraphComponent(SNTGraphAdapter adapter, Context context) {
        super(adapter);
        context.inject(this);
        this.adapter = adapter;
        //setCenterZoom(true);
        //setCenterPage(true);
        //setPreferPageSize(true);
        setEscapeEnabled(true);
        //setFoldingEnabled(true);
        setToolTips(true);

        // rendering and performance
        setPageVisible(false);
        setTripleBuffered(false);
        setAntiAlias(true);
        setTextAntiAlias(true);

        // cell editing
		((mxCellEditor) getCellEditor()).setShiftEnterSubmitsText(true);

        // By default disable editing options. These can be toggled in GraphEditor
        // if the user is really willing to  change the graph's connectivity
        setDragEnabled(false);
        setConnectable(false);
        graph.setCellsDisconnectable(false);
        graph.setDropEnabled(false);
        graph.setDisconnectOnMove(false);

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
        mxGeometry geo = adapter.getModel().setGeometry(adapter.getDefaultParent(),
                new mxGeometry((widthLayout - width) / 2, (heightLayout - height) / 2, widthLayout, heightLayout));
    }

//    @Override
//    public boolean isPanningEvent(final MouseEvent event) {
//        return panMenuItem.isSelected();
//    }

    protected JMenuItem saveAsMenuItem(final String label, final String extension) {
        final JMenuItem menuItem = new JMenuItem(label);
        menuItem.addActionListener(e -> export(extension));
        return menuItem;
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

    private class ZoomMouseWheelListener implements MouseWheelListener {
        @Override
        public void mouseWheelMoved(final MouseWheelEvent e) {
            if (e.getSource() instanceof mxGraphOutline || e.isShiftDown()) {
                if (e.getWheelRotation() < 0) {
                    SNTGraphComponent.this.zoomIn();
                } else {
                    SNTGraphComponent.this.zoomOut();
                }

            }
        }
    }

    protected static class KeyboardHandler extends mxKeyboardHandler {

        public KeyboardHandler(mxGraphComponent graphComponent) {
            super(graphComponent);
        }

        protected InputMap getInputMap(int condition) {
            final InputMap map = super.getInputMap(condition);
            if (condition == JComponent.WHEN_FOCUSED) {
                map.put(KeyStroke.getKeyStroke("EQUALS"), "zoomIn");
                map.put(KeyStroke.getKeyStroke("control EQUALS"), "zoomIn");
                map.put(KeyStroke.getKeyStroke("MINUS"), "zoomOut");
                map.put(KeyStroke.getKeyStroke("control MINUS"), "zoomOut");
            }
            return map;
        }

        protected void displayKeyMap() {
            final InputMap inputMap = getInputMap(JComponent.WHEN_FOCUSED);
            final KeyStroke[] keys = inputMap.allKeys();
            final ArrayList<String> lines = new ArrayList<>();
            final String common = "<span style='display:inline-block;width:100px;font-weight:bold'>";
            if (keys != null) {
                for (int i = 0; i < keys.length; i++) {
                    final KeyStroke key = keys[i];
                    final String keyString = key.toString().replace("pressed", "");
                    lines.add(common + keyString + "</span>&nbsp;&nbsp;" + inputMap.get(key));
                }
                Collections.sort(lines);
            }
            GuiUtils.showHTMLDialog("<HTML>" + String.join("<br>", lines), "Dendrogram Viewer Shortcuts");
        }
    }

}
