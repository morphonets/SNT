/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package sc.fiji.snt.viewer.geditor;

import com.mxgraph.analysis.mxAnalysisGraph;
import com.mxgraph.analysis.mxGraphProperties;
import com.mxgraph.analysis.mxTraversal;
import com.mxgraph.model.mxCell;
import com.mxgraph.swing.util.mxGraphActions;
import com.mxgraph.util.mxResources;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.*;

class EditorPopupMenu extends JPopupMenu
{

	/**
	 * 
	 */
	private static final long serialVersionUID = -3132749140550242191L;

	@SuppressWarnings("serial")
	EditorPopupMenu(GraphEditor editor)
	{

		mxCell[] selectedCells = Arrays.stream(editor.getGraphComponent().getGraph().getSelectionCells())
				.map(obj -> (mxCell) obj)
				.toArray(mxCell[]::new);
		mxCell[] selectedVertices = Arrays.stream(selectedCells)
				.filter(mxCell::isVertex)
				.toArray(mxCell[]::new);

		boolean selected = !(selectedCells.length == 0);
		boolean vertexSelected = !(selectedVertices.length == 0);

		add(editor.bind(mxResources.get("undo"), new EditorActions.HistoryAction(true),
                "/mx_shape_images/undo.gif"));

		addSeparator();

		add(
				editor.bind(mxResources.get("cut"), TransferHandler
						.getCutAction(),
                        "/mx_shape_images/cut.gif"))
				.setEnabled(selected);
		add(
				editor.bind(mxResources.get("copy"), TransferHandler
						.getCopyAction(),
                        "/mx_shape_images/copy.gif"))
				.setEnabled(selected);
		add(editor.bind(mxResources.get("paste"), TransferHandler
				.getPasteAction(),
                "/mx_shape_images/paste.gif"));

		addSeparator();

		add(
				editor.bind(mxResources.get("delete"), mxGraphActions
						.getDeleteAction(),
                        "/mx_shape_images/delete.gif"))
				.setEnabled(selected);

		addSeparator();

		// Creates the format menu
		JMenu menu = (JMenu) add(new JMenu(mxResources.get("format")));

		EditorMenuBar.populateFormatMenu(menu, editor);

		// Creates the shape menu
		menu = (JMenu) add(new JMenu(mxResources.get("shape")));

		EditorMenuBar.populateShapeMenu(menu, editor);

		addSeparator();

		add(
				editor.bind(mxResources.get("edit"), mxGraphActions
						.getEditAction())).setEnabled(selected);

		addSeparator();

		add(editor.bind(mxResources.get("selectVertices"), mxGraphActions
				.getSelectVerticesAction()));
		add(editor.bind(mxResources.get("selectEdges"), mxGraphActions
				.getSelectEdgesAction()));

		addSeparator();

		add(editor.bind(mxResources.get("selectAll"), mxGraphActions
				.getSelectAllAction()));

		addSeparator();
		add(editor.bind("Select All Descendants...", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				mxAnalysisGraph aGraph = new mxAnalysisGraph();
				aGraph.setGraph(editor.getGraphComponent().getGraph());
				Map<String, Object> properties = new HashMap<>();
				mxGraphProperties.setDirected(properties, true);
				aGraph.setProperties(properties);
				List<Object> newSelected = new ArrayList<>();
				for (mxCell sv : selectedVertices) {
					mxTraversal.dfs(aGraph, sv, (vertex, edge) -> {
						newSelected.add(vertex);
						newSelected.add(edge);
						return false;
					});
				}
				editor.getGraphComponent().getGraph().setSelectionCells(newSelected);
			}
		})).setEnabled(vertexSelected);

	}

}
