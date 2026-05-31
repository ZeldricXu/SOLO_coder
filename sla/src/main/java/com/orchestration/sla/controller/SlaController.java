package com.orchestration.sla.controller;

import com.orchestration.common.api.ApiConstants;
import com.orchestration.common.base.Result;
import com.orchestration.persistence.entity.SlaPolicy;
import com.orchestration.persistence.entity.SlaRecord;
import com.orchestration.sla.service.SlaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiConstants.API_V1_PREFIX + "/sla")
@RequiredArgsConstructor
public class SlaController {

    private final SlaService slaService;

    @PostMapping("/policies")
    public Result<Long> createPolicy(@RequestBody SlaPolicy policy) {
        return Result.success(slaService.createPolicy(policy));
    }

    @PutMapping("/policies/{id}")
    public Result<Boolean> updatePolicy(@PathVariable Long id, @RequestBody SlaPolicy policy) {
        policy.setId(id);
        return Result.success(slaService.updatePolicy(policy));
    }

    @GetMapping("/policies/{id}")
    public Result<SlaPolicy> getPolicy(@PathVariable Long id) {
        return Result.success(slaService.getPolicy(id));
    }

    @GetMapping("/policies")
    public Result<List<SlaPolicy>> listPolicies(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(slaService.listPolicies(page, size));
    }

    @DeleteMapping("/policies/{id}")
    public Result<Boolean> deletePolicy(@PathVariable Long id) {
        return Result.success(slaService.deletePolicy(id));
    }

    @PostMapping("/records/task/{taskInstanceId}")
    public Result<Long> createSlaRecord(@PathVariable Long taskInstanceId, @RequestParam Long policyId) {
        return Result.success(slaService.createSlaRecord(taskInstanceId, policyId));
    }

    @GetMapping("/records/task/{taskInstanceId}")
    public Result<SlaRecord> getSlaRecord(@PathVariable Long taskInstanceId) {
        return Result.success(slaService.getSlaRecord(taskInstanceId));
    }

    @GetMapping("/records/overtime")
    public Result<List<SlaRecord>> listOvertimeRecords(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(slaService.listOvertimeRecords(page, size));
    }

    @GetMapping("/records/{recordId}/remaining")
    public Result<Map<String, Object>> calculateRemainingTime(@PathVariable Long recordId) {
        return Result.success(slaService.calculateRemainingTime(recordId));
    }

    @PostMapping("/records/{recordId}/notify")
    public Result<Boolean> notifyEscalation(@PathVariable Long recordId, @RequestParam Integer level) {
        return Result.success(slaService.notifyEscalation(recordId, level));
    }
}
