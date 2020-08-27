"""
file:       Brain_Comparment_Analysis.py
author:     Tiago Ferreira
version:    20200126
info:       A Python demo on how to assess the brain areas a neuron projects to
            (in this case a MouseLight neuron retrived from the MouseLight
            database). Internet connection required. 
"""
from sc.fiji.snt.analysis import (NodeStatistics, TreeStatistics)
from sc.fiji.snt.annotation import AllenUtils
from sc.fiji.snt.io import MouseLightLoader


# In this example we are going to analyze the axonal projections of a Mouselight
# neuron across the whole brain (Allen CCF). For simplicity, we'll focus only on
# brain regions defined by mid level ontologies (see Allen CCF Navigator in
# Reconstruction Viewer):
max_ontology_depth = 6

# Let's retrive the axonal arbor of cell id AA0788 and its soma location:
loader = MouseLightLoader("AA0788")
axon = loader.getTree("axon")
soma_loc = loader.getSomaLocation()

# Let's determine the soma's compartment (annotated brain area) at the specified
# ontology depth:
cSoma = loader.getSomaCompartment()
if cSoma.getOntologyDepth() > max_ontology_depth:
    cSoma = cSoma.getAncestor(max_ontology_depth - cSoma.getOntologyDepth())
    
# First we initialize TreeStatistics: It will be at the core of our analysis
tStats = TreeStatistics(axon)

# Let's analyze the axonal terminals: Where are they located?
tips = tStats.getTips()
nStats = NodeStatistics(tips)
hist = nStats.getAnnotatedHistogram(max_ontology_depth)
hist.annotate('No. of tips: {}'.format(tips.size()))
hist.annotateCategory(cSoma.acronym(), "soma", "blue")
hist.show()

# Does the distribution of axonal cable reflect the distribution of tips?
hist = tStats.getAnnotatedLengthHistogram(max_ontology_depth)
hist.annotateCategory(cSoma.acronym(), "soma", "blue")
hist.show()

# Does this neuron project to contra-lateral locations?
hist = nStats.getHistogram("x coordinates") # Medial-Lateral axis
midline = AllenUtils.brainCenter().getX()
hist.annotateXline(midline, "midline")
hemisphere = "left" if AllenUtils.isLeftHemisphere(soma_loc) else "right" 
hist.annotatePoint(soma_loc.getX(), 0, "soma ({} hemi.)".format(hemisphere))
hist.show()

# More simply, we can just obtain ipsi/contralateral ratios directly
hist = tStats.getAnnotatedLengthHistogram(max_ontology_depth, 'ratio')
hist.show()
hist = nStats.getAnnotatedFrequencyHistogram(max_ontology_depth, 'ratio', axon)
hist.show()


