package sc.fiji.snt.viewer.geditor;

import java.util.ArrayList;
import java.util.Collections;

import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.KeyStroke;

import com.mxgraph.swing.mxGraphComponent;
import com.mxgraph.swing.handler.mxKeyboardHandler;
import com.mxgraph.swing.util.mxGraphActions;

import sc.fiji.snt.gui.GuiUtils;

/**
 * @author Gaudenz Alder Copyright (c) 2008
 * 
 */
public class EditorKeyboardHandler extends mxKeyboardHandler
{

	/**
	 * 
	 * @param graphComponent
	 */
	public EditorKeyboardHandler(mxGraphComponent graphComponent)
	{
		super(graphComponent);
	}

	/**
	 * Return JTree's input map.
	 */
	protected InputMap getInputMap(int condition)
	{
		InputMap map = super.getInputMap(condition);

		if (condition == JComponent.WHEN_FOCUSED && map != null)
		{
			map.put(KeyStroke.getKeyStroke("control S"), "save");
			map.put(KeyStroke.getKeyStroke("control shift S"), "saveAs");
			map.put(KeyStroke.getKeyStroke("control N"), "new");
			map.put(KeyStroke.getKeyStroke("control O"), "open");

			map.put(KeyStroke.getKeyStroke("control Z"), "undo");
			map.put(KeyStroke.getKeyStroke("control Y"), "redo");

			map.put(KeyStroke.getKeyStroke("control shift V"), "selectVertices");
			map.put(KeyStroke.getKeyStroke("control shift E"), "selectEdges");

			map.put(KeyStroke.getKeyStroke("control EQUALS"), "zoomIn");
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
        GuiUtils.showHTMLDialog("<HTML>" + String.join("<br>", lines), "Graph Viewer Shortcuts");
    }
  
	/**
	 * Return the mapping between JTree's input map and JGraph's actions.
	 */
	protected ActionMap createActionMap()
	{
		ActionMap map = super.createActionMap();

		map.put("save", new EditorActions.SaveAction(false));
		map.put("saveAs", new EditorActions.SaveAction(true));
		map.put("new", new EditorActions.NewAction());
		map.put("open", new EditorActions.OpenAction());
		map.put("undo", new EditorActions.HistoryAction(true));
		map.put("redo", new EditorActions.HistoryAction(false));
		map.put("selectVertices", mxGraphActions.getSelectVerticesAction());
		map.put("selectEdges", mxGraphActions.getSelectEdgesAction());

		return map;
	}

}
