<p align="center"><img src="https://imagej.net/_images/5/5d/SNTLogo512.png" alt="SNT" width="150"></p>
<h1 align="center">Notes</h1>

SNT is both a scripting library and a GUI program. More formally, it is a collection of [SciJava](https://scijava.org/) commands (plugins), organized around a common API. The GUI is written in Swing.


## Projects

SNT has incorporated several projects that were previously scattered across the Fiji ecosystem of plugins. Notably:

* Sholl Analysis: Originally hosted at  https://github.com/tferr/ASA ([Project summary](https://github.com/tferr/ASA#sholl-analysis)), _Sholl Analysis_ is now part of SNT. Its dedicated documentation page is at https://imagej.net/Sholl
* Simple Neurite Tracer: The founding ImageJ1 plugin released in 2010. SNT stems from its rewrite. Originally hosted at https://github.com/fiji/SNT

An overview of SNT's history is also provided in the [FAQs](https://imagej.net/SNT:_FAQ).


## Publications

SNT is associated with several publications. Please cite the appropriate manuscripts when you use this software in your own research:

The *SNT* framework is described in:

- Arshadi C, Günther U, Eddison M, Kyle I. S. Harrington KIS, Ferreira T. [SNT: A Unifying Toolbox for Quantification of Neuronal Anatomy](https://doi.org/10.1101/2020.07.13.179325). **bioRxiv** 2020.07.13.179325; doi: https://doi.org/10.1101/2020.07.13.179325 

The _Sholl Analysis_ plugin is described in:

- Ferreira T, Blackman A, Oyrer J, Jayabal A, Chung A, Watt A, Sjöström J, van Meyel D. [Neuronal morphometry directly from bitmap images](http://www.nature.com/nmeth/journal/v11/n10/full/nmeth.3125.html), **Nature Methods** 11(10): 982–984, 2014

Simple Neurite Tracer is described in:

- Longair MH, Baker DA, Armstrong JD. [Simple Neurite Tracer: Open Source software for reconstruction, visualization and analysis of neuronal processes](http://bioinformatics.oxfordjournals.org/content/early/2011/07/04/bioinformatics.btr390.long). **Bioinformatics**, 2011


## Algorithms

Key aspects of SNT are implemented from published literature:

| Algorithm/Operation                                          | Reference                                                    |
| ------------------------------------------------------------ | ------------------------------------------------------------ |
| Bi-directional Path Search: A* search                        | Hart, P. E., Nilsson, N. J., & Raphael, B. (1968). A formal basis for the heuristic determination of minimum cost paths. *IEEE transactions on Systems Science and Cybernetics*, *4*(2), 100-107. |
| Bi-directional Path Search: Cost function                    | Wink, O., Niessen, W. J., & Viergever, M. A. (2000, September).  Minimum cost path determination using a simple heuristic function. In *Proceedings 15th International Conference on Pattern Recognition. ICPR-2000* (Vol. 3, pp. 998-1001). IEEE. |
|                                                              |                                                              |
| Image Processing: Hessian ("[Tubeness](https://javadoc.scijava.org/ImageJ/net/imagej/ops/filter/tubeness/DefaultTubeness.html)") Analysis | Sato, Y., Nakajima, S., Shiraga, N., Atsumi, H., Yoshida, S., Koller,  T., ... & Kikinis, R. (1998). Three-dimensional multi-scale line  filter for segmentation and visualization of curvilinear structures in  medical images. *Medical image analysis*, *2*(2), 143-168. |
| Image Processing: [Tubular Geodesics](https://imagej.net/SNT:_Tubular_Geodesics) | Türetken, E., Benmansour, F., & Fua, P. (2012, June). Automated  reconstruction of tree structures using path classifiers and mixed  integer programming. In *2012 IEEE conference on computer vision and pattern recognition* (pp. 566-573). IEEE. |
| Image Processing: [Frangi](https://javadoc.scijava.org/ImageJ/net/imagej/ops/filter/vesselness/DefaultFrangi.html) Vesselness | Frangi, A. F., Niessen, W. J., Vincken, K. L., & Viergever, M. A.  (1998, October). Multiscale vessel enhancement filtering. In *International conference on medical image computing and computer-assisted intervention* (pp. 130-137). Springer, Berlin, Heidelberg. |
|                                                              |                                                              |
| Persistent homology: Topological Morphology Descriptor (*TMD*) algorithm | Kanari, L., Dłotko, P., Scolamiero, M., Levi, R., Shillcock, J., Hess,  K., & Markram, H. (2018). A topological representation of branching  neuronal morphologies. *Neuroinformatics*, *16*(1), 3-13. |
| Persistent homology: Persistence Lanscapes                   | Bubenik,  P. (2015). Statistical Topological Data Analysis Using Persistence Landscapes. *Journal of Machine Learning Research* 16, 77-102 |
|                                                              |                                                              |
| [Cx3D simulation engine](https://imagej.net/SNT:_Modeling)   | Zubler, F., & Douglas, R. (2009). A framework for modeling the growth and development of neurons and networks. *Frontiers in computational neuroscience*, *3*, 25. |
|                                                              |                                                              |
| [L-measure](http://cng.gmu.edu:8080/Lm/) metrics             | Scorcioni, R., Polavaram, S., & Ascoli, G. A. (2008). L-Measure: a  web-accessible tool for the analysis, comparison and search of digital  reconstructions of neuronal morphologies. *Nature protocols*, *3*(5), 866. |
| Sholl-based metrics                                          | Ferreira, T. A., Blackman, A. V., Oyrer, J., Jayabal, S., Chung, A. J.,  Watt, A. J., Sjöström, P. J., & Van Meyel, D. J. (2014). Neuronal morphometry  directly from bitmap images. *Nature methods*, *11*(10), 982-984. |
|                                                              |
| Longest shortest-path (Graph Diameter)                       | Bulterman, R.W., van der Sommen, F.W., Zwaan, G., Verhoeff, T., van Gasteren, A.J.M., Feijen, W.H.J. (2002). On computing a longest path in a tree. Information Processing Letters, 81(2), 93-96.

## Databases

Any work that uses data from the supported databases and/or reference brains should acknowledge the data source directly.

| Database                     | URL                                           | Reference                                                    |
| :--------------------------- | :-------------------------------------------- | :----------------------------------------------------------- |
| FlyCircuit                   | http://www.flycircuit.tw/                     | Chiang A, Lin C, Chuang C, et al., “Three-Dimensional Reconstruction of Brain-Wide Wiring Networks in Drosophila at Single-Cell Resolution.” https://doi.org/10.1016/j.cub.2010.11.056 |
| FlyLight                     | https://www.janelia.org/project-team/flylight | Jenett A, Rubin GM, Ngo TB et al., “A GAL4-Driver Line Resource for Drosophila Neurobiology.” https://doi.org/10.1016/j.celrep.2012.09.011 |
| InsectBrainDatabase          | https://insectbraindb.org/app/                | Heinze S, Jundi B, Berg B, et al., “InsectBrainDatabase - A Unified Platform to Manage, Share, and Archive Morphological and Functional Data.” https://doi.org/10.1101/2020.11.30.397489 |
| mapzebrain (zebrafish atlas) | https://fishatlas.neuro.mpg.de/               | Kunst M, Laurell E, Mokayes N, et al. “A Cellular-Resolution Atlas of the Larval Zebrafish Brain.” https://doi.org/10.1016/j.neuron.2019.04.034 |
| MouseLight                   | http://ml-neuronbrowser.janelia.org/          | Winnubst J, Bas E, Ferreira TA, et al., “Reconstruction of 1,000 Projection Neurons Reveals New Cell Types and Organization of Long-Range Connectivity in the Mouse Brain.” https://dx.doi.org/10.1016/j.cell.2019.07.042 |
| NeuroMorpho                  | http://neuromorpho.org/                       | Ascoli GA, Donohue DE, Halavi M, “NeuroMorpho.Org: A Central Resource for Neuronal Morphologies.” https://dx.doi.org/10.1523/JNEUROSCI.2055-07.2007 |
| Virtual Fly brain            | https://virtualflybrain.org/                  | Milyaev N, Osumi-Sutherlandet D, Reeve S, et al., “The Virtual Fly Brain Browser and Query Interface.” https://dx.doi.org/10.1093/bioinformatics/btr677 |


## Dependencies

[SNT](https://imagej.net/SNT) relies heavily on several [SciJava](https://scijava.org/), [sciview](https://imagej.net/SciView) (and [scenery](https://github.com/scenerygraphics/scenery)), and [Fiji](https://imagej.net/Fiji) libraries. It also relies on other packages developed under the [morphonets](https://github.com/morphonets) umbrella and other external open-source packages. Below is a non-exhaustive list of external libraries on top of which SNT is built:

| Libraries                                                    | Scope/Usage                                           |
| ------------------------------------------------------------ | :---------------------------------------------------- |
| [3D Viewer](https://imagej.net/3D_Viewer)                    | Legacy 3D Viewer                                      |
| [AnalyzeSkeleton](https://imagej.net/AnalyzeSkeleton), [Skeletonize3D](https://imagej.net/Skeletonize3D) | Handling of skeletonized images                       |
| [Apache Commons](https://commons.apache.org/)                | Misc. utilities                                       |
| [Apache XML Graphics](https://xmlgraphics.apache.org/)       | SVG/PDF export                                        |
| [ImageJ1](https://github.com/imagej/imagej1)                 | ImagePlus and ROI handling                            |
| [imagej-plot-service](https://github.com/maarzt/scijava-plot), [jfreechart](https://xmlgraphics.apache.org/) | Histograms and plots including Reconstruction Plotter |
| [JGraphT](https://jgrapht.org/)                              | Graph theory -based analyses                          |
| [JIDE common layer](https://github.com/jidesoft/jide-oss), [font awesome](https://fontawesome.com/) | GUI customizations                                    |
| [JSON-Java](https://github.com/stleary/JSON-java), [okhttp](https://square.github.io/okhttp/) | Access/query of online databases                      |
| [Jzy3D](http://www.jzy3d.org/)                               | Reconstruction Viewer                                 |
| [pyimagej](https://pypi.org/project/pyimagej/)               | Python bindings                                       |
| [SMILE](https://haifengl.github.io/)                         | Nearest neighbor search (KD-Tree)                     |
