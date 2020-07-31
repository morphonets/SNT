package sc.fiji.snt.viewer;

import com.mxgraph.layout.mxCircleLayout;
import com.mxgraph.layout.mxFastOrganicLayout;
import com.mxgraph.layout.mxGraphLayout;
import com.mxgraph.layout.mxParallelEdgeLayout;

import com.mxgraph.util.mxPoint;
import org.scijava.Context;

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.plugin.GraphAdapterMapperCmd;

import javax.swing.*;

import java.awt.*;
import java.awt.event.*;
import java.util.HashMap;
import java.util.Map;

public class AnnotationGraphComponent extends SNTGraphComponent {

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

    @SuppressWarnings("unused")
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

}
