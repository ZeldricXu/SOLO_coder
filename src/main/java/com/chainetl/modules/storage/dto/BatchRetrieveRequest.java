package com.chainetl.modules.storage.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchRetrieveRequest {

    @NotEmpty(message = "recordIds is required")
    private List<String> recordIds;
}
