package com.intel.cosbench.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.annotate.JsonSerialize.Inclusion;

import com.intel.cosbench.bench.Metrics;
import com.intel.cosbench.bench.Report;
import com.intel.cosbench.bench.Snapshot;
import com.intel.cosbench.config.ConfigException;
import com.intel.cosbench.config.XmlConfig;
import com.intel.cosbench.model.DriverInfo;
import com.intel.cosbench.model.MissionInfo;
import com.intel.cosbench.model.MissionState;
import com.intel.cosbench.model.TaskState;
import com.intel.cosbench.protocol.AbortResponse;
import com.intel.cosbench.protocol.CloseResponse;
import com.intel.cosbench.protocol.LaunchResponse;
import com.intel.cosbench.protocol.LoginResponse;
import com.intel.cosbench.protocol.PingResponse;
import com.intel.cosbench.protocol.QueryResponse;
import com.intel.cosbench.protocol.Response;
import com.intel.cosbench.protocol.SubmitResponse;
import com.intel.cosbench.service.DriverService;

/** Translates the legacy command protocol to the DriverService application port. */
public final class LegacyDriverCommandPort implements DriverCommandController.DriverCommandPort {
    private final DriverService driver;
    private final ObjectMapper mapper;

    public LegacyDriverCommandPort(DriverService driver) {
        this.driver = driver;
        this.mapper = new ObjectMapper();
        this.mapper.getSerializationConfig().setSerializationInclusion(Inclusion.NON_NULL);
    }

    @Override
    public DriverCommandResult ping(byte[] body) {
        return execute(() -> {
            String time = firstToken(body);
            if (time == null) {
                return badRequest();
            }
            Long.parseLong(time);
            DriverInfo info = driver.getDriverInfo();
            PingResponse response = new PingResponse();
            response.setName(info.getName());
            response.setAddress(info.getUrl());
            response.setTimeStamp(String.valueOf(System.currentTimeMillis()));
            return response;
        });
    }

    @Override
    public DriverCommandResult submit(byte[] body) {
        return execute(() -> {
            String id = driver.submit(new XmlConfig(new ByteArrayInputStream(body)));
            SubmitResponse response = new SubmitResponse();
            response.setId(id);
            return response;
        });
    }

    @Override public DriverCommandResult login(byte[] body) { return mission(body, "login"); }
    @Override public DriverCommandResult launch(byte[] body) { return mission(body, "launch"); }
    @Override public DriverCommandResult close(byte[] body) { return mission(body, "close"); }
    @Override public DriverCommandResult abort(byte[] body) { return mission(body, "abort"); }
    @Override public DriverCommandResult query(byte[] body) { return mission(body, "query"); }

    @Override
    public DriverCommandResult trigger(byte[] body) {
        return execute(() -> {
            String command = firstToken(body);
            return command == null ? badRequest() : driver.trigger(command);
        });
    }

    private DriverCommandResult mission(byte[] body, String command) {
        return execute(() -> {
            String id = firstToken(body);
            if (id == null) {
                return badRequest();
            }
            MissionInfo info = driver.getMissionInfo(id);
            if (info == null) {
                return new Response(404, "mission not found");
            }
            return switch (command) {
                case "login" -> {
                    driver.login(id);
                    yield info.getState() == MissionState.AUTHED
                            ? new LoginResponse() : new Response(false, "fail to login - see log for details");
                }
                case "launch" -> {
                    driver.launch(id);
                    yield info.getState() == MissionState.TERMINATED
                            ? new Response(false, "launch failed") : launchResponse(info);
                }
                case "close" -> {
                    driver.close(id);
                    yield info.getState() == MissionState.TERMINATED
                            ? new Response(false, "close failed") : closeResponse(info);
                }
                case "abort" -> {
                    driver.abort(id);
                    yield abortResponse(info);
                }
                case "query" -> queryResponse(info);
                default -> throw new IllegalArgumentException("unsupported Driver command");
            };
        });
    }

    private DriverCommandResult execute(Supplier<Response> action) {
        Response response;
        try {
            response = action.get();
        } catch (com.intel.cosbench.service.IllegalStateException failure) {
            response = new Response(409, failure.getMessage());
        } catch (ConfigException failure) {
            response = new Response(400, failure.getMessage());
        } catch (RejectedExecutionException failure) {
            response = new Response(503, "Driver is stopping");
        } catch (RuntimeException failure) {
            response = new Response(500, failure.getMessage());
        }
        try {
            return new DriverCommandResult(response.getCode(), mapper.writeValueAsString(response));
        } catch (IOException failure) {
            throw new IllegalStateException("cannot serialize Driver response", failure);
        }
    }

    private static LaunchResponse launchResponse(MissionInfo info) {
        LaunchResponse response = new LaunchResponse();
        response.setInterval(info.getMission().getInterval());
        return response;
    }

    private static QueryResponse queryResponse(MissionInfo info) {
        QueryResponse response = new QueryResponse();
        Snapshot snapshot = info.getSnapshot();
        response.setTime(snapshot.getTimestamp());
        response.setVersion(snapshot.getVersion());
        response.setMinVersion(snapshot.getMinVersion());
        response.setMaxVersion(snapshot.getMaxVersion());
        response.setReport(Arrays.asList(snapshot.getReport().getAllMetrics()));
        response.setRunning(info.getState() == MissionState.LAUNCHED);
        return response;
    }

    private static CloseResponse closeResponse(MissionInfo info) {
        CloseResponse response = new CloseResponse();
        List<Metrics> workerMetrics = new ArrayList<>();
        for (Report report : info.getWorkerReports()) {
            for (Metrics metrics : report) {
                workerMetrics.add(metrics);
            }
        }
        response.setWrReport(workerMetrics);
        response.setReport(Arrays.asList(info.getReport().getAllMetrics()));
        response.setState(info.getState() == MissionState.FAILED ? TaskState.FAILED : TaskState.ACCOMPLISHED);
        response.setDriverLog(driverLog(info));
        response.setErrorStatistics(info.getErrorStatistics().getErrorCodeAndNum());
        return response;
    }

    private static AbortResponse abortResponse(MissionInfo info) {
        AbortResponse response = new AbortResponse();
        response.setReport(Arrays.asList(info.getReport().getAllMetrics()));
        response.setDriverLog(driverLog(info));
        return response;
    }

    private static String driverLog(MissionInfo info) {
        try {
            return info.getLogManager().getLogAsString();
        } catch (IOException failure) {
            return "[N/A]";
        }
    }

    private static String firstToken(byte[] body) {
        try (Scanner scanner = new Scanner(new ByteArrayInputStream(body), StandardCharsets.UTF_8)) {
            return scanner.hasNext() ? scanner.next() : null;
        }
    }

    private static Response badRequest() {
        return new Response(400, "unrecognized request");
    }
}
