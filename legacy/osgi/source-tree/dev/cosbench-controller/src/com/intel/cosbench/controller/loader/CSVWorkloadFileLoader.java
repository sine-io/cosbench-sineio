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

package com.intel.cosbench.controller.loader;

import static com.intel.cosbench.controller.loader.Formats.DATETIME;

import java.io.*;
import java.text.ParseException;
import java.util.Date;

import com.intel.cosbench.bench.Histogram;
import com.intel.cosbench.bench.Metrics;
import com.intel.cosbench.bench.Report;
import com.intel.cosbench.controller.model.StageContext;
import com.intel.cosbench.model.StageState;
import com.intel.cosbench.model.WorkloadInfo;

class CSVWorkloadFileLoader extends AbstractWorkloadFileLoader {

    public CSVWorkloadFileLoader(BufferedReader reader,
            WorkloadInfo workloadContext) throws IOException {
        super.init(reader, workloadContext);
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
        boolean sameStage = false;
        
        int index = 1;
        while ((workloadRecordLine = this.reader.readLine()) != null) {
            String[] columns = workloadRecordLine.split(",");
            sameStage = true;
            
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
                sameStage = false;
            }
            
            if (columns[18].equalsIgnoreCase("completed")) { // because of Interval and Op-Id, column is 18.
                Metrics metrics = loadMetrics(columns);
                if (!sameStage) {
                    Report report = new Report();
                    workloadContext.getStageInfo(stageId).setReport(report); // add an empty report container.
                }
                workloadContext.getStageInfo(stageId).getReport().addMetrics(metrics);
                
                /*
                 * 2023.8.24, sine.
                 * key should be w40-s1-create bucket-1, w40-s2-test write-1, w40-s2-test write-2...
                 * actually, the key is op1.write.write.write...
                 * and the name not used, so let it be.
                 * */
                workloadContext.getReport().addMetrics(metrics);
                
                // 2023.8.16, sine. set stage interval.
                int interval = 5; // default if 5.
                try {
                	interval = Integer.parseInt(columns[1]);
				} catch (NumberFormatException nfe) {
					// do nothing.
					nfe.printStackTrace();
				}
                // StageInfo does not have setInterval, so turn to StageContext.
                ((StageContext) workloadContext.getStageInfo(stageId)).setInterval(interval);
            }
            
            for (StageState state : StageState.values()) {
                // if (columns[16].equalsIgnoreCase(state.toString().toLowerCase())) {
            	if (columns[18].equalsIgnoreCase(state.toString().toLowerCase())) { // because of Interval and Op-Id, column is 18.
                	workloadContext.getStageInfo(stageId).setState(state, true);
                    break;
                }
            }
            
            // int pos = 16;
            int pos = 18; // because of Interval and Op-Id, column is 18.
            while (!sameStage && ++pos <= columns.length - 1) {
                String[] strArray = columns[pos].split("@");
                String stateName = strArray[0].trim();
                Date stateDate = null;
                try {
                    stateDate = DATETIME.parse(strArray[1].trim());
                } catch (ParseException e) {
                    e.printStackTrace();
                }
                workloadContext.getStageInfo(stageId).setState(stateName, stateDate);
            }
        }
    }

    private Metrics loadMetrics(String[] columns) {
        Metrics metrics = new Metrics();
        metrics.setOpId(columns[2]); // 2023.6.14, sine.
        
        // 2023.6.14 because of Interval and Op-id column, all numbers should +2.
        int n = columns[3].lastIndexOf("-");
        if (n > 0) {
            metrics.setOpName(columns[3].substring(0, n));
            metrics.setSampleType(columns[3].substring(n + 1));
        } else {
            metrics.setOpName(columns[3]);
            metrics.setSampleType(columns[3]);
        }
        
        metrics.setOpType(columns[4]);
        metrics.setSampleCount(Integer.valueOf(columns[5]));
        metrics.setByteCount(Long.valueOf(columns[6]));
        double rt = columns[7].equalsIgnoreCase("N/A") ? 0 : Double.valueOf(columns[7]);
        metrics.setAvgResTime(rt);
        double pt = columns[8].equalsIgnoreCase("N/A") ? 0 : Double.valueOf(columns[8]);
        metrics.setAvgXferTime(rt - pt);
        metrics.setLatency(loadHistogram(columns));
        metrics.setThroughput(Double.valueOf(columns[15]));
        metrics.setBandwidth(Double.valueOf(columns[16]));
        setRatio(columns[17], metrics);
        
        // 2023.6.15, sine. metrics's name should be op1.head.head.head, etc.
        String mType = Metrics.getMetricsType(metrics.getOpId(), metrics.getOpType(), metrics.getSampleType(), metrics.getOpName());
        metrics.setName(mType);
        
        return metrics;
    }

    private void setRatio(String column, Metrics metrics) {
        if (!column.equalsIgnoreCase("N/A")) {
            metrics.setRatio(Double.valueOf(column.substring(0,
                    column.length() - 1)) / 100.0);
            metrics.setTotalSampleCount(metrics.getSampleCount()
                    / metrics.getRatio() > Integer.MAX_VALUE ? Integer.MAX_VALUE
                    : (int) (metrics.getSampleCount() / metrics.getRatio()));
        } else {
            metrics.setRatio(0D);
            metrics.setTotalSampleCount(0);
        }
    }

    private Histogram loadHistogram(String[] columns) {
        Histogram histogram = new Histogram();
        
     // 2023.8.16 because of Interval and Op-id column, all numbers should +2.
        long[] l_60 = new long[2];
        l_60[1] = columns[9].equalsIgnoreCase("N/A") ? 0L : Long
                .valueOf(columns[9]);
        histogram.set_60(l_60);
        long[] l_80 = new long[2];
        l_80[1] = columns[10].equalsIgnoreCase("N/A") ? 0L : Long
                .valueOf(columns[10]);
        histogram.set_80(l_80);
        long[] l_90 = new long[2];
        l_90[1] = columns[11].equalsIgnoreCase("N/A") ? 0L : Long
                .valueOf(columns[11]);
        histogram.set_90(l_90);
        long[] l_95 = new long[2];
        l_95[1] = columns[12].equalsIgnoreCase("N/A") ? 0L : Long
                .valueOf(columns[12]);
        histogram.set_95(l_95);
        long[] l_99 = new long[2];
        l_99[1] = columns[13].equalsIgnoreCase("N/A") ? 0L : Long
                .valueOf(columns[13]);
        histogram.set_99(l_99);
        long[] l_100 = new long[2];
        l_100[1] = columns[14].equalsIgnoreCase("N/A") ? 0L : Long
                .valueOf(columns[14]);
        histogram.set_100(l_100);
        return histogram;
    }
}