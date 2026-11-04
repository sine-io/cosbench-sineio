package com.intel.cosbench.driver.web;

import static com.intel.cosbench.model.MissionState.isRunning;
import static com.intel.cosbench.model.MissionState.isStopped;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.ModelAndView;

import com.intel.cosbench.model.MissionInfo;
import com.intel.cosbench.service.DriverService;
import com.intel.cosbench.web.AbstractController;
import com.intel.cosbench.web.BadRequestException;
import com.intel.cosbench.web.NotFoundException;

public class WorkersPageController extends AbstractController {

    private DriverService driver;

    public void setDriver(DriverService driver) {
        this.driver = driver;
    }

    @Override
    protected ModelAndView process(HttpServletRequest request, HttpServletResponse response) {
        String id = request.getParameter("id");
        if (id == null || id.isEmpty()) {
            throw new BadRequestException();
        }
        MissionInfo info = driver.getMissionInfo(id);
        if (info == null) {
            throw new NotFoundException();
        }
        ModelAndView result = new ModelAndView("workers");
        result.addObject("info", info);
        result.addObject("dInfo", driver.getDriverInfo());
        result.addObject("isStopped", isStopped(info.getState()));
        result.addObject("isRunning", isRunning(info.getState()));
        return result;
    }
}
