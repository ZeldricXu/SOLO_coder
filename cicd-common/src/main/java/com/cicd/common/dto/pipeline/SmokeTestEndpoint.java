package com.cicd.common.dto.pipeline;

import lombok.Data;

@Data
public class SmokeTestEndpoint {
    private String path;
    private String method;
    private int expectedStatus;
    private String expectedBody;
    private String headers;
}
