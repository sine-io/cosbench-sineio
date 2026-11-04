/*
Copyright 2019 OpenIO Corporation, All Rights Reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
*/

package com.intel.cosbench.controller.web;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;

import com.intel.cosbench.bench.Metrics;
import com.intel.cosbench.bench.Report;
import com.intel.cosbench.model.WorkloadInfo;
import com.intel.cosbench.model.WorkloadState;

public class PrometheusController extends IndexPageController {

    private static final View PROMETHEUS = new PrometheusView();

    private static final class PrometheusView implements View {
        private static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

        @Override
        public String getContentType() {
            return CONTENT_TYPE;
        }

        @Override
        public void render(Map<String, ?> model, HttpServletRequest request, HttpServletResponse response)
                throws Exception {
            var controller = (com.intel.cosbench.service.ControllerService) model.get("controller");
            String includeActive = System.getProperty("prometheus.export.workloads.active");
            String includeHistory = System.getProperty("prometheus.export.workloads.history");
            String includeArchived = System.getProperty("prometheus.export.workloads.archived");
            int stopExporting = parseStopExportingMinutes();

            List<WorkloadInfo> workloads = new ArrayList<>();
            if (enabledByDefault(includeActive)) {
                workloads.addAll(List.of(controller.getActiveWorkloads()));
            }
            if (enabledByDefault(includeHistory)) {
                workloads.addAll(List.of(controller.getHistoryWorkloads()));
            }
            if ("true".equalsIgnoreCase(includeArchived)) {
                workloads.addAll(List.of(controller.getArchivedWorkloads()));
            }

            response.setContentType(CONTENT_TYPE);
            PrintWriter writer = response.getWriter();
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("instance", InetAddress.getLocalHost().getHostName());
            for (WorkloadInfo workload : workloads) {
                if (isExpired(workload, stopExporting)) {
                    continue;
                }
                labels.put("workload", workload.getId() + " (" + workload.getWorkload().getName() + ")");
                printMetric(writer, 0, workloadStateValue(workload.getState()), "workload_state", labels);
                if (workload.getState() == WorkloadState.QUEUING) {
                    continue;
                }

                Report report = workload.getState() == WorkloadState.PROCESSING
                        ? workload.getSnapshot().getReport()
                        : workload.getReport();
                printMetrics(report, labels, writer);
            }
            writer.print('\n');
        }

        private static boolean enabledByDefault(String setting) {
            return setting == null || "true".equalsIgnoreCase(setting);
        }

        private static int parseStopExportingMinutes() {
            try {
                return Integer.parseInt(System.getProperty("prometheus.export.timeout"));
            } catch (Exception ignored) {
                return 5;
            }
        }

        private static boolean isExpired(WorkloadInfo workload, int stopExportingMinutes) {
            if (stopExportingMinutes <= 0 || !WorkloadState.isStopped(workload.getState())) {
                return false;
            }
            Date stopDate = workload.getStopDate();
            return stopDate != null && System.currentTimeMillis() >
                    stopDate.getTime() + (60_000L * stopExportingMinutes);
        }

        private static float workloadStateValue(WorkloadState state) {
            return switch (state) {
                case QUEUING -> 0;
                case PROCESSING -> 1;
                case FINISHED -> 2;
                case FAILED -> 3;
                case TERMINATED -> 4;
                case CANCELLED -> 5;
                default -> -1;
            };
        }

        private static void printMetrics(Report report, Map<String, String> workloadLabels, PrintWriter writer) {
            Map<String, String> labels = new LinkedHashMap<>(workloadLabels);
            for (Metrics metrics : report.getAllMetrics()) {
                String operationName = metrics.getOpName();
                if (metrics.getOpId() != null && !metrics.getOpId().isEmpty()) {
                    operationName = metrics.getOpId() + ":" + operationName;
                }
                labels.put("operation_name", operationName);
                if (metrics.getOpType() != null && !metrics.getOpType().isEmpty()) {
                    labels.put("operation_type", metrics.getOpType());
                }

                labels.put("unit", "operation");
                printMetric(writer, 0, (float) metrics.getSampleCount(), "workload_ops_count", labels);
                labels.put("unit", "byte");
                printMetric(writer, 0, (float) metrics.getByteCount(), "workload_byte_count", labels);
                labels.put("unit", "ms");
                printMetric(writer, 0, (float) metrics.getAvgResTime(), "workload_average_response_time", labels);
                printMetric(writer, 0, (float) metrics.getAvgXferTime(), "workload_average_transfer_time", labels);
                printMetric(writer, 0,
                        (float) metrics.getAvgResTime() - (long) metrics.getAvgXferTime(),
                        "workload_average_process_time", labels);
                labels.put("unit", "op/s");
                printMetric(writer, 0, (float) metrics.getThroughput(), "workload_throughput", labels);
                labels.put("unit", "byte/s");
                printMetric(writer, 0, (float) metrics.getBandwidth(), "workload_bandwidth", labels);
                labels.put("unit", "%");
                printMetric(writer, 0, (float) (metrics.getRatio() * 100), "workload_success_ratio", labels);
            }
        }

        private static void printMetric(PrintWriter writer, long timestamp, float value, String name,
                Map<String, String> labels) {
            writer.print("cosbench_" + name);
            if (labels != null && !labels.isEmpty()) {
                writer.print(' ');
                writer.print('{');
                boolean first = true;
                for (Map.Entry<String, String> label : labels.entrySet()) {
                    if (!first) {
                        writer.print(',');
                    }
                    writer.print(label.getKey());
                    writer.print("=\"");
                    writer.print(escapeLabel(label.getValue()));
                    writer.print('"');
                    first = false;
                }
                writer.print('}');
            }
            writer.print(' ');
            if (value == (long) value) {
                writer.print(Long.toString((long) value));
            } else {
                writer.print(Float.toString(value));
            }
            if (timestamp > 0) {
                writer.print(' ');
                writer.print(timestamp);
            }
            writer.print('\n');
        }

        private static String escapeLabel(String value) {
            return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
        }
    }

    @Override
    protected ModelAndView process(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ModelAndView result = new ModelAndView(PROMETHEUS);
        result.addObject("controller", controller);
        return result;
    }
}
