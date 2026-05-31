package com.web3platform.chainindexer.dto;

import com.web3platform.chaininteraction.model.EventLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DecodeEventRequest {

    private EventLog log;
    private String contractAddress;
}
