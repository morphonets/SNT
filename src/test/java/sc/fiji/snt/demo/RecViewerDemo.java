/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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

package sc.fiji.snt.demo;

import org.scijava.util.Colors;

import net.imagej.ImageJ;
import net.imagej.display.ColorTables;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.TreeAnalyzer;
import sc.fiji.snt.analysis.TreeColorMapper;
import sc.fiji.snt.annotation.AllenUtils;
import sc.fiji.snt.io.MouseLightLoader;
import sc.fiji.snt.util.SNTPoint;
import sc.fiji.snt.viewer.Annotation3D;
import sc.fiji.snt.viewer.OBJMesh;
import sc.fiji.snt.viewer.Viewer3D;

public class RecViewerDemo {

	/**
	 * Under Java17 and later, this demo requires the following VM arguments to be
	 * specified: <code>
	 * --add-opens java.base/java.lang=ALL-UNNAMED
	 * </code>
	 * <p>
	 * In Eclipse: Run -> Run Configurations..., Arguments tab<br>
	 * In IntelliJ: Run -> Edit Configurations..., Add VM Options (Alt+V)
	 * </p>
	 */
	public static void main(final String[] args) {

		new ImageJ().ui().showUI();
		SNTUtils.setDebugMode(true);

		final Viewer3D viewer = new Viewer3D(true);
		final OBJMesh brainMesh = viewer.loadRefBrain("Allen CCF");
		brainMesh.setBoundingBoxColor(Colors.RED);
		final OBJMesh mesh = AllenUtils.getCompartment("CP").getMesh();
		if (mesh != null) { // server is online and reachable
			viewer.addMesh(mesh);
			SNTPoint centroid = mesh.getCentroid("r");
			Annotation3D cAnnot = viewer.annotatePoint(centroid, "CP right centroid");
			cAnnot.setSize(200);
			cAnnot.setColor("magenta");
		}
		final MouseLightLoader loader = new MouseLightLoader("AA1044");
		final Tree aa1044 = loader.getTree("axon");
		if (aa1044 != null) { // server is online and reachable
			viewer.addTree(aa1044);
			viewer.annotateSurface(new TreeAnalyzer(aa1044).getTips(), "Convex Hull Tips", true);
			final TreeColorMapper mapper = new TreeColorMapper();
			mapper.map(aa1044, TreeColorMapper.PATH_ORDER, ColorTables.ICE);
			viewer.rebuild(aa1044);
			viewer.addColorBarLegend(mapper);
		}
		viewer.setAnimationEnabled(true);
		viewer.setEnableDarkMode(false);
		viewer.show();
	}
}
