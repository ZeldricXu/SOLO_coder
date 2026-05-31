package com.solocoder.platform.storage.service;

import com.solocoder.platform.storage.model.BatchOperationRequest;
import com.solocoder.platform.storage.model.BatchOperationResult;

import java.util.List;

public interface BatchOperationService {

    BatchOperationResult executeBatch(BatchOperationRequest request);

    BatchOperationRequest mergeRequests(List<BatchOperationRequest> requests);

    BatchOperationResult executeBatchWithMerge(BatchOperationRequest request);
}
