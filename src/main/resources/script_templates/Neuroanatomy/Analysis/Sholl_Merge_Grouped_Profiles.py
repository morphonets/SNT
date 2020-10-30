# @String(visibility="MESSAGE",value="<html>This script merges Sholl profiles from multiple files into a single table and plot.<br>It assumes that files share the same structure and profiles the same <i>starting<br> radius</i> and <i>radius step size</i>.<br><br>Tip: Subscribe to the BAR update site to use <i>BAR>Data Analysis></i> routines to plot<br>processed data in a more flexible way.<br>&nbsp;") MSG
# @File(label="Input directory", style="directory", description="The directory containing the files with the tabular profiles to be parsed") dir
# @String(label="Filename contains", value="", description="<html>Only files containing this string will be considered.<br>Leave blank to consider all files. Glob patterns accepted.") pattern
# @String(label="File extension", choices={".csv",".txt",".xls",".ods", "any extension"}, description="<html>The extension of the files to be parsed.") extension
# @String(label="X-values column", min=1, value="radii", description="<html>The header of the column containing the distances shared by all profiles (case-sensitive).<br>It will be retrieved from the <b>first valid file</b> found in the directory.") xcolumn_header
# @String(label="Y-values column", min=1, value="counts", description="<html>The header of the column containing the Y-values to be agregated (case-sensitive).<br>It will be extracted from all files.") ycolumn_header
# @UIService uiservice
# @LogService lservice

from __future__ import with_statement
import csv, glob, os
from collections import defaultdict
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


def mean(data):
    """ Returns the arithmetic mean of a list """
    return sum(data)/float(len(data))


def ss(data):
    """ Returns the sum of square deviations
       (see http://stackoverflow.com/a/27758326)
    """
    c = mean(data)
    ss = sum((x-c)**2 for x in data)
    return ss


def stdev(data):
    """Calculates the (population) standard deviation"""
    n = len(data)
    if n < 2:
        return float('nan')
    ssd = ss(data)
    svar = ssd/(n)
    return svar**0.5


def tofloat(v):
    try:
        return float(v)
    except ValueError:
        #log("Non-numeric entry: %s" % v, "warn")
        return v


def newtable(header, values):
    """Returns a table populated with the specified column"""
    table = SNTTable()
    for v in values:
        table.appendRow()
        table.appendToLastRow(header, v)
    return table


def main():

    ext = "" if "any" in extension else extension
    glob_pattern = "*%s*%s" % (pattern, ext)
    files = sorted(glob.glob(os.path.join(str(dir), glob_pattern)))

    if not files:
        error("The directory %s\ndoes not contain files matching the specified"\
              " pattern (or it does not exist)." % dir)
        return

    uiservice.getDefaultUI().getConsolePane().show()
    log("Parsing %s for files matching '%s'" % (str(dir), glob_pattern))

    xvalues, all_ydata = [], []
    first_valid_idx = 0
    last_row = -1

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
                log("Skipping... file does not contain enough columns", "warn")
                continue
  
            for row_idx, row in enumerate(incsv):
                try:
                    all_ydata.append((filename, row_idx, tofloat(row[ycolumn_idx])))
                    if row_idx > last_row:
                        xvalues.append(tofloat(row[xcolumn_idx]))
                        last_row = row_idx
                except IndexError:
                    log("Skipping... file does not contain enough columns", "warn")
                    continue

    if not all_ydata:
        error("%s files were parsed but no valid data existed.\nPlease revise"\
              " settings or check the Console for details." % len(files))
        return

    data_identifier = "Col_%s_%s" % (ycolumn_header, pattern)

    log("Building table with merged Y-data...")
    table = newtable(ycolumn_header, xvalues)
    for filename, row, row_value in all_ydata:
        table.set(filename, row, row_value)
    uiservice.show("MergedFiles_%s" % data_identifier, table)

    log("Retrieving statistics for merged Y-data...")
    list_of_rows = defaultdict(list)
    for data in all_ydata:
        list_of_rows[data[1]].append(data[2])

    row_stats = {}
    for row_key, row_values in list_of_rows.iteritems():
        row_stats[row_key] = (mean(row_values), stdev(row_values), len(row_values))

    table = newtable(xcolumn_header, xvalues)
    for key, value in row_stats.iteritems():
        table.set("Mean", int(key), value[0])
        table.set("StdDev", int(key), value[1])
        table.set("N", int(key), value[2])
    uiservice.show("Stats_%s" % data_identifier, table)

    plot = Plot("Mean Sholl Plot [%s]" % ycolumn_header, xcolumn_header, "N. of intersections")
    plot.setLegend("Mean"+ u'\u00B1' +"SD", Plot.LEGEND_TRANSPARENT + Plot.AUTO_POSITION)
    plot.setColor("cyan", "blue")
    plot.addPoints(table.get(0), table.get(1), table.get(2), Plot.CONNECTED_CIRCLES, data_identifier)
    plot.show()

    log("Parsing concluded.")


main()
