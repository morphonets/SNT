package sc.fiji.snt.analysis.graph;

import org.jgrapht.graph.DefaultWeightedEdge;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.annotation.BrainAnnotation;

public class AnnotationWeightedEdge extends DefaultWeightedEdge {

    private static final long serialVersionUID = 1L;

    public double getWeight() {
        return super.getWeight();
    }

    public double getLength() {
        return super.getWeight();
    }

    public BrainAnnotation getSource() {
        return (BrainAnnotation) super.getSource();
    }

    public BrainAnnotation getTarget() {
        return (BrainAnnotation) super.getTarget();
    }

    @Override
    public String toString() {
        return SNTUtils.formatDouble(getWeight(), 2);
    }

}
