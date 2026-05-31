package com.datamasker.interfaces.dto.federation;

import lombok.Data;

@Data
public class CreateTaskResponse {

    private String taskId;

    private String status;

    private int minParticipants;
}
