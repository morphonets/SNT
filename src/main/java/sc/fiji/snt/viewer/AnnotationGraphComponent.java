package sc.fiji.snt.viewer;

import com.mxgraph.layout.mxCircleLayout;
import com.mxgraph.layout.mxFastOrganicLayout;
import com.mxgraph.layout.mxGraphLayout;
import com.mxgraph.layout.mxParallelEdgeLayout;

import org.scijava.Context;

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.plugin.GraphAdapterMapperCmd;

import javax.swing.*;

import java.awt.*;
import java.awt.event.*;
import java.util.HashMap;
import java.util.Map;

class AnnotationGraphComponent extends SNTGraphComponent {

    private static final long serialVersionUID = 1L;
    private mxGraphLayout layout;
    // Layout parameters, these are all the default value unless specified
    // Fast organic layout parameters
    private double forceConstant = 50;
    private double minDistance = 2;
    private double maxDistance = 500;
    private double initialTemp = 200;
    private double maxIterations = 0;
    // Circle layout parameters
    private double radius = 100;

    protected AnnotationGraphComponent(final AnnotationGraphAdapter adapter, Context context) {
        super(adapter, context);
        layout = new mxCircleLayout(adapter);
        layout.execute(adapter.getDefaultParent());
        new mxParallelEdgeLayout(adapter).execute(adapter.getDefaultParent());
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
            new mxParallelEdgeLayout(adapter).execute(adapter.getDefaultParent());
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

}
