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

package com.intel.cosbench.controller.handler;

import java.io.InputStream;

import jakarta.servlet.http.*;

import com.intel.cosbench.config.XmlConfig;
import com.intel.cosbench.controller.web.MultipartConfigStream;
import com.intel.cosbench.service.ControllerService;

public class ConfigHandler extends AbstractClientHandler {

    private ControllerService controller;

    public void setController(ControllerService controller) {
        this.controller = controller;
    }

    @Override
    protected String process(HttpServletRequest req, HttpServletResponse res)
            throws Exception {
        String id;
        try (InputStream stream = MultipartConfigStream.retrieve(req)) {
            id = controller.submit(new XmlConfig(stream));
        }
        controller.fire(id);
        return "Accepted with ID: " + id;
    }
}
