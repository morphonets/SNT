#@File(label="Input file (leave empty for demo):", required="false", description="Reconstruction file (.traces, .swc, json) to be analyzed. A folder of files is also supported.") input_file
#@String(label="Restrict to :", choices={"All processes", "Axon", "Dendrites"}) subtree_choice
#@ImageJ ij
#@SNTService snt


"""
file:       Strahler_Analysis.py
author:     Tiago Ferreira
version:    20220110
info:       Performs Strahler Analysis on a single reconstruction file(s).
            See Batch>Strahler_Bulk_Analysis for a batch processing alternative.
"""

from sc.fiji.snt import Tree
from sc.fiji.snt.plugin import StrahlerCmd
import os.path


def getTrees():
    global subtree_choice, input_file

    # return a demo reconstruction if input_file is empty:
    if not str(input_file) or not os.path.isfile(str(input_file)):
        subtree_choice = "All"
        return [snt.demoTree("pyramidal")]
    # A single file may contain more than one cell, so we'll
    # assume input_file may contain a collection of Trees
    trees = Tree.listFromFile(input_file.getAbsolutePath())
    return trees


def main():

    trees = getTrees()
    if not trees:
        ij.ui().showDialog("File did not contain valid reconstruction(s).", "Error")
        return
    else:
        msg = []
        for tree in trees:
            if not subtree_choice.startswith("All"):
                tree = tree.subTree(subtree_choice)
                if tree.isEmpty():
                    msg.append("%s does not contain %s." % (tree.getLabel(), subtree_choice))
                    continue
            sa = StrahlerCmd(tree)
            sa.setContext(ij.context())
            if not sa.validInput():
                msg.append("%s is not a valid structure!?" % tree.getLabel())
                continue
            sa.run()
    if msg:
        ij.ui().showDialog("<HTML>The following errors occurred:<br>%s" % '<br>'.join(msg), "Analysis Completed")

main()
