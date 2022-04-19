package sc.fiji.snt.analysis.plotservice;

import org.scijava.plot.XYPlot;
import org.jfree.chart.JFreeChart;
import org.scijava.Priority;
import org.scijava.convert.AbstractConverter;
import org.scijava.convert.Converter;
import org.scijava.plugin.Plugin;

/**
 * @author Matthias.Arzt
 */
@Plugin(type = Converter.class, priority = Priority.NORMAL)
public class XYPlotConverter extends AbstractConverter<XYPlot, JFreeChart> {
	@SuppressWarnings("unchecked")
	@Override
	public <T> T convert(Object o, Class<T> aClass) {
		return (T) XYPlotGenerator.run((XYPlot) o);
	}

	@Override
	public Class<JFreeChart> getOutputType() {
		return JFreeChart.class;
	}

	@Override
	public Class<XYPlot> getInputType() {
		return XYPlot.class;
	}
}