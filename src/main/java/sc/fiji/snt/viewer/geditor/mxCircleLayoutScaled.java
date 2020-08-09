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
			if (vertices.length < 20)
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
