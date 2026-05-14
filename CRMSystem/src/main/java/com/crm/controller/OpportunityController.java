package com.crm.controller;

import com.crm.common.ApiResponse;
import com.crm.dto.OpportunityFollowRequest;
import com.crm.dto.OpportunityRequest;
import com.crm.entity.Opportunity;
import com.crm.service.OpportunityService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/opportunities")
public class OpportunityController {

    @Autowired
    private OpportunityService opportunityService;

    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> createOpportunity(@Valid @RequestBody OpportunityRequest request) {
        Map<String, Object> result = opportunityService.createOpportunity(request);
        return ApiResponse.success(result);
    }

    @PostMapping("/follow")
    public ApiResponse<Map<String, Object>> followOpportunity(@Valid @RequestBody OpportunityFollowRequest request) {
        Map<String, Object> result = opportunityService.followOpportunity(request);
        return ApiResponse.success(result);
    }

    @GetMapping("/{opportunityId}")
    public ApiResponse<Opportunity> getOpportunityById(@PathVariable String opportunityId) {
        Opportunity opportunity = opportunityService.getOpportunityById(opportunityId);
        return ApiResponse.success(opportunity);
    }

    @GetMapping
    public ApiResponse<List<Opportunity>> getAllOpportunities() {
        List<Opportunity> opportunities = opportunityService.getAllOpportunities();
        return ApiResponse.success(opportunities);
    }

    @GetMapping("/customer/{customerId}")
    public ApiResponse<List<Opportunity>> getCustomerOpportunities(@PathVariable String customerId) {
        List<Opportunity> opportunities = opportunityService.getCustomerOpportunities(customerId);
        return ApiResponse.success(opportunities);
    }

    @GetMapping("/sales/{salesId}")
    public ApiResponse<List<Opportunity>> getSalesOpportunities(@PathVariable String salesId) {
        List<Opportunity> opportunities = opportunityService.getSalesOpportunities(salesId);
        return ApiResponse.success(opportunities);
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<Opportunity>> getOpportunitiesByStatus(@PathVariable String status) {
        List<Opportunity> opportunities = opportunityService.getOpportunitiesByStatus(status);
        return ApiResponse.success(opportunities);
    }
}
