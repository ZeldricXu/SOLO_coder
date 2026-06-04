package com.cicd.server.runner;

import com.cicd.common.enums.PipelineStatus;
import com.cicd.grpc.*;
import com.cicd.server.pipeline.PipelineOrchestrator;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class GrpcRunnerService extends RunnerServiceGrpc.RunnerServiceImplBase {

    private final RunnerManager runnerManager;
    private final PipelineOrchestrator orchestrator;
    private final JobEventStore eventStore;
    private final Map<Long, StreamObserver<JobAssignment>> jobObservers = new ConcurrentHashMap<>();

    @Override
    public void registerRunner(RegisterRunnerRequest request, StreamObserver<RegisterRunnerResponse> responseObserver) {
        try {
            Long runnerId = runnerManager.registerRunner(
                request.getName(),
                request.getToken(),
                request.getHostname(),
                request.getIpAddress(),
                request.getOs(),
                request.getArchitecture(),
                request.getCpuCores(),
                request.getMemoryMb(),
                request.getTags(),
                request.getVersion()
            );

            RegisterRunnerResponse response = RegisterRunnerResponse.newBuilder()
                .setRunnerId(runnerId)
                .setSuccess(true)
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Failed to register runner", e);
            RegisterRunnerResponse response = RegisterRunnerResponse.newBuilder()
                .setSuccess(false)
                .setErrorMessage(e.getMessage())
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void sendHeartbeat(HeartbeatRequest request, StreamObserver<HeartbeatResponse> responseObserver) {
        try {
            runnerManager.heartbeat(request.getRunnerId());
            HeartbeatResponse response = HeartbeatResponse.newBuilder()
                .setSuccess(true)
                .setTimestamp(System.currentTimeMillis())
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Failed to process heartbeat from runner {}", request.getRunnerId(), e);
            HeartbeatResponse response = HeartbeatResponse.newBuilder()
                .setSuccess(false)
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public StreamObserver<StepStatus> streamJobUpdates(StreamObserver<JobAssignment> responseObserver) {
        return new StreamObserver<>() {
            private Long runnerId;

            @Override
            public void onNext(StepStatus status) {
                if (runnerId == null) {
                    runnerId = status.getRunnerId();
                    runnerManager.registerRunnerStream(runnerId, responseObserver);
                    jobObservers.put(runnerId, responseObserver);
                }

                try {
                    if (!status.getJobToken().isEmpty()) {
                        if (!eventStore.validateJobToken(status.getJobId(), status.getJobToken())) {
                            log.warn("Invalid job token for job {}, ignoring step status", status.getJobId());
                            return;
                        }
                    }

                    PipelineStatus stepStatus = switch (status.getStatus()) {
                        case "RUNNING" -> PipelineStatus.RUNNING;
                        case "SUCCESS" -> PipelineStatus.SUCCESS;
                        case "FAILED" -> PipelineStatus.FAILED;
                        default -> PipelineStatus.PENDING;
                    };

                    String eventType = switch (status.getStatus()) {
                        case "RUNNING" -> "STEP_STARTED";
                        case "SUCCESS" -> "STEP_COMPLETED";
                        case "FAILED" -> "STEP_FAILED";
                        default -> "STEP_LOG_CHUNK";
                    };

                    eventStore.appendEvent(
                        status.getJobToken(),
                        status.getJobId(),
                        eventType,
                        status.getStepIndex(),
                        null,
                        status.getStatus(),
                        status.getRunnerId(),
                        status.getLogLine(),
                        status.getLogOffset(),
                        status.hasExitCode() ? status.getExitCode() : null,
                        null
                    );

                    if (status.getIsJobComplete()) {
                        eventStore.appendEvent(
                            status.getJobToken(),
                            status.getJobId(),
                            "JOB_COMPLETED",
                            null,
                            null,
                            status.getSuccess() ? "SUCCESS" : "FAILED",
                            status.getRunnerId(),
                            null,
                            null,
                            status.getSuccess() ? 0 : 1,
                            status.getOutput()
                        );
                    }
                } catch (Exception e) {
                    log.error("Failed to process step status update", e);
                }
            }

            @Override
            public void onError(Throwable t) {
                log.error("Runner stream error for runner {}", runnerId, t);
                if (runnerId != null) {
                    runnerManager.removeRunnerStream(runnerId);
                    jobObservers.remove(runnerId);
                }
            }

            @Override
            public void onCompleted() {
                log.info("Runner stream closed for runner {}", runnerId);
                if (runnerId != null) {
                    runnerManager.removeRunnerStream(runnerId);
                    jobObservers.remove(runnerId);
                }
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public void reportProgress(ReportProgressRequest request, StreamObserver<ReportProgressResponse> responseObserver) {
        try {
            if (request.getJobToken().isEmpty()) {
                ReportProgressResponse response = ReportProgressResponse.newBuilder()
                    .setAccepted(false)
                    .setErrorMessage("Missing job_token")
                    .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                return;
            }

            if (!eventStore.validateJobToken(request.getJobId(), request.getJobToken())) {
                log.warn("Invalid job token for progress report, job {}", request.getJobId());
                ReportProgressResponse response = ReportProgressResponse.newBuilder()
                    .setAccepted(false)
                    .setErrorMessage("Invalid job_token")
                    .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                return;
            }

            String eventType = switch (request.getStepStatus()) {
                case "RUNNING" -> "STEP_STARTED";
                case "SUCCESS" -> "STEP_COMPLETED";
                case "FAILED" -> "STEP_FAILED";
                default -> "STEP_LOG_CHUNK";
            };

            eventStore.appendEvent(
                request.getJobToken(),
                request.getJobId(),
                eventType,
                request.getStepIndex(),
                request.getStepName(),
                request.getStepStatus(),
                null,
                request.getLogIncrement(),
                null,
                request.getExitCode(),
                request.getErrorMessage()
            );

            JobEventStore.JobStateRecovery recovery = eventStore.recoverJobState(request.getJobId());

            ReportProgressResponse.Builder responseBuilder = ReportProgressResponse.newBuilder()
                .setAccepted(true)
                .setServerRecovered(recovery.needsResend())
                .setLastKnownStatus(recovery.jobStatus() != null ? recovery.jobStatus() : "UNKNOWN")
                .setLastKnownStepIndex(recovery.lastCompletedStep());

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Failed to process progress report for job {}", request.getJobId(), e);
            ReportProgressResponse response = ReportProgressResponse.newBuilder()
                .setAccepted(false)
                .setErrorMessage(e.getMessage())
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void queryJobState(QueryJobStateRequest request, StreamObserver<QueryJobStateResponse> responseObserver) {
        try {
            if (request.getJobToken().isEmpty()) {
                QueryJobStateResponse response = QueryJobStateResponse.newBuilder()
                    .setFound(false)
                    .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                return;
            }

            JobEventStore.JobStateRecovery recovery = eventStore.recoverJobState(request.getJobId());

            QueryJobStateResponse response = QueryJobStateResponse.newBuilder()
                .setFound(recovery.found())
                .setJobStatus(recovery.jobStatus() != null ? recovery.jobStatus() : "UNKNOWN")
                .setLastCompletedStep(recovery.lastCompletedStep())
                .setLastStepStatus(recovery.lastStepStatus() != null ? recovery.lastStepStatus() : "UNKNOWN")
                .setLastEventTimestamp(recovery.lastEventTimestamp())
                .setNeedsResend(recovery.needsResend())
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Failed to query job state for job {}", request.getJobId(), e);
            QueryJobStateResponse response = QueryJobStateResponse.newBuilder()
                .setFound(false)
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
