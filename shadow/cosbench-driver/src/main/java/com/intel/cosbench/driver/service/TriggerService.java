package com.intel.cosbench.driver.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
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
            return emptyResponse();
        }
        if (!enable && parts.size() != 3) {
            return emptyResponse();
        }
        if (enable && parts.size() < 2) {
            throw new ConfigException("enable-trigger command requires a script name");
        }

        Path script = resolveScript(parts.get(enable ? 1 : 2));
        if (script == null) {
            return emptyResponse();
        }
        List<String> arguments = enable ? parts.subList(2, parts.size()) : List.of("-k");
        long processId = enable ? 0 : parsePid(parts.get(1));
        ProcessHandle triggerToStop = enable ? null : ownedTriggers.get(processId);
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
            commandProcess.waitFor();
            processes.remove(commandPid);
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

        if (!enable && triggerToStop != null) {
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
                Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                processes.put(process.pid(), process.toHandle());
                return process;
            } catch (IOException error) {
                throw new ConfigException("cannot start trigger script '" + script.getFileName() + "'", error);
            }
        }
    }

    private Path resolveScript(String name) {
        if (name == null || name.isBlank()) {
            throw new ConfigException("trigger script name must not be blank");
        }
        try {
            Path relative = Path.of(name);
            if (relative.isAbsolute()) {
                throw new ConfigException("trigger script must be relative to scripts directory: " + name);
            }
            Path root = scriptsDirectory.toRealPath();
            Path candidate = root.resolve(relative).normalize();
            if (!candidate.startsWith(root) || candidate.equals(root)) {
                throw new ConfigException("trigger script is outside scripts directory: " + name);
            }
            Path script = candidate.toRealPath();
            if (!script.startsWith(root)) {
                throw new ConfigException("trigger script is outside scripts directory: " + name);
            }
            return Files.isRegularFile(script) ? script : null;
        } catch (NoSuchFileException missing) {
            return null;
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
            return Math.max(pid, 0);
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static TriggerResponse emptyResponse() {
        TriggerResponse response = new TriggerResponse();
        response.setPID("0");
        response.setScriptLog("");
        return response;
    }

    private static String readOutput(InputStream input) throws IOException {
        StringBuilder captured = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                captured.append(line).append('\n');
            }
        }
        return captured.toString();
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
