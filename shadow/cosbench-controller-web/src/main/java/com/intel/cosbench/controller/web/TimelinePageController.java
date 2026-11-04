/*
Copyright 2013 Intel Corporation, All Rights Reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
*/

package com.intel.cosbench.controller.web;

import org.springframework.web.servlet.ModelAndView;

import com.intel.cosbench.model.StageInfo;
import com.intel.cosbench.model.WorkloadInfo;

public class TimelinePageController extends StagePageController {

    @Override
    protected ModelAndView process(WorkloadInfo workload, StageInfo stage) {
        ModelAndView result = new ModelAndView("timeline");
        result.addObject("cInfo", controller.getControllerInfo());
        result.addObject("wInfo", workload);
        result.addObject("sInfo", stage);
        return result;
    }
}
