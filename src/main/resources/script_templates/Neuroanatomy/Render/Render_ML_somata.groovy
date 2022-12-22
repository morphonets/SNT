#@String(value="This script retrieves soma locations for cells in The MouseLight database. Please<br> specify the brain region to be queried (e.g., 'Whole brain', 'MOp', 'Thalamus', 'VIS'):", visibility="MESSAGE") msg
#@String(label="Brain region (acronym/name):", value="VIS") brainRegion
#@UIService ui

import sc.fiji.snt.*
import sc.fiji.snt.annotation.*
import sc.fiji.snt.io.*
import sc.fiji.snt.util.*
import sc.fiji.snt.viewer.*

"""
file:       Render_ML_somata.groovy
author:     Tiago Ferreira
version:    20221220
info:       Renders soma locations of MouseLight neurons
"""


def loadSomas(ids, holdingList) {
	failures = []
	for (id : ids) {
		println("Retrieving soma " + id)
		loader = new MouseLightLoader(id)
		try {
			soma = loader.getTree("soma")
			holdingList.add(soma)
		} catch (Exception e) {
			println("  ...Error: download failed")
			failures.add(id)
		}
	}
	return failures
}

compartment = AllenUtils.getCompartment(brainRegion)
if (!compartment) {
	ui.showDialog("Invalid compartment. Make sure you typed it correctly.")
	return;
}
ids = MouseLightQuerier.getIDs(compartment)
if (!ids) {
	ui.showDialog("Unfortunately, no cells were found in ${compartment}.")
	return;
}
somas = []
failures = loadSomas(ids, somas)
if (failures) {
	// There have been problems connecting en-masse to the ML server
	// so we'll re-attempt any failed downloads
	println(">> Re-attempt download of ${failures.size()} failed IDs")
	loadSomas(failures, somas)
}
println(">> Brain region: " + compartment)
println(">> # cells in database: " + ids.size())
println(">> # cells downloaded: " + somas.size())

viewer = new Viewer3D(true)
viewer.loadRefBrain("mouse")
viewer.add(somas)
viewer.setSomaRadius(50)
viewer.setViewMode("XZ")
viewer.show()

return null
