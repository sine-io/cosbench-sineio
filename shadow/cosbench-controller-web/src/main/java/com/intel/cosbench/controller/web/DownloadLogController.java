/*
Copyright 2013 Intel Corporation, All Rights Reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
*/

package com.intel.cosbench.controller.web;

import java.io.File;

import org.springframework.web.servlet.ModelAndView;

import com.intel.cosbench.model.WorkloadInfo;
import com.intel.cosbench.web.NotFoundException;

public class DownloadLogController extends WorkloadPageController {

    @Override
    protected ModelAndView process(WorkloadInfo workload) {
        File log = controller.getWorkloadLog(workload);
        if (log == null || !log.isFile()) {
            throw new NotFoundException();
        }
        ModelAndView result = new ModelAndView(new FileDownloadView(log, "text/plain", "workload-log.txt"));
        result.addObject("cInfo", controller.getControllerInfo());
        return result;
    }
}
