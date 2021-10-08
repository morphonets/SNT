<p align="center"><img src="https://imagej.net/media/logos/snt.png" alt="SNT" width="150"></p>
<h1 align="center">Notes</h1>

SNT is both a scripting library and a GUI program. More formally, it is a collection of [SciJava](https://scijava.org/) commands (plugins), organized around a common API. The GUI is written in Swing.



## Projects

SNT has incorporated several projects that were previously scattered across the Fiji ecosystem of plugins. Notably:

* Simple Neurite Tracer: The founding ImageJ1 plugin released in 2010. SNT stems from its rewrite. Originally hosted at https://github.com/fiji/SNT

* Sholl Analysis: Originally hosted at  https://github.com/tferr/ASA ([Project summary](https://github.com/tferr/ASA#sholl-analysis)), _Sholl Analysis_ is now part of SNT. Its dedicated documentation page is at https://imagej.net/Sholl
* hIPNAT: Originally hosted at  https://github.com/tferr/hIPNAT ([Project summary](https://github.com/tferr/hIPNAT#hipnat)), _hIPNAT_ is now part of SNT.

An overview of SNT's history is also provided in the [FAQs](https://imagej.net/SNT:_FAQ).



## Publications

SNT is associated with several publications. Please cite the appropriate manuscripts when you use this software in your own research:

The *SNT* framework is described in:

- Arshadi C, Günther U, Eddison M, Kyle I. S. Harrington KIS, Ferreira T. [SNT: A Unifying Toolbox for Quantification of Neuronal Anatomy](https://doi.org/10.1101/2020.07.13.179325). *Nature Method*s, in press.  https://doi.org/10.1038/s41592-021-01105-7

The _Sholl Analysis_ plugin is described in:

- Ferreira T, Blackman A, Oyrer J, Jayabal A, Chung A, Watt A, Sjöström J, van Meyel D. [Neuronal morphometry directly from bitmap images](http://www.nature.com/nmeth/journal/v11/n10/full/nmeth.3125.html), *Nature Methods* 11(10): 982–984, 2014

*Simple Neurite Tracer* is described in:

- Longair MH, Baker DA, Armstrong JD. [Simple Neurite Tracer: Open Source software for reconstruction, visualization and analysis of neuronal processes](http://bioinformatics.oxfordjournals.org/content/early/2011/07/04/bioinformatics.btr390.long). *Bioinformatics*, 27(17): 2453–54, 2011
- Longair, MH. (2009). Computational neuroanatomy of the central complex of *Drosophila melanogaster*.



## Algorithms

Key aspects of SNT are implemented from published literature:

| Algorithm/Operation                                          | Reference                                                    |
| ------------------------------------------------------------ | ------------------------------------------------------------ |
| A* search                                                    | Hart, P. E., Nilsson, N. J., & Raphael, B. (1968). A formal basis for the heuristic determination of minimum cost paths. *IEEE transactions on Systems Science and Cybernetics*, 4(2), 100–107. https://doi.org/10.1109/TSSC.1968.300136 |
| Bi-directional Path Search: Reciprocal cost function         | Wink, O., Niessen, W. J., & Viergever, M. A. (2000).  Minimum cost path determination using a simple heuristic function. In *Proceedings 15th International Conference on Pattern Recognition. ICPR-2000* (3, 998–1001). IEEE. https://doi.org/10.1109/ICPR.2000.903713 |
| Bi-directional A* search (alternate)                         | Pijls, W.H.L.M. & Post, H., 2009. Yet another bidirectional algorithm for shortest paths, Econometric Institute Research Papers EI 2009-10,Erasmus University Rotterdam, Erasmus School of Economics (ESE), Econometric Institute. |
| Dijktra's algorithm: Seeded-volume segmentation              | Dijkstra, E.W. A note on two problems in connexion with graphs. Numer. Math. 1, 269–271 (1959). https://doi.org/10.1007/BF01386390 |
|                                                              |                                                              |
| Image Processing: Tubeness | Sato, Y., Nakajima, S., Shiraga, N., *et al*. (1998). Three-dimensional multi-scale line  filter for segmentation and visualization of curvilinear structures in medical images. *Medical image analysis*, 2(2), 143–168. https://doi.org/10.1016/S1361-8415(98)80009-1 |
| Image Processing: [Tubular Geodesics](https://imagej.net/SNT:_Tubular_Geodesics) | Türetken, E., Benmansour, F., & Fua, P. (2012). Automated  reconstruction of tree structures using path classifiers and mixed  integer programming. In *2012 IEEE conference on computer vision and pattern recognition* (pp. 566–573). IEEE. https://doi.org/10.1109/CVPR.2012.6247722 |
| Image Processing: Frangi Vesselness | Frangi, A. F., Niessen, W. J., Vincken, K. L., *et al*. (1998). Multiscale vessel enhancement filtering. In *International conference on medical image computing and computer-assisted intervention*. *MICCAI 1998* (pp. 130–137). https://doi.org/10.1007/BFb0056195 |
| Image Processing: [Skeletonization](https://imagej.net/AnalyzeSkeleton) | Arganda-Carreras I., Fernandez-Gonzalez R., Munoz-Barrutia A., *et. al*. (2010). 3D reconstruction of histological sections: Application to mammary gland tissue. *Microscopy Research and Technique*, 73(11), 1019–1029.  https://doi.org/10.1002/jemt.20829 |
|                                                              |                                                              |
| Convex hull: Volume                                          | Goldman, R. N. (1991). IV.1 - AREA OF PLANAR POLYGONS AND VOLUME OF POLYHEDRA. In J. Arvo (Ed.), Graphics Gems II (pp. 170–171). Morgan Kaufmann. https://doi.org/10.1016/B978-0-08-050754-5.50043-8                                                         |
|                                                              |
| Persistent homology: Topological Morphology Descriptor (*TMD*) algorithm | Kanari, L., Dłotko, P., Scolamiero, M., *et al*. (2018). A topological representation of branching  neuronal morphologies. *Neuroinformatics*, 16(1), 3–13. https://doi.org/10.1007/s12021-017-9341-1 |
| Persistent homology: Persistence Lanscapes                   | Bubenik,  P. (2015). Statistical Topological Data Analysis Using Persistence Landscapes. *Journal of Machine Learning Research*, 16(3), 77–102. https://arxiv.org/abs/1207.6437 |
| Longest shortest-path (Graph Diameter)                       | Bulterman, R.W., van der Sommen, F.W., Zwaan, G., *et al*. (2002). On computing a longest path in a tree. *Information Processing Letter*s, 81(2), 93–96. https://doi.org/10.1016/S0020-0190(01)00198-3 |
|                                                              |                                                              |
| [Cx3D simulation engine](https://imagej.net/SNT:_Modeling)   | Zubler, F., & Douglas, R. (2009). A framework for modeling the growth and development of neurons and networks. *Frontiers in Computational Neuroscience*, 3, 25. https://doi.org/10.3389/neuro.10.025.2009 |
|                                                              |                                                              |
| [L-measure](http://cng.gmu.edu:8080/Lm/) metrics             | Scorcioni, R., Polavaram, S., & Ascoli, G. A. (2008). L-Measure: a  web-accessible tool for the analysis, comparison and search of digital  reconstructions of neuronal morphologies. *Nature Protocols*, 3(5), 866. https://doi.org/10.1038/nprot.2008.51 |
| [Sholl-based metrics](https://imagej.net/Sholl.html#Metrics) | Ferreira, T., Blackman, A., Oyrer, J. *et al.* (2014). Neuronal morphometry directly from bitmap images. *Nature Methods*, 11, 982–984 . https://doi.org/10.1038/nmeth.3125<br/>Milosević, N.T. & Ristanović, D. (2007). The Sholl analysis of neuronal cell images: semi-log or log-log method? *Journal of Theoretical Biology* 245, 130–140. https://doi.org/10.1016/j.jtbi.2006.09.022<br/>Ristanović,  D.,  Milosević,  N.T.  &  Stulić,  V.  (2006). Application  of  modified  Sholl  analysis  to  neuronal  dendritic arborization of the cat spinal cord. *Journal of Neuroscience Methods* 158, 2120–218. https://doi.org/10.1016/j.jneumeth.2006.05.030 |



## Databases

Any work that uses data from the supported databases and/or reference brains should acknowledge the data source directly:

| Database                                                     | Reference                                                    |
| :----------------------------------------------------------- | :----------------------------------------------------------- |
| [FlyCircuit](http://www.flycircuit.tw/)                      | Chiang A, Lin C, Chuang C, *et al*. Three-Dimensional Reconstruction of Brain-wide Wiring Networks in Drosophila at Single-Cell Resolution. *Current Biology* 21, 1–11 (2011). https://doi.org/10.1016/j.cub.2010.11.056 |
| [FlyLight](https://www.janelia.org/project-team/flylight)    | Jenett A, Rubin GM, Ngo TB *et al*. A GAL4-Driver Line Resource for Drosophila Neurobiology. *Cell Reports*, 2, 991–1001 (2012). https://doi.org/10.1016/j.celrep.2012.09.011 |
| [InsectBrainDatabase](https://insectbraindb.org/app/)        | Heinze S, Jundi B, Berg B, *et al*. InsectBrainDatabase – A Unified Platform to Manage, Share, and Archive Morphological and Functional Data (2020). https://doi.org/10.1101/2020.11.30.397489 |
| [mapzebrain](https://fishatlas.neuro.mpg.de/) (zebrafish atlas) | Kunst M, Laurell E, Mokayes N, *et al*. A Cellular-Resolution Atlas of the Larval Zebrafish Brain. *Neuron*, 103(1), 21–38.e5 (2019). https://doi.org/10.1016/j.neuron.2019.04.034 |
| [MouseLight](http://ml-neuronbrowser.janelia.org/)           | Winnubst J, Bas E, Ferreira TA, *et al*. Reconstruction of 1,000 Projection Neurons Reveals New Cell Types and Organization of Long-Range Connectivity in the Mouse Brain. *Cell*,  179(1), 268–281.e13 (2019). https://dx.doi.org/10.1016/j.cell.2019.07.042 |
| [NeuroMorpho](http://neuromorpho.org/)                       | Ascoli GA, Donohue DE, Halavi M. NeuroMorpho.Org: A Central Resource for Neuronal Morphologies. *Journal of Neuroscience* (35) 9247–9251 (2007). https://dx.doi.org/10.1523/JNEUROSCI.2055-07.2007 |
| [Virtual Fly brain](https://virtualflybrain.org/)            | Milyaev N, Osumi-Sutherlandet D, Reeve S, *et al*. The Virtual Fly Brain Browser and Query Interface. *Bioinformatics*, 28(3), 411–415 (2012). https://dx.doi.org/10.1093/bioinformatics/btr677 |



## Dependencies

[SNT](https://imagej.net/SNT) relies heavily on several [SciJava](https://scijava.org/), [sciview](https://imagej.net/SciView) (and [scenery](https://github.com/scenerygraphics/scenery)), and [Fiji](https://imagej.net/Fiji) libraries. It also relies on other packages developed under the [morphonets](https://github.com/morphonets) umbrella and other external open-source packages. Below is a non-exhaustive list of external libraries on top of which SNT is built:

| Libraries                                                    | Scope/Usage                                           |
| ------------------------------------------------------------ | :---------------------------------------------------- |
| [3D Viewer](https://imagej.net/3D_Viewer)                    | Legacy 3D Viewer                                      |
| [AnalyzeSkeleton](https://imagej.net/AnalyzeSkeleton), [Skeletonize3D](https://imagej.net/Skeletonize3D) | Handling of skeletonized images                       |
| [Apache Commons](https://commons.apache.org/)                | Misc. utilities                                       |
| [Apache XML Graphics](https://xmlgraphics.apache.org/)       | SVG/PDF export                                        |
| [fastutil](https://fastutil.di.unimi.it/)                    | High performance, low footprint data structures
| [ImageJ1](https://github.com/imagej/imagej1)                 | ImagePlus and ROI handling                            |
| [imglib2](https://github.com/imglib/imglib2)                 | Image representation and processing
| [imagej-plot-service](https://github.com/maarzt/scijava-plot), [jfreechart](https://xmlgraphics.apache.org/) | Histograms and plots including Reconstruction Plotter |
| [ImageJ Ops](https://github.com/imagej/imagej-ops)           | Image processing and convex hull                      |
| [JGraphT](https://jgrapht.org/)                              | Graph theory -based analyses                          |
| [JGraphX](https://github.com/jgraph/jgraphx)                 | Graph Viewer                                          |
| [JHeaps](https://www.jheaps.org/)                            | Pathfinding algorithms and data structures
| [JIDE common layer](https://github.com/jidesoft/jide-oss), [font awesome](https://fontawesome.com/), [FlatLaf](https://github.com/JFormDesigner/FlatLaf) | GUI customizations                                    |
| [JSON-Java](https://github.com/stleary/JSON-java), [okhttp](https://square.github.io/okhttp/) | Access/query of online databases                      |
| [Jzy3D](http://www.jzy3d.org/)                               | Reconstruction Viewer                                 |
| [pyimagej](https://pypi.org/project/pyimagej/)               | Python bindings                                       |
| [SMILE](https://haifengl.github.io/)                         | Math and algorithm utilities                |
