package com.enterprise.gateway.common;

import com.enterprise.gateway.common.model.RouteDefinition;

public class RouteDefinitionBuilder {

    private String routeId = "test-route";
    private String uri = "http://localhost:8081";
    private String predicates = "[{\"name\":\"Path\",\"args\":{\"pattern\":\"/api/test/**\"}}]";
    private String filters = "[]";
    private String metadata = "{}";
    private Integer orderNum = 0;
    private Integer status = 1;
    private String matchType = "PREFIX";
    private Integer weight = 100;
    private String groupId = "test-group";

    private RouteDefinitionBuilder() {
    }

    public static RouteDefinitionBuilder builder() {
        return new RouteDefinitionBuilder();
    }

    public RouteDefinitionBuilder withRouteId(String routeId) {
        this.routeId = routeId;
        return this;
    }

    public RouteDefinitionBuilder withUri(String uri) {
        this.uri = uri;
        return this;
    }

    public RouteDefinitionBuilder withPredicates(String predicates) {
        this.predicates = predicates;
        return this;
    }

    public RouteDefinitionBuilder withFilters(String filters) {
        this.filters = filters;
        return this;
    }

    public RouteDefinitionBuilder withMetadata(String metadata) {
        this.metadata = metadata;
        return this;
    }

    public RouteDefinitionBuilder withOrderNum(Integer orderNum) {
        this.orderNum = orderNum;
        return this;
    }

    public RouteDefinitionBuilder withStatus(Integer status) {
        this.status = status;
        return this;
    }

    public RouteDefinitionBuilder withMatchType(String matchType) {
        this.matchType = matchType;
        return this;
    }

    public RouteDefinitionBuilder withWeight(Integer weight) {
        this.weight = weight;
        return this;
    }

    public RouteDefinitionBuilder withGroupId(String groupId) {
        this.groupId = groupId;
        return this;
    }

    public RouteDefinition build() {
        return RouteDefinition.builder()
                .routeId(routeId)
                .uri(uri)
                .predicates(predicates)
                .filters(filters)
                .metadata(metadata)
                .orderNum(orderNum)
                .status(status)
                .matchType(matchType)
                .weight(weight)
                .groupId(groupId)
                .build();
    }
}
