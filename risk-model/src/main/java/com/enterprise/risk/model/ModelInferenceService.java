package com.enterprise.risk.model;

import com.enterprise.risk.common.event.RiskEvent;
import com.enterprise.risk.common.model.ModelConfig;
import com.enterprise.risk.common.rule.RuleEvaluationResult;
import com.microsoft.onnxruntime.OnnxTensor;
import com.microsoft.onnxruntime.OrtException;
import com.microsoft.onnxruntime.OrtSession;
import com.microsoft.onnxruntime.OrtSession.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelInferenceService {

    private final OnnxModelLoader modelLoader;
    private final FeatureExtractor featureExtractor;
    private final ModelDriftDetector driftDetector;

    private static final double DEFAULT_FALLBACK_SCORE = 0.0;

    @Autowired
    public ModelInferenceService(OnnxModelLoader modelLoader,
                                 FeatureExtractor featureExtractor,
                                 ModelDriftDetector driftDetector) {
        this.modelLoader = modelLoader;
        this.featureExtractor = featureExtractor;
        this.driftDetector = driftDetector;
    }

    public double infer(RiskEvent event,
                        Map<String, Object> context,
                        ModelConfig config) {
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            log.debug("模型 [{}] 已禁用，返回默认分", config.getModelId());
            return DEFAULT_FALLBACK_SCORE;
        }

        try {
            float[] features = featureExtractor.extractFeatures(event, context, config);

            if (features.length == 0) {
                log.warn("模型 [{}] 特征向量为空，降级返回默认分", config.getModelId());
                return DEFAULT_FALLBACK_SCORE;
            }

            double score = doInference(features, config);

            driftDetector.recordFeatures(config.getModelId(), features);

            log.debug("模型 [{}] 推理完成: 分数={}", config.getModelId(), score);
            return score;

        } catch (Exception e) {
            log.error("模型 [{}] 推理异常，降级返回默认分", config.getModelId(), e);
            return DEFAULT_FALLBACK_SCORE;
        }
    }

    public List<Double> inferBatch(List<RiskEvent> events,
                                   Map<String, Object> context,
                                   ModelConfig config) {
        if (events == null || events.isEmpty()) {
            return Collections.emptyList();
        }

        if (!Boolean.TRUE.equals(config.getEnabled())) {
            log.debug("模型 [{}] 已禁用，批量返回默认分", config.getModelId());
            List<Double> result = new ArrayList<>(events.size());
            for (int i = 0; i < events.size(); i++) {
                result.add(DEFAULT_FALLBACK_SCORE);
            }
            return result;
        }

        try {
            float[][] batchFeatures = featureExtractor.extractBatchFeatures(events, context, config);

            if (batchFeatures.length == 0) {
                log.warn("模型 [{}] 批量特征为空，降级返回默认分", config.getModelId());
                return createFallbackList(events.size());
            }

            List<Double> scores = doBatchInference(batchFeatures, config);

            for (float[] features : batchFeatures) {
                driftDetector.recordFeatures(config.getModelId(), features);
            }

            log.debug("模型 [{}] 批量推理完成: 样本数={}", config.getModelId(), scores.size());
            return scores;

        } catch (Exception e) {
            log.error("模型 [{}] 批量推理异常，降级返回默认分", config.getModelId(), e);
            return createFallbackList(events.size());
        }
    }

    public RuleEvaluationResult enrichWithModelScore(RuleEvaluationResult result,
                                                     ModelConfig config,
                                                     RiskEvent event) {
        if (result == null || config == null) {
            return result;
        }

        double modelScore = infer(event, result.getContext(), config);
        result.setModelScore(modelScore);

        Double threshold = config.getThreshold();
        if (threshold != null && modelScore < threshold) {
            result.setMatched(false);
            if (result.getMatchedReasons() != null) {
                result.getMatchedReasons().add(
                        String.format("模型分%.4f低于阈值%.4f", modelScore, threshold)
                );
            }
        }

        return result;
    }

    private double doInference(float[] features, ModelConfig config) {
        OrtSession session = modelLoader.loadModel(config);

        int featureDim = features.length;
        long[] inputShape = new long[]{1, featureDim};
        FloatBuffer buffer = FloatBuffer.wrap(features);

        String inputName = config.getInputName();
        if (inputName == null) {
            inputName = resolveFirstInputName(session);
        }

        String outputName = config.getOutputName();
        if (outputName == null) {
            outputName = resolveFirstOutputName(session);
        }

        try (OnnxTensor tensor = OnnxTensor.createTensor(
                session.getEnvironment(), buffer, inputShape)) {

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(inputName, tensor);

            try (Result result = session.run(inputs, Collections.singleton(outputName))) {
                Object outputObj = result.get(0).getValue();
                double score = extractScore(outputObj);
                return normalizeScore(score);
            }

        } catch (OrtException e) {
            throw new RuntimeException("ONNX推理执行失败", e);
        }
    }

    private List<Double> doBatchInference(float[][] batchFeatures, ModelConfig config) {
        OrtSession session = modelLoader.loadModel(config);

        int batchSize = batchFeatures.length;
        int featureDim = batchFeatures[0].length;
        long[] inputShape = new long[]{batchSize, featureDim};

        float[] flatArray = new float[batchSize * featureDim];
        for (int i = 0; i < batchSize; i++) {
            System.arraycopy(batchFeatures[i], 0, flatArray, i * featureDim, featureDim);
        }
        FloatBuffer buffer = FloatBuffer.wrap(flatArray);

        String inputName = config.getInputName();
        if (inputName == null) {
            inputName = resolveFirstInputName(session);
        }

        String outputName = config.getOutputName();
        if (outputName == null) {
            outputName = resolveFirstOutputName(session);
        }

        try (OnnxTensor tensor = OnnxTensor.createTensor(
                session.getEnvironment(), buffer, inputShape)) {

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(inputName, tensor);

            try (Result result = session.run(inputs, Collections.singleton(outputName))) {
                Object outputObj = result.get(0).getValue();
                return extractBatchScores(outputObj, batchSize);
            }

        } catch (OrtException e) {
            throw new RuntimeException("ONNX批量推理执行失败", e);
        }
    }

    private double extractScore(Object output) {
        if (output == null) {
            return DEFAULT_FALLBACK_SCORE;
        }

        if (output instanceof float[][]) {
            float[][] arr = (float[][]) output;
            if (arr.length > 0 && arr[0].length > 0) {
                if (arr[0].length == 1) {
                    return arr[0][0];
                }
                return arr[0].length > 1 ? arr[0][1] : arr[0][0];
            }
        }

        if (output instanceof float[]) {
            float[] arr = (float[]) output;
            if (arr.length > 0) {
                return arr.length > 1 ? arr[1] : arr[0];
            }
        }

        if (output instanceof double[][]) {
            double[][] arr = (double[][]) output;
            if (arr.length > 0 && arr[0].length > 0) {
                return arr[0].length > 1 ? arr[0][1] : arr[0][0];
            }
        }

        if (output instanceof double[]) {
            double[] arr = (double[]) output;
            if (arr.length > 0) {
                return arr.length > 1 ? arr[1] : arr[0];
            }
        }

        if (output instanceof long[][]) {
            long[][] arr = (long[][]) output;
            if (arr.length > 0 && arr[0].length > 0) {
                return arr[0][0];
            }
        }

        if (output instanceof Number) {
            return ((Number) output).doubleValue();
        }

        log.warn("无法解析模型输出类型: {}", output.getClass().getName());
        return DEFAULT_FALLBACK_SCORE;
    }

    private List<Double> extractBatchScores(Object output, int expectedSize) {
        List<Double> scores = new ArrayList<>(expectedSize);

        if (output instanceof float[][]) {
            float[][] arr = (float[][]) output;
            for (int i = 0; i < Math.min(arr.length, expectedSize); i++) {
                float score = arr[i].length > 1 ? arr[i][1] : (arr[i].length > 0 ? arr[i][0] : 0.0f);
                scores.add(normalizeScore(score));
            }
        } else if (output instanceof double[][]) {
            double[][] arr = (double[][]) output;
            for (int i = 0; i < Math.min(arr.length, expectedSize); i++) {
                double score = arr[i].length > 1 ? arr[i][1] : (arr[i].length > 0 ? arr[i][0] : 0.0);
                scores.add(normalizeScore(score));
            }
        } else {
            double score = extractScore(output);
            for (int i = 0; i < expectedSize; i++) {
                scores.add(score);
            }
        }

        while (scores.size() < expectedSize) {
            scores.add(DEFAULT_FALLBACK_SCORE);
        }

        return scores;
    }

    private double normalizeScore(double score) {
        if (score < 0.0) {
            return 0.0;
        }
        if (score > 1.0) {
            return 1.0;
        }
        return score;
    }

    private String resolveFirstInputName(OrtSession session) {
        try {
            return session.getInputNames().iterator().next();
        } catch (Exception e) {
            throw new RuntimeException("无法获取模型输入名称", e);
        }
    }

    private String resolveFirstOutputName(OrtSession session) {
        try {
            return session.getOutputNames().iterator().next();
        } catch (Exception e) {
            throw new RuntimeException("无法获取模型输出名称", e);
        }
    }

    private List<Double> createFallbackList(int size) {
        List<Double> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(DEFAULT_FALLBACK_SCORE);
        }
        return list;
    }
}
