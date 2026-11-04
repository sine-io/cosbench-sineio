package com.intel.cosbench.driver.web;

import org.springframework.web.servlet.ModelAndView;

import com.intel.cosbench.model.MissionInfo;

public class PerformLoginController extends MissionPageController {

    @Override
    protected ModelAndView process(MissionInfo info) {
        driver.login(info.getId());
        return super.process(info);
    }
}
