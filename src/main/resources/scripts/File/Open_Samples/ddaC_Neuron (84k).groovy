import sc.fiji.snt.analysis.sholl.ShollUtils
import ij.IJ

try {
	ShollUtils.sampleImage().show()
} catch (Exception ex) {
	ij.IJ.error(ex.getMessage())
}
