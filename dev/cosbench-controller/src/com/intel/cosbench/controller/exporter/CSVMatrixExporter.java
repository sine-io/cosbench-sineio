/**

Copyright 2013 Intel Corporation, All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.intel.cosbench.controller.exporter;

import static com.intel.cosbench.controller.exporter.Formats.*;

import java.io.*;

import com.intel.cosbench.bench.*;
import com.intel.cosbench.config.*;
import com.intel.cosbench.model.StageInfo;

/**
 * This class is to export performance matrix data into CSV format.
 *
 * @author ywang19, qzheng7
 *
 */
class CSVMatrixExporter extends AbstractMatrixExporter {

    @Override
    protected void writeHeader(Writer writer) throws IOException {
    	/* 2023.8.22, sine. reflect, use common-csv.jar */
//    	final String[] header= {
//    			"Id", "Op-Name", 
//    			"Op-Count", "Byte-Count", 
//        		"Op-Count", "Byte-Count", 
//        		"Worker-Count", "Avg-ResTime", 
//        		"Avg-ProcTime", "60%-ResTime", 
//        		"80%-ResTime", "90%-ResTime", 
//        		"95%-ResTime", "100%-ResTime", 
//        		"Throughput", "Bandwidth", 
//        		"Succ-Ratio", "Config-Info"};
//    	try {
//            CSVFormat.DEFAULT.builder().setHeader(header).build().print(writer);
//        } catch (Exception e) {
//			e.printStackTrace();
//		} finally {
//            if(null != writer) {
//            	writer.flush();
//            }
//        }
    	/* end */
    	
        StringBuilder buffer = new StringBuilder();
        buffer.append("Id").append(',');
        buffer.append("Op-Name").append(',');
        buffer.append("Op-Count").append(',');
        buffer.append("Byte-Count").append(',');
        buffer.append("Worker-Count").append(',');
        buffer.append("Avg-ResTime").append(',');
        buffer.append("Avg-ProcTime").append(',');
        buffer.append("60%-ResTime").append(',');
        buffer.append("80%-ResTime").append(',');
        buffer.append("90%-ResTime").append(',');
        buffer.append("95%-ResTime").append(',');
        buffer.append("99%-ResTime").append(',');
        buffer.append("100%-ResTime").append(',');
        buffer.append("Throughput").append(',');
        buffer.append("Bandwidth").append(',');
        buffer.append("Succ-Ratio").append(',');
        buffer.append("Config-Info").append('\n');
        writer.write(buffer.toString());
    }

    @Override
    protected void writeMetrics(Writer writer, StageInfo stage,
            Metrics metrics, int idx) throws IOException {
    	
        StringBuilder buffer = new StringBuilder();
        String uuid = workload.getId() + '-' + stage.getId() + '-' + idx;
        buffer.append(uuid).append(',');
        String opt = metrics.getOpName();
        String spt = metrics.getSampleType();
        if (spt.equals(opt))
            buffer.append(opt);
        else
            buffer.append(opt + '-' + spt);
        buffer.append(',');
        buffer.append(metrics.getSampleCount()).append(',');
        buffer.append(metrics.getByteCount()).append(',');
        buffer.append(metrics.getWorkerCount()).append(',');
        double r = metrics.getAvgResTime();
        if (r > 0)
            buffer.append(NUM.format(r));
        else
            buffer.append("N/A");
        buffer.append(',');

        double pt = r - metrics.getAvgXferTime();
        if (pt > 0)
            buffer.append(NUM.format(pt));
        else
            buffer.append("N/A");
        buffer.append(',');

        writeLatencyInfo(buffer, metrics.getLatency());
        buffer.append(NUM.format(metrics.getThroughput())).append(',');
        buffer.append(NUM.format(metrics.getBandwidth())).append(',');
        double t = (double) metrics.getRatio();
        if (t > 0)
            buffer.append(RATIO.format(metrics.getRatio()));
        else
            buffer.append("N/A");
        buffer.append(',');
        writeConfigInfo(buffer, stage, metrics);
        buffer.setCharAt(buffer.length() - 1, '\n');
        writer.write(buffer.toString());
    }

    private static void writeLatencyInfo(StringBuilder buffer, Histogram latency)
            throws IOException {
        if(latency == null) {
            writePercentileRT(buffer, null);
            writePercentileRT(buffer, null);
            writePercentileRT(buffer, null);
            writePercentileRT(buffer, null);
            writePercentileRT(buffer, null);
            writePercentileRT(buffer, null);
        }else {
            writePercentileRT(buffer, latency.get_60());
            writePercentileRT(buffer, latency.get_80());
            writePercentileRT(buffer, latency.get_90());
            writePercentileRT(buffer, latency.get_95());
            writePercentileRT(buffer, latency.get_99());
            writePercentileRT(buffer, latency.get_100());
        }

    }

    private static void writePercentileRT(StringBuilder buffer, long[] resTime) {
        if (resTime == null)
            buffer.append("N/A");
        else
            buffer.append(resTime[1]);
        buffer.append(',');
    }

    private static void writeConfigInfo(StringBuilder buffer, StageInfo stage,
            Metrics metrics) throws IOException {
        for (Work work : stage.getStage())
            for (Operation op : work) {
                if (op.getId().equals(metrics.getOpId())) {
                    buffer.append(op.getRatio()).append('%').append(' ');
                    String config = op.getConfig();
                    config = config.replaceAll(",", "-").replaceAll(";", " ");
                    buffer.append(config);
                }
            }
        buffer.append(',');
    }

}
