package com.intel.cosbench.driver.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.ModelAndView;

import com.intel.cosbench.service.DriverService;
import com.intel.cosbench.web.AbstractController;

public class IndexPageController extends AbstractController {

    private DriverService driver;

    public void setDriver(DriverService driver) {
        this.driver = driver;
    }

    @Override
    protected ModelAndView process(HttpServletRequest request, HttpServletResponse response) {
        ModelAndView result = new ModelAndView("index");
        result.addObject("dInfo", driver.getDriverInfo());
        result.addObject("aInfos", driver.getActiveMissions());
        result.addObject("hInfos", driver.getHistoryMissions());
        return result;
    }
}
