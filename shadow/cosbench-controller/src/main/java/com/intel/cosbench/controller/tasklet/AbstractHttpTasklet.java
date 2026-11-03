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

package com.intel.cosbench.controller.tasklet;

import java.nio.charset.StandardCharsets;

import com.intel.cosbench.controller.model.TaskContext;
import com.intel.cosbench.controller.port.DriverCommandClient;
import com.intel.cosbench.controller.port.DriverCommandResponse;
import com.intel.cosbench.service.CancelledException;

abstract class AbstractHttpTasklet extends AbstractTasklet {

    public AbstractHttpTasklet(TaskContext context) {
        super(context);
    }

    protected String issueHttpRequest(String command, String content) {
        String url = getDriver().getUrl() + "/i/" + command + ".command";
        DriverCommandClient client = context.getDriverCommandClient();
        if (client == null) {
            throw new TaskletException(new IllegalStateException("Driver command client is not configured"));
        }
        byte[] payload = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        boolean write = isWrite(command);
        long deadline = command.equals("close") || command.equals("abort")
                ? Long.MAX_VALUE : context.getWorkloadDeadlineNanos();
        LOGGER.debug("[ >> ] - {} -> {}", payload.length == 0 ? "[empty-body]" : "[command-body]", url);
        try {
            DriverCommandResponse response = client.execute(command, payload, write, deadline);
            String body = response.body();
            if (body.length() < 2048) {
                LOGGER.debug("[ << ] - HTTP {} {}", response.status(), body);
            } else {
                LOGGER.debug("[ << ] - HTTP {} [body-omitted]", response.status());
            }
            return body;
        } catch (RuntimeException failure) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancelledException();
            }
            LOGGER.error("fail to POST Driver command " + command + " to " + url, failure);
            throw new TaskletException(failure);
        }
    }

    private static boolean isWrite(String command) {
        return switch (command) {
            case "submit", "login", "launch", "close", "abort", "trigger" -> true;
            default -> false;
        };
    }

}
