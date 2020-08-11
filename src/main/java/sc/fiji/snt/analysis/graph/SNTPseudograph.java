package sc.fiji.snt.analysis.graph;

import org.jgrapht.graph.DefaultGraphType;
import org.jgrapht.util.SupplierUtil;

public class SNTPseudograph<V, E> extends SNTGraph<V, E> {

    public SNTPseudograph(Class<? extends E> edgeClass) {
        super(null, SupplierUtil.createSupplier(edgeClass), new DefaultGraphType.Builder()
                .directed().allowMultipleEdges(true).allowSelfLoops(true).allowCycles(true).weighted(true)
                .modifiable(true)
                .build());
    }

}
