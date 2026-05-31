package com.iotplatform.protocol.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.iotplatform.protocol.dto.AdapterCreateDTO;
import com.iotplatform.protocol.dto.ProtocolDataDTO;
import com.iotplatform.protocol.entity.ProtocolAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface ProtocolService {

    Mono<ProtocolAdapter> registerAdapter(AdapterCreateDTO dto);

    Mono<ProtocolAdapter> getAdapter(String adapterId);

    Mono<IPage<ProtocolAdapter>> listAdapters(String protocolType, Boolean enabled,
                                               Integer pageNum, Integer pageSize);

    Mono<List<ProtocolAdapter>> getAdaptersByProtocol(String protocolType);

    Mono<String> convertToStandardFormat(ProtocolDataDTO data);

    Mono<ProtocolDataDTO> convertFromStandardFormat(String standardData, String targetProtocol);

    Mono<Map<String, Object>> readData(ProtocolDataDTO data);

    Mono<Void> writeData(ProtocolDataDTO data);

    Flux<Map<String, Object>> subscribeData(ProtocolDataDTO data);

    Mono<Void> enableAdapter(String adapterId);

    Mono<Void> disableAdapter(String adapterId);

    Mono<Void> deleteAdapter(String adapterId);

    Mono<Map<String, Integer>> getProtocolStats();
}
