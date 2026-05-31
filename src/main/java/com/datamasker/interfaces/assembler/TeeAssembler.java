package com.datamasker.interfaces.assembler;

import com.datamasker.domain.tee.model.AttestationResult;
import com.datamasker.domain.tee.model.EnclaveInstance;
import com.datamasker.domain.tee.model.SecureChannel;
import com.datamasker.interfaces.dto.tee.AttestationResponse;
import com.datamasker.interfaces.dto.tee.ChannelResponse;
import com.datamasker.interfaces.dto.tee.CreateEnclaveResponse;

public class TeeAssembler {

    public static CreateEnclaveResponse toCreateEnclaveResponse(EnclaveInstance instance) {
        CreateEnclaveResponse response = new CreateEnclaveResponse();
        response.setEnclaveId(instance.getEnclaveId());
        response.setStatus(instance.getStatus());
        response.setMeasurementHash(instance.getMeasurementHash());
        return response;
    }

    public static AttestationResponse toAttestationResponse(AttestationResult result) {
        AttestationResponse response = new AttestationResponse();
        response.setEnclaveId(result.getEnclaveId());
        response.setVerified(result.isVerified());
        response.setMeasurementHash(result.getMeasurementHash());
        response.setExpectedHash(result.getExpectedHash());
        response.setSignatureValid(result.isSignatureValid());
        return response;
    }

    public static ChannelResponse toChannelResponse(SecureChannel channel) {
        ChannelResponse response = new ChannelResponse();
        response.setChannelId(channel.getChannelId());
        response.setEnclaveId(channel.getEnclaveId());
        response.setSessionKey(channel.getSessionKey());
        response.setActive(channel.isActive());
        return response;
    }
}
