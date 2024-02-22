/**
 * file: Compare_Cell_Groups.groovy
 * info: Runs SNT's 'Compare Cell Groups...' command for statistical reports on
 *       up to 6 groups of cells. Reports include: two-sample t-tests/one-way,
 *       ANOVA and color-coded montages. Computations are performed by
 *       GroupedTreeStatistics[1]. For details have a look at GroupAnalyzerCmd
 *       source code[2]
 *       [1] https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/analysis/GroupedTreeStatistics.html
 *       [2] https://github.com/morphonets/SNT/blob/master/src/main/java/sc/fiji/snt/plugin/GroupAnalyzerCmd.java
 */

#@CommandService cmd
cmd.run(GroupAnalyzerCmd.class, true)

import sc.fiji.snt.plugin.GroupAnalyzerCmd
