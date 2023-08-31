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
package com.intel.cosbench.controller.loader;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import com.intel.cosbench.config.Stage;
import com.intel.cosbench.config.WorkloadResolver;
import com.intel.cosbench.config.XmlConfig;
import com.intel.cosbench.config.castor.CastorConfigTools;
import com.intel.cosbench.controller.model.StageContext;
import com.intel.cosbench.controller.model.StageRegistry;
import com.intel.cosbench.controller.model.TaskRegistry;
import com.intel.cosbench.controller.model.WorkloadContext;
import com.intel.cosbench.log.LogFactory;
import com.intel.cosbench.log.Logger;
import com.intel.cosbench.model.DriverInfo;
import com.intel.cosbench.model.StageInfo;
import com.intel.cosbench.model.StageState;
import com.intel.cosbench.model.WorkloadInfo;
import com.intel.cosbench.service.WorkloadLoader;

public class SimpleWorkloadLoader implements WorkloadLoader {
    private static final Logger LOGGER = LogFactory.getSystemLogger();

    private static File ARCHIVE_DIR = new File("archive");

//    static {
//        if (!ROOT_DIR.exists())
//            ROOT_DIR.mkdirs();
//        String path = ROOT_DIR.getAbsolutePath();
//        LOGGER.info("using {} for loading workload archives", path);
//    }

    public SimpleWorkloadLoader() {
        this("archive");
    }

    public SimpleWorkloadLoader(final String archive) {
        ARCHIVE_DIR = new File(archive);

        if (!ARCHIVE_DIR.exists())
            ARCHIVE_DIR.mkdirs();
        String path = ARCHIVE_DIR.getAbsolutePath();
        LOGGER.info("loading workload archives from {}", path);
    }

    private static String getRunDirName(WorkloadInfo info) {
        String name = info.getId();
        name += '-' + info.getWorkload().getName();
        return name;
    }

    @Override
    public List<WorkloadInfo> loadWorkloadRun() throws IOException {
        File file = new File(ARCHIVE_DIR, "run-history.csv");
        if (!file.exists())
            return null;
        BufferedReader reader = new BufferedReader(new FileReader(file));
        RunLoader loader = Loaders.newRunExporter(reader); // CSVRunLoader(reader)
        List<WorkloadInfo> workloads = new ArrayList<WorkloadInfo>();
        workloads = loader.load(); // #CSVRunLoader.java -> #readWorkload
        if (reader != null)
            reader.close();
        return workloads;
    }

    @Override
    public void loadWorkloadPageInfo(WorkloadInfo workloadContext)
            throws IOException {
    	/*
    	 * WorkloadContext
    	 * 		DriverRegistry --- Map <--(1)-----loadAllDriversFile
    	 * 			|--->DriverContext ------------------
    	 * 												|
    	 * 		Report <-------- add report -----------~|~---------------------(6)
    	 * 												|						|
    	 * 		StageRegistry --- List <-----(3)-------~|~------|		loadWorkloadFile						
    	 * 			|--->StageContext					|		|				|
    	 * 					Interval					|		|				|
    	 * 					ID <--(4)------------------~|~------|				|
    	 * 		------------Report --- Map <------------|------~|~----------(7)-|
    	 * 		|			ScheduleRegistry --- List -~|~-----~|~-------------~|~---- TODO: add schedule registry?
    	 * 		|				SchedulePlan ----------~|~-----~|~-------		|
    	 *	 	|					DriverContext -------		|	    |		|
    	 * 		|					offset						|		|		|
    	 * 		|					Work ----------------------~|~---	|		|
    	 * 		|	|<------SnapshotRegistry --- List <-(9)----~|~-~|~-~|~-----~|~---loadStagePageInfo
    	 * 		|	----------->Snapshot---------------------	|	|	|		|
    	 * 		|		|----------->minVersion				|	|	|	|		|
    	 * 		|		|----------->maxVersion				|	|	|	|		|
    	 * 		|		|----------->Report --- Map			|	|	|	|		|
    	 * 		|		|----------->timestamp				|	|	|	|		|
    	 * 		|		------------>version				|	|	|	|		|
    	 * 		|			Stage <----------(5)-----------~|~--|	|	|		|
    	 * 		|				name						|	|	|	|		|
    	 * 		|				storage						|	|	|	|		|
    	 * 		|				works --- List				|	|	|	|		|
    	 * 		|					Work ------------------~|~-~|~---	|		|
    	 * 		|			State <------------------------~|~--|		|		|
    	 * 		|			StateHistory <-----------------~|~-~|~-----~|~-----(8)
    	 * 		|			TaskRegistry --- List ---------~|~-~|~-----~|~------------ TODO: add task registry
    	 * 		|				TaskContext					|	|		|
    	 * 		|					Id						|	|		|
    	 * 		|					Interval				|	|		|
    	 * 		|					missionId				|	|		|
    	 * 		--------------------Report --- Map			|	|		|
    	 * 							SchedulePlan ----------~|~-~|~------- TODO: add schedule registry
    	 * 							Snapshot ----------------	|
    	 * 							TaskState					|
    	 * 					TaskReports							|
    	 * 														|
    	 * 		Workload <----(2)-loadWorkloadConfig			|
    	 * 		|--->Workflow				|					|
    	 * 			|--->stages				|				  	|
    	 * 				|--->Stage			|------------------>|
    	 * 						
    	 * */
    	
    	/* DriverRegistry - DriverContext - Map */
    	loadAllDriversFile(workloadContext); // 2023.8.23, sine.
    	
    	/* Workload */
    	loadWorkloadConfig(workloadContext);
    	
    	/* StageRegistry - StageContext - List */
        loadWorkloadFile(workloadContext);
        
        // 2023.8.16, sine. load stage page info.
        for (StageInfo stage : workloadContext.getStageInfos()) {
        	loadStagePageInfo(workloadContext, stage.getId()); // SnapshotRegistry
        	// TaskRegistry
        	loadTaskInfoFile(workloadContext, stage.getId()); // 2023.8.24, sine.
		}
    }
    
    // 2023.8.23, sine. load driver into to driver registry
    private void loadAllDriversFile(WorkloadInfo workloadContext) throws IOException {
    	File file = new File(
                new File(ARCHIVE_DIR, getRunDirName(workloadContext)), "all-drivers.csv");
        if (!file.exists())
            return;
        BufferedReader reader = new BufferedReader(new FileReader(file));
        AllDriversFileLoader loader = Loaders.newAllDriversFileLoader(reader,
                workloadContext);
        loader.load();
    }

    private void loadWorkloadConfig(WorkloadInfo workloadContext)
            throws FileNotFoundException {
        XmlConfig config = getWorkloadConfg(workloadContext);
        
        if(config != null) {
            WorkloadResolver resolver = CastorConfigTools.getWorkloadResolver();
            workloadContext.setWorkload(resolver.toWorkload(config));
            createStages(workloadContext);
        } else {
            ((WorkloadContext) workloadContext).setStageRegistry(new StageRegistry());
        }
    }

    public static XmlConfig getWorkloadConfg(WorkloadInfo workloadContext)
            throws FileNotFoundException {
        File file = new File(
                new File(ARCHIVE_DIR, getRunDirName(workloadContext)),
                "workload-config.xml");
        if (!file.exists())
            return null;
        XmlConfig config = new XmlConfig(new FileInputStream(file));
        
        return config;
    }

    private void createStages(WorkloadInfo workloadContext) {
        StageRegistry registry = new StageRegistry();
        int index = 1;
        for (Stage stage : workloadContext.getWorkload().getWorkflow()) {
            String id = "s" + index++;
            registry.addStage(createStageContext(id, stage));
        }
        ((WorkloadContext) workloadContext).setStageRegistry(registry);
    }

    private static StageContext createStageContext(String id, Stage stage) {
        StageContext sContext = new StageContext();
        String sid = id + "-" + stage.getName(); // 2023.6.15, sine. s1-test restore, not s1.
        sContext.setId(sid);
        sContext.setStage(stage);
        sContext.setState(StageState.COMPLETED, true);
        
        sContext.setTaskRegistry(new TaskRegistry()); // 2023.8.30, sine. add an empty container
        
        // TODO: can set stage interval
        // 1. if stage has one work, stage interval = work interval
        // 2. if stage has more works, stage interval = which work's interval when works's interval are different? --- how to decide?
        
        return sContext;
    }
    
    private void loadWorkloadFile(WorkloadInfo workloadContext)
            throws IOException {
        File file = new File(
                new File(ARCHIVE_DIR, getRunDirName(workloadContext)),
                getWorkloadFileName(workloadContext) + ".csv");
        if (!file.exists())
            return;
        BufferedReader reader = new BufferedReader(new FileReader(file));
        WorkloadFileLoader loader = Loaders.newWorkloadLoader(reader, 
        		workloadContext); // CSVWorkloadFileLoader(reader, workloadContext)
        loader.load(); // CSVWorkloadFileLoader.java#readWorkload
    }
    
    private static String getWorkloadFileName(WorkloadInfo info) {
        String name = info.getId();
        name += "-" + info.getWorkload().getName();
        return name;
    }

    @Override
    public void loadStagePageInfo(WorkloadInfo workloadContext, String stageId)
            throws IOException {
        File file = new File(
                new File(ARCHIVE_DIR, getRunDirName(workloadContext)),
                getStageFileName(workloadContext.getStageInfo(stageId))
                        + ".csv");
        if (!file.exists())
            return;
        BufferedReader reader = new BufferedReader(new FileReader(file));
        SnapshotLoader loader = Loaders.newSnapshotLoader(reader,
                workloadContext, stageId); // CSVSnapshotLoader(reader, workloadContext, stageId)
        loader.load(); // CSVSnapshotLoader.java#readSnapshot
    }
    
    private static String getStageFileName(StageInfo info) {
        String name = info.getId();
        //name += "-" + info.getStage().getName(); // because of stageId is s1-xxx
        return name;
    }
    
    // 2023.8.23, sine.
    // load task info to task registry
    private void loadTaskInfoFile(WorkloadInfo workloadContext, String stageId) throws IOException {
    	for (DriverInfo dInfo : workloadContext.getDriverInfos()) {
    		File file = new File(
                    new File(ARCHIVE_DIR, getRunDirName(workloadContext)), dInfo.getName() + ".csv");
            if (!file.exists())
                return;
            BufferedReader reader = new BufferedReader(new FileReader(file));
            TaskInfoFileLoader loader = Loaders.newTaskInfoFileLoader(reader,
                    workloadContext, dInfo, stageId);
            loader.load();
		}
	}
}
