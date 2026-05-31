package com.datamasker.application.service;

import com.datamasker.domain.privacy.batch.BatchPrivacyRequest;
import com.datamasker.domain.privacy.batch.BatchPrivacyResult;
import com.datamasker.domain.privacy.batch.BatchRequestAccumulator;
import com.datamasker.domain.privacy.model.NoisyResult;
import com.datamasker.infrastructure.config.PrivacyConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BatchPrivacyService {

    private final DifferentialPrivacyService differentialPrivacyService;
    private final BatchRequestAccumulator accumulator;
    private final PrivacyConfig privacyConfig;

    public BatchPrivacyResult processBatch(BatchPrivacyRequest request) {
        long startTime = System.currentTimeMillis();
        List<BatchPrivacyResult.BatchResultItem> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        List<BatchPrivacyRequest.BatchItem> sortedItems = request.getItems().stream()
                .sorted(Comparator.comparing(BatchPrivacyRequest.BatchItem::getMechanism))
                .collect(Collectors.toList());

        for (BatchPrivacyRequest.BatchItem item : sortedItems) {
            BatchPrivacyResult.BatchResultItem resultItem = new BatchPrivacyResult.BatchResultItem();
            resultItem.setQueryId(item.getQueryId() != null ? item.getQueryId() : UUID.randomUUID().toString());
            resultItem.setOriginalValue(item.getValue());

            try {
                double epsilon = item.getEpsilon() != null ? item.getEpsilon() : privacyConfig.getDefaultEpsilon();
                double delta = item.getDelta() != null ? item.getDelta() : privacyConfig.getDefaultDelta();

                NoisyResult noisyResult = differentialPrivacyService.addNoiseToQuery(
                        item.getValue(),
                        item.getSensitivity(),
                        epsilon,
                        delta,
                        item.getMechanism()
                );

                resultItem.setNoiseAdded(noisyResult.getNoiseAdded());
                resultItem.setNoisyValue(noisyResult.getNoisyValue());
                resultItem.setSuccess(true);
                successCount++;
            } catch (Exception e) {
                resultItem.setNoiseAdded(0.0);
                resultItem.setNoisyValue(item.getValue());
                resultItem.setSuccess(false);
                resultItem.setErrorMessage(e.getMessage());
                failureCount++;
            }

            results.add(resultItem);
        }

        BatchPrivacyResult result = new BatchPrivacyResult();
        result.setRequestId(request.getRequestId() != null ? request.getRequestId() : UUID.randomUUID().toString());
        result.setResults(results);
        result.setTotalItems(request.getItems().size());
        result.setSuccessCount(successCount);
        result.setFailureCount(failureCount);
        result.setTotalLatencyMs(System.currentTimeMillis() - startTime);

        return result;
    }

    public BatchPrivacyResult processAndMerge(BatchPrivacyRequest request) {
        long startTime = System.currentTimeMillis();
        List<BatchPrivacyResult.BatchResultItem> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        double totalEpsilon = 0.0;
        double totalDelta = 0.0;

        Map<String, List<BatchPrivacyRequest.BatchItem>> groupedByMechanism = request.getItems().stream()
                .collect(Collectors.groupingBy(BatchPrivacyRequest.BatchItem::getMechanism));

        for (Map.Entry<String, List<BatchPrivacyRequest.BatchItem>> entry : groupedByMechanism.entrySet()) {
            String mechanism = entry.getKey();
            List<BatchPrivacyRequest.BatchItem> items = entry.getValue();

            double sharedEpsilon = items.stream()
                    .mapToDouble(item -> item.getEpsilon() != null ? item.getEpsilon() : privacyConfig.getDefaultEpsilon())
                    .max()
                    .orElse(privacyConfig.getDefaultEpsilon());

            double sharedDelta = items.stream()
                    .mapToDouble(item -> item.getDelta() != null ? item.getDelta() : privacyConfig.getDefaultDelta())
                    .max()
                    .orElse(privacyConfig.getDefaultDelta());

            double sharedSensitivity = items.stream()
                    .mapToDouble(BatchPrivacyRequest.BatchItem::getSensitivity)
                    .max()
                    .orElse(1.0);

            for (BatchPrivacyRequest.BatchItem item : items) {
                BatchPrivacyResult.BatchResultItem resultItem = new BatchPrivacyResult.BatchResultItem();
                resultItem.setQueryId(item.getQueryId() != null ? item.getQueryId() : UUID.randomUUID().toString());
                resultItem.setOriginalValue(item.getValue());

                try {
                    NoisyResult noisyResult = differentialPrivacyService.addNoiseToQuery(
                            item.getValue(),
                            sharedSensitivity,
                            sharedEpsilon,
                            sharedDelta,
                            mechanism
                    );

                    resultItem.setNoiseAdded(noisyResult.getNoiseAdded());
                    resultItem.setNoisyValue(noisyResult.getNoisyValue());
                    resultItem.setSuccess(true);
                    successCount++;
                    totalEpsilon += sharedEpsilon;
                    totalDelta += sharedDelta;
                } catch (Exception e) {
                    resultItem.setNoiseAdded(0.0);
                    resultItem.setNoisyValue(item.getValue());
                    resultItem.setSuccess(false);
                    resultItem.setErrorMessage(e.getMessage());
                    failureCount++;
                }

                results.add(resultItem);
            }
        }

        BatchPrivacyResult result = new BatchPrivacyResult();
        result.setRequestId(request.getRequestId() != null ? request.getRequestId() : UUID.randomUUID().toString());
        result.setResults(results);
        result.setTotalItems(request.getItems().size());
        result.setSuccessCount(successCount);
        result.setFailureCount(failureCount);
        result.setTotalLatencyMs(System.currentTimeMillis() - startTime);

        return result;
    }

    @Async
    public void accumulateAndProcess(BatchPrivacyRequest.BatchItem item) {
        accumulator.addRequest(item);
        if (accumulator.shouldFlush()) {
            flushAccumulator();
        }
    }

    public BatchPrivacyResult flushAccumulator() {
        List<BatchPrivacyRequest.BatchItem> items = accumulator.drain();
        if (items.isEmpty()) {
            BatchPrivacyResult emptyResult = new BatchPrivacyResult();
            emptyResult.setRequestId(UUID.randomUUID().toString());
            emptyResult.setResults(new ArrayList<>());
            emptyResult.setTotalItems(0);
            emptyResult.setSuccessCount(0);
            emptyResult.setFailureCount(0);
            emptyResult.setTotalLatencyMs(0);
            return emptyResult;
        }

        BatchPrivacyRequest request = new BatchPrivacyRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setItems(items);

        return processAndMerge(request);
    }
}
