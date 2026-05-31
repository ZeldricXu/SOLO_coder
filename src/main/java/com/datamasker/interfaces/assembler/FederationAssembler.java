package com.datamasker.interfaces.assembler;

import com.datamasker.domain.federation.model.FederationTask;
import com.datamasker.domain.federation.model.GlobalModelUpdate;
import com.datamasker.interfaces.dto.federation.CreateTaskResponse;
import com.datamasker.interfaces.dto.federation.GlobalModelResponse;

public class FederationAssembler {

    public static CreateTaskResponse toCreateTaskResponse(FederationTask task) {
        CreateTaskResponse response = new CreateTaskResponse();
        response.setTaskId(task.getTaskId());
        response.setStatus(task.getStatus());
        response.setMinParticipants(task.getParticipantCount());
        return response;
    }

    public static GlobalModelResponse toGlobalModelResponse(GlobalModelUpdate update, boolean converged) {
        GlobalModelResponse response = new GlobalModelResponse();
        response.setTaskId(update.getTaskId());
        response.setRoundNumber(update.getRoundNumber());
        response.setGlobalModelHash(update.getGlobalModelHash());
        response.setParticipantCount(update.getParticipantCount());
        response.setConvergenceMetric(update.getConvergenceMetric());
        response.setConverged(converged);
        return response;
    }
}
