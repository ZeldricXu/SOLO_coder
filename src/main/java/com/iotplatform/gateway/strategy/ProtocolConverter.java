package com.iotplatform.gateway.strategy;

import com.iotplatform.gateway.dto.ProtocolConvertRequest;

public interface ProtocolConverter {

    String convert(ProtocolConvertRequest request);

    String getSourceProtocol();

    String getTargetProtocol();
}
