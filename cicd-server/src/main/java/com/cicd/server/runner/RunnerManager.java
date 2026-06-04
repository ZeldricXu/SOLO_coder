package com.cicd.server.runner;

import com.cicd.server.entity.JobExecution;
import com.cicd.server.entity.Runner;
import com.cicd.server.repository.JobExecutionRepository;
import com.cicd.server.repository.RunnerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RunnerManager {

    private final RunnerRepository runnerRepository;
    private final JobExecutionRepository jobExecutionRepository;
    private final StringRedisTemplate redisTemplate;
    private final DistributedJobLock distributedJobLock;
    private final com.cicd.server.metrics.CicdMetrics cicdMetrics;

    private final Map<Long, io.grpc.stub.StreamObserver<?>> runnerStreams = new ConcurrentHashMap<>();
    private final Map<Long, List<Long>> runnerJobQueues = new ConcurrentHashMap<>();
    private final Map<Long, String> jobTokens = new ConcurrentHashMap<>();

    private static final String RUNNER_HEARTBEAT_KEY = "cicd:runner:heartbeat:";
    private static final String JOB_QUEUE_KEY = "cicd:job:queue:";

    public Long registerRunner(String name, String token, String hostname, String ip,
                               String os, String arch, Integer cpu, Integer memory,
                               String tags, String version) {
        Runner runner = runnerRepository.findByRunnerToken(token)
            .orElseThrow(() -> new SecurityException("Invalid runner token"));

        if (!runner.getIsActive() || runner.getIsLocked()) {
            throw new IllegalStateException("Runner is not active or locked");
        }

        runner.setName(name);
        runner.setHostname(hostname);
        runner.setIpAddress(ip);
        runner.setOs(os);
        runner.setArchitecture(arch);
        runner.setCpuCores(cpu);
        runner.setMemoryMb(memory);
        runner.setTags(tags);
        runner.setVersion(version);
        runner.setStatus("ONLINE");
        runner.setConnectedAt(LocalDateTime.now());
        runner.setLastHeartbeatAt(LocalDateTime.now());

        runner = runnerRepository.save(runner);
        log.info("Runner registered: {} (ID: {})", name, runner.getId());

        return runner.getId();
    }

    public void heartbeat(Long runnerId) {
        Runner runner = runnerRepository.findById(runnerId).orElse(null);
        if (runner != null) {
            runner.setLastHeartbeatAt(LocalDateTime.now());
            runnerRepository.save(runner);
            redisTemplate.opsForValue().set(RUNNER_HEARTBEAT_KEY + runnerId,
                String.valueOf(System.currentTimeMillis()), 5, TimeUnit.MINUTES);
        }
    }

    public Long assignJob(Long jobId, String[] tags, Map<String, String> params) {
        if (!distributedJobLock.tryLock(jobId)) {
            log.warn("Another server instance is assigning job {}, skipping", jobId);
            return null;
        }

        try {
            List<Runner> availableRunners = findAvailableRunners(tags);

            if (availableRunners.isEmpty()) {
                log.warn("No available runners for job {}, tags: {}", jobId, Arrays.toString(tags));
                return null;
            }

            Runner bestRunner = selectBestRunner(availableRunners);

            try {
                String jobToken = generateJobToken(jobId);
                jobTokens.put(jobId, jobToken);

                redisTemplate.opsForList().leftPush(JOB_QUEUE_KEY + bestRunner.getId(),
                    jobId + ":" + serializeParams(params));

                runnerJobQueues.computeIfAbsent(bestRunner.getId(), k -> new ArrayList<>()).add(jobId);

                if (runnerStreams.containsKey(bestRunner.getId())) {
                    notifyRunnerNewJob(bestRunner.getId(), jobId);
                }

                log.info("Assigned job {} to runner {} with token {}", jobId, bestRunner.getId(), jobToken);
                return bestRunner.getId();
            } catch (Exception e) {
                log.error("Failed to assign job {} to runner {}", jobId, bestRunner.getId(), e);
                return null;
            }
        } finally {
            distributedJobLock.unlock(jobId);
        }
    }

    private List<Runner> findAvailableRunners(String[] tags) {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(2);
        List<Runner> runners = runnerRepository.findAvailableRunners(threshold);

        if (tags == null || tags.length == 0) {
            return runners;
        }

        return runners.stream()
            .filter(runner -> {
                if (runner.getTags() == null) return false;
                Set<String> runnerTags = new HashSet<>(Arrays.asList(runner.getTags().split(",")));
                for (String tag : tags) {
                    if (!runnerTags.contains(tag)) return false;
                }
                return true;
            })
            .toList();
    }

    private Runner selectBestRunner(List<Runner> runners) {
        return runners.stream()
            .min(Comparator.comparingInt(r -> {
                List<Long> queue = runnerJobQueues.getOrDefault(r.getId(), Collections.emptyList());
                return queue.size();
            }))
            .orElse(null);
    }

    private void notifyRunnerNewJob(Long runnerId, Long jobId) {
        JobExecution job = jobExecutionRepository.findById(jobId).orElse(null);
        if (job == null) return;

        io.grpc.stub.StreamObserver<?> stream = runnerStreams.get(runnerId);
        if (stream != null) {
            try {
                @SuppressWarnings("unchecked")
                io.grpc.stub.StreamObserver<com.cicd.grpc.JobAssignment> observer =
                    (io.grpc.stub.StreamObserver<com.cicd.grpc.JobAssignment>) stream;

                String jobToken = jobTokens.getOrDefault(jobId, "");

                com.cicd.grpc.JobAssignment assignment = com.cicd.grpc.JobAssignment.newBuilder()
                    .setRunnerId(runnerId)
                    .setTimestamp(System.currentTimeMillis())
                    .setJobToken(jobToken)
                    .build();

                observer.onNext(assignment);
            } catch (Exception e) {
                log.error("Failed to notify runner {} of new job {}", runnerId, jobId, e);
            }
        }
    }

    public void releaseJob(Long jobId, Long runnerId) {
        jobTokens.remove(jobId);
        if (runnerId != null) {
            redisTemplate.opsForList().remove(JOB_QUEUE_KEY + runnerId, 0, String.valueOf(jobId));
            List<Long> queue = runnerJobQueues.get(runnerId);
            if (queue != null) {
                queue.remove(jobId);
            }
        }
    }

    public void cancelJob(Long jobId, Long runnerId) {
        if (runnerId != null && runnerStreams.containsKey(runnerId)) {
            try {
                @SuppressWarnings("unchecked")
                io.grpc.stub.StreamObserver<com.cicd.grpc.JobAssignment> observer =
                    (io.grpc.stub.StreamObserver<com.cicd.grpc.JobAssignment>) runnerStreams.get(runnerId);

                com.cicd.grpc.JobAssignment cancel = com.cicd.grpc.JobAssignment.newBuilder()
                    .setRunnerId(runnerId)
                    .setTimestamp(System.currentTimeMillis())
                    .build();

                observer.onNext(cancel);
            } catch (Exception e) {
                log.error("Failed to send cancel command for job {}", jobId, e);
            }
        }
    }

    public void registerRunnerStream(Long runnerId, io.grpc.stub.StreamObserver<?> stream) {
        runnerStreams.put(runnerId, stream);
        log.info("Runner {} stream registered", runnerId);

        redisTemplate.opsForList().range(JOB_QUEUE_KEY + runnerId, 0, -1).forEach(jobStr -> {
            try {
                Long jobId = Long.parseLong(jobStr.split(":")[0]);
                notifyRunnerNewJob(runnerId, jobId);
            } catch (Exception e) {
                log.error("Failed to replay queued job", e);
            }
        });
    }

    public void removeRunnerStream(Long runnerId) {
        runnerStreams.remove(runnerId);
        log.info("Runner {} stream removed", runnerId);
    }

    @Scheduled(fixedRate = 60000)
    public void checkStaleRunners() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
        List<Runner> staleRunners = runnerRepository.findStaleRunners(threshold);

        for (Runner runner : staleRunners) {
            log.warn("Runner {} is stale, marking as OFFLINE", runner.getId());
            runner.setStatus("OFFLINE");
            runnerRepository.save(runner);

            List<Long> queue = runnerJobQueues.get(runner.getId());
            if (queue != null && !queue.isEmpty()) {
                for (Long jobId : queue) {
                    log.warn("Re-queuing job {} from stale runner {}", jobId, runner.getId());
                    JobExecution job = jobExecutionRepository.findById(jobId).orElse(null);
                    if (job != null) {
                        String[] tags = job.getRunnerTags() != null ?
                            job.getRunnerTags().split(",") : new String[0];
                        assignJob(jobId, tags, new HashMap<>());
                    }
                }
            }

            runnerJobQueues.remove(runner.getId());
            runnerStreams.remove(runner.getId());
        }

        updateRunnerMetrics();
    }

    private void updateRunnerMetrics() {
        List<Runner> allRunners = runnerRepository.findAll();
        long activeCount = allRunners.stream().filter(r -> "ONLINE".equals(r.getStatus())).count();
        cicdMetrics.setTotalRunners(allRunners.size());
        cicdMetrics.setActiveRunners((int) activeCount);

        int totalQueued = runnerJobQueues.values().stream()
            .mapToInt(List::size)
            .sum();
        cicdMetrics.setQueuedJobs(totalQueued);
        cicdMetrics.setRunningJobs(runnerStreams.size());
    }

    public List<Runner> getAllRunners() {
        return runnerRepository.findAll();
    }

    public Runner getRunner(Long id) {
        return runnerRepository.findById(id).orElse(null);
    }

    public Runner createRunner(Runner runner) {
        runner.setRunnerToken(generateToken());
        runner.setStatus("OFFLINE");
        return runnerRepository.save(runner);
    }

    public void deleteRunner(Long id) {
        runnerRepository.deleteById(id);
        runnerStreams.remove(id);
        runnerJobQueues.remove(id);
    }

    private String generateToken() {
        return "RUNNER-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private String generateJobToken(Long jobId) {
        return UUID.randomUUID().toString().replace("-", "") + Long.toHexString(jobId);
    }

    private String serializeParams(Map<String, String> params) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(params);
        } catch (Exception e) {
            return "{}";
        }
    }
}
