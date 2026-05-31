package com.datamasker.interfaces.controller;

import com.datamasker.application.service.TeeService;
import com.datamasker.domain.tee.model.AttestationResult;
import com.datamasker.domain.tee.model.EnclaveInstance;
import com.datamasker.domain.tee.model.SecureChannel;
import com.datamasker.infrastructure.persistence.entity.TeeEnclaveEntity;
import com.datamasker.interfaces.assembler.TeeAssembler;
import com.datamasker.interfaces.dto.Result;
import com.datamasker.interfaces.dto.tee.AttestationRequest;
import com.datamasker.interfaces.dto.tee.AttestationResponse;
import com.datamasker.interfaces.dto.tee.ChannelResponse;
import com.datamasker.interfaces.dto.tee.CreateEnclaveResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tee")
@RequiredArgsConstructor
public class TeeController {

    private final TeeService teeService;

    @PostMapping("/enclaves")
    public Result<CreateEnclaveResponse> createEnclave() {
        EnclaveInstance instance = teeService.createAndStartEnclave();
        CreateEnclaveResponse response = TeeAssembler.toCreateEnclaveResponse(instance);
        return Result.success(response);
    }

    @PostMapping("/enclaves/{enclaveId}/attestation")
    public Result<AttestationResponse> attestEnclave(
            @PathVariable String enclaveId,
            @RequestBody AttestationRequest request) {
        AttestationResult result = teeService.attestEnclave(enclaveId, request.getExpectedMeasurement());
        AttestationResponse response = TeeAssembler.toAttestationResponse(result);
        return Result.success(response);
    }

    @GetMapping("/enclaves/{enclaveId}")
    public Result<CreateEnclaveResponse> getEnclaveStatus(@PathVariable String enclaveId) {
        TeeEnclaveEntity entity = teeService.getEnclaveStatus(enclaveId);
        if (entity == null) {
            return Result.fail("Enclave not found: " + enclaveId);
        }
        CreateEnclaveResponse response = new CreateEnclaveResponse();
        response.setEnclaveId(entity.getEnclaveId());
        response.setStatus(entity.getStatus());
        response.setMeasurementHash(entity.getMeasurementHash());
        return Result.success(response);
    }

    @PostMapping("/enclaves/{enclaveId}/channel")
    public Result<ChannelResponse> establishChannel(@PathVariable String enclaveId) {
        SecureChannel channel = teeService.establishChannel(enclaveId);
        ChannelResponse response = TeeAssembler.toChannelResponse(channel);
        return Result.success(response);
    }

    @DeleteMapping("/enclaves/{enclaveId}")
    public Result<Void> stopEnclave(@PathVariable String enclaveId) {
        teeService.stopEnclave(enclaveId);
        return Result.success(null);
    }
}
