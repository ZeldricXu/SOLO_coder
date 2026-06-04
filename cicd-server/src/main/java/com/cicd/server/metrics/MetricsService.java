package com.cicd.server.metrics;

import com.cicd.server.entity.*;
import com.cicd.server.repository.*;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsService {

    private final InfluxDBClient influxDBClient;
    private final PipelineExecutionRepository executionRepository;
    private final DeploymentRepository deploymentRepository;
    private final StageExecutionRepository stageExecutionRepository;

    @Value("${influxdb.bucket:cicd-metrics}")
    private String bucket;

    @Value("${influxdb.org:cicd-org}")
    private String org;

    public void recordPipelineExecution(PipelineExecution execution) {
        try (WriteApi writeApi = influxDBClient.getWriteApi()) {
            Map<String, String> tags = new HashMap<>();
            tags.put("project_id", String.valueOf(execution.getPipeline().getProject().getId()));
            tags.put("project_name", execution.getPipeline().getProject().getName());
            tags.put("pipeline_id", String.valueOf(execution.getPipeline().getId()));
            tags.put("pipeline_name", execution.getPipeline().getName());
            tags.put("branch", execution.getBranchName() != null ? execution.getBranchName() : "unknown");
            tags.put("status", execution.getStatus().name());
            tags.put("trigger_type", execution.getTriggerType().name());

            Map<String, Object> fields = new HashMap<>();
            fields.put("execution_number", execution.getExecutionNumber());
            fields.put("duration_seconds", execution.getDurationSeconds() != null ? execution.getDurationSeconds() : 0);
            fields.put("success", execution.getStatus().isSuccess() ? 1 : 0);
            fields.put("failed", execution.getStatus().isFailure() ? 1 : 0);
            if (execution.getCommitSha() != null) {
                fields.put("commit_sha", execution.getCommitSha());
            }

            Instant timestamp = execution.getStartedAt() != null ?
                    execution.getStartedAt().atZone(ZoneId.systemDefault()).toInstant() :
                    Instant.now();

            writeApi.writeMeasurement(bucket, org, WritePrecision.NS, timestamp, tags, fields, "pipeline_execution");
            log.info("Recorded pipeline execution metrics for #{}", execution.getExecutionNumber());
        } catch (Exception e) {
            log.error("Failed to record pipeline execution metrics", e);
        }
    }

    public void recordStageExecution(StageExecution stage) {
        try (WriteApi writeApi = influxDBClient.getWriteApi()) {
            Map<String, String> tags = new HashMap<>();
            tags.put("pipeline_execution_id", String.valueOf(stage.getPipelineExecution().getId()));
            tags.put("stage_name", stage.getName());
            tags.put("status", stage.getStatus().name());

            Map<String, Object> fields = new HashMap<>();
            fields.put("duration_seconds", stage.getDurationSeconds() != null ? stage.getDurationSeconds() : 0);
            fields.put("success", stage.getStatus().isSuccess() ? 1 : 0);

            Instant timestamp = stage.getStartedAt() != null ?
                    stage.getStartedAt().atZone(ZoneId.systemDefault()).toInstant() :
                    Instant.now();

            writeApi.writeMeasurement(bucket, org, WritePrecision.NS, timestamp, tags, fields, "stage_execution");
        } catch (Exception e) {
            log.error("Failed to record stage execution metrics", e);
        }
    }

    public void recordDeployment(Deployment deployment) {
        try (WriteApi writeApi = influxDBClient.getWriteApi()) {
            Map<String, String> tags = new HashMap<>();
            tags.put("project_id", String.valueOf(deployment.getProject().getId()));
            tags.put("environment_id", String.valueOf(deployment.getEnvironment().getId()));
            tags.put("environment_name", deployment.getEnvironment().getName());
            tags.put("service_name", deployment.getServiceName());
            tags.put("status", deployment.getStatus().name());
            tags.put("strategy", deployment.getStrategy().name());

            Map<String, Object> fields = new HashMap<>();
            fields.put("duration_seconds", deployment.getDurationSeconds() != null ? deployment.getDurationSeconds() : 0);
            fields.put("success", deployment.getStatus().isSuccess() ? 1 : 0);
            fields.put("failed", deployment.getStatus().isFailure() ? 1 : 0);
            fields.put("rollback", deployment.getRolledBack() ? 1 : 0);
            fields.put("version", deployment.getVersion());
            if (deployment.getSmokeTestPassed() != null) {
                fields.put("smoke_test_passed", deployment.getSmokeTestPassed() ? 1 : 0);
            }

            Instant timestamp = deployment.getStartedAt() != null ?
                    deployment.getStartedAt().atZone(ZoneId.systemDefault()).toInstant() :
                    Instant.now();

            writeApi.writeMeasurement(bucket, org, WritePrecision.NS, timestamp, tags, fields, "deployment");
            log.info("Recorded deployment metrics for {} to {}", deployment.getServiceName(), deployment.getEnvironment().getName());
        } catch (Exception e) {
            log.error("Failed to record deployment metrics", e);
        }
    }

    public Map<String, Object> getPipelineStats(Long projectId, String range) {
        Map<String, Object> result = new HashMap<>();

        try {
            long rangeSeconds = parseRange(range);
            String fluxQuery = String.format(
                    "from(bucket: \"%s\") " +
                            "|> range(start: -%ds) " +
                            "|> filter(fn: (r) => r._measurement == \"pipeline_execution\" and r.project_id == \"%d\") " +
                            "|> group(columns: [\"_field\"]) " +
                            "|> sum()",
                    bucket, rangeSeconds, projectId
            );

            List<FluxTable> tables = influxDBClient.getQueryApi().query(fluxQuery, org);

            int totalBuilds = 0;
            int successfulBuilds = 0;
            int failedBuilds = 0;
            long totalDuration = 0;

            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    String field = record.getField();
                    long value = ((Number) record.getValue()).longValue();

                    if ("execution_number".equals(field)) {
                        totalBuilds = (int) value;
                    } else if ("success".equals(field)) {
                        successfulBuilds = (int) value;
                    } else if ("failed".equals(field)) {
                        failedBuilds = (int) value;
                    } else if ("duration_seconds".equals(field)) {
                        totalDuration = value;
                    }
                }
            }

            double successRate = totalBuilds > 0 ? (double) successfulBuilds / totalBuilds * 100 : 0;
            double avgDuration = totalBuilds > 0 ? (double) totalDuration / totalBuilds : 0;

            result.put("totalBuilds", totalBuilds);
            result.put("successfulBuilds", successfulBuilds);
            result.put("failedBuilds", failedBuilds);
            result.put("successRate", Math.round(successRate * 100.0) / 100.0);
            result.put("avgDurationSeconds", Math.round(avgDuration));

            result.put("buildTrend", getBuildTrend(projectId, range));
            result.put("stageDurationDistribution", getStageDurationDistribution(projectId, range));

        } catch (Exception e) {
            log.error("Failed to get pipeline stats", e);
            result.put("error", e.getMessage());
        }

        return result;
    }

    public Map<String, Object> getDoraMetrics(Long projectId, String range) {
        Map<String, Object> result = new HashMap<>();

        try {
            long rangeSeconds = parseRange(range);
            LocalDateTime startTime = LocalDateTime.now().minusSeconds(rangeSeconds);

            List<Deployment> deployments = deploymentRepository.findByProjectIdAndStartedAtAfter(projectId, startTime);

            int totalDeployments = deployments.size();
            int failedDeployments = (int) deployments.stream()
                    .filter(d -> d.getStatus() != null && d.getStatus().isFailure())
                    .count();
            int rollbacks = (int) deployments.stream()
                    .filter(d -> Boolean.TRUE.equals(d.getRolledBack()))
                    .count();

            double deploymentFrequency = totalDeployments / (rangeSeconds / 86400.0);
            double changeFailureRate = totalDeployments > 0 ? (double) (failedDeployments + rollbacks) / totalDeployments * 100 : 0;

            result.put("deploymentFrequency", Math.round(deploymentFrequency * 100.0) / 100.0);
            result.put("totalDeployments", totalDeployments);
            result.put("failedDeployments", failedDeployments);
            result.put("rollbacks", rollbacks);
            result.put("changeFailureRate", Math.round(changeFailureRate * 100.0) / 100.0);
            result.put("deploymentTrend", getDeploymentTrend(projectId, range));

        } catch (Exception e) {
            log.error("Failed to get DORA metrics", e);
            result.put("error", e.getMessage());
        }

        return result;
    }

    public List<Map<String, Object>> getEnvironmentVersions(Long projectId) {
        List<Map<String, Object>> result = new ArrayList<>();

        try {
            List<Deployment> deployments = deploymentRepository.findLatestByProjectIdGroupByEnvironment(projectId);

            for (Deployment deployment : deployments) {
                Map<String, Object> envInfo = new HashMap<>();
                envInfo.put("environmentId", deployment.getEnvironment().getId());
                envInfo.put("environmentName", deployment.getEnvironment().getName());
                envInfo.put("serviceName", deployment.getServiceName());
                envInfo.put("version", deployment.getVersion());
                envInfo.put("deployedAt", deployment.getStartedAt());
                envInfo.put("deployedBy", deployment.getTriggeredBy());
                envInfo.put("status", deployment.getStatus().name());
                envInfo.put("commitSha", deployment.getCommitSha());
                result.add(envInfo);
            }
        } catch (Exception e) {
            log.error("Failed to get environment versions", e);
        }

        return result;
    }

    public Map<String, Object> getDashboardOverview(Long projectId) {
        Map<String, Object> result = new HashMap<>();

        result.put("last24hStats", getPipelineStats(projectId, "24h"));
        result.put("doraMetrics", getDoraMetrics(projectId, "7d"));
        result.put("environmentVersions", getEnvironmentVersions(projectId));

        return result;
    }

    private List<Map<String, Object>> getBuildTrend(Long projectId, String range) {
        List<Map<String, Object>> trend = new ArrayList<>();
        try {
            long rangeSeconds = parseRange(range);
            String fluxQuery = String.format(
                    "from(bucket: \"%s\") " +
                            "|> range(start: -%ds) " +
                            "|> filter(fn: (r) => r._measurement == \"pipeline_execution\" and r.project_id == \"%d\") " +
                            "|> filter(fn: (r) => r._field == \"success\" or r._field == \"failed\") " +
                            "|> aggregateWindow(every: 1h, fn: sum, createEmpty: false) " +
                            "|> yield(name: \"trend\")",
                    bucket, rangeSeconds, projectId
            );

            List<FluxTable> tables = influxDBClient.getQueryApi().query(fluxQuery, org);

            Map<String, Map<String, Object>> timePoints = new LinkedHashMap<>();

            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    Instant time = record.getTime();
                    String key = time.toString();
                    Map<String, Object> point = timePoints.computeIfAbsent(key, k -> new HashMap<>());
                    point.put("time", time.toString());
                    point.put(record.getField(), record.getValue());
                }
            }

            trend.addAll(timePoints.values());
        } catch (Exception e) {
            log.error("Failed to get build trend", e);
        }
        return trend;
    }

    private List<Map<String, Object>> getDeploymentTrend(Long projectId, String range) {
        List<Map<String, Object>> trend = new ArrayList<>();
        try {
            long rangeSeconds = parseRange(range);
            String fluxQuery = String.format(
                    "from(bucket: \"%s\") " +
                            "|> range(start: -%ds) " +
                            "|> filter(fn: (r) => r._measurement == \"deployment\" and r.project_id == \"%d\") " +
                            "|> filter(fn: (r) => r._field == \"success\" or r._field == \"failed\") " +
                            "|> aggregateWindow(every: 1d, fn: sum, createEmpty: false) " +
                            "|> yield(name: \"trend\")",
                    bucket, rangeSeconds, projectId
            );

            List<FluxTable> tables = influxDBClient.getQueryApi().query(fluxQuery, org);

            Map<String, Map<String, Object>> timePoints = new LinkedHashMap<>();

            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    Instant time = record.getTime();
                    String key = time.toString();
                    Map<String, Object> point = timePoints.computeIfAbsent(key, k -> new HashMap<>());
                    point.put("time", time.toString());
                    point.put(record.getField(), record.getValue());
                }
            }

            trend.addAll(timePoints.values());
        } catch (Exception e) {
            log.error("Failed to get deployment trend", e);
        }
        return trend;
    }

    private Map<String, Long> getStageDurationDistribution(Long projectId, String range) {
        Map<String, Long> distribution = new LinkedHashMap<>();
        try {
            List<StageExecution> stages = stageExecutionRepository.findByProjectIdAndTimeRange(projectId,
                    LocalDateTime.now().minusSeconds(parseRange(range)));

            Map<String, Long> totalDuration = new HashMap<>();
            Map<String, Integer> count = new HashMap<>();

            for (StageExecution stage : stages) {
                if (stage.getDurationSeconds() != null && stage.getStatus() != null && stage.getStatus().isSuccess()) {
                    totalDuration.merge(stage.getName(), stage.getDurationSeconds(), Long::sum);
                    count.merge(stage.getName(), 1, Integer::sum);
                }
            }

            for (Map.Entry<String, Long> entry : totalDuration.entrySet()) {
                long avg = entry.getValue() / count.getOrDefault(entry.getKey(), 1);
                distribution.put(entry.getKey(), avg);
            }
        } catch (Exception e) {
            log.error("Failed to get stage duration distribution", e);
        }
        return distribution;
    }

    private long parseRange(String range) {
        if (range == null) return 86400;
        try {
            char unit = range.charAt(range.length() - 1);
            long value = Long.parseLong(range.substring(0, range.length() - 1));
            return switch (unit) {
                case 'h' -> value * 3600;
                case 'd' -> value * 86400;
                case 'w' -> value * 604800;
                default -> value;
            };
        } catch (Exception e) {
            return 86400;
        }
    }
}
