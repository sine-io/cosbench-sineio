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
package com.intel.cosbench.controller.exporter;

import java.io.IOException;
import java.io.Writer;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import com.intel.cosbench.bench.TaskReport;
import com.intel.cosbench.controller.model.SchedulePlan;
import com.intel.cosbench.controller.model.TaskContext;
import com.intel.cosbench.model.TaskInfo;
import com.intel.cosbench.model.TaskState;

public class CSVTaskExporter extends AbstractTaskExporter {

    public CSVTaskExporter() {
        /* empty */
    }
    
    protected void writeHeader(Writer writer) throws IOException {
    	// 2023.8.22, sine. overwrite, use common-csv.jar
    	final String[] header= {
    			"Stage", "Mission-ID", "Task-ID",
    			"Task-Interval", "Task-State", "Schedule-offset", "Work-Name", "Workers"};
    	
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
    		
    		// taskContext
			TaskInfo tInfo = tReport.getTaskInfo();
			
			/* Stage ID */
			String stageName = tReport.getStageName();
        	/* Mission ID */
			String  missionId= tInfo.getMissionId();
			/* Task ID */
			String taskId = tInfo.getId();
			/* Task-Interval */
			int interval = ((TaskContext) tInfo).getInterval();
        	/* Task-State */
			TaskState tState = tInfo.getState();
        	/* Schedule-offset */
			int sOffset = ((SchedulePlan) tInfo.getSchedule()).getOffset();
			/* Work Name */
			String workName = tInfo.getSchedule().getWork().getName();
			/* Workers */
			int workers = tInfo.getSchedule().getWorkers();

            // write records.
            csvPrinter.printRecord(
            		stageName, 
            		missionId, taskId, interval, 
            		tState, sOffset, workName, workers);
        } catch (Exception e) {
			e.printStackTrace();
		} finally {
            if(null != writer) {
            	writer.flush();
            }
        }
    }
}
