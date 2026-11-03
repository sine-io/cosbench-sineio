/**

Copyright 2013 Intel Corporation, All Rights Reserved.
Copyright 2019 OpenIO Corporation, All Rights Reserved.

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

import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.DeserializationConfig.Feature;

import com.intel.cosbench.controller.model.DriverContext;
import com.intel.cosbench.controller.port.DriverCommandClient;
import com.intel.cosbench.controller.port.DriverCommandResponse;
import com.intel.cosbench.log.LogFactory;
import com.intel.cosbench.log.Logger;
import com.intel.cosbench.protocol.TriggerResponse;

abstract class TriggerHttpTasklet implements Tasklet{
    private transient ObjectMapper mapper;
    private final DriverCommandClient client;
    private final long workloadDeadlineNanos;
    protected DriverContext driver;
    protected String trigger = null;
    protected boolean isEnable; // true=enableTrigger; false=killTrigger
    protected String scriptName = null;
    protected String wsId = null;

    Class<TriggerResponse> clazz = TriggerResponse.class;

    protected static final Logger LOGGER = LogFactory.getSystemLogger();

    protected abstract void execute();
    protected abstract void handleResponse(TriggerResponse response);

    public TriggerHttpTasklet(DriverContext driver, String trigger, boolean option, String wsId,
            DriverCommandClient client, long workloadDeadlineNanos) {
        this.driver = driver;
        this.trigger = trigger;
        this.isEnable = option;
        this.wsId = wsId;
        this.client = client;
        this.workloadDeadlineNanos = workloadDeadlineNanos;
    }

    protected void initObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        DeserializationConfig config = mapper.copyDeserializationConfig();
        config.disable(Feature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.setDeserializationConfig(config);
        this.mapper = mapper;
    }

    private String issueHttpRequest(String command, String content) {
        String url = driver.getUrl() + "/i/" + command + ".command";
        try {
            byte[] payload = content == null ? new byte[0]
                    : content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            long deadline = isEnable ? workloadDeadlineNanos : Long.MAX_VALUE;
            DriverCommandResponse response = client.execute(command, payload, true, deadline);
            return response.body();
        } catch (Exception e) {
            LOGGER.error("fail to POST Driver trigger command to {}", url, e);
            return null;
        }
    }

    protected void issueCommand(String command, String content) {
        TriggerResponse response = null;
        String body = issueHttpRequest(command, content);
        if (body == null) {
            LOGGER.error("TriggerResponse body is null");
            return;
        }
        try {
            response = this.mapper.readValue(body, clazz);
        } catch (Exception e) {
            LOGGER.error("can not parse TriggerResponse body", e);
            return;
        }
        if (!response.isSucc()) {
            String msg = "driver report error: HTTP {} - {}";
            LOGGER.error(msg, response.getCode(), response.getError());
            return;
        }
        handleResponse(response);
    }

    @Override
    public Tasklet call() {
        try {
            execute();
        } catch (Exception e) {
            LOGGER.error("unexpected exception of trigger", e);
        }
        return this; /* okay -- done */
    }

}
