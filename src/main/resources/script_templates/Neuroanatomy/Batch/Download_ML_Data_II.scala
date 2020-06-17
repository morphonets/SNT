import sc.fiji.snt.io.{MouseLightLoader, MouseLightQuerier}
import sc.fiji.snt.annotation.AllenUtils
import scala.collection.JavaConversions._

/**
 * Streamlined version of Download_ML_Data_I.groovy written in Scala. Have
 * a look at the original Download_ML_Data_I.groovy script for details.
 * TF 20200417
 */


// Absolute path to output directory. Note the trailing file separator
var outDir = System.getProperty("user.home") + "/Desktop/ML-neurons/"

// The name/acronym of the brain compartments to be queried
var somaLoc = "CA3"

if (MouseLightQuerier.isDatabaseAvailable()) {
    var ids = MouseLightQuerier.getIDs(AllenUtils.getCompartment(somaLoc))
    for (id <- ids.toList) {
        new MouseLightLoader(id).save(outDir)
    }
}

