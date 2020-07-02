# @ImageJ ij


"""
file:       Measure_Multiple_Files_(With_Options).py
author:     Tiago Ferreira
version:    20191217
info:       Applies SNT's measure command to a directory
"""

import sc.fiji.snt.plugin.AnalyzerCmd

ij.command().run(AnalyzerCmd.class, true)
