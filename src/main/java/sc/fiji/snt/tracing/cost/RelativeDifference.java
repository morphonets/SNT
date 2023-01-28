package sc.fiji.snt.tracing.cost;

/**
 * The step cost is given by the relative difference between a reference value and the intensity of the new voxel.
 *
 * @author Cameron Arshadi
 */
public class RelativeDifference implements Cost {

    private final double valueAtStart;

    public RelativeDifference(final double valueAtStart) {
        this.valueAtStart = valueAtStart;
    }

    @Override
    public double costMovingTo(final double valueAtNewPoint) {
        // TODO: test if normalizing helps
        if (valueAtStart == valueAtNewPoint) {
            return 1;
        }
        // Cost must always be > 0
        return 1 + Math.abs(valueAtNewPoint - valueAtStart) / (valueAtStart + valueAtNewPoint);
    }

    @Override
    public double minStepCost() {
        return 1;
    }
}
