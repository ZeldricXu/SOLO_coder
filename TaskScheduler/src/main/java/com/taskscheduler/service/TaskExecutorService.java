package com.taskscheduler.service;

import com.taskscheduler.dto.ExecuteResult;
import com.taskscheduler.entity.TaskConfig;
import com.taskscheduler.exception.TaskExecutionTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.*;

@Slf4j
@Service
public class TaskExecutorService {

    private final ExecutorService taskExecutorService = Executors.newCachedThreadPool();

    public ExecuteResult executeTask(TaskConfig taskConfig, String executeId) throws Exception {
        log.info("[{}] Starting task execution: {}", executeId, taskConfig.getExecuteCommand());

        long startTime = System.currentTimeMillis();
        int timeoutSeconds = taskConfig.getTimeoutSeconds();

        Future<String> future = taskExecutorService.submit(() -> runCommand(taskConfig.getExecuteCommand()));

        try {
            String result = timeoutSeconds > 0
                    ? future.get(timeoutSeconds, TimeUnit.SECONDS)
                    : future.get();

            long duration = (System.currentTimeMillis() - startTime) / 1000;
            log.info("[{}] Task executed successfully, duration: {}s", executeId, duration);

            ExecuteResult executeResult = new ExecuteResult();
            executeResult.setExecuteId(executeId);
            executeResult.setTaskId(taskConfig.getTaskId());
            executeResult.setSuccess(true);
            executeResult.setResult(result);
            executeResult.setDurationSeconds(duration);

            return executeResult;

        } catch (TimeoutException e) {
            future.cancel(true);
            log.error("[{}] Task execution timeout after {} seconds", executeId, timeoutSeconds);
            throw new TaskExecutionTimeoutException(taskConfig.getTaskId(), timeoutSeconds);
        } catch (Exception e) {
            log.error("[{}] Task execution error: {}", executeId, e.getMessage());
            throw e;
        }
    }

    private String runCommand(String command) throws Exception {
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        ProcessBuilder builder = new ProcessBuilder();

        if (isWindows) {
            builder.command("cmd.exe", "/c", command);
        } else {
            builder.command("sh", "-c", command);
        }

        builder.redirectErrorStream(true);
        Process process = builder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command execution failed with exit code: " + exitCode + ". Output: " + output.toString());
        }

        return output.toString();
    }
}
