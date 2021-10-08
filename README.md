<p align="center"><img src="https://imagej.net/media/logos/snt.png" alt="SNT" width="150"></p>
<h2 align="center">The ImageJ framework for quantification of neuronal anatomy</h2>
<div align="center">
  <!-- BioRiv -->
  <a href="https://doi.org/10.1101/2020.07.13.179325">
    <img alt="Publication" src="https://img.shields.io/badge/Publication-BioRiv-red.svg">
  </a>
  <!-- Gitpod -->
  <a href="https://gitpod.io/#https://github.com/fiji/SNT">
    <img alt="Gitpod ready-to-code" src="https://img.shields.io/badge/Gitpod-ready--to--code-blue?logo=gitpod">
  </a>
  <!-- License -->
  <a href="https://github.com/morphonets/SNT/blob/master/LICENSE.txt">
    <img alt="GitHub license" src="https://img.shields.io/github/license/morphonets/SNT">
  </a>
  <!-- Build Status -->
  <a href="https://github.com/morphonets/SNT/actions/workflows/build-main.yml/badge.svg)](https://github.com/morphonets/SNT/actions/workflows/build-main.yml)
    <img alt="build" src="https://github.com/morphonets/SNT/actions/workflows/build-main.yml/badge.svg)](https://github.com/morphonets/SNT/actions/workflows/build-main.yml)
  </a>
    <!-- Issues -->
  <a href="https://github.com/morphonets/SNT/issues">
    <img alt="GitHub issues" src="https://img.shields.io/github/issues/morphonets/SNT">
  </a>
  <a href="https://github.com/morphonets/SNT/issues">
    <img alt="GitHub closed issues" src="https://img.shields.io/github/issues-closed/morphonets/SNT">
  </a>
  <a href="https://forum.image.sc/tags/snt">
    <img alt="Forum.sc topics" src="https://img.shields.io/badge/dynamic/json.svg?label=forum&url=https%3A%2F%2Fforum.image.sc%2Ftag%2Fsnt.json&query=%24.topic_list.tags.0.topic_count&suffix=%20topics">
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
    <a href="#Contributing">
      Contributing
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

SNT can be used as a regular application or as a scripting library. Python (through [pyimagej](https://github.com/imagej/pyimagej)) and  all of SciJava's scripting languages are supported. It is distributed with [Fiji](https://imagej.net/Fiji) and supersedes the original [Simple Neurite Tracer](#backwards-compatibility) plug-in. It also incorporates several other neuroanatomy-related Fiji plugins. **See  [SNT's publication](https://doi.org/10.1038/s41592-021-01105-7)  and [techical notes](./NOTES.md) for details**.

## Features
### Tracing

* Semi-automated Tracing:
  * Support for up to 5D multidimensional images, including multichannel, and timelapse sequences
  * Support for both ImageJ1 and [ImgLib2](https://imagej.net/libs/imglib2/) data structures
  * Several bi-directional search algorithms (A\*, NBA\*, Fast marching) with adjustable cost functions allow for efficient computation of curvatures for a wide range of imagery, that are <u>up to 20x faster</u> relatively to the original _Simple Neurite Tracer_ plugin
  * Tracing in "secondary layers". This allows for paths to be computed on "enhanced" (pre-processed) images while interacting with the unfiltered, original image (or vice-versa). Toggling between the two data sources is immediate
  * Precise placement of nodes is aided by a local search that automatically snaps the cursor to neurites wihin a 3D neighborhood
* Auto-tracing:
  * Generation of traces from thresholded images
* Tracing can be interleaved with image processing routines
* Tracing is scriptable. Interactive scripts allow for real-time inspection of results
* Paths can be tagged, searched, grouped and filtered by morphometric properties (length, radius, etc.)
* Paths can be edited, i.e., a path can be linked or merged together, or split into two. Nodes can be moved, deleted, or inserted
* Post-hoc refinement of node positioning and radii by 'fitting' traces to the fluorescent signal associated with a path

### Analysis
* Extensive repertoire of metrics, namely those provided by [L-measure](http://cng.gmu.edu:8080/Lm/help/index.htm) and [NeuroM](https://github.com/BlueBrain/NeuroM). Metrics can be collected from groups of cells, single cells, or parts thereof
* Analysis based on neuropil annotations for whole-brain data such as [MouseLight](https://ml-neuronbrowser.janelia.org/)
* Direct access to public databases, including [FlyCircuit](http://www.flycircuit.tw), [Insect Brain Database](https://insectbraindb.org/app/), [MouseLight](https://ml-neuronbrowser.janelia.org/), [NeuroMorpho](http://neuromorpho.org/), and [Virtual Fly Brain](https://v2.virtualflybrain.org/)
* Built-in commands for immediate retrieval of statistical reports, including summary statistics, comparison plots and histograms
* Convex hull analyses
* Graph theory-based analyses
* Persistent homology-based analyses
* [Sholl](./NOTES.md) and Horton-Strahler analyses
* Image processing workflows: Reconstructions can be converted to masks and ROIs. Voxel intensities can be profiled around traced paths

### Visualization
* Quantitative visualizations: Display neurons color coded by morphometric traits, or neuropil annotations. 
* Publication-quality visualizations:  Neuronal reconstructions, diagrams, plots and histograms can be exported as vector graphics
* [Reconstruction Viewer](https://imagej.net/SNT:_Reconstruction_Viewer): Standalone hardware-accelerated 3D visualization tool for both meshes and reconstructions.
  * Interactive and programmatic scenes (controlled rotations, panning, zoom, scaling, animation,  "dark/light mode", etc.)
  * Customizable views: Interactive management of scene elements, controls for transparency, color interpolation, lightning, path smoothing, etc.. Ability to render both local and remote files on the same scene
  * Built-in support for several template brains: Drosophila, [zebrafish](https://fishatlas.neuro.mpg.de/), and Allen CCF (Allen Mouse Brain Atlas)
* [sciview](https://github.com/scenerygraphics/sciview) integration
* Graph Viewer: A dedicated viewer for graph-theory-based diagrams
  * Display reconstructions as dendrograms
  * Quantitative connectivity graphs for single cells and groups of cells

### Scripting
* Almost every aspect of the program can be scripted in any of the IJ2 supported languages, or from Python through [pyimagej](https://github.com/imagej/pyimagej)
* Detailed [documentation](https://imagej.net/SNT:_Scripting) and examples, including Python [notebooks](https://github.com/morphonets/SNT/tree/master/notebooks), and [end-to-end examples](https://github.com/morphonets/SNTmanuscript)
* Headless scripts supported

### Modeling
* Biophysical modeling of neuronal growth is performed through [Cortex3D (Cx3D)](https://github.com/morphonets/cx3d) and [sciview](https://docs.scenery.graphics/sciview/ "SciView"), in which a modified version of [Cx3D](https://github.com/morphonets/cx3d) grows neuronal processes with [sciview](https://docs.scenery.graphics/sciview/)’s data structures.

### Backwards Compatibility
* Special effort was put into backwards compatibility with  [Simple Neurite Tracer](https://github.com/fiji/SNT)  (including [TrakEM2](https://github.com/trakem2/TrakEM2) and [ITK](https://imagej.net/SNT:_Tubular_Geodesics) interaction). Inherited functionality has been improved, namely:
  * Support for sub-pixel accuracy
  * Synchronization of XY, ZY, and XZ views
  * Improved "filling" and "fitting" routines
  * Multi-threading improvements
  * Modernized GUI
* Aggregation of [legacy plugins](./NOTES.md)


## Installation
SNT is a [Fiji](https://imagej.net/Fiji) plugin, currently distributed through the *Neuroanatomy* [update site](https://imagej.net/Update_Sites).

The first time you start SNT from Fiji's menu structure (*Plugins>Neuroanatomy>SNT...* you should be prompted for automatic subscription and download of required dependencies. If not:

1.  Run the Fiji Updater (*Help › Update...*, the penultimate entry in the  *Help ›*  menu)
2.  Click *Manage update sites*
3.  Select the *Neuroanatomy* checkbox
4.  Optionally, select the *Sciview* checkbox. This is only required for extra [sciview](https://docs.scenery.graphics/sciview/) functionality
5.  Click *Apply changes* and Restart Fiji. SNT commands are registered under _Plugins>Neuroanatomy>_ in the main menu and SNT scripts under _Templates>Neuroanatomy>_ in Fiji's Script Editor.

Problems? Have a look at the full [documentation](https://imagej.net/SNT).


## Developing

### On the cloud

Use this button to open the project on the cloud using [Gitpod](https://gitpod.io). No local installation necessary.

[![Open in Gitpod](https://gitpod.io/button/open-in-gitpod.svg)](https://gitpod.io/#https://github.com/morphonets/SNT) 

### Locally

  1. Clone this repository (use the green _code_ button above the list of files) 
  2. Import the project into an IDE such as [Eclipse](https://www.eclipse.org/downloads/packages/)/[IntelliJ](https://www.jetbrains.com/idea/download/)/[NetBeans](https://netbeans.apache.org/download/index.html):
        - In Eclipse: Run _Import> Existing Maven Projects_ and specify the path to the downloaded `SNT` folder in _Root Directory_
        - In IntelliJ: In the _Welcome Prompt_, choose _Open or Import_ and specify the path to the downloaded `SNT` folder
        - In NetBeans: Run _File> Open Project..._, select the downloaded `SNT` directory, and click on _Open Project_
  3. Wait for all the dependencies to be downloaded, and run [snt/gui/cmds/SNTLoaderCmd/SNTLoaderCmd](./src/main/java/sc/fiji/snt/gui/cmds/SNTLoaderCmd.java). 

Useful resources to start hacking SNT:
  -  _main_ methods found on most classes: These test/showcase some of the class's functionality
  -  [JUnit tests](./src/test/java/sc/fiji/snt/),  [Script templates](./src/main/resources/script_templates/Neuroanatomy/) and [notebooks](./notebooks)


## Contributing
Want to contribute? Please, please do! We welcome [issues](https://github.com/morphonets/SNT/issues) and [pull requests](https://github.com/morphonets/SNT/pulls) any time.
