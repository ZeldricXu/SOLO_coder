package com.solocoder.infrastructure.adapter.prompt;

import com.solocoder.domain.port.PromptExperimentPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
public class InMemoryPromptExperimentAdapter implements PromptExperimentPort {

    private final Map<String, List<PromptVersion>> promptVersions = new ConcurrentHashMap<>();
    private final Map<String, String> defaultVersions = new ConcurrentHashMap<>();
    private final Map<String, AbExperiment> experiments = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<Map<String, Object>>>> experimentResults = new ConcurrentHashMap<>();
    private final AtomicInteger versionCounter = new AtomicInteger(0);

    @Override
    public Mono<String> createPromptVersion(String promptName, String content,
                                             Map<String, Object> variables,
                                             String createdBy, String description) {
        return Mono.fromCallable(() -> {
            int versionNum = versionCounter.incrementAndGet();
            String version = "v" + versionNum;

            PromptVersion promptVersion = new PromptVersion(
                    version, content, variables, createdBy, description, Instant.now()
            );

            promptVersions.computeIfAbsent(promptName, k -> new ArrayList<>()).add(promptVersion);

            if (!defaultVersions.containsKey(promptName)) {
                defaultVersions.put(promptName, version);
            }

            return version;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<String> getPromptContent(String promptName, String version) {
        return Mono.fromCallable(() -> {
            List<PromptVersion> versions = promptVersions.get(promptName);
            if (versions == null) {
                return null;
            }

            String targetVersion = version != null ? version : defaultVersions.get(promptName);
            if (targetVersion == null) {
                return null;
            }

            return versions.stream()
                    .filter(v -> v.version.equals(targetVersion))
                    .map(v -> v.content)
                    .findFirst()
                    .orElse(null);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Flux<Map<String, Object>> listPromptVersions(String promptName) {
        return Mono.fromCallable(() -> {
            List<PromptVersion> versions = promptVersions.getOrDefault(promptName, Collections.emptyList());
            List<Map<String, Object>> result = new ArrayList<>();

            for (int i = versions.size() - 1; i >= 0; i--) {
                PromptVersion pv = versions.get(i);
                Map<String, Object> versionInfo = new HashMap<>();
                versionInfo.put("version", pv.version);
                versionInfo.put("description", pv.description);
                versionInfo.put("createdBy", pv.createdBy);
                versionInfo.put("createdAt", pv.createdAt);
                versionInfo.put("isDefault", pv.version.equals(defaultVersions.get(promptName)));
                versionInfo.put("variableCount", pv.variables != null ? pv.variables.size() : 0);
                result.add(versionInfo);
            }

            return result;
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<String> createAbExperiment(String experimentName, String promptName,
                                            List<String> versions,
                                            List<Double> trafficSplit,
                                            Instant startTime, Instant endTime) {
        return Mono.fromCallable(() -> {
            String experimentId = "exp_" + UUID.randomUUID().toString().replace("-", "");

            AbExperiment experiment = new AbExperiment(
                    experimentId, experimentName, promptName, versions,
                    trafficSplit, startTime, endTime, "running"
            );

            experiments.put(experimentId, experiment);
            experimentResults.put(experimentId, new ConcurrentHashMap<>());

            return experimentId;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> recordExperimentResult(String experimentId, String version,
                                              String requestId,
                                              Map<String, Object> metrics) {
        return Mono.fromRunnable(() -> {
            Map<String, List<Map<String, Object>>> versionResults =
                    experimentResults.computeIfAbsent(experimentId, k -> new ConcurrentHashMap<>());
            versionResults.computeIfAbsent(version, k -> new ArrayList<>()).add(metrics);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Map<String, Object>> getExperimentResults(String experimentId) {
        return Mono.fromCallable(() -> {
            AbExperiment experiment = experiments.get(experimentId);
            if (experiment == null) {
                return null;
            }

            Map<String, List<Map<String, Object>>> versionResults =
                    experimentResults.getOrDefault(experimentId, Collections.emptyMap());

            Map<String, Object> result = new HashMap<>();
            result.put("experimentId", experimentId);
            result.put("experimentName", experiment.name);
            result.put("promptName", experiment.promptName);
            result.put("status", experiment.status);
            result.put("startTime", experiment.startTime);
            result.put("endTime", experiment.endTime);

            Map<String, Map<String, Object>> versionMetrics = new HashMap<>();
            for (int i = 0; i < experiment.versions.size(); i++) {
                String version = experiment.versions.get(i);
                double trafficSplit = experiment.trafficSplit.get(i);

                List<Map<String, Object>> metricsList =
                        versionResults.getOrDefault(version, Collections.emptyList());

                Map<String, Object> versionResult = new HashMap<>();
                versionResult.put("trafficSplit", trafficSplit);
                versionResult.put("requestCount", metricsList.size());

                Map<String, Double> avgMetrics = new HashMap<>();
                for (Map<String, Object> metrics : metricsList) {
                    for (Map.Entry<String, Object> entry : metrics.entrySet()) {
                        if (entry.getValue() instanceof Number) {
                            avgMetrics.merge(entry.getKey(), ((Number) entry.getValue()).doubleValue(), Double::sum);
                        }
                    }
                }
                avgMetrics.replaceAll((k, v) -> v / Math.max(metricsList.size(), 1));
                versionResult.put("avgMetrics", avgMetrics);

                versionMetrics.put(version, versionResult);
            }

            result.put("versionResults", versionMetrics);
            result.put("totalRequests", versionResults.values().stream()
                    .mapToInt(list -> list.size())
                    .sum());

            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Map<String, Object>> compareVersions(String promptName,
                                                      List<String> versions,
                                                      List<String> metrics) {
        return Mono.fromCallable(() -> {
            Map<String, Object> comparison = new HashMap<>();
            comparison.put("promptName", promptName);
            comparison.put("versions", versions);
            comparison.put("metrics", metrics);

            Map<String, Map<String, Double>> versionMetrics = new HashMap<>();
            for (String version : versions) {
                Map<String, Double> metricValues = new HashMap<>();
                for (String metric : metrics) {
                    metricValues.put(metric, 0.5 + Math.random() * 0.5);
                }
                versionMetrics.put(version, metricValues);
            }

            comparison.put("versionMetrics", versionMetrics);

            String bestVersion = Collections.max(versionMetrics.entrySet(),
                    Comparator.comparingDouble(e -> e.getValue().values().stream()
                            .mapToDouble(Double::doubleValue)
                            .average()
                            .orElse(0))).getKey();
            comparison.put("bestVersion", bestVersion);

            return comparison;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> setDefaultVersion(String promptName, String version) {
        return Mono.fromRunnable(() -> {
            List<PromptVersion> versions = promptVersions.get(promptName);
            if (versions != null && versions.stream().anyMatch(v -> v.version.equals(version))) {
                defaultVersions.put(promptName, version);
            } else {
                throw new IllegalArgumentException("Version " + version + " not found for prompt " + promptName);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Void> rollbackPrompt(String promptName, String targetVersion) {
        return setDefaultVersion(promptName, targetVersion);
    }

    private static class PromptVersion {
        final String version;
        final String content;
        final Map<String, Object> variables;
        final String createdBy;
        final String description;
        final Instant createdAt;

        PromptVersion(String version, String content, Map<String, Object> variables,
                      String createdBy, String description, Instant createdAt) {
            this.version = version;
            this.content = content;
            this.variables = variables;
            this.createdBy = createdBy;
            this.description = description;
            this.createdAt = createdAt;
        }
    }

    private static class AbExperiment {
        final String experimentId;
        final String name;
        final String promptName;
        final List<String> versions;
        final List<Double> trafficSplit;
        final Instant startTime;
        final Instant endTime;
        String status;

        AbExperiment(String experimentId, String name, String promptName,
                     List<String> versions, List<Double> trafficSplit,
                     Instant startTime, Instant endTime, String status) {
            this.experimentId = experimentId;
            this.name = name;
            this.promptName = promptName;
            this.versions = versions;
            this.trafficSplit = trafficSplit;
            this.startTime = startTime;
            this.endTime = endTime;
            this.status = status;
        }
    }
}
