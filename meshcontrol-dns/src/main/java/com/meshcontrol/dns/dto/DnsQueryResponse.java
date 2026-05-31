package com.meshcontrol.dns.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DnsQueryResponse {

    private String domain;
    private String type;
    private List<Map<String, Object>> records;
    private Long ttl;
    private boolean fromCache;
    private String upstreamUsed;
}
