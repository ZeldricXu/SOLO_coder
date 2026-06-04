package com.cicd.runner;

import com.cicd.grpc.JobAssignment;
import com.cicd.grpc.StepStatus;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class CICDRunnerApplication {
    private static volatile boolean running = true;
    private static GrpcClient grpcClient;
    private static JobExecutor jobExecutor;
    private static ScheduledExecutorService heartbeatScheduler;
    private static StreamObserver<StepStatus> statusObserver;

    public static void main(String[] args) {
        log.info("Starting CI/CD Runner...");

        RunnerConfig config = RunnerConfig.load();
        log.info("Runner config: name={}, tags={}, server={}:{}",
                config.getName(), config.getTags(), config.getServerHost(), config.getServerPort());

        grpcClient = new GrpcClient(config);

        int maxRetries = 5;
        int retryCount = 0;
        boolean registered = false;

        while (!registered && retryCount < maxRetries) {
            registered = grpcClient.register();
            if (!registered) {
                retryCount++;
                log.warn("Registration failed, retrying in {}s (attempt {}/{})",
                        5 * retryCount, retryCount, maxRetries);
                try {
                    Thread.sleep(5000L * retryCount);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        if (!registered) {
            log.error("Failed to register after {} attempts, exiting", maxRetries);
            System.exit(1);
        }

        jobExecutor = new JobExecutor(grpcClient, config);

        startHeartbeat(config);
        startJobStream();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down runner...");
            running = false;
            if (statusObserver != null) {
                try {
                    statusObserver.onCompleted();
                } catch (Exception e) {
                    // ignore
                }
            }
            if (heartbeatScheduler != null) {
                heartbeatScheduler.shutdown();
            }
            if (jobExecutor != null) {
                jobExecutor.shutdown();
            }
            try {
                if (grpcClient != null) {
                    grpcClient.shutdown();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("Runner shutdown complete");
        }));

        log.info("CI/CD Runner started successfully, waiting for jobs...");
        while (running) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static void startHeartbeat(RunnerConfig config) {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                grpcClient.sendHeartbeat();
            } catch (Exception e) {
                log.warn("Heartbeat failed", e);
            }
        }, config.getHeartbeatInterval(), config.getHeartbeatInterval(), TimeUnit.SECONDS);
        log.info("Heartbeat scheduler started with interval {}s", config.getHeartbeatInterval());
    }

    private static void startJobStream() {
        statusObserver = grpcClient.streamJobUpdates(new StreamObserver<>() {
            @Override
            public void onNext(JobAssignment jobAssignment) {
                log.info("Received job assignment: jobId={}, name={}",
                        jobAssignment.getJob().getId(), jobAssignment.getJob().getName());
                try {
                    jobExecutor.executeJob(jobAssignment.getJob(), statusObserver);
                } catch (Exception e) {
                    log.error("Failed to execute job", e);
                }
            }

            @Override
            public void onError(Throwable t) {
                log.error("Job stream error", t);
                if (running) {
                    log.info("Reconnecting job stream in 5s...");
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (running) {
                        startJobStream();
                    }
                }
            }

            @Override
            public void onCompleted() {
                log.info("Job stream completed");
                if (running) {
                    log.info("Reconnecting job stream in 5s...");
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (running) {
                        startJobStream();
                    }
                }
            }
        });

        StepStatus initialStatus = StepStatus.newBuilder()
                .setRunnerId(grpcClient.getRunnerId())
                .setStatus("IDLE")
                .setTimestamp(System.currentTimeMillis())
                .build();
        statusObserver.onNext(initialStatus);

        log.info("Job stream started");
    }
}
