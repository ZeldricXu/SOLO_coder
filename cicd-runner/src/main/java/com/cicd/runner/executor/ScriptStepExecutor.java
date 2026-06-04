package com.cicd.runner.executor;

import com.cicd.grpc.PipelineJob;
import com.cicd.grpc.PipelineStep;
import lombok.extern.slf4j.Slf4j;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public class ScriptStepExecutor implements StepExecutor {

    @Override
    public boolean execute(PipelineStep step, PipelineJob job, Map<String, String> env, Consumer<String> logConsumer) throws Exception {
        String script = step.getScript() != null && !step.getScript().isEmpty() ? step.getScript() : step.getRun();
        if (script == null || script.isEmpty()) {
            logConsumer.accept("ERROR: No script or run command provided");
            return false;
        }

        String workingDir = job.getWorkingDir();
        if (workingDir == null || workingDir.isEmpty()) {
            workingDir = System.getProperty("user.dir");
        }

        ProcessBuilder pb = new ProcessBuilder();
        pb.directory(new java.io.File(workingDir));

        List<String> command = new ArrayList<>();
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        if (isWindows) {
            command.add("cmd.exe");
            command.add("/c");
            command.add(script);
        } else {
            command.add("bash");
            command.add("-c");
            command.add(script);
        }
        pb.command(command);

        Map<String, String> processEnv = pb.environment();
        processEnv.putAll(env);

        logConsumer.accept("$ " + script);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        Thread stdoutThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logConsumer.accept(line);
                    output.append(line).append("\n");
                }
            } catch (Exception e) {
                log.error("Error reading stdout", e);
            }
        });

        Thread stderrThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logConsumer.accept(line);
                    output.append(line).append("\n");
                }
            } catch (Exception e) {
                log.error("Error reading stderr", e);
            }
        });

        stdoutThread.start();
        stderrThread.start();

        int timeout = step.getTimeoutSeconds() > 0 ? step.getTimeoutSeconds() : 3600;
        boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            logConsumer.accept("ERROR: Step timed out after " + timeout + " seconds");
            return false;
        }

        stdoutThread.join();
        stderrThread.join();

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            logConsumer.accept("ERROR: Command failed with exit code " + exitCode);
            return step.getContinueOnError();
        }

        logConsumer.accept("Step completed successfully");
        return true;
    }
}
