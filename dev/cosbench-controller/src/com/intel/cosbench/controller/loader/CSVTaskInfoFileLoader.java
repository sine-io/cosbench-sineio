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

package com.intel.cosbench.controller.loader;

import static com.intel.cosbench.controller.loader.Formats.getIntValue;

import java.io.*;

import com.intel.cosbench.config.Work;
import com.intel.cosbench.controller.model.DriverContext;
import com.intel.cosbench.controller.model.SchedulePlan;
import com.intel.cosbench.controller.model.StageContext;
import com.intel.cosbench.controller.model.TaskContext;
import com.intel.cosbench.model.DriverInfo;
import com.intel.cosbench.model.TaskState;
import com.intel.cosbench.model.WorkloadInfo;

class CSVTaskInfoFileLoader extends AbstractTaskInfoFileLoader {

    public CSVTaskInfoFileLoader(BufferedReader reader,
            WorkloadInfo workloadContext, DriverInfo dInfo, String stageId) throws IOException {
        super.init(reader, workloadContext, dInfo, stageId);
    }

    @Override
    protected void readHeader() throws IOException {
        this.reader.readLine();
    }

    @Override
    protected void readWorkload() throws IOException {
        String workloadRecordLine = null;
        String lastStageName = null;
        String stageId = null;
        
        int index = 1;
    	while ((workloadRecordLine = this.reader.readLine()) != null) {
            String[] columns = workloadRecordLine.split(",");
            
            if (lastStageName == null
                    || !lastStageName.equalsIgnoreCase(columns[0])) {
            	
            	// #1 2023.6.15, sine. stageId should be: s1-test restore, etc. otherwise, is columns[0]
            	int n = columns[0].lastIndexOf("-"); // mission cancelled or terminated and etc, columns[0] does not has -
            	if (n > 0) {
            		stageId = columns[0];
				} else {
					stageId = "s" + index++ + '-' + columns[0]; // should add columns[0] = s1-columns[0]
				}
            	
                lastStageName = columns[0];
            }
            
            if (stageId.equals(stageContext.getId())) { // if readline's stage id  equal to me, do something.
            	TaskContext tContext = new TaskContext();

            	/* Mission ID */
            	tContext.setMissionId(columns[1]);
            	
            	/* Task ID */
            	tContext.setId(columns[2]);
            	/* Task Interval*/
            	tContext.setInterval(getIntValue(columns[3]));
            	/* Task State */
            	tContext.setState(TaskState.valueOf(columns[4]));
                
                /* Schedule */
            	SchedulePlan sPlan = new SchedulePlan();
            	
            	/* Driver Context */
            	DriverContext driver = new DriverContext();
            	driver.setName(dInfo.getName());
            	driver.setUrl(dInfo.getUrl());
            	sPlan.setDriver(driver);
            	
            	/* Schedule offset */
            	sPlan.setOffset(getIntValue(columns[5]));
            	/* Work */
				for (Work work : stageContext.getStage().getWorks()) {
					if (work.getName().equals(columns[6])) {
						sPlan.setWork(work);
					}
				}
				/* Workers */
				sPlan.setWorkers(getIntValue(columns[7]));
				
				tContext.setSchedule(sPlan);
            	
            	((StageContext) stageContext).getTaskRegistry().addTask(tContext);
            }
    	}
    }
}