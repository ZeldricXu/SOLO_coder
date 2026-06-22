package com.enterprise.gateway.common;

import com.enterprise.gateway.common.model.GrayReleaseRule;

public class GrayReleaseRuleBuilder {

    private String routeId = "test-route";
    private String grayVersion = "v2";
    private Integer grayWeight = 30;
    private String grayHeaders = null;
    private String grayParams = null;
    private Boolean enabled = true;

    private GrayReleaseRuleBuilder() {
    }

    public static GrayReleaseRuleBuilder builder() {
        return new GrayReleaseRuleBuilder();
    }

    public GrayReleaseRuleBuilder withRouteId(String routeId) {
        this.routeId = routeId;
        return this;
    }

    public GrayReleaseRuleBuilder withGrayVersion(String grayVersion) {
        this.grayVersion = grayVersion;
        return this;
    }

    public GrayReleaseRuleBuilder withGrayWeight(Integer grayWeight) {
        this.grayWeight = grayWeight;
        return this;
    }

    public GrayReleaseRuleBuilder withGrayHeaders(String grayHeaders) {
        this.grayHeaders = grayHeaders;
        return this;
    }

    public GrayReleaseRuleBuilder withGrayParams(String grayParams) {
        this.grayParams = grayParams;
        return this;
    }

    public GrayReleaseRuleBuilder withEnabled(Boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public GrayReleaseRule build() {
        return GrayReleaseRule.builder()
                .routeId(routeId)
                .grayVersion(grayVersion)
                .grayWeight(grayWeight)
                .grayHeaders(grayHeaders)
                .grayParams(grayParams)
                .enabled(enabled)
                .build();
    }
}
