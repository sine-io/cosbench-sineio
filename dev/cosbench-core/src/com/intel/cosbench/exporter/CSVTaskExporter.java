/**

Copyright 2013 Intel Corporation, All Rights Reserved.
Copyright 2019 OpenIO Corporation, All Rights Reserved.

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
package com.intel.cosbench.exporter;

import static com.intel.cosbench.exporter.Formats.NUM;
import static com.intel.cosbench.exporter.Formats.RATIO;

import java.io.IOException;
import java.io.Writer;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import com.intel.cosbench.bench.Metrics;
import com.intel.cosbench.bench.Report;
import com.intel.cosbench.bench.TaskReport;
import com.intel.cosbench.model.TaskInfo;

public class CSVTaskExporter extends AbstractTaskExporter{

    public CSVTaskExporter() {
        /* empty */
    }
    
    protected void writeHeader(Writer writer) throws IOException {
    	// 2023.8.22, sine. overwrite, use common-csv.jar
    	final String[] header= {
    			"Stage-ID", "Task-Interval",
    			"Mission-ID", "Schedule-Offset",
    			"Schedule-Workers", "Work-Name",
    			"Op-Type", "Op-Ratio",
        		"Sample-Type", "Op-Count", 
        		"Byte-Count", "Avg-ResTime", 
        		"Avg-ProcTime", "Throughput", 
        		"Bandwidth", "Succ-Ratio"};
    	
    	try {
            CSVFormat.DEFAULT.builder().setHeader(header).build().print(writer);
        } catch (Exception e) {
			e.printStackTrace();
		} finally {
            if(null != writer) {
            	writer.flush();
            }
        }
    }

    protected void writeMetrics(Writer writer, TaskReport tReport)throws IOException {
    	// 2023.8.22, sine. use common-csv.jar
    	try {
    		CSVPrinter csvPrinter = CSVFormat.DEFAULT.builder()
    				.setSkipHeaderRecord(true).build().print(writer);
    		Report report = tReport.getReport();
    		
    		for (Metrics metrics : report) {
    			// taskContext
    			TaskInfo tInfo = tReport.getTaskInfo();
    			
    			/* Stage ID */
    			String stageName = tReport.getStageName();
    			/* Task-Interval */
    			tReport.getTaskInfo();
            	/* Mission ID */
    			String  missionId= tReport.getTaskInfo().getMissionId();
    			/* Schedule-Offset */
    			tReport.getTaskInfo().getSchedule();
    			/* Schedule-Workers */
    			int workers = tReport.getTaskInfo().getSchedule().getWorkers();
    			/* Work-Name */
    			String workName = tReport.getTaskInfo().getSchedule().getWork().getName();
            	/* Operation Type */
    			String opType = metrics.getOpType();
    			/* Op-Ratio */
    			double ratio = metrics.getRatio();
            	/* Sample Type */
    			String sampleType = metrics.getSampleType();
            	/* Operation Count */
    			int sampleCount = metrics.getSampleCount();
            	/* Byte Count */
    			long byteCount = metrics.getByteCount();
    			
            	/* Response Time */
    			String resTime = "N/A";
            	double r = metrics.getAvgResTime();
                if (r > 0) {
                	resTime = NUM.format(r);
                }
                
            	/* Transfer Time */
                String transTime = "N/A";
                double pt = metrics.getAvgResTime() - metrics.getAvgXferTime();
                if (pt > 0) {
                	transTime = NUM.format(pt);
                }
                
            	/* Throughput */
                String throught = NUM.format(metrics.getThroughput());
            	/* Bandwidth */
                String bandwidth = NUM.format(metrics.getBandwidth());
                
            	/* Success Ratio */
                String succRatio = "N/A";
                double t = (double) metrics.getRatio();
                if (t > 0) {
                	succRatio = RATIO.format(metrics.getRatio());
                }

                // write records.
                csvPrinter.printRecord(
                		stageName, missionId, opType, 
                		sampleType, sampleCount, 
                		byteCount, resTime, 
                		transTime, throught, 
                		bandwidth, succRatio);
			}
        } catch (Exception e) {
			e.printStackTrace();
		} finally {
            if(null != writer) {
            	writer.flush();
            }
        }
    }
}
