/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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

import com.mxgraph.layout.mxCircleLayout;
import com.mxgraph.view.mxGraph;

/**
 * The a circle layout that is more compact than the default
 * {@code mxCircleLayout} by applying a 'reduction factor' to the layout radius.
 * By default, such factor is computed from the number of vertices in the graph.
 *
 * @author Tiago Ferreira
 */
public class mxCircleLayoutScaled extends mxCircleLayout
{

	private double reductionFactor;

	public mxCircleLayoutScaled(final mxGraph graph) {
		super(graph);
		setReductionFactor(-1d); // auto-adjustment
	}

	public double getReductionFactor() {
		return reductionFactor;
	}

	public void setReductionFactor(final double reductionFactor) {
		this.reductionFactor = reductionFactor;
	}

	@Override
	public void circle(final Object[] vertices, final double r, final double left, final double top)
	{
		if (getReductionFactor() <= 0) {
			if (vertices.length < 10)
				setReductionFactor(1);
			else if (vertices.length < 20)
				setReductionFactor(2);
			else if (vertices.length < 30)
				setReductionFactor(4);
			else if (vertices.length < 40)
				setReductionFactor(3);
			else if (vertices.length < 50)
				setReductionFactor(2);
			else
				setReductionFactor(1);
		}
		super.circle(vertices, r/getReductionFactor(), left, top);
	}

}
