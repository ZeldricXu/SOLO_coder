package com.solocoder.dns.dnsproxy.model;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

@Data
public class DnsResolveRequest implements Serializable {
    private String domain;
    private Integer recordType;
    private Boolean skipCache;
    private String clientIp;
}
