/*
Copyright 2013 Intel Corporation, All Rights Reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
*/

package com.intel.cosbench.controller.web;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;

import com.intel.cosbench.controller.exporter.Exporters;
import com.intel.cosbench.controller.exporter.StageExporter;
import com.intel.cosbench.model.StageInfo;
import com.intel.cosbench.model.WorkloadInfo;

public class TimelineCSVController extends StagePageController {

    private static final View CSV = new CsvView();

    private static final class CsvView implements View {

        @Override
        public String getContentType() {
            return "text/csv;charset=ISO-8859-1";
        }

        @Override
        public void render(Map<String, ?> model, HttpServletRequest request, HttpServletResponse response)
                throws Exception {
            response.setContentType("text/csv;charset=ISO-8859-1");
            StageInfo stage = (StageInfo) model.get("sInfo");
            StageExporter exporter = Exporters.newStageExporter(stage);
            exporter.export(response.getWriter());
        }
    }

    @Override
    protected ModelAndView process(WorkloadInfo workload, StageInfo stage) {
        ModelAndView result = new ModelAndView(CSV, "sInfo", stage);
        result.addObject("cInfo", controller.getControllerInfo());
        return result;
    }
}
