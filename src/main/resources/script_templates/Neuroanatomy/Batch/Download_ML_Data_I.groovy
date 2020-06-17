import sc.fiji.snt.io.MouseLightLoader
import sc.fiji.snt.io.MouseLightQuerier
import sc.fiji.snt.annotation.AllenUtils


/**
 * Exemplifies how to programmatically retrieve data from MouseLight's database
 * at ml-neuronbrowser.janelia.org: It retrieves the IDs of the cells with soma
 * associated with a specified brain area, downloading the data (both JSON and
 * SWC formats) for each cell.
 *
 * Downloaded files will contain all the metadata associated with the cell  
 * (labeling used, mouse strain, etc.).
 * 
 * TF 20190317, https://imagej.net/SNT/Scripting
 */

// Absolute path to saving directory. Will be created as needed
destinationDirectory = System.properties.'user.home' + '/Desktop/ML-neurons'

// The name of the brain compartment (as displayed by Reconstruction Viewer's
// CCF Navigator). NB: Specifying "Whole Brain" would effectively download _all_
// reconstructions from the database
compartmentOfInterest = AllenUtils.getCompartment("CA3")

if (!MouseLightQuerier.isDatabaseAvailable() || !compartmentOfInterest) {
    println("""Aborting: Can only proceed with valid compartment and
               successful connection to database""")
    return
}

println("ML Database is online with ${MouseLightQuerier.getNeuronCount()} cells.")

ids = MouseLightQuerier.getIDs(compartmentOfInterest)
println("Found ${ids.size()} cells with soma in $compartmentOfInterest")

ids.eachWithIndex { id, index ->
    loader = new MouseLightLoader(id)
    println("${index+1}/${ids.size()} Saving cell $id...")
    println(" JSON saved: " + loader.saveAsJSON(destinationDirectory))
    println(" SWC  saved: " + loader.saveAsSWC(destinationDirectory)) 
}

println("Finished.")

