# @SNTService snt
# @UIService ui


"""
file:       Fully_Automated_Tracing_Demo_(Interactive).py
author:     Tiago Ferreira
version:    20231214
info:       Exemplifies how to programmatically interact with a running
            instance of SNT to perform automated (unsupervised) tracing
"""

from sc.fiji.snt.analysis import SkeletonConverter

def run():

    # We'll not proceed if SNT is currently open and busy with something else
    if snt.getUI() and not snt.getUI().isReady():
        ui.showDialog("Demo cannot run in current state: UI not ready", "Error")
        return
    
    # We could also delete any existing paths, but there is really no need for that
    #snt.getUI().runCommand("Delete...")

    # We'll now open startup SNT with the OP_1 demo image. Typically we would
    # initialize SNT with an image or image path, but in this case we can specify
    # a demo dataset:
    snt.initialize("demo: OP1", True)  # args: image path, boolean for GUI display

    # In order to extract paths from the image we need to enhance it (or threshold
    # it). A quick way to do so is to run the 'Secondary layer' wizard in the GUI
    # (see Batch> scripts for a more structured alternative to process images). The 
    # runSecondaryLayerWizard() method in SNTUI needs two arguments: 1) the name
    # of the filter, and 2) a list of 'scales', reflecting the radii of neurites
    # in the image. In this particular case, we know these very well (load the OP1
    # dataset using File > Load Demo Dataset... and obtain the histogram of all its
    # radii. Here we'll use Q1, Q2, and Q3 of the distribution (NB. We could also
    # use the Estimate Radii (Local Thickness).. command directly on the image)
    snt.getUI().runSecondaryLayerWizard("Frangi Vesselness", [0.56, 0.74, 0.98])

    # the SkeletonConverter class is the workhorse class for the conversion. We'll
    # use it to skeletonize the Frangi image, thresholding out the voxels with
    # low 'vesselness scores' (here, we'll only consider values above 0.005..
    # NB: The last boolean argument in the skeletonize() method specifies whether
    # isolated voxels that may exist should be eroded
    filtered_img = snt.getPlugin().getSecondaryDataAsImp()
    #filtered_img.duplicate().show() # display copy
    SkeletonConverter.skeletonize(filtered_img, 0.005, 1, True)

    # Now we place a rectangle over the base of the OP neuron, to inform the
    # algorithm that that area contains the root of the arbor
    filtered_img.setRoi(10, 410, 45, 45)
    #filtered_img.show() # display skeletonized image

    # We can now initialize the converter and specify some tweaks:
    converter = SkeletonConverter(filtered_img)
    converter.setPruneByLength(False) # Discard smaller disconnected branches?
    #converter.setLengthThreshold(10) # 10um
    converter.setConnectComponents(True) # Attempt to bridge gaps in the structure?
    converter.setMaxConnectDist(20) # Attempt only if gap is smaller than 20um

    # Lastly, extract the result and add it to SNT. Since 1) the image may not be
    # contiguous, and 2) loops may exist, the result may not be a single Tree, but
    # a list of Trees (some may have only a single branch or even a single node)
    trees = converter.getTrees(filtered_img.getRoi(), False) # Root roi, 2D ROI?
    for tree in trees:
        snt.loadTree(tree)
    snt.getUI().getPathManager().runCommand("Assign Distinct Colors")
    snt.updateViewers()


run()
