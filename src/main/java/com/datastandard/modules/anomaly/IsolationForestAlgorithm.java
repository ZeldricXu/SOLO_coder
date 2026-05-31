package com.datastandard.modules.anomaly;

import com.datastandard.common.util.IdGenerator;
import com.datastandard.modules.anomaly.dto.AlgorithmConfig;
import com.datastandard.modules.anomaly.dto.AnomalyDetectionRequest;
import com.datastandard.modules.anomaly.dto.AnomalyResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
public class IsolationForestAlgorithm implements DetectionAlgorithm {

    private static final String ALGORITHM_NAME = "Isolation Forest";
    private static final String ALGORITHM_TYPE = "ISOLATION_FOREST";
    private static final int DEFAULT_NUM_TREES = 100;
    private static final int DEFAULT_SAMPLE_SIZE = 256;
    private static final int DEFAULT_MAX_DEPTH = 12;
    private static final BigDecimal DEFAULT_CONTAMINATION = new BigDecimal("0.1");

    @Override
    public String getAlgorithmName() {
        return ALGORITHM_NAME;
    }

    @Override
    public String getAlgorithmType() {
        return ALGORITHM_TYPE;
    }

    @Override
    public Mono<List<AnomalyResult>> detect(AnomalyDetectionRequest request, AlgorithmConfig config) {
        return isDataSufficient(request.getDataPoints(), config)
                .flatMap(sufficient -> {
                    if (!sufficient) {
                        log.warn("数据点不足，跳过孤立森林检测: metricCode={}, count={}",
                                request.getMetricCode(), request.getDataPoints().size());
                        return Mono.just(new ArrayList<AnomalyResult>());
                    }
                    return Mono.fromCallable(() -> performDetection(request, config))
                            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
                });
    }

    private List<AnomalyResult> performDetection(AnomalyDetectionRequest request, AlgorithmConfig config) {
        List<AnomalyResult> results = new ArrayList<>();
        List<AnomalyDetectionRequest.DataPoint> dataPoints = request.getDataPoints();
        List<BigDecimal> values = dataPoints.stream()
                .map(AnomalyDetectionRequest.DataPoint::getValue)
                .toList();

        AlgorithmConfig.IsolationForestConfig ifConfig = config.getIsolationForestConfig();
        int numTrees = ifConfig != null && ifConfig.getNumTrees() != null
                ? ifConfig.getNumTrees() : DEFAULT_NUM_TREES;
        int sampleSize = ifConfig != null && ifConfig.getSampleSize() != null
                ? ifConfig.getSampleSize() : DEFAULT_SAMPLE_SIZE;
        int maxDepth = ifConfig != null && ifConfig.getMaxDepth() != null
                ? ifConfig.getMaxDepth() : DEFAULT_MAX_DEPTH;
        BigDecimal contamination = ifConfig != null && ifConfig.getContamination() != null
                ? ifConfig.getContamination() : DEFAULT_CONTAMINATION;

        sampleSize = Math.min(sampleSize, values.size());

        double[] anomalyScores = calculateAnomalyScores(values, numTrees, sampleSize, maxDepth);
        double threshold = calculateThreshold(anomalyScores, contamination);

        for (int i = 0; i < dataPoints.size(); i++) {
            double score = anomalyScores[i];
            if (score > threshold) {
                BigDecimal anomalyScore = BigDecimal.valueOf(Math.min(score, 1.0));
                BigDecimal confidence = calculateConfidence(score, threshold);
                String severity = determineSeverity(score, threshold);

                AnomalyResult result = AnomalyResult.builder()
                        .resultId(IdGenerator.generateStrId())
                        .detectionCode(request.getDetectionCode())
                        .metricCode(request.getMetricCode())
                        .entityId(request.getEntityId())
                        .instanceId(request.getInstanceId())
                        .isAnomaly(true)
                        .anomalyType("OUTLIER")
                        .severity(severity)
                        .confidence(confidence)
                        .anomalyScore(anomalyScore)
                        .threshold(BigDecimal.valueOf(threshold))
                        .expectedValue(calculateExpectedValue(values, i))
                        .actualValue(dataPoints.get(i).getValue())
                        .detectedAt(LocalDateTime.now())
                        .windowStart(request.getWindowStart())
                        .windowEnd(request.getWindowEnd())
                        .algorithmType(ALGORITHM_TYPE)
                        .anomalyData(Map.of(
                                "anomalyScore", score,
                                "numTrees", numTrees,
                                "sampleSize", sampleSize,
                                "maxDepth", maxDepth,
                                "contamination", contamination,
                                "dataPointIndex", i
                        ))
                        .description(String.format("孤立森林检测到异常: 异常分数=%.4f, 阈值=%.4f", score, threshold))
                        .suggestedAction("检查该数据点是否为真实离群点，考虑调整contamination参数")
                        .build();
                results.add(result);
            }
        }

        log.info("孤立森林检测完成: metricCode={}, 异常数={}, 数据点总数={}",
                request.getMetricCode(), results.size(), values.size());
        return results;
    }

    @Override
    public Mono<BigDecimal> calculateAnomalyScore(List<BigDecimal> data, BigDecimal value, AlgorithmConfig config) {
        return Mono.fromCallable(() -> {
            if (data.size() < 10) return BigDecimal.ZERO;

            AlgorithmConfig.IsolationForestConfig ifConfig = config.getIsolationForestConfig();
            int numTrees = ifConfig != null && ifConfig.getNumTrees() != null
                    ? ifConfig.getNumTrees() : DEFAULT_NUM_TREES;
            int sampleSize = ifConfig != null && ifConfig.getSampleSize() != null
                    ? ifConfig.getSampleSize() : DEFAULT_SAMPLE_SIZE;
            int maxDepth = ifConfig != null && ifConfig.getMaxDepth() != null
                    ? ifConfig.getMaxDepth() : DEFAULT_MAX_DEPTH;

            sampleSize = Math.min(sampleSize, data.size());

            List<BigDecimal> allData = new ArrayList<>(data);
            allData.add(value);

            double[] scores = calculateAnomalyScores(allData, numTrees, sampleSize, maxDepth);
            return BigDecimal.valueOf(Math.min(scores[scores.length - 1], 1.0));
        });
    }

    private double[] calculateAnomalyScores(List<BigDecimal> values, int numTrees, int sampleSize, int maxDepth) {
        int n = values.size();
        double[] avgPathLengths = new double[n];
        Arrays.fill(avgPathLengths, 0.0);

        for (int t = 0; t < numTrees; t++) {
            List<BigDecimal> sample = sample(values, sampleSize);
            IsolationTree tree = buildTree(sample, maxDepth);

            for (int i = 0; i < n; i++) {
                avgPathLengths[i] += getPathLength(tree, values.get(i).doubleValue());
            }
        }

        for (int i = 0; i < n; i++) {
            avgPathLengths[i] /= numTrees;
        }

        double c = computeC(n);
        double[] scores = new double[n];
        for (int i = 0; i < n; i++) {
            scores[i] = Math.pow(2, -avgPathLengths[i] / c);
        }

        return scores;
    }

    private List<BigDecimal> sample(List<BigDecimal> values, int sampleSize) {
        List<BigDecimal> copy = new ArrayList<>(values);
        Collections.shuffle(copy, ThreadLocalRandom.current());
        return copy.subList(0, Math.min(sampleSize, copy.size()));
    }

    private IsolationTree buildTree(List<BigDecimal> data, int maxDepth) {
        return buildTree(data, 0, maxDepth);
    }

    private IsolationTree buildTree(List<BigDecimal> data, int currentDepth, int maxDepth) {
        if (currentDepth >= maxDepth || data.size() <= 1) {
            return new IsolationTree(null, null, null, null, data.size());
        }

        double min = data.stream().mapToDouble(BigDecimal::doubleValue).min().orElse(0);
        double max = data.stream().mapToDouble(BigDecimal::doubleValue).max().orElse(0);

        if (min == max) {
            return new IsolationTree(null, null, null, null, data.size());
        }

        double splitValue = min + ThreadLocalRandom.current().nextDouble() * (max - min);

        List<BigDecimal> leftData = new ArrayList<>();
        List<BigDecimal> rightData = new ArrayList<>();
        for (BigDecimal v : data) {
            if (v.doubleValue() < splitValue) {
                leftData.add(v);
            } else {
                rightData.add(v);
            }
        }

        IsolationTree left = buildTree(leftData, currentDepth + 1, maxDepth);
        IsolationTree right = buildTree(rightData, currentDepth + 1, maxDepth);

        return new IsolationTree(left, right, 0, splitValue, data.size());
    }

    private double getPathLength(IsolationTree tree, double value) {
        int pathLength = 0;
        IsolationTree current = tree;

        while (current.left != null) {
            pathLength++;
            if (value < current.splitValue) {
                current = current.left;
            } else {
                current = current.right;
            }
        }

        return pathLength + computeC(current.size);
    }

    private double computeC(int n) {
        if (n <= 1) return 0;
        double H = Math.log(n - 1) + 0.5772156649;
        return 2 * H - (2 * (n - 1)) / n;
    }

    private double calculateThreshold(double[] scores, BigDecimal contamination) {
        double[] sorted = Arrays.copyOf(scores, scores.length);
        Arrays.sort(sorted);

        int thresholdIndex = (int) Math.ceil((1 - contamination.doubleValue()) * sorted.length);
        thresholdIndex = Math.min(thresholdIndex, sorted.length - 1);
        thresholdIndex = Math.max(thresholdIndex, 0);

        return Math.max(sorted[thresholdIndex], 0.5);
    }

    private BigDecimal calculateExpectedValue(List<BigDecimal> values, int index) {
        int start = Math.max(0, index - 5);
        int end = Math.min(values.size(), index + 6);
        List<BigDecimal> window = values.subList(start, end);
        BigDecimal sum = window.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(window.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateConfidence(double score, double threshold) {
        double ratio = score / threshold;
        double confidence = Math.min(ratio * 0.5 + 0.5, 0.99);
        return BigDecimal.valueOf(confidence);
    }

    private String determineSeverity(double score, double threshold) {
        double ratio = score / threshold;
        if (ratio >= 1.8) return "CRITICAL";
        if (ratio >= 1.4) return "HIGH";
        if (ratio >= 1.15) return "MEDIUM";
        return "LOW";
    }

    private static class IsolationTree {
        final IsolationTree left;
        final IsolationTree right;
        final Integer splitAttribute;
        final Double splitValue;
        final int size;

        IsolationTree(IsolationTree left, IsolationTree right, Integer splitAttribute, Double splitValue, int size) {
            this.left = left;
            this.right = right;
            this.splitAttribute = splitAttribute;
            this.splitValue = splitValue;
            this.size = size;
        }
    }
}
