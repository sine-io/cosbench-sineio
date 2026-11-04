/**

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

import java.io.InputStream;

import jakarta.servlet.http.*;

import org.xml.sax.SAXParseException;
import org.springframework.web.servlet.ModelAndView;

import com.intel.cosbench.config.*;
import com.intel.cosbench.service.ControllerService;
import com.intel.cosbench.web.*;

public class WorkloadSubmissionController extends AbstractController {

    private static final String MALFORMED_XML_MESSAGE = "cannot parse the xml configuration from <stream>, "
            + "due to XML document structures must start and end within the same entity.";
    private static final String SAX_PROLOG_ERROR = "Content is not allowed in prolog.";

    private ControllerService controller;

    public void setController(ControllerService controller) {
        this.controller = controller;
    }

    @Override
    protected ModelAndView process(HttpServletRequest req,
            HttpServletResponse res) throws Exception {
        String id = null;
        try {
            try (InputStream stream = MultipartConfigStream.retrieve(req)) {
                id = controller.submit(new XmlConfig(stream));
            }
            controller.fire(id);
        } catch (ConfigException e) {
            return createErrResult(submissionErrorMessage(e));
        }
        return createSuccResult(id);
    }

    private ModelAndView createErrResult(String msg) {
        ModelAndView result = new ModelAndView("submit");
        result.addObject("aInfos", controller.getActiveWorkloads());
        result.addObject("error", msg);
        result.addObject("cInfo", controller.getControllerInfo());
        return result;
    }

    private static String submissionErrorMessage(ConfigException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SAXParseException parseError) {
                String detail = parseError.getMessage();
                if (SAX_PROLOG_ERROR.equals(detail)) {
                    return MALFORMED_XML_MESSAGE;
                }
                if (detail != null && !detail.isBlank()) {
                    return "cannot parse the xml configuration from <stream>, due to " + detail;
                }
            }
        }
        return error.getMessage();
    }

    private ModelAndView createSuccResult(String id) {
        ModelAndView result = new ModelAndView("submit");
        result.addObject("aInfos", controller.getActiveWorkloads());
        result.addObject("submitted", "your workload has been accepted");
        result.addObject("id", id);
        result.addObject("cInfo", controller.getControllerInfo());
        return result;
    }

}
