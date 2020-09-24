package sc.fiji.snt.analysis.graph;

import org.jgrapht.graph.DefaultGraphType;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.util.SupplierUtil;

public class SNTPseudograph<V, E extends DefaultWeightedEdge> extends SNTGraph<V, E> {

	private static final long serialVersionUID = 4375953050236896508L;

	public SNTPseudograph(Class<? extends E> edgeClass) {
        super(null, SupplierUtil.createSupplier(edgeClass), new DefaultGraphType.Builder()
                .directed().allowMultipleEdges(true).allowSelfLoops(true).allowCycles(true).weighted(true)
                .modifiable(true)
                .build());
    }

}
