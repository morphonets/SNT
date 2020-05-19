<h1 align="center">SNT</h1>
<div align="center">
  The ImageJ framework for semi-automated tracing and analysis of neurons
</div>
<div align="center">
  <!-- License -->
  <a href="https://github.com/morphonets/SNT/blob/master/LICENSE.txt">
    <img alt="GitHub license" src="https://img.shields.io/github/license/morphonets/SNT">
  </a>
  <!-- Build Status -->
  <a href="https://travis-ci.org/fiji/SNT">
    <img alt="build" src="https://travis-ci.org/morphonets/SNT.svg?branch=master">
  </a>
    <!-- Issues -->
  <a href="https://github.com/morphonets/SNT/issues">
    <img alt="GitHub issues" src="https://img.shields.io/github/issues/morphonets/SNT">
  </a>
  <a href="https://github.com/morphonets/SNT/issues">
    <img alt="GitHub closed issues" src="https://img.shields.io/github/issues-closed/morphonets/SNT">
  </a>
</div>
<div align="center">
  <h3>
    <a href="#Features">
      Features
    </a>
    <span style="margin:.5em">|</span>
    <a href="#Installation">
      Installation
    </a>
    <span style="margin:.5em">|</span>
    <a href="#Contribution">
      Contribution
    </a>
    <span style="margin:.5em">|</span>
    <a href="https://imagej.net/SNT">
       Documentation
    </a>
    <span style="margin:.5em">|</span>
    <a href="https://morphonets.github.io/SNT/">
      API
    </a>
    <span style="margin:.5em">|</span>
    <a href="https://forum.image.sc/tag/SNT">
      Support
    </a>
  </h3>
</div>



SNT is [ImageJ](https://imagej.net/)'s framework for semi-automated tracing, visualization, quantitative analyses and modeling of neuronal morphology. For tracing, SNT supports modern multi-dimensional microscopy data, and highly-customizable routines. For data analysis, SNT features advanced visualization tools, access to all major morphology databases, and support for whole-brain circuitry data.

Most importantly, SNT can be used as a regular application or as a scripting library. Python (through [pyimagej](https://github.com/imagej/pyimagej)) and  all of SciJava's scripting languages are supported. It is distributed with [Fiji](https://imagej.net/Fiji) and supersedes the now outdated [Simple Neurite Tracer](https://github.com/fiji/SNT) plug-in.


## Features

### Tracing
* Support for up to 5D multidimensional images (including multichannel, and those with a time axis).
  While tracing, visibility of non-traced channels can be toggled at will
* Precise placement of nodes is aided by a local search that automatically snaps the cursor to neurites wihin a 3D neighborhood
* A* search can be performed on a second, non-displayed image.
  This allows for e.g., tracing on a pre-process (filtered) image while interacting with the unfiltered image (or vice-versa).
  If enough RAM is available toggling between the two data sources is immediate
* Tracing can be interleaved with image processing routines
* Tracing is scriptable. Interactive scripts allow for real-time inspection of results
* Paths can be tagged, searched, grouped and filtered by morphometric properties (length, radius, etc.)
* Paths can be edited, i.e., a path can be merged into a existing one, or split into two. Nodes can be moved, deleted, or inserted
* Post-hoc refinement of node positioning by 'snapping' traces to the fluorescent signal associated with a a path.

### Analysis
* *Extensive* repertoire of metrics, namely those provided by [L-measure](http://cng.gmu.edu:8080/Lm/help/index.htm) and [NeuroM](https://github.com/BlueBrain/NeuroM). Metrics can be collected from groups of cells, single cells, or parts thereof
* Analysis based on neuropil annotations for whole-brain data such as [MouseLight](https://ml-neuronbrowser.janelia.org/)
* Direct access to public databases, including [MouseLight](https://ml-neuronbrowser.janelia.org/), [FlyCircuit](http://www.flycircuit.tw) and [NeuroMorpho](http://neuromorpho.org/)
* Built-in commands for *immediate* retrieval of summary statistics, plots and histograms
* Graph theory-based analyses
* Sholl and Horton-Strahler analyses
* Branches can be converted to ROIs and voxel intensities profiles

### Visualization
* Reconstruction Viewer: Hardware-accelerated 3D visualization tool for both meshes and reconstructions.
* Interactive and programmatic scenes (controlled rotations, panning, zoom, scaling, animation,  "dark"/"light" mode, etc.)
* Customizable views: Interactive management of scene elements, controls for transparency, color interpolation, lightning, path smoothing, etc.
* Ability to render both local and remote files on the same scene
* Loading of surface meshes of several template brains (Drosophila, zebrafish, and Allen CCF (Allen Mouse Brain Atlas)
* [SciView](https://github.com/scenerygraphics/sciview) integration
* Quantitative visualizations: 
* Publication-quality figures: plots, traces and diagrams can be exported as SVG or PDF

### Scripting
* *Every* aspect of the program can be scripted in any of the IJ2 supported languages, or from Python through [pyimagej](https://github.com/imagej/pyimagej)
* Detailed [examples and tutorials](https://imagej.net/SNT:_Scripting), including Python [notebooks](https://github.com/morphonets/SNT/tree/master/notebooks)
* Headless scripts supported

### Modeling
* Modeling is performed through [Cortex3D (Cx3D)](https://github.com/morphonets/cx3d) and [SciView](https://imagej.net/SciView "SciView"), in which a modified version of [Cx3D](https://github.com/morphonets/cx3d) grows neuronal processes with [SciView](https://imagej.net/SciView)’s data structures.

### Backwards Compatibility
* Special effort was put into backwards compatibility with  [Simple Neurite Tracer](https://github.com/fiji/SNT)  (including [TrakEM2](https://github.com/trakem2/TrakEM2) interaction). Inherited functionality has been improved, namely:
  * Extended support for sub-pixel accuracy
  * Improved synchronization of XY, ZY, and XZ views
  * Improved calls to Dijkstra's filling and Path-fitting routines
  * Multi-threading improvements


## Installation
SNT is currently distributed through two ImageJ [update sites](https://imagej.net/Update_Sites): *NeuroAnatomy* and *NeuroAnatomy-Unstable*. The former hosts the latest officially  released distribution of SNT. The latter  hosts development builds, sometimes released weekly. Note that [SciView](https://imagej.net/SciView) is released under a similar strategy. To subscribe to the NeuroAnatomy update site:

1.  Run *Help › Update...*
2.  Click *Manage update sites*
3.  Select the *Neuroanatomy* checkbox (see also [list of update sites](https://imagej.github.io/list-of-update-sites/))
4.  Click *Apply changes* and Restart ImageJ.


## Contribution
Want to contribute? Please, please do! We welcome [issues](https://github.com/morphonets/SNT/issues) and [pull requests](https://github.com/morphonets/SNT/pulls) any time.
