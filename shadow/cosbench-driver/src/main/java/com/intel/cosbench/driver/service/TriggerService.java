package com.intel.cosbench.driver.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.intel.cosbench.config.ConfigException;
import com.intel.cosbench.protocol.TriggerResponse;

/** Executes legacy Driver trigger commands against scripts confined to one directory. */
final class TriggerService implements AutoCloseable {
    private static final int MAX_LOG_BYTES = 64 * 1024;

    private final Path scriptsDirectory;
    private final Map<Long, ProcessHandle> processes = new ConcurrentHashMap<>();
    private final Map<Long, ProcessHandle> ownedTriggers = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private volatile boolean closed;

    TriggerService(Path scriptsDirectory) {
        this.scriptsDirectory = scriptsDirectory.toAbsolutePath().normalize();
    }

    TriggerResponse execute(String command) {
        List<String> parts = parse(command);
        String action = parts.get(0);
        boolean enable = "enableTrigger".equals(action);
        if (!enable && !"killTrigger".equals(action)) {
            throw new ConfigException("unsupported trigger command: " + action);
        }
        if (!enable && parts.size() != 3) {
            throw new ConfigException("kill-trigger command requires a PID and script name");
        }
        if (enable && parts.size() < 2) {
            throw new ConfigException("enable-trigger command requires a script name");
        }

        Path script = resolveScript(parts.get(enable ? 1 : 2));
        List<String> arguments = enable ? parts.subList(2, parts.size()) : List.of("-k");
        long processId = enable ? 0 : parsePid(parts.get(1));
        ProcessHandle triggerToStop = enable ? null : ownedTriggers.get(processId);
        if (!enable && triggerToStop == null) {
            throw new ConfigException("trigger PID " + processId + " is not owned by this Driver");
        }
        Process commandProcess = start(script, arguments);
        long commandPid = commandProcess.pid();
        if (enable) {
            synchronized (lifecycleLock) {
                if (closed) {
                    terminate(commandProcess.toHandle());
                    throw new java.util.concurrent.RejectedExecutionException("Driver trigger service is stopping");
                }
                ownedTriggers.put(commandPid, commandProcess.toHandle());
            }
        }

        String output;
        try {
            output = readOutput(commandProcess.getInputStream());
            int exit = commandProcess.waitFor();
            processes.remove(commandPid);
            if (exit != 0) {
                throw new ConfigException("trigger script '" + script.getFileName() + "' exited with status " + exit
                        + (output.isBlank() ? "" : ": " + output.strip()));
            }
        } catch (InterruptedException error) {
            terminate(commandProcess.toHandle());
            Thread.currentThread().interrupt();
            throw new IllegalStateException("trigger execution interrupted", error);
        } catch (IOException error) {
            terminate(commandProcess.toHandle());
            throw new ConfigException("cannot read trigger output for '" + script.getFileName() + "'", error);
        } finally {
            if (!commandProcess.isAlive()) {
                processes.remove(commandPid);
            }
        }

        if (!enable) {
            ownedTriggers.remove(processId, triggerToStop);
            terminate(triggerToStop);
        }

        TriggerResponse response = new TriggerResponse();
        response.setPID(enable ? Long.toString(commandPid) : "0");
        response.setScriptLog(output);
        return response;
    }

    private Process start(Path script, List<String> arguments) {
        synchronized (lifecycleLock) {
            if (closed) {
                throw new java.util.concurrent.RejectedExecutionException("Driver trigger service is stopping");
            }
            List<String> command = new ArrayList<>(arguments.size() + 2);
            command.add("/bin/sh");
            command.add(script.toString());
            command.addAll(arguments);
            try {
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                processes.put(process.pid(), process.toHandle());
                return process;
            } catch (IOException error) {
                throw new ConfigException("cannot start trigger script '" + script.getFileName() + "'", error);
            }
        }
    }

    private Path resolveScript(String name) {
        if (name == null || !name.matches("[A-Za-z0-9._-]+\\.sh")) {
            throw new ConfigException("trigger script name must be a simple .sh filename");
        }
        try {
            Path root = scriptsDirectory.toRealPath();
            Path script = root.resolve(name).toRealPath();
            if (!script.startsWith(root) || !Files.isRegularFile(script)) {
                throw new ConfigException("trigger script does not exist within scripts directory: " + name);
            }
            return script;
        } catch (InvalidPathException | IOException error) {
            throw new ConfigException("cannot resolve trigger script within scripts directory: " + name, error);
        }
    }

    private static List<String> parse(String command) {
        if (command == null || command.isBlank()) {
            throw new ConfigException("trigger command is empty");
        }
        return List.of(command.trim().split(",", -1));
    }

    private static long parsePid(String value) {
        try {
            long pid = Long.parseLong(value);
            if (pid <= 0) {
                throw new NumberFormatException("PID must be positive");
            }
            return pid;
        } catch (NumberFormatException error) {
            throw new ConfigException("invalid trigger PID: " + value, error);
        }
    }

    private static String readOutput(InputStream input) throws IOException {
        try (input; ByteArrayOutputStream captured = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                int remaining = MAX_LOG_BYTES - captured.size();
                if (remaining > 0) {
                    captured.write(buffer, 0, Math.min(remaining, count));
                }
            }
            return captured.toString(StandardCharsets.UTF_8);
        }
    }

    private static void terminate(ProcessHandle process) {
        process.descendants().sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .forEach(TriggerService::terminateOne);
        terminateOne(process);
    }

    private static void terminateOne(ProcessHandle process) {
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    @Override
    public void close() {
        List<ProcessHandle> owned;
        synchronized (lifecycleLock) {
            closed = true;
            ArrayList<ProcessHandle> handles = new ArrayList<>(processes.values());
            handles.addAll(ownedTriggers.values());
            owned = List.copyOf(handles);
            processes.clear();
            ownedTriggers.clear();
        }
        owned.forEach(TriggerService::terminate);
    }
}
