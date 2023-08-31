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

import java.io.*;

import com.intel.cosbench.bench.*;
import com.intel.cosbench.model.StageInfo;
import com.intel.cosbench.model.StateInfo;

import static com.intel.cosbench.controller.exporter.Formats.*;

/**
 * This class is to export workload information into CSV format.
 *
 * @author ywang19, qzheng7, sine
 *
 */
class CSVWorkloadExporter extends AbstractWorkloadExporter {

    @Override
    protected void writeHeader(Writer writer) throws IOException {
    	
//    	// 2023.8.22, sine. overwrite, use common-csv.jar
//    	final String[] header= {
//    			"Driver-Name", "Driver-URL", "Interval", "Op-Id",  // 2023.8.23, sine. add driver-name, driver-url, interval, op-id
//    			"Stage", "Op-Name", "Op-Type", 
//    			"Op-Count", "Byte-Count", "Avg-ResTime", 
//    			"Avg-ProcTime", "60%-ResTime", "80%-ResTime", 
//    			"90%-ResTime", "95%-ResTime", "99%-ResTime", 
//    			"100%-ResTime", "Throughput", "Bandwidth", 
//    			"Succ-Ratio", "Status", "Detailed Status"};
//    	
//    	try {
//            CSVFormat.DEFAULT.builder().setHeader(header).build().print(writer);
//        } catch (Exception e) {
//			e.printStackTrace();
//		} finally {
//            if(null != writer) {
//            	writer.flush();
//            }
//        }
    	
        StringBuilder buffer = new StringBuilder();
        buffer.append("Stage").append(',');
        buffer.append("Interval").append(','); // 2023.8.16, sine. add stage Interval
        buffer.append("Op-Id").append(','); // 2023.6.14, sine. add Op-Id
        buffer.append("Op-Name").append(',');
        buffer.append("Op-Type").append(',');
        buffer.append("Op-Count").append(',');
        buffer.append("Byte-Count").append(',');
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
        buffer.append("Status").append(',');
        buffer.append("Detailed Status").append('\n');
        writer.write(buffer.toString());
    }

    @Override
    protected void writeMetrics(Writer writer, Metrics metrics, StageInfo stage)
            throws IOException {
    	
//    	// 2023.8.22, sine. use common-csv.jar
//    	try {
//    		CSVPrinter csvPrinter = CSVFormat.DEFAULT.builder()
//    				.setSkipHeaderRecord(true).build().print(writer);
//    		
//    		/* Driver-Name */
//    		String driverName = "N/A";
//    		/* Driver-URL */
//    		String driverURL = "N/A";
//    		/* Interval */
//    		int interval = stage.getInterval();
//    		/* Op-Id */
//    		String opId = metrics.getOpId();
//			/* Stage */
//			String stageName = stage.getId();
//			/* Op-Name */
//			String opt = metrics.getOpName();
//	        String spt = metrics.getSampleType();
//	        if (!spt.equals(opt)) {
//	        	opt += '-';
//				opt += spt;
//			}
//			/* Op-Type */
//	        String opType = metrics.getOpType();
//			/* Op-Count */
//	        int opCount = metrics.getSampleCount();
//    		/* Byte-Count */
//	        long byteCount = metrics.getByteCount();
//    		/* Avg-ResTime */
//	        String avgResTime = "N/A";
//	        double r = metrics.getAvgResTime();
//	        if (r > 0)
//	        	avgResTime = NUM.format(r);
//    		/* Avg-ProcTime */
//	        String avgProcTime = "N/A";
//	        double pt = r - metrics.getAvgXferTime();
//	        if (pt > 0)
//	            avgProcTime = NUM.format(pt);
//	        
//	        /* LatencyInfo */
//	        Histogram latency = metrics.getLatency();
//    		/* 60%-ResTime */
//	        String rt60 = latency != null ? String.valueOf(metrics.getLatency().get_60()[1]) : "N/A";
//    		/* 80%-ResTime */
//	        String rt80 = latency != null ? String.valueOf(metrics.getLatency().get_80()[1]) : "N/A";
//    		/* 90%-ResTime */
//	        String rt90 = latency != null ? String.valueOf(metrics.getLatency().get_90()[1]) : "N/A";
//    		/* 95%-ResTime */
//	        String rt95 = latency != null ? String.valueOf(metrics.getLatency().get_95()[1]) : "N/A";
//    		/* 99%-ResTime */
//	        String rt99 = latency != null ? String.valueOf(metrics.getLatency().get_99()[1]) : "N/A";
//    		/* 100%-ResTime */
//	        String rt100 = latency != null ? String.valueOf(metrics.getLatency().get_100()[1]) : "N/A";
//	        
//    		/* Throughput */
//	        String throughput = NUM.format(metrics.getThroughput());
//    		/* Bandwidth */
//	        String bandwidth = NUM.format(metrics.getBandwidth());
//    		/* Succ-Ratio */
//	        String ratio = "N/A";
//	        double t = (double) metrics.getRatio();
//	        if (t > 0)
//	            ratio = RATIO.format(metrics.getRatio());
//    		/* Status */
//	        String status = stage.getState().name().toLowerCase();
//    		/* Detailed Status */
//	        String dStatus = "";
//	        for (StateInfo state : stage.getStateHistory()) {
//	        	dStatus += state.getName().toLowerCase() + " @ " + DATETIME.format(state.getDate());
//	        }
//    		
//    		// write records.
//            csvPrinter.printRecord(
//            		stageName, missionId, opType, 
//            		sampleType, sampleCount, 
//            		byteCount, resTime, 
//            		transTime, throught, 
//            		bandwidth, succRatio);
//        } catch (Exception e) {
//			e.printStackTrace();
//		} finally {
//            if(null != writer) {
//            	writer.flush();
//            }
//        }
    	
        StringBuilder buffer = new StringBuilder();
        buffer.append(stage.getId()).append(',');
        buffer.append(stage.getInterval()).append(','); // 2023.8.16, sine. add interval
        buffer.append(metrics.getOpId()).append(','); // 2023.6.14, sine. add op id
        String opt = metrics.getOpName();
        String spt = metrics.getSampleType();
        if (spt.equals(opt))
            buffer.append(opt);
        else
            buffer.append(opt + '-' + spt);
        buffer.append(',');
        buffer.append(metrics.getOpType()).append(',');
        buffer.append(metrics.getSampleCount()).append(',');
        buffer.append(metrics.getByteCount()).append(',');
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
            buffer.append(RATIO.format(metrics.getRatio())).append(',');
        else
            buffer.append("N/A").append(',');
        
        buffer.append(stage.getState().name().toLowerCase()).append(',');
        for (StateInfo state : stage.getStateHistory()) {
            buffer.append(
                    state.getName().toLowerCase() + " @ "
                            + DATETIME.format(state.getDate())).append(',');
        }
        buffer.setCharAt(buffer.length() - 1, '\n');
        writer.write(buffer.toString());
    }

    @Override
    protected void writeMetrics(Writer writer, StageInfo stage)
            throws IOException {
         StringBuilder buffer = new StringBuilder();
         buffer.append(stage.getStage().getName()).append(',');
         buffer.append("N/A").append(','); // 2023.8.16, sine. interval
         buffer.append("N/A").append(','); // 2023.6.14, sine. op id
         buffer.append("N/A").append(',');
         buffer.append("N/A").append(',');
         buffer.append("N/A").append(',');
         buffer.append("N/A").append(',');
         buffer.append("N/A").append(',');
         buffer.append("N/A").append(',');
         buffer.append("N/A").append(',');
         buffer.append("N/A").append(',');
         buffer.append("N/A").append(',');
         buffer.append("N/A").append(',');
         buffer.append("N/A").append(',');
         buffer.append("N/A").append(',');
         buffer.append("N/A").append(',');
         buffer.append("N/A").append(',');
         buffer.append("N/A").append(',');
         buffer.append(stage.getState().name().toLowerCase()).append(',');
         for (StateInfo state : stage.getStateHistory()) {
             buffer.append(
                     state.getName().toLowerCase() + " @ "
                             + DATETIME.format(state.getDate())).append(',');
         }
         buffer.setCharAt(buffer.length() - 1, '\n');
         writer.write(buffer.toString());
    }

    private static void writeLatencyInfo(StringBuilder buffer, Histogram latency)
            throws IOException {
        if(latency == null) {
            writePercentileRT(buffer,null);
            writePercentileRT(buffer, null);
            writePercentileRT(buffer, null);
            writePercentileRT(buffer, null);
            writePercentileRT(buffer, null);
            writePercentileRT(buffer, null);
        } else {
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

}
