/*
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

package com.intel.cosbench.controller.web;

import static com.intel.cosbench.model.StageState.isRunning;
import static com.intel.cosbench.model.StageState.isStopped;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.springframework.web.servlet.ModelAndView;

import com.intel.cosbench.model.StageInfo;
import com.intel.cosbench.model.WorkloadInfo;
import com.intel.cosbench.service.ControllerService;
import com.intel.cosbench.web.AbstractController;
import com.intel.cosbench.web.BadRequestException;
import com.intel.cosbench.web.NotFoundException;

public class StagePageController extends AbstractController {

    protected ControllerService controller;

    public void setController(ControllerService controller) {
        this.controller = controller;
    }

    @Override
    protected ModelAndView process(HttpServletRequest req, HttpServletResponse res) {
        String workloadId = req.getParameter("wid");
        String stageId = req.getParameter("sid");
        if (StringUtils.isEmpty(workloadId) || StringUtils.isEmpty(stageId)) {
            throw new BadRequestException();
        }
        return process(workloadId, stageId);
    }

    protected ModelAndView process(String workloadId, String stageId) {
        WorkloadInfo workload = controller.getWorkloadInfo(workloadId);
        if (workload == null) {
            throw new NotFoundException();
        }
        StageInfo stage = workload.getStageInfo(stageId);
        if (stage == null) {
            throw new NotFoundException();
        }
        if (controller.getloadArch() && stage.getSnapshotRegistry().getSize() == 0) {
            try {
                controller.getWorkloadLoader().loadStagePageInfo(workload, stage.getId());
            } catch (IOException ignored) {
                // Preserve the legacy page behavior when archived details are unavailable.
            }
        }
        return process(workload, stage);
    }

    protected ModelAndView process(WorkloadInfo workload, StageInfo stage) {
        ModelAndView result = new ModelAndView("stage");
        result.addObject("cInfo", controller.getControllerInfo());
        result.addObject("sInfo", stage);
        result.addObject("wInfo", workload);
        result.addObject("isStopped", isStopped(stage.getState()));
        result.addObject("isRunning", isRunning(stage.getState()));
        return result;
    }
}
