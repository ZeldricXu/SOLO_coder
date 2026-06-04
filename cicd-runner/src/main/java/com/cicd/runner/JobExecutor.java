package com.cicd.runner;

import com.cicd.grpc.*;
import com.cicd.runner.executor.*;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Slf4j
public class JobExecutor {
    private final GrpcClient grpcClient;
    private final RunnerConfig config;
    private final ExecutorService executorService;
    private final DockerClient dockerClient;
    private final Map<String, StepExecutor> executors;

    public JobExecutor(GrpcClient grpcClient, RunnerConfig config) {
        this.grpcClient = grpcClient;
        this.config = config;
        this.executorService = Executors.newFixedThreadPool(2);

        DockerClientConfig dockerConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        this.dockerClient = DockerClientBuilder.getInstance(dockerConfig).build();

        this.executors = new HashMap<>();
        executors.put("RUN", new ScriptStepExecutor());
        executors.put("SCRIPT", new ScriptStepExecutor());
        executors.put("DOCKER", new DockerStepExecutor(dockerClient));
        executors.put("PUSH", new PushStepExecutor(dockerClient));
        executors.put("KUBECTL", new KubectlStepExecutor());
        executors.put("CALL_WEBHOOK", new WebhookStepExecutor());
    }

    public void executeJob(PipelineJob job, StreamObserver<StepStatus> statusObserver) {
        Future<?> future = executorService.submit(() -> {
            boolean jobSuccess = true;
            StringBuilder jobOutput = new StringBuilder();
            long jobStartTime = System.currentTimeMillis();

            try {
                prepareWorkingDir(job);
                Map<String, String> env = buildEnvironment(job);

                if (job.getGitUrl() != null && !job.getGitUrl().isEmpty()) {
                    cloneRepository(job, env, statusObserver);
                }

                for (PipelineStep step : job.getStepsList()) {
                    long stepStartTime = System.currentTimeMillis();

                    sendStepStatus(statusObserver, job, step, "RUNNING", null, null, false, null);

                    StepExecutor executor = executors.get(step.getType().toUpperCase());
                    if (executor == null) {
                        String errorMsg = "ERROR: Unsupported step type: " + step.getType();
                        sendStepStatus(statusObserver, job, step, "FAILED", errorMsg, -1, false, null);
                        jobSuccess = false;
                        break;
                    }

                    try {
                        boolean stepSuccess = executor.execute(step, job, env,
                                logLine -> sendStepStatus(statusObserver, job, step, "RUNNING", logLine, null, false, null));

                        long stepDuration = (System.currentTimeMillis() - stepStartTime) / 1000;
                        String statusMsg = stepSuccess ? "Step completed in " + stepDuration + "s" : "Step failed in " + stepDuration + "s";

                        sendStepStatus(statusObserver, job, step,
                                stepSuccess ? "SUCCESS" : "FAILED",
                                statusMsg,
                                stepSuccess ? 0 : 1,
                                false,
                                null);

                        jobOutput.append("Step ").append(step.getName()).append(": ")
                                .append(stepSuccess ? "SUCCESS" : "FAILED").append("\n");

                        if (!stepSuccess && !step.getContinueOnError()) {
                            jobSuccess = false;
                            break;
                        }
                    } catch (Exception e) {
                        log.error("Step execution failed", e);
                        sendStepStatus(statusObserver, job, step, "FAILED",
                                "ERROR: " + e.getMessage(), -1, false, null);
                        if (!step.getContinueOnError()) {
                            jobSuccess = false;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Job execution failed", e);
                jobSuccess = false;
                jobOutput.append("Job failed: ").append(e.getMessage());
            } finally {
                long jobDuration = (System.currentTimeMillis() - jobStartTime) / 1000;
                String finalStatus = jobSuccess ? "SUCCESS" : "FAILED";
                jobOutput.append("Job ").append(finalStatus).append(" in ").append(jobDuration).append("s");

                sendStepStatus(statusObserver, job, null, finalStatus, null, jobSuccess ? 0 : 1, true, jobOutput.toString());
            }
        });
    }

    private void prepareWorkingDir(PipelineJob job) throws Exception {
        String workingDir = job.getWorkingDir();
        if (workingDir == null || workingDir.isEmpty()) {
            workingDir = config.getWorkingDir() + "/jobs/" + job.getId();
        }
        File dir = new File(workingDir);
        if (!dir.exists()) {
            Files.createDirectories(dir.toPath());
        }
    }

    private Map<String, String> buildEnvironment(PipelineJob job) {
        Map<String, String> env = new HashMap<>();
        env.putAll(job.getEnvironmentMap());
        env.put("JOB_ID", String.valueOf(job.getId()));
        env.put("PIPELINE_ID", String.valueOf(job.getPipelineId()));
        env.put("EXECUTION_ID", String.valueOf(job.getExecutionId()));
        env.put("PROJECT_ID", String.valueOf(job.getProjectId()));
        if (job.getBranch() != null) env.put("GIT_BRANCH", job.getBranch());
        if (job.getCommitSha() != null) env.put("GIT_COMMIT", job.getCommitSha());
        env.put("WORKING_DIR", job.getWorkingDir() != null ? job.getWorkingDir() : config.getWorkingDir());
        return env;
    }

    private void cloneRepository(PipelineJob job, Map<String, String> env, StreamObserver<StepStatus> statusObserver) throws Exception {
        String workingDir = job.getWorkingDir() != null ? job.getWorkingDir() : config.getWorkingDir() + "/jobs/" + job.getId();
        String branch = job.getBranch() != null ? job.getBranch() : "main";

        StringBuilder cloneScript = new StringBuilder();
        cloneScript.append("cd ").append(workingDir).append(" && ");

        File gitDir = new File(workingDir + "/.git");
        if (gitDir.exists()) {
            cloneScript.append("git fetch origin && ");
            cloneScript.append("git checkout ").append(branch).append(" && ");
            cloneScript.append("git pull origin ").append(branch);
        } else {
            cloneScript.append("git clone --depth 1 -b ").append(branch).append(" ").append(job.getGitUrl()).append(" .");
        }

        if (job.getCommitSha() != null && !job.getCommitSha().isEmpty()) {
            cloneScript.append(" && git checkout ").append(job.getCommitSha());
        }

        ProcessBuilder pb = new ProcessBuilder("bash", "-c", cloneScript.toString());
        pb.environment().putAll(env);
        Process process = pb.start();

        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            sendStepStatus(statusObserver, job, null, "RUNNING", line, null, false, null);
        }
        process.waitFor();

        if (process.exitValue() != 0) {
            throw new RuntimeException("Git clone failed with exit code " + process.exitValue());
        }
    }

    private void sendStepStatus(StreamObserver<StepStatus> observer, PipelineJob job, PipelineStep step,
                                String status, String logLine, Integer exitCode, boolean isJobComplete, String output) {
        StepStatus.Builder builder = StepStatus.newBuilder()
                .setRunnerId(grpcClient.getRunnerId())
                .setJobId(job.getId())
                .setStatus(status)
                .setIsJobComplete(isJobComplete)
                .setSuccess("SUCCESS".equals(status))
                .setTimestamp(System.currentTimeMillis());

        if (step != null) {
            builder.setStepId(step.getId());
        }

        if (logLine != null) {
            builder.setLogLine(logLine);
        }

        if (exitCode != null) {
            builder.setExitCode(exitCode);
        }

        if (output != null) {
            builder.setOutput(output);
        }

        try {
            observer.onNext(builder.build());
        } catch (Exception e) {
            log.warn("Failed to send step status", e);
        }
    }

    public void shutdown() {
        executorService.shutdown();
        try {
            dockerClient.close();
        } catch (Exception e) {
            log.warn("Failed to close docker client", e);
        }
    }
}
