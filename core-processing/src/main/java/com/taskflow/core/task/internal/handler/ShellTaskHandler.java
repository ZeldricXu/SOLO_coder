package com.taskflow.core.task.internal.handler;

import com.taskflow.common.utils.AssertUtils;
import com.taskflow.core.task.api.TaskHandler;
import com.taskflow.core.task.domain.ExecutionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Shell命令任务处理器
 * 内部实现，不对外暴露
 */
@Slf4j
@Component
public class ShellTaskHandler implements TaskHandler {

    @Override
    public String getType() {
        return "shell";
    }

    @Override
    public boolean validate(Map<String, Object> parameters) {
        AssertUtils.notBlank((String) parameters.get("command"), "命令不能为空");
        return true;
    }

    @Override
    public Object handle(Map<String, Object> parameters, ExecutionContext context) throws Exception {
        String command = (String) parameters.get("command");
        long timeout = ((Number) parameters.getOrDefault("timeout", 30)).longValue();
        String workingDir = (String) parameters.get("workingDir");

        log.info("Executing shell command: {}", command);

        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        if (workingDir != null) {
            pb.directory(new java.io.File(workingDir));
        }
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean completed = process.waitFor(timeout, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new RuntimeException("Command execution timeout");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed with exit code " + exitCode + ", output: " + output);
        }

        return Map.of("output", output.toString(), "exitCode", exitCode);
    }
}
