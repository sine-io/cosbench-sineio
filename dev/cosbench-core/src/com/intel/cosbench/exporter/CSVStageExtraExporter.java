/**

MIT License

Copyright (c) 2021-Present SineIO

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/

package com.intel.cosbench.exporter;

import static com.intel.cosbench.exporter.Formats.*;

import java.io.*;
import java.util.Arrays;

import org.apache.commons.lang.StringUtils;

import com.intel.cosbench.bench.*;

/**
 * This class is to export stage extra information into CSV format.
 *
 * @author sine
 *
 */
class CSVStageExtraExporter extends AbstractStageExtraExporter {

    public CSVStageExtraExporter() {
        /* empty */
    }

    protected void writeHeader(Writer writer) throws IOException {
    	/*
    	 * Header:
    	 * Timestamp,Op-Count,...,Version-Info,,\n,
    	 * 
    	 * CSV:
         * Timestamp	Op-Count		Byte-Count	Avg-ResTime	Avg-ProcTime	Throughput	Bandwidth	Succ-Ratio	Version-Info		
						write...write	write		write		write			write		write		write		Min-Version		Version		Max-Version
         * 
         * */
    	
        StringBuilder buffer = new StringBuilder();
        buffer.append("Timestamp").append(',');
        char[] cs = new char[numOpTypes];
        Arrays.fill(cs, ',');
        String suffix = new String(cs);
        buffer.append("Op-Count").append(suffix);
        buffer.append("Byte-Count").append(suffix);
        buffer.append("Avg-ResTime").append(suffix);
        buffer.append("Avg-ProcTime").append(suffix);
        buffer.append("Throughput").append(suffix);
        buffer.append("Bandwidth").append(suffix);
        buffer.append("Succ-Ratio").append(suffix);
        buffer.append("Version-Info");
        buffer.append(',').append(',').append('\n').append(',');
        
        for (int i = 0; i < 7; i++) { // Op-count ~ Succ-Ratio, total is 7
            // 7 metrics
            for (Metrics metrics : snapshots[0].getReport()) {
                buffer.append(
                		StringUtils.join(
                				new Object[] {(metrics.getOpName().equals(metrics.getSampleType()) ? null : metrics.getOpName() + "-"), 
                				metrics.getSampleType()})
                		).append(',');
            }
        }
        
        buffer.append("Min-Version").append(',');
        buffer.append("Version").append(',');
        buffer.append("Max-Version").append('\n');
        
        writer.write(buffer.toString());
    }

    protected void writeMetrics(Writer writer, Snapshot snapshot)
            throws IOException {
        StringBuilder buffer = new StringBuilder();
        buffer.append(TIME.format(snapshot.getTimestamp())).append(',');
        Report report = snapshot.getReport();

        if(report.getSize() == 0) {
        	report.addMetrics(Metrics.newMetrics("na.na"));
        }

        /* Operation Count */
        for (Metrics metrics : report)
            buffer.append(metrics.getSampleCount()).append(',');
        /* Byte Count */
        for (Metrics metrics : report)
            buffer.append(metrics.getByteCount()).append(',');
        /* Response Time */
        for (Metrics metrics : report) {
            double r = metrics.getAvgResTime();
            if (r > 0)
                buffer.append(NUM.format(r));
            else
                buffer.append("N/A");
            buffer.append(',');
        }
        /* Transfer Time */
        for (Metrics metrics : report) {
            double pt = metrics.getAvgResTime() - metrics.getAvgXferTime();
            if (pt > 0)
                buffer.append(NUM.format(pt));
            else
                buffer.append("N/A");
            buffer.append(',');
        }
        /* Throughput */
        for (Metrics metrics : report)
            buffer.append(NUM.format(metrics.getThroughput())).append(',');
        /* Bandwidth */
        for (Metrics metrics : report)
            buffer.append(NUM.format(metrics.getBandwidth())).append(',');
        /* Success Ratio */
        for (Metrics metrics : report) {
            double t = (double) metrics.getRatio();
            if (t > 0)
                buffer.append(RATIO.format(metrics.getRatio()));
            else
                buffer.append("N/A");
            buffer.append(',');
        }
        /* Version Info */
        buffer.append(snapshot.getMinVersion()).append(',');
        buffer.append(snapshot.getVersion()).append(',');
        buffer.append(snapshot.getMaxVersion()).append('\n');
        writer.write(buffer.toString());
    }

}
