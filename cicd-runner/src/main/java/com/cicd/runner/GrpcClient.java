package com.cicd.runner;

import com.cicd.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class GrpcClient {
    private final ManagedChannel channel;
    private final RunnerServiceGrpc.RunnerServiceBlockingStub blockingStub;
    private final RunnerServiceGrpc.RunnerServiceStub asyncStub;
    private final RunnerConfig config;
    private Long runnerId;

    private final AtomicReference<String> currentJobToken = new AtomicReference<>();

    public GrpcClient(RunnerConfig config) {
        this.config = config;
        this.channel = ManagedChannelBuilder.forAddress(config.getServerHost(), config.getServerPort())
                .usePlaintext()
                .build();
        this.blockingStub = RunnerServiceGrpc.newBlockingStub(channel);
        this.asyncStub = RunnerServiceGrpc.newStub(channel);
    }

    public boolean register() {
        try {
            RegisterRunnerRequest request = RegisterRunnerRequest.newBuilder()
                    .setName(config.getName())
                    .setToken(config.getToken())
                    .setHostname(config.getHostname())
                    .setIpAddress(config.getIpAddress())
                    .setOs(config.getOs())
                    .setArchitecture(config.getArchitecture())
                    .setCpuCores(config.getCpuCores())
                    .setMemoryMb(config.getMemoryMb())
                    .addAllTags(config.getTags())
                    .setVersion(config.getVersion())
                    .build();

            RegisterRunnerResponse response = blockingStub.registerRunner(request);
            if (response.getSuccess()) {
                this.runnerId = response.getRunnerId();
                log.info("Successfully registered with server, runnerId: {}", runnerId);
                return true;
            } else {
                log.error("Failed to register: {}", response.getErrorMessage());
                return false;
            }
        } catch (Exception e) {
            log.error("Registration failed", e);
            return false;
        }
    }

    public void sendHeartbeat() {
        if (runnerId == null) {
            return;
        }
        try {
            HeartbeatRequest request = HeartbeatRequest.newBuilder()
                    .setRunnerId(runnerId)
                    .build();
            blockingStub.sendHeartbeat(request);
        } catch (Exception e) {
            log.warn("Failed to send heartbeat", e);
        }
    }

    public StreamObserver<StepStatus> streamJobUpdates(StreamObserver<JobAssignment> responseObserver) {
        return asyncStub.streamJobUpdates(responseObserver);
    }

    public boolean reportProgress(long jobId, String jobToken, int stepIndex, String stepName,
                                   String stepStatus, String logIncrement, int exitCode, String errorMessage) {
        try {
            ReportProgressRequest request = ReportProgressRequest.newBuilder()
                    .setJobToken(jobToken)
                    .setJobId(jobId)
                    .setStepIndex(stepIndex)
                    .setStepName(stepName != null ? stepName : "")
                    .setStepStatus(stepStatus)
                    .setLogIncrement(logIncrement != null ? logIncrement : "")
                    .setTimestamp(System.currentTimeMillis())
                    .setExitCode(exitCode)
                    .setErrorMessage(errorMessage != null ? errorMessage : "")
                    .build();

            ReportProgressResponse response = blockingStub.reportProgress(request);

            if (response.getAccepted()) {
                if (response.getServerRecovered()) {
                    log.warn("Server recovered from restart, last known status: {}, step: {}",
                            response.getLastKnownStatus(), response.getLastKnownStepIndex());
                }
                return true;
            } else {
                log.error("Progress report rejected: {}", response.getErrorMessage());
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to report progress for job {}", jobId, e);
            return false;
        }
    }

    public QueryJobStateResponse queryJobState(long jobId, String jobToken) {
        try {
            QueryJobStateRequest request = QueryJobStateRequest.newBuilder()
                    .setJobToken(jobToken)
                    .setJobId(jobId)
                    .build();

            return blockingStub.queryJobState(request);
        } catch (Exception e) {
            log.error("Failed to query job state for job {}", jobId, e);
            return QueryJobStateResponse.newBuilder()
                    .setFound(false)
                    .build();
        }
    }

    public boolean checkAndRecoverJob(long jobId, String jobToken) {
        QueryJobStateResponse state = queryJobState(jobId, jobToken);

        if (!state.getFound()) {
            log.warn("Server has no record of job {}, server may have restarted and lost state", jobId);
            return false;
        }

        if (state.getNeedsResend()) {
            log.info("Server indicates job {} needs resending from step {}", jobId, state.getLastCompletedStep());
            return true;
        }

        if ("SUCCESS".equals(state.getJobStatus()) || "FAILED".equals(state.getJobStatus())) {
            log.info("Server already knows job {} as {}, no action needed", jobId, state.getJobStatus());
            return false;
        }

        return true;
    }

    public void setCurrentJobToken(String token) {
        currentJobToken.set(token);
    }

    public String getCurrentJobToken() {
        return currentJobToken.get();
    }

    public Long getRunnerId() {
        return runnerId;
    }

    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
}
