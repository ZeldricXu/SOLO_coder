package com.contractai.sla.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.contractai.common.dto.PageQuery;
import com.contractai.common.result.ApiResponse;
import com.contractai.sla.dto.SlaDTO;
import com.contractai.sla.entity.SlaEscalation;
import com.contractai.sla.entity.SlaPolicy;
import com.contractai.sla.entity.SlaRecord;
import com.contractai.sla.service.SlaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/sla")
@RequiredArgsConstructor
public class SlaController {

    private final SlaService slaService;

    @PostMapping("/policies")
    public ApiResponse<SlaPolicy> createPolicy(@RequestBody SlaDTO.PolicyCreateDTO dto) {
        return ApiResponse.success(slaService.createPolicy(dto));
    }

    @PutMapping("/policies/{id}")
    public ApiResponse<SlaPolicy> updatePolicy(@PathVariable Long id, @RequestBody SlaDTO.PolicyUpdateDTO dto) {
        return ApiResponse.success(slaService.updatePolicy(id, dto));
    }

    @GetMapping("/policies")
    public ApiResponse<Page<SlaPolicy>> listPolicies(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String slaType,
            @RequestParam(required = false) Boolean enabled) {
        return ApiResponse.success(slaService.listPolicies(page, size, slaType, enabled));
    }

    @GetMapping("/policies/{id}")
    public ApiResponse<SlaPolicy> getPolicy(@PathVariable Long id) {
        return ApiResponse.success(slaService.getPolicy(id));
    }

    @DeleteMapping("/policies/{id}")
    public ApiResponse<Void> deletePolicy(@PathVariable Long id) {
        slaService.deletePolicy(id);
        return ApiResponse.success();
    }

    @PostMapping("/records")
    public ApiResponse<SlaRecord> createRecord(@RequestBody SlaDTO.RecordCreateDTO dto) {
        return ApiResponse.success(slaService.createRecord(dto));
    }

    @PostMapping("/records/{id}/ack-response")
    public ApiResponse<SlaRecord> ackResponse(@PathVariable Long id, @RequestParam Long operatorId) {
        return ApiResponse.success(slaService.ackResponse(id, operatorId));
    }

    @PostMapping("/records/{id}/ack-resolution")
    public ApiResponse<SlaRecord> ackResolution(@PathVariable Long id, @RequestParam Long operatorId) {
        return ApiResponse.success(slaService.ackResolution(id, operatorId));
    }

    @GetMapping("/records/{id}")
    public ApiResponse<SlaRecord> getRecord(@PathVariable Long id) {
        return ApiResponse.success(slaService.getRecord(id));
    }

    @GetMapping("/records")
    public ApiResponse<Page<SlaRecord>> listRecords(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String businessType) {
        return ApiResponse.success(slaService.listRecords(page, size, status, businessType));
    }

    @GetMapping("/records/{id}/escalations")
    public ApiResponse<List<SlaEscalation>> getRecordEscalations(@PathVariable Long id) {
        return ApiResponse.success(slaService.getRecordEscalations(id));
    }

    @PostMapping("/escalations/{id}/ack")
    public ApiResponse<SlaEscalation> ackEscalation(@PathVariable Long id, @RequestParam Long operatorId) {
        return ApiResponse.success(slaService.ackEscalation(id, operatorId));
    }

    @GetMapping("/summary")
    public ApiResponse<SlaDTO.SlaSummaryDTO> getSummary() {
        return ApiResponse.success(slaService.getSummary());
    }

    @PostMapping("/monitor/batch")
    public ApiResponse<Void> batchMonitor(@RequestBody SlaDTO.BatchMonitorDTO dto) {
        slaService.monitorSlaDeadlines();
        return ApiResponse.success();
    }
}
