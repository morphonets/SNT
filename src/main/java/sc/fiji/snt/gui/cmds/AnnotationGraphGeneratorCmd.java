/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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
package sc.fiji.snt.gui.cmds;

import net.imagej.ImageJ;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.MultiTreeStatistics;
import sc.fiji.snt.analysis.graph.AnnotationGraph;
import sc.fiji.snt.analysis.graph.AnnotationWeightedEdge;
import sc.fiji.snt.analysis.graph.GraphColorMapper;
import sc.fiji.snt.annotation.BrainAnnotation;
import sc.fiji.snt.util.ColorMaps;
import sc.fiji.snt.viewer.GraphViewer;
import sc.fiji.snt.viewer.geditor.AnnotationGraphAdapter;
import sc.fiji.snt.viewer.geditor.GraphEditor;
import sc.fiji.snt.viewer.geditor.mxCircleLayoutGrouped;

import java.util.*;

@Plugin(type = Command.class, label = "Create Annotation Diagram(s)")
public class AnnotationGraphGeneratorCmd extends CommonDynamicCmd {

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE)
	private final String msg1 = "<HTML>" + "This command assembles connectiviy diagrams from one or more cells<br>"
			+ "previously tagged with neuropil labels (e.g., as with MouseLight neurons)<br>"
			+ "These diagrams depict a semi-quantitative summary of the brain areas<br>"
			+ "targeted by neuronal arbors.";

	@Parameter(label = "Metric", choices = { AnnotationGraph.TIPS, AnnotationGraph.BRANCH_POINTS,
			AnnotationGraph.LENGTH }, description = "<HTML><div WIDTH=600>The morphometric trait defining connectivy")
	protected String metric;

	@Parameter(label = "Cutoff value:", description = "<HTML><div WIDTH=600>Brain areas associated with less "
			+ "than this quantity are ignored. E.g., if metric is '" + AnnotationGraph.TIPS + "' and this value "
			+ " is 10, only brain areas targeted by at least 11 tips are reported")
	protected double threshold;

	@Parameter(required = false, label = "Deepest ontology:", description = "<HTML><div WIDTH=600>The highest ontology level to be considered for neuropil labels. As a reference, the deepest level for "
			+ "several mouse brain atlases is around 10. Set it to 0 to consider all depths", min = "0")
	protected int depth;

	@Parameter(label = "Compartment(s):", choices = { "All", "Axon", "Dendrites" })
	private String compartment;

	@Parameter(label = "Output diagram(s):", choices = { "Ferris wheel", "Flow (Sankey)", "Both" })
	private String diagram;

	@Parameter(label = "Trees")
	private Collection<Tree> trees;

	private int adjustedDepth() {
		return depth <= 0 ? Integer.MAX_VALUE : depth;
	}

	@Override
	public void run() {
		if (trees == null || trees.isEmpty()) {
			cancel("Invalid Tree Collection");
			return;
		}
		final List<Tree> annotatedTrees = new ArrayList<>();
		for (Tree tree : trees) {
			if (!tree.isAnnotated()) {
				SNTUtils.log(tree.getLabel() + " does not have neuropil labels. Skipping...");
				continue;
			}
			if (!compartment.equals("All")) {
				final String oldLabel = tree.getLabel();
				tree = tree.subTree(compartment);
				if (tree.isEmpty()) {
					SNTUtils.log(
							oldLabel + " does not contain processes tagged as \"" + compartment + "\". Skipping...");
					continue;
				}
			}
			annotatedTrees.add(tree);
		}
		if (annotatedTrees.isEmpty()) {
			error("None of the selected Trees meet the necessary criteria.<br>"
					+ "Please ensure that Tree(s) and selected compartment(s) and "
					+ "are annotated with neuropil labels.");
			return;
		}
		// SNTUtils.setIsLoading(true);
		statusService.showStatus("Generating diagram(s)...");
		final int depth = adjustedDepth();
		if (diagram.startsWith("Ferris") || diagram.startsWith("Both")) {
			SNTUtils.log("Creating Ferris wheel diagram");
			final AnnotationGraph annotationGraph = new AnnotationGraph(annotatedTrees, metric, threshold, depth);
			final boolean large = annotationGraph.vertexSet().size() > 10;
			final GraphViewer graphViewer = new GraphViewer(annotationGraph);
			graphViewer.setContext(getContext());
			final GraphEditor editor = graphViewer.getEditor();
			final GraphColorMapper<BrainAnnotation, AnnotationWeightedEdge> mapper = new GraphColorMapper<>();
			mapper.map(annotationGraph, GraphColorMapper.EDGE_WEIGHT, ColorMaps.ICE);
			final AnnotationGraphAdapter graphAdapter = (AnnotationGraphAdapter) (editor.getGraphComponent()
					.getGraph());
			graphAdapter.scaleEdgeWidths(1, (large) ? 15 : 10, "linear");
			final mxCircleLayoutGrouped groupedLayout = new mxCircleLayoutGrouped(graphAdapter, depth, depth / 2);
			groupedLayout.setRadius((large) ? 400 : 200);
			groupedLayout.setCenterSource(true);
			groupedLayout.setSortMidLevel(true);
			editor.applyLayout(groupedLayout);
			mapper.setLegend(editor);
			graphViewer.show("Ferris-Wheel Diagram");
			SNTUtils.log("Finished. Diagram created from " + annotatedTrees.size() + " tree(s).");
		}
		if (diagram.startsWith("Flow") || diagram.startsWith("Both")) {
			SNTUtils.log("Creating Flow plot (Sankey diagram)");
			final MultiTreeStatistics stats = new MultiTreeStatistics(annotatedTrees);
			if (annotatedTrees.size()==1)
				stats.setLabel(annotatedTrees.iterator().next().getLabel());
			stats.getFlowPlot(metric, stats.getAnnotations(depth), "sum", threshold, false).show();
			SNTUtils.log("Finished. Diagram created from " + annotatedTrees.size() + " tree(s).");
		}
		statusService.clearStatus();
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final Collection<Tree> trees = new SNTService().demoTrees();
		final Map<String, Object> inputs = new HashMap<>();
		inputs.put("trees", trees);
		ij.command().run(AnnotationGraphGeneratorCmd.class, true, inputs);
	}

}
