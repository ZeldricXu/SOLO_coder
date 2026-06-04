package com.cicd.runner.executor;

import com.cicd.grpc.KubectlConfig;
import com.cicd.grpc.PipelineJob;
import com.cicd.grpc.PipelineStep;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.util.Config;
import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
public class KubectlStepExecutor implements StepExecutor {

    @Override
    public boolean execute(PipelineStep step, PipelineJob job, Map<String, String> env, Consumer<String> logConsumer) throws Exception {
        KubectlConfig kubectlConfig = step.getKubectl();
        if (kubectlConfig == null) {
            logConsumer.accept("ERROR: No kubectl config provided");
            return false;
        }

        List<String> commands = kubectlConfig.getCommandsList();
        if (commands.isEmpty()) {
            logConsumer.accept("ERROR: No kubectl commands provided");
            return false;
        }

        String namespace = kubectlConfig.getNamespace();
        String kubeconfigContent = kubectlConfig.getKubeconfig();

        File tempKubeconfig = null;
        if (kubeconfigContent != null && !kubeconfigContent.isEmpty()) {
            tempKubeconfig = File.createTempFile("kubeconfig-", ".yaml");
            Files.writeString(tempKubeconfig.toPath(), kubeconfigContent);
            env.put("KUBECONFIG", tempKubeconfig.getAbsolutePath());
        }

        try {
            for (String command : commands) {
                String fullCommand = "kubectl";
                if (namespace != null && !namespace.isEmpty()) {
                    fullCommand += " -n " + namespace;
                }
                fullCommand += " " + command;

                logConsumer.accept("$ " + fullCommand);

                ProcessBuilder pb = new ProcessBuilder();
                pb.command("bash", "-c", fullCommand);
                pb.environment().putAll(env);

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

                int timeout = step.getTimeoutSeconds() > 0 ? step.getTimeoutSeconds() : 600;
                boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);

                if (!finished) {
                    process.destroyForcibly();
                    logConsumer.accept("ERROR: kubectl command timed out after " + timeout + " seconds");
                    return false;
                }

                stdoutThread.join();
                stderrThread.join();

                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    logConsumer.accept("ERROR: kubectl command failed with exit code " + exitCode);
                    return step.getContinueOnError();
                }
            }

            logConsumer.accept("kubectl steps completed successfully");
            return true;
        } finally {
            if (tempKubeconfig != null) {
                tempKubeconfig.delete();
            }
        }
    }
}
