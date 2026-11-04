package com.intel.cosbench.driver.web;

import org.springframework.web.servlet.ModelAndView;

import com.intel.cosbench.model.MissionInfo;

public class LaunchMissionController extends MissionPageController {

    @Override
    protected ModelAndView process(MissionInfo info) {
        driver.launch(info.getId());
        return super.process(info);
    }
}
