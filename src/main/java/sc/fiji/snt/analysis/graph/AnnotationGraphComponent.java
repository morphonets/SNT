package sc.fiji.snt.analysis.graph;

import com.mxgraph.layout.mxCircleLayout;
import com.mxgraph.layout.mxFastOrganicLayout;
import com.mxgraph.layout.mxGraphLayout;
import com.mxgraph.layout.mxParallelEdgeLayout;
import com.mxgraph.model.mxGeometry;
import com.mxgraph.swing.handler.mxKeyboardHandler;
import com.mxgraph.swing.handler.mxRubberband;
import com.mxgraph.swing.mxGraphComponent;
import com.mxgraph.swing.mxGraphOutline;
import com.mxgraph.util.mxCellRenderer;
import com.mxgraph.view.mxGraphView;

import org.scijava.Context;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;

import org.w3c.dom.Document;

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.plugin.GraphAdapterMapperCmd;

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
import java.util.HashMap;
import java.util.Map;

public class AnnotationGraphComponent extends mxGraphComponent {
    @Parameter
    private CommandService cmdService;
    private static final long serialVersionUID = 1L;
    private final AnnotationGraphAdapter adapter;
    private mxGraphLayout layout;
    private final KeyboardHandler keyboardHandler;
    private final JCheckBoxMenuItem panMenuItem;
    private File saveDir;
    // Layout parameters, these are all the default value unless specified
    // Fast organic layout parameters
    private double forceConstant = 50;
    private double minDistance = 2;
    private double maxDistance = 500;
    private double initialTemp = 200;
    private double maxIterations = 0;
    // Circular layout parameters
    private double radius = 100;

    protected AnnotationGraphComponent(final AnnotationGraphAdapter adapter, Context context) {
        super(adapter);
        context.inject(this);
        this.adapter = adapter;
        addMouseWheelListener(new AnnotationGraphComponent.ZoomMouseWheelListener());
        setKeepSelectionVisibleOnZoom(true);
        setCenterZoom(true);
        //setCenterPage(true);
        //setPageVisible(true);
        //setPreferPageSize(true);
        addComponentListener(new InitialComponentResizeListener());
        addRubberBandZoom();
        panningHandler = createPanningHandler();
        setPanning(true);
        keyboardHandler = new AnnotationGraphComponent.KeyboardHandler(this);
        setEscapeEnabled(true);
        setAntiAlias(true);
        setDoubleBuffered(true);
        setConnectable(true);
        setFoldingEnabled(true);
        setDragEnabled(false);
        setToolTips(true);
        layout = new mxCircleLayout(adapter);
        layout.execute(adapter.getDefaultParent());
        new mxParallelEdgeLayout(adapter).execute(adapter.getDefaultParent());
        panMenuItem = new JCheckBoxMenuItem("Pan Mode");
    }

    class InitialComponentResizeListener implements ComponentListener {

        @Override
        public void componentHidden(ComponentEvent e) {
        }

        @Override
        public void componentMoved(ComponentEvent e) {
        }

        @Override
        public void componentResized(ComponentEvent e) {
          e.getComponent().removeComponentListener(this);
            zoomToFitHorizontal();
        }

        @Override
        public void componentShown(ComponentEvent e) {

        }

      }

	public void zoomToFitHorizontal() {
		// See https://www.javatips.net/api/bundlemaker-master/bundlemaker.incubator/
		// org.bundlemaker.core.ui.editor.dependencyviewer/src/org/bundlemaker/core/ui/
		// editor/dependencyviewer/graph/DependencyViewerGraph.java
		mxGraphView view = graph.getView();
		int compLen = getWidth();
		int viewLen = (int) view.getGraphBounds().getWidth();

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

    protected Component getJSplitPane() {
        // Default dimensions are exaggerated. Curb them a bit
        setPreferredSize(getPreferredSize());
        assignPopupMenu(this);
        centerGraph();
        requestFocusInWindow();
        return new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, getControlPanel(), this);
    }

    private JComponent getControlPanel() {
        final JPanel buttonPanel = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = new GridBagConstraints();
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridwidth = GridBagConstraints.REMAINDER;

        GuiUtils.addSeparator(buttonPanel, "Navigation:", true, gbc);
        JButton button = new JButton("Zoom In");
        button.setToolTipText("[+] or Shift + Mouse Wheel");
        button.addActionListener(e -> {
            zoomIn();
        });
        buttonPanel.add(button, gbc);
        button = new JButton("Zoom Out");
        button.setToolTipText("[-] or Shift + Mouse Wheel");
        button.addActionListener(e -> {
            zoomOut();
        });
        buttonPanel.add(button, gbc);
        button = new JButton("Reset Zoom");
        button.addActionListener(e -> {
            zoomActual();
            zoomAndCenter();
        });
        buttonPanel.add(button, gbc);
        button = new JButton("Center");
        button.addActionListener(e -> {
            centerGraph();
        });
        buttonPanel.add(button, gbc);
        panMenuItem.addActionListener(e -> getPanningHandler().setEnabled(panMenuItem.isSelected()));
        buttonPanel.add(panMenuItem, gbc);

        GuiUtils.addSeparator(buttonPanel, "Layout:", true, gbc);
        final JButton layoutButton = new JButton("Choose Layout");
        final JPopupMenu layoutPopup = new JPopupMenu();
        final JButton circleLayoutButton = new JButton("Circular");
        circleLayoutButton.addActionListener(e -> {
            mxCircleLayout circleLayout = new mxCircleLayout(adapter);
            circleLayout.setRadius(radius);
            circleLayout.execute(adapter.getDefaultParent());
            new mxParallelEdgeLayout((adapter)).execute(adapter.getDefaultParent());
            zoomActual();
            zoomAndCenter();
            centerGraph();

        });
        layoutPopup.add(circleLayoutButton);
        final JButton fastOrganicLayoutButton = new JButton("Fast Organic");
        fastOrganicLayoutButton.addActionListener(e -> {
            mxFastOrganicLayout fastOrganicLayout = new mxFastOrganicLayout(adapter);
            fastOrganicLayout.setForceConstant(forceConstant);
            fastOrganicLayout.setMinDistanceLimit(minDistance);
            fastOrganicLayout.setMaxDistanceLimit(maxDistance);
            fastOrganicLayout.setInitialTemp(initialTemp);
            fastOrganicLayout.setMaxIterations(maxIterations);
            fastOrganicLayout.execute(adapter.getDefaultParent());
            new mxParallelEdgeLayout((adapter)).execute(adapter.getDefaultParent());
            zoomActual();
            zoomAndCenter();
            centerGraph();
        });
        layoutPopup.add(fastOrganicLayoutButton);

        layoutButton.addMouseListener(new MouseAdapter() {
            public void mousePressed(final MouseEvent e) {
                layoutPopup.show(layoutButton, e.getX(), e.getY());
            }
        });
        buttonPanel.add(layoutButton, gbc);

        button = new JButton("Reset");
        button.addActionListener(e -> {
            zoomActual();
            zoomAndCenter();
        });
        buttonPanel.add(button, gbc);
        final JButton labelsButton = new JButton("Labels");
        final JPopupMenu lPopup = new JPopupMenu();
        final JCheckBox vCheckbox = new JCheckBox("Vertices (Node ID)", adapter.isVertexLabelsEnabled());
        vCheckbox.addActionListener(e -> {
            adapter.setEnableVertexLabels(vCheckbox.isSelected());
        });
        lPopup.add(vCheckbox);
        final JCheckBox eCheckbox = new JCheckBox("Edges (Inter-node distance)", adapter.isEdgeLabelsEnabled());
        eCheckbox.addActionListener(e -> {
            adapter.setEnableEdgeLabels(eCheckbox.isSelected());
        });
        lPopup.add(eCheckbox);
        labelsButton.addMouseListener(new MouseAdapter() {
            public void mousePressed(final MouseEvent e) {
                lPopup.show(labelsButton, e.getX(), e.getY());
            }
        });
        buttonPanel.add(labelsButton, gbc);
        final JButton layoutSettingsButton = new JButton("Layout settings");
        layoutSettingsButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setAutomaticLayoutPrefs();
            }
        });
        buttonPanel.add(layoutSettingsButton, gbc);

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

    private void setAutomaticLayoutPrefs() {
        JTextField forceConstantField = new JTextField(SNTUtils.formatDouble(forceConstant, 2), 2);
        JTextField minDistanceField = new JTextField(SNTUtils.formatDouble(minDistance, 2), 2);
        JTextField maxDistanceField = new JTextField(SNTUtils.formatDouble(maxDistance, 2), 2);
        JTextField initialTempField = new JTextField(SNTUtils.formatDouble(initialTemp, 2), 2);
        JTextField maxIterationsField = new JTextField(SNTUtils.formatDouble(maxIterations, 1), 2);
        JTextField radiusField = new JTextField(SNTUtils.formatDouble(radius, 2), 2);

        JPanel myPanel = new JPanel();
        myPanel.setLayout(new BoxLayout(myPanel, BoxLayout.Y_AXIS));
        myPanel.add(new JLabel("<html><b>Fast-organic layout settings"));
        myPanel.add(new JLabel("<html>Force Constant"));
        myPanel.add(forceConstantField);
        myPanel.add(new JLabel("<html><br>Minimum distance"));
        myPanel.add(minDistanceField);
        myPanel.add(new JLabel("<html><br>Maximum distance"));
        myPanel.add(maxDistanceField);
        myPanel.add(new JLabel("<html><br>Initial Temperature"));
        myPanel.add(initialTempField);
        myPanel.add(new JLabel("<html><br>Maximum iterations"));
        myPanel.add(maxIterationsField);
        myPanel.add(Box.createVerticalStrut(30)); // a spacer
        myPanel.add(new JLabel("<html><b>Circle layout settings"));
        myPanel.add(new JLabel("<html><br>Radius"));
        myPanel.add(radiusField);

        int result = JOptionPane.showConfirmDialog(null, myPanel,
                "Please Specify Options", JOptionPane.OK_CANCEL_OPTION);
        if (result == JOptionPane.OK_OPTION) {
            double input = GuiUtils.extractDouble(forceConstantField);
            if (Double.isNaN(input) || input <= 0) {
                GuiUtils.errorPrompt("Force constant must be positive number.");
                return;
            }
            forceConstant = input;
            input = GuiUtils.extractDouble(minDistanceField);
            if (Double.isNaN(input) || input <= 0) {
                GuiUtils.errorPrompt("Minimum distance limit must be positive.");
                return;
            }
            minDistance = input;
            input = GuiUtils.extractDouble(maxDistanceField);
            if (Double.isNaN(input) || input <= 0) {
                GuiUtils.errorPrompt("Maximum distance limit must be positive.");
                return;
            }
            maxDistance = input;
            input = GuiUtils.extractDouble(initialTempField);
            if (Double.isNaN(input) || input <= 0) {
                GuiUtils.errorPrompt("Initial temp must be positive.");
                return;
            }
            initialTemp = input;
            input = GuiUtils.extractDouble(maxIterationsField);
            if (Double.isNaN(input) || input < 0) {
                GuiUtils.errorPrompt("Maximum iterations must be non-negative.");
                return;
            }
            maxIterations = input;
            input = Double.parseDouble(radiusField.getText());
            if (Double.isNaN(input) || input <= 0) {
                GuiUtils.errorPrompt("Radius must be positive.");
                return;
            }
            radius = input;
        }
    }

    @Override
    public void zoomIn() {
        super.zoomIn();
        centerGraph();
    }

    @Override
    public void zoomOut() {
        super.zoomOut();
        centerGraph();
    }

    private void addRubberBandZoom() {
        new mxRubberband(this) {

            @Override
            public void mouseReleased(final MouseEvent e) {
                if (e.isAltDown()) {
                    // get bounds before they are reset
                    final Rectangle rect = super.bounds;

                    super.mouseReleased(e);
                    if (rect == null)
                        return;

                    double newScale = 1d;
                    final Dimension graphSize = new Dimension(rect.width, rect.height);
                    final Dimension viewPortSize = graphComponent.getViewport().getSize();

                    final int gw = (int) graphSize.getWidth();
                    final int gh = (int) graphSize.getHeight();

                    if (gw > 0 && gh > 0) {
                        final int w = (int) viewPortSize.getWidth();
                        final int h = (int) viewPortSize.getHeight();
                        newScale = Math.min((double) w / gw, (double) h / gh);
                    }

                    // zoom to fit selected area
                    graphComponent.zoom(newScale);

                    // make selected area visible
                    graphComponent.getGraphControl().scrollRectToVisible(new Rectangle((int) (rect.x * newScale),
                            (int) (rect.y * newScale), (int) (rect.width * newScale), (int) (rect.height * newScale)));
                } else {
                    super.mouseReleased(e);
                }
            }
        };
    }

    private void assignPopupMenu(final JComponent component) {
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
        mItem.addActionListener(e -> {
            zoomActual();
            zoomAndCenter();
            centerGraph();
        });
        popup.add(mItem);
        popup.addSeparator();
        mItem = new JMenuItem("Available Shortcuts...");
        mItem.addActionListener(e -> keyboardHandler.displayKeyMap());
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

    @Override
    public Dimension getPreferredSize() {
        final double width = adapter.getGraphBounds().getWidth();
        final double height = adapter.getGraphBounds().getHeight();
        return new Dimension((int) Math.min(600, width), (int) Math.min(400, height));
    }

    private void centerGraph() {
        // https://stackoverflow.com/a/36947526
        final double widthLayout = getLayoutAreaSize().getWidth();
        final double heightLayout = getLayoutAreaSize().getHeight();
        final double width = adapter.getGraphBounds().getWidth();
        final double height = adapter.getGraphBounds().getHeight();
        adapter.getModel().setGeometry(adapter.getDefaultParent(),
                new mxGeometry((widthLayout - width) / 2, (heightLayout - height) / 2, widthLayout, heightLayout));
    }

    private JMenuItem saveAsMenuItem(final String label, final String extension) {
        final JMenuItem menuItem = new JMenuItem(label);
        menuItem.addActionListener(e -> export(extension));
        return menuItem;
    }

    private void export(final String extension) {
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

    private File getSaveDir() {
        if (saveDir == null)
            return new File(System.getProperty("user.home"));
        return saveDir;
    }

    private void exportDocument(final Document doc, final File file) throws TransformerException {
        final Transformer transformer = TransformerFactory.newInstance().newTransformer();
        final Result output = new StreamResult(file);
        final Source input = new DOMSource(doc);
        transformer.transform(input, output);
    }

    @Override
    public boolean isPanningEvent(final MouseEvent event) {
        return panMenuItem.isSelected();
    }

    private class ZoomMouseWheelListener implements MouseWheelListener {
        @Override
        public void mouseWheelMoved(final MouseWheelEvent e) {
            if (e.getSource() instanceof mxGraphOutline || e.isShiftDown()) {
                if (e.getWheelRotation() < 0) {
                    AnnotationGraphComponent.this.zoomIn();
                } else {
                    AnnotationGraphComponent.this.zoomOut();
                }

            }
        }
    }

    private class KeyboardHandler extends mxKeyboardHandler {

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

        private void displayKeyMap() {
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
