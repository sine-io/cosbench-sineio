package com.intel.cosbench.driver.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class DriverErrorController implements ErrorController {

    @RequestMapping("/error")
    ModelAndView error(HttpServletRequest request, HttpServletResponse response) {
        Object statusValue = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = statusValue instanceof Integer code ? code : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        response.setStatus(status);
        if (status == HttpServletResponse.SC_NOT_FOUND) {
            return new ModelAndView("404");
        }
        if (status == HttpServletResponse.SC_BAD_REQUEST) {
            return new ModelAndView("400");
        }
        Object exception = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        String cause = exception instanceof Throwable failure ? failure.toString() : "request failed";
        return new ModelAndView("500", "cause", cause);
    }
}
