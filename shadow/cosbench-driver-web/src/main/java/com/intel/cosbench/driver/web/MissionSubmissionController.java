package com.intel.cosbench.driver.web;

import java.io.InputStream;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;

import com.intel.cosbench.config.ConfigException;
import com.intel.cosbench.config.XmlConfig;
import com.intel.cosbench.service.DriverService;
import com.intel.cosbench.web.AbstractController;
import com.intel.cosbench.web.BadRequestException;

public class MissionSubmissionController extends AbstractController {

    private DriverService driver;

    public void setDriver(DriverService driver) {
        this.driver = driver;
    }

    @Override
    protected ModelAndView process(HttpServletRequest request, HttpServletResponse response) throws Exception {
        if (!(request instanceof MultipartHttpServletRequest multipart)) {
            throw new BadRequestException();
        }
        MultipartFile config = multipart.getFile("config");
        if (config == null) {
            throw new BadRequestException();
        }

        try (InputStream stream = config.getInputStream()) {
            String id = driver.submit(new XmlConfig(stream));
            return submissionResult("your mission has been accepted", id, null);
        } catch (ConfigException failure) {
            return submissionResult(null, null, failure.getMessage());
        }
    }

    private ModelAndView submissionResult(String submitted, String id, String error) {
        ModelAndView result = new ModelAndView("submit");
        result.addObject("dInfo", driver.getDriverInfo());
        result.addObject("aInfos", driver.getActiveMissions());
        if (submitted != null) {
            result.addObject("submitted", submitted);
            result.addObject("id", id);
        }
        if (error != null) {
            result.addObject("error", error);
        }
        return result;
    }
}
