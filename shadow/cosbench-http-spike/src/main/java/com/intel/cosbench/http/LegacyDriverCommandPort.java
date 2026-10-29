package com.intel.cosbench.http;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Scanner;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intel.cosbench.config.XmlConfig;
import com.intel.cosbench.model.DriverInfo;
import com.intel.cosbench.model.MissionInfo;
import com.intel.cosbench.model.MissionState;
import com.intel.cosbench.protocol.AbortResponse;
import com.intel.cosbench.protocol.CloseResponse;
import com.intel.cosbench.protocol.LaunchResponse;
import com.intel.cosbench.protocol.PingResponse;
import com.intel.cosbench.protocol.QueryResponse;
import com.intel.cosbench.protocol.SubmitResponse;
import com.intel.cosbench.service.DriverService;

/** Adapter from HTTP request bytes to the existing DriverService application port. */
public final class LegacyDriverCommandPort implements DriverCommandController.DriverCommandPort {

    private final DriverService driver;
    private final ObjectMapper mapper;

    public LegacyDriverCommandPort(DriverService driver) {
        this(driver, new ObjectMapper());
    }

    LegacyDriverCommandPort(DriverService driver, ObjectMapper mapper) {
        this.driver = driver;
        this.mapper = mapper;
    }

    @Override
    public DriverCommandResult ping(byte[] body) {
        if (body == null || body.length == 0) {
            return badRequest();
        }
        DriverInfo info = driver.getDriverInfo();
        PingResponse response = new PingResponse();
        response.setName(info.getName());
        response.setAddress(info.getUrl());
        response.setTimeStamp(String.valueOf(System.currentTimeMillis()));
        return json(response);
    }

    @Override
    public DriverCommandResult submit(byte[] body) {
        if (body == null || body.length == 0) {
            return badRequest();
        }
        String id = driver.submit(new XmlConfig(new ByteArrayInputStream(body)));
        SubmitResponse response = new SubmitResponse();
        response.setId(id);
        return json(response);
    }

    @Override public DriverCommandResult login(byte[] body) { return mission(body, "login"); }
    @Override public DriverCommandResult launch(byte[] body) { return mission(body, "launch"); }
    @Override public DriverCommandResult close(byte[] body) { return mission(body, "close"); }
    @Override public DriverCommandResult abort(byte[] body) { return mission(body, "abort"); }
    @Override public DriverCommandResult query(byte[] body) { return mission(body, "query"); }

    @Override
    public DriverCommandResult trigger(byte[] body) {
        throw new UnsupportedOperationException("trigger execution remains on the legacy web handler");
    }

    private DriverCommandResult mission(byte[] body, String command) {
        String id = missionId(body);
        if (id == null) {
            return badRequest();
        }
        MissionInfo info = driver.getMissionInfo(id);
        if (info == null) {
            return new DriverCommandResult(404, "{\"code\":404,\"succ\":false,\"error\":\"mission not found\"}");
        }
        return switch (command) {
            case "login" -> {
                driver.login(id);
                yield missionResponse(id, ignored -> new com.intel.cosbench.protocol.LoginResponse());
            }
            case "launch" -> {
                driver.launch(id);
                yield missionResponse(id, LegacyDriverCommandPort::launchResponse);
            }
            case "close" -> {
                driver.close(id);
                yield missionResponse(id, LegacyDriverCommandPort::closeResponse);
            }
            case "abort" -> {
                driver.abort(id);
                yield missionResponse(id, LegacyDriverCommandPort::abortResponse);
            }
            case "query" -> missionResponse(id, LegacyDriverCommandPort::queryResponse);
            default -> throw new IllegalArgumentException("unsupported Driver command: " + command);
        };
    }

    private DriverCommandResult missionResponse(String id, Function<MissionInfo, Object> responseFactory) {
        MissionInfo current = driver.getMissionInfo(id);
        if (current == null) {
            return new DriverCommandResult(404,
                    "{\"code\":404,\"succ\":false,\"error\":\"mission not found\"}");
        }
        return json(responseFactory.apply(current));
    }

    private static LaunchResponse launchResponse(MissionInfo info) {
        LaunchResponse response = new LaunchResponse();
        if (info.getMission() != null) {
            response.setInterval(info.getMission().getInterval());
        }
        return response;
    }

    private static QueryResponse queryResponse(MissionInfo info) {
        QueryResponse response = new QueryResponse();
        if (info.getSnapshot() != null) {
            response.setTime(info.getSnapshot().getTimestamp());
            response.setVersion(info.getSnapshot().getVersion());
            response.setMinVersion(info.getSnapshot().getMinVersion());
            response.setMaxVersion(info.getSnapshot().getMaxVersion());
            response.setReport(Arrays.asList(info.getSnapshot().getReport().getAllMetrics()));
        }
        response.setRunning(info.getState() == MissionState.LAUNCHED);
        return response;
    }

    private static CloseResponse closeResponse(MissionInfo info) {
        CloseResponse response = new CloseResponse();
        response.setState(info.getState() == MissionState.FAILED
                ? com.intel.cosbench.model.TaskState.FAILED : com.intel.cosbench.model.TaskState.ACCOMPLISHED);
        response.setReport(Arrays.asList(info.getReport().getAllMetrics()));
        return response;
    }

    private static AbortResponse abortResponse(MissionInfo info) {
        AbortResponse response = new AbortResponse();
        response.setReport(Arrays.asList(info.getReport().getAllMetrics()));
        return response;
    }

    private static String missionId(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try (Scanner scanner = new Scanner(new ByteArrayInputStream(body), StandardCharsets.UTF_8)) {
            return scanner.hasNext() ? scanner.next() : null;
        }
    }

    private static DriverCommandResult badRequest() {
        return new DriverCommandResult(400, "{\"code\":400,\"succ\":false,\"error\":\"unrecognized request\"}");
    }

    private DriverCommandResult json(Object response) {
        try {
            return new DriverCommandResult(200, mapper.writeValueAsString(response));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("cannot serialize Driver response", failure);
        }
    }
}
