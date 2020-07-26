package sc.fiji.snt.viewer;

import com.mxgraph.swing.mxGraphComponent;
import org.scijava.Context;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;
import sc.fiji.GraphEditor.editor.BasicGraphEditor;
import sc.fiji.GraphEditor.editor.EditorMenuBar;
import sc.fiji.snt.plugin.GraphAdapterMapperCmd;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.HashMap;
import java.util.Map;

public class SNTEditorMenuBar extends EditorMenuBar {
    @Parameter
    Context context;

    public enum SNTAnalyzeType {
        COLOR_CODE
    }

    public SNTEditorMenuBar(BasicGraphEditor editor, Context context) {
        super(editor);
        context.inject(this);
    }

    @Override
    public void createDeveloperMenu() {
        super.createDeveloperMenu();
        menu.add(editor.bind("Color code", new SNTAnalyzeGraph(SNTAnalyzeType.COLOR_CODE, graphComponent)));

    }

    public class SNTAnalyzeGraph extends AbstractAction {
        /**
         *
         */
        protected SNTAnalyzeType analyzeType;

        /**
         * Examples for calling analysis methods from mxGraphStructure
         */
        public SNTAnalyzeGraph(SNTAnalyzeType analyzeType, mxGraphComponent component)
        {
            this.analyzeType = analyzeType;
        }
         public void actionPerformed(ActionEvent e) {
             if (e.getSource() instanceof SNTGraphComponent) {
                 SNTGraphComponent graphComponent = (SNTGraphComponent) e.getSource();
                 SNTGraphAdapter adapter = (SNTGraphAdapter) graphComponent.getGraph();
                 if (analyzeType == SNTAnalyzeType.COLOR_CODE) {
                     final Map<String, Object> input = new HashMap<>();
                     input.put("adapter", adapter);
                     context.getService(CommandService.class).run(GraphAdapterMapperCmd.class, true, input);
                 }
             }
         }

    }

}
