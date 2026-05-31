package com.datamasker.interfaces.controller;

import com.datamasker.application.service.BatchPrivacyService;
import com.datamasker.domain.privacy.batch.BatchPrivacyRequest;
import com.datamasker.domain.privacy.batch.BatchPrivacyResult;
import com.datamasker.domain.privacy.batch.BatchRequestAccumulator;
import com.datamasker.interfaces.dto.Result;
import com.datamasker.interfaces.dto.privacy.AccumulatorStatsResponse;
import com.datamasker.interfaces.dto.privacy.BatchAddNoiseRequest;
import com.datamasker.interfaces.dto.privacy.BatchAddNoiseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/privacy/batch")
@RequiredArgsConstructor
public class BatchPrivacyController {

    private final BatchPrivacyService batchPrivacyService;
    private final BatchRequestAccumulator accumulator;

    @PostMapping("/noise")
    public Result<BatchAddNoiseResponse> processBatch(@Valid @RequestBody BatchAddNoiseRequest request) {
        BatchPrivacyRequest domainRequest = new BatchPrivacyRequest();
        domainRequest.setItems(request.getItems().stream().map(dtoItem -> {
            BatchPrivacyRequest.BatchItem domainItem = new BatchPrivacyRequest.BatchItem();
            domainItem.setQueryId(dtoItem.getQueryId());
            domainItem.setValue(dtoItem.getValue());
            domainItem.setSensitivity(dtoItem.getSensitivity());
            domainItem.setMechanism(dtoItem.getMechanism());
            domainItem.setEpsilon(dtoItem.getEpsilon());
            domainItem.setDelta(dtoItem.getDelta());
            return domainItem;
        }).collect(Collectors.toList()));

        BatchPrivacyResult domainResult = batchPrivacyService.processBatch(domainRequest);
        BatchAddNoiseResponse response = toResponse(domainResult);
        return Result.success(response);
    }

    @PostMapping("/flush")
    public Result<BatchAddNoiseResponse> flushAccumulator() {
        BatchPrivacyResult domainResult = batchPrivacyService.flushAccumulator();
        BatchAddNoiseResponse response = toResponse(domainResult);
        return Result.success(response);
    }

    @GetMapping("/accumulator")
    public Result<AccumulatorStatsResponse> getAccumulatorStats() {
        AccumulatorStatsResponse response = new AccumulatorStatsResponse();
        response.setPendingItems(accumulator.getPendingItems());
        response.setTotalProcessed(accumulator.getTotalProcessed());
        response.setLastFlushTime(accumulator.getLastFlushTime());
        response.setFlushCount(accumulator.getFlushCount());
        return Result.success(response);
    }

    private BatchAddNoiseResponse toResponse(BatchPrivacyResult domainResult) {
        BatchAddNoiseResponse response = new BatchAddNoiseResponse();
        response.setRequestId(domainResult.getRequestId());
        response.setTotalItems(domainResult.getTotalItems());
        response.setSuccessCount(domainResult.getSuccessCount());

        List<BatchAddNoiseResponse.BatchResultItem> resultItems = domainResult.getResults().stream().map(domainItem -> {
            BatchAddNoiseResponse.BatchResultItem dtoItem = new BatchAddNoiseResponse.BatchResultItem();
            dtoItem.setQueryId(domainItem.getQueryId());
            dtoItem.setOriginalValue(domainItem.getOriginalValue());
            dtoItem.setNoiseAdded(domainItem.getNoiseAdded());
            dtoItem.setNoisyValue(domainItem.getNoisyValue());
            dtoItem.setSuccess(domainItem.isSuccess());
            dtoItem.setErrorMessage(domainItem.getErrorMessage());
            return dtoItem;
        }).collect(Collectors.toList());

        response.setResults(resultItems);
        return response;
    }
}
