package sc.fiji.snt.viewer;

import com.mxgraph.analysis.mxAnalysisGraph;
import com.mxgraph.model.mxICell;
import com.mxgraph.util.mxConstants;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.scijava.Context;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;
import sc.fiji.GraphEditor.editor.BasicGraphEditor;
import sc.fiji.GraphEditor.editor.EditorMenuBar;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.graph.SNTGraph;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.plugin.GraphAdapterMapperCmd;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.text.NumberFormat;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

public class SNTEditorMenuBar extends EditorMenuBar {
    @Parameter
    Context context;

    public enum SNTAnalyzeType {
        COLOR_CODE, SCALE_CELLS
    }

    public SNTEditorMenuBar(BasicGraphEditor editor, Context context) {
        super(editor);
        context.inject(this);
    }

    @Override
    public void createDeveloperMenu() {
        super.createDeveloperMenu();
        menu.add(editor.bind("Color code", new SNTAnalyzeGraph(SNTAnalyzeType.COLOR_CODE, aGraph)));
        menu.add(editor.bind("Edge Scaling", new SNTAnalyzeGraph(SNTAnalyzeType.SCALE_CELLS, aGraph)));

    }

    public class SNTAnalyzeGraph extends AbstractAction {
        /**
         *
         */
        protected SNTAnalyzeType analyzeType;
        mxAnalysisGraph aGraph;

        /**
         * Examples for calling analysis methods from mxGraphStructure
         */
        public SNTAnalyzeGraph(SNTAnalyzeType analyzeType, mxAnalysisGraph aGraph) {
            this.analyzeType = analyzeType;
            this.aGraph = aGraph;

        }

        public void actionPerformed(ActionEvent e) {
            if (e.getSource() instanceof SNTGraphComponent) {
                SNTGraphComponent graphComponent = (SNTGraphComponent) e.getSource();
                SNTGraphAdapter<Object, DefaultWeightedEdge> adapter = (SNTGraphAdapter<Object, DefaultWeightedEdge>) graphComponent.getGraph();

                if (analyzeType == SNTAnalyzeType.COLOR_CODE) {
                    final Map<String, Object> input = new HashMap<>();
                    input.put("adapter", adapter);
                    context.getService(CommandService.class).run(GraphAdapterMapperCmd.class, true, input);
                }

                else if (analyzeType == SNTAnalyzeType.SCALE_CELLS) {
                    JTextField maxWidthField = new JTextField(SNTUtils.formatDouble(5, 2), 5);
                    JRadioButton linearScaleButton = new JRadioButton("linear");
                    linearScaleButton.setSelected(true);
                    JRadioButton logScaleButton = new JRadioButton("log");
                    ButtonGroup bg = new ButtonGroup();
                    bg.add(linearScaleButton);
                    bg.add(logScaleButton);
                    JPanel myPanel = new JPanel();
                    myPanel.setLayout(new BoxLayout(myPanel, BoxLayout.Y_AXIS));
                    myPanel.add(new JLabel("<html><b>Edge Scaling Parameters"));
                    myPanel.add(new JLabel("<html>Max line width"));
                    myPanel.add(maxWidthField);
                    myPanel.add(new JLabel("<html><br>Scale"));
                    myPanel.add(linearScaleButton);
                    myPanel.add(logScaleButton);
                    double newMax = 1;
                    String scale = "linear";
                    int result = JOptionPane.showConfirmDialog(null, myPanel,
                            "Please Specify Options", JOptionPane.OK_CANCEL_OPTION);
                    if (result == JOptionPane.OK_OPTION) {
                        double input = GuiUtils.extractDouble(maxWidthField);
                        if (Double.isNaN(input) || input <= 0) {
                            GuiUtils.errorPrompt("Max width must be > 0");
                            return;
                        }
                        newMax = input;
                        for (Enumeration<AbstractButton> buttons = bg.getElements(); buttons.hasMoreElements();) {
                            AbstractButton button = buttons.nextElement();
                            if (button.isSelected()) {
                                scale = button.getText();
                            }
                        }
                    } else {
                        return;
                    }
                    Object[] cells = adapter.getEdgeToCellMap().values().toArray();
                    if (cells.length == 0) {
                        return;
                    }
                    double newMin = 1.0;
                    double minWeight = Double.MAX_VALUE;
                    double maxWeight = -Double.MAX_VALUE;
                    SNTGraph<Object, DefaultWeightedEdge> sntGraph = adapter.getSourceGraph();
                    for (Object cell : cells) {
                        mxICell mxc = (mxICell) cell;
                        if (!mxc.isEdge()) continue;
                        double weight = sntGraph.getEdgeWeight((DefaultWeightedEdge)adapter.getCellToEdgeMap().get(mxc));
                        if (weight < minWeight) {minWeight = weight;}
                        if (weight > maxWeight) {maxWeight = weight;}
                    }
                    if (scale.equals("linear")) {
                        for (Object cell : cells) {
                            mxICell mxc = (mxICell) cell;
                            if (!mxc.isEdge()) continue;
                            double weight = sntGraph.getEdgeWeight((DefaultWeightedEdge)adapter.getCellToEdgeMap().get(mxc));
                            double scaledWeight = newMin + ((newMax - newMin) / (maxWeight - minWeight)) * (weight - minWeight);
                            graph.setCellStyles(mxConstants.STYLE_STROKEWIDTH, String.valueOf(scaledWeight), new Object[]{mxc});
                        }
                    }
                    else if (scale.equals("log")) {
                        double rightShift = 0;
                        double leftShift = 0;
                        if (minWeight < 1) {
                            rightShift = 1 - minWeight;
                        }
                        else if (minWeight > 1) {
                            leftShift = 1 - minWeight;
                        }
                        for (Object cell : cells) {
                            mxICell mxc = (mxICell) cell;
                            if (!mxc.isEdge()) continue;
                            double weight = sntGraph.getEdgeWeight(
                                    (DefaultWeightedEdge)adapter.getCellToEdgeMap().get(mxc)
                            ) + rightShift + leftShift;
                            double k = newMax / Math.log(maxWeight + rightShift + leftShift);
                            double scaledWeight = k * Math.log(weight) + newMin;
                            graph.setCellStyles(mxConstants.STYLE_STROKEWIDTH, String.valueOf(scaledWeight), new Object[]{mxc});
                        }
                    }
                }
            }
        }

    }



}
