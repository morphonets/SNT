# @String(visibility="MESSAGE",value="<html>This script merges Sholl profiles from multiple files into a single table and plot,<br>obtaining Mean&plusmn;StdDev profiles for groups of cells. It assumes that files share<br>the same structure and the same column headings.<br>&nbsp;") MSG
# @File(label="Input directory", style="directory", description="The directory containing the files with the tabular profiles to be parsed") dir
# @String(label="Filename contains", value="", description="<html>Only files containing this string will be considered.<br>Leave blank to consider all files. Glob patterns accepted.") pattern
# @String(label="File extension", choices={".csv",".txt",".xls",".ods", "any extension"}, description="<html>The extension of the files to be parsed.") extension
# @String(label="'Radius' column", value="radii", description="<html>The header of the column containing the distances of the profiles <b>(case-sensitive)</b>") xcolumn_header
# @String(label="'Intersections' column", value="counts", description="<html>The header of the column containing the Y-values to be agregated <b>(case-sensitive)</b>.") ycolumn_header
# @String(visibility="MESSAGE",value="<html>&nbsp;") SPACER
# @boolean(label="Impose starting radius", description="<html>If selected, merged profiles will start at the radius specified below") impose_sradius
# @double(label="Starting radius") sradius
# @boolean(label="Impose radius step size", min=0, description="<html>If selected, merged profiles will have the radius step size specified below") impose_step_size
# @double(label="Radius step size", min=0) step_size
# @boolean(label="Impose end radius", description="<html>If selected, merged profiles will end at the radius specified below") impose_eradius
# @double(label="Ending radius",min=0) eradius

# @UIService uiservice
# @LogService lservice

from __future__ import with_statement
import csv, glob, math, os
from collections import defaultdict, OrderedDict
from sc.fiji.snt.analysis import SNTTable
from ij.gui import Plot 


def log(msg, level = "info"):
    # https://forum.image.sc/t/logservice-issue-with-jython-slim-2-7-2-and-scripting-jython-1-0-0/
    from org.scijava.log import LogLevel
    if "warn" in level:
        lservice.log(LogLevel.WARN, msg)
    elif "error" in level:
        lservice.log(LogLevel.ERROR, msg)
    else:
        lservice.log(LogLevel.INFO, msg)


def error(msg):
    """ Displays an error message """
    uiservice.showDialog(msg, "Error")


def ss(data, mean):
    """ Returns the sum of square deviations
       (see http://stackoverflow.com/a/27758326)
    """
    ss = sum((x-mean)**2 for x in data)
    return ss


def stdev(data, mean):
    """Calculates the (population) standard deviation"""
    n = len(data)
    if n < 2:
        return float('nan')
    ssd = ss(data, mean)
    svar = ssd/(n)
    return svar**0.5


def tofloat(v):
    try:
        return float(v)
    except ValueError:
        #log("Non-numeric entry: %s" % v, "warn")
        return v


def main():

    global dir, eradius, extension, impose_eradius, impose_sradius, impose_step_size
    global pattern, sradius, step_size, xcolumn_header, ycolumn_header

    ext = "" if "any" in extension else extension
    glob_pattern = "*%s*%s" % (pattern, ext)
    files = sorted(glob.glob(os.path.join(str(dir), glob_pattern)))

    if not files:
        error("The directory %s\ndoes not contain files matching the specified"\
              " pattern (or it does not exist)." % dir)
        return

    uiservice.getDefaultUI().getConsolePane().show()
    log("Parsing %s for files matching '%s'" % (str(dir), glob_pattern))
    log("Column header(s) (case-sensitive, exact match expected): ['%s', '%s']" % (xcolumn_header, ycolumn_header))

    all_data = OrderedDict()
    for f_idx, f in enumerate(files):

        filename = os.path.basename(f)
        log("Parsing file %s: %s" % (f_idx+1, filename))
        if os.path.isdir(f):
            log("Skipping... file is directory.", "warn")
            continue

        with open(f, 'rU') as inf:

            try:
                # Guess file properties
                sample = inf.read(1024)
                dialect = csv.Sniffer().sniff(sample, delimiters=";,\t")
                has_header = csv.Sniffer().has_header(sample)
                inf.seek(0)
                incsv = csv.reader(inf, dialect)
            except csv.Error, reason:  #Jython 3: except csv.Error as reason:
                log("Skipping... %s" % reason, "error")
                first_valid_idx += 1
                continue

            if not has_header:
                log("Skipping... File has no column headings...")
                continue
            
            header_row = incsv.next()
            try:
                xcolumn_idx = header_row.index(xcolumn_header)
                ycolumn_idx = header_row.index(ycolumn_header)
            except ValueError:
                log("Skipping... Column header(s) not found in file", "warn")
                continue

            for row_idx, row in enumerate(incsv):
                try:
                    xvalue = round(tofloat(row[xcolumn_idx]), 4)
                    if impose_sradius and xvalue < sradius:
                        continue
                    if not xvalue in all_data:
                        all_data[xvalue] = []
                    all_data[xvalue].append((filename, tofloat(row[ycolumn_idx])))
                except IndexError:
                    log("Skipping... Column header(s) not found in file", "warn")
                    continue

    if not all_data:
        error("%s files were parsed but no valid data existed.\nPlease revise"\
              " settings or check the Console for details." % len(files))
        return


    log("Assembling table with merged data...")
    table = SNTTable()
    for radius, entries in all_data.items():
        table.appendRow()
        table.appendToLastRow("Radius", radius)
        for entry in entries:
            table.appendToLastRow(entry[0], entry[1])
    uiservice.show("MergedProfiles_Inputs", table)
    
    log("Assembling stats...")
    # define number of intervals we'll be dealing with
    min_radius = sradius if impose_sradius and sradius >= 0 else min(all_data.keys())
    max_radius = eradius if impose_eradius and eradius > 0 else max(all_data.keys()) 
    if not impose_step_size or step_size <= 0:
        log("Computing step size...")
        step_size = max([all_data.keys()[i+1]-all_data.keys()[i] for i in range(len(all_data.keys())-1)])
    nbins = int(math.ceil((max_radius-min_radius)/step_size))
    bins_start = [min_radius + i * step_size for i in range(nbins)]

    # For every imported file keep a list of the instersection values that fall within each bin
    stats = OrderedDict()
    for bin_start in bins_start:
        stats[bin_start] = []
        for col_idx in range(1, table.getColumnCount()):
            interval_inters = []
            for row_idx in range(0, table.getRowCount()):
                radius = table.get(0, row_idx)
                if radius >= bin_start and radius < bin_start + step_size:
                    value = table.get(col_idx, row_idx)
                    if value:
                        interval_inters.append(table.get(col_idx, row_idx))
            if interval_inters:
                # if intersections existed in this range, stash the average of the intersections
                # in the interval. This could be replaced with the median, mode, etc..
                stats[bin_start].append(sum(interval_inters)/float(len(interval_inters)))

    if not stats:
        error("It was not possible to assemble statistical data.\n"\
              "Please revise settings.")
        return

    log("Assembling table with statistics...")
    table = SNTTable()
    for bin_start, interval_means in stats.items():
        if len(interval_means) == 0:
            continue
        table.appendRow()
        the_sum = sum(interval_means)
        the_avg = the_sum/len(interval_means)
        table.appendToLastRow("Radius (interval start)", bin_start)
        table.appendToLastRow("Mean", the_avg)
        table.appendToLastRow("StDev", stdev(interval_means, the_avg))
        table.appendToLastRow("Sum", the_sum)
        table.appendToLastRow("N", len(interval_means))
    uiservice.show("MergedProfiles_Stats", table)

    plot = Plot("Mean Sholl Plot [%s]" % ycolumn_header, xcolumn_header, "No. of intersections")
    label = "Mean" + u'\u00B1' + "SD"
    plot.setLegend(label, Plot.LEGEND_TRANSPARENT + Plot.AUTO_POSITION)
    plot.setColor("cyan", "blue")
    plot.addPoints(table.get(0), table.get(1), table.get(2), Plot.CONNECTED_CIRCLES, label)
    plot.show()

    log("Parsing concluded.")
 
main()
