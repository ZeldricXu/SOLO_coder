package com.chainetl.modules.events.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessEventRequest {

    @NotBlank(message = "logId is required")
    private String logId;

    private String callbackResult;
}
