package com.datapipeline.gateway.routing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Route {

    private String id;
    private String path;
    private String targetUrl;
    @Builder.Default
    private List<String> methods = new ArrayList<>();
    @Builder.Default
    private int timeoutMs = 30000;
    @Builder.Default
    private boolean retryEnabled = false;
    @Builder.Default
    private int maxRetries = 3;

    public List<String> getMethods() {
        return methods != null ? methods : Collections.emptyList();
    }

}
