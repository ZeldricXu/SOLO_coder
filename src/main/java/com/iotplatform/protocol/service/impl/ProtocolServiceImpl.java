package com.iotplatform.protocol.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.iotplatform.common.exception.BusinessException;
import com.iotplatform.protocol.dto.AdapterCreateDTO;
import com.iotplatform.protocol.dto.ProtocolDataDTO;
import com.iotplatform.protocol.entity.ProtocolAdapter;
import com.iotplatform.protocol.mapper.ProtocolAdapterMapper;
import com.iotplatform.protocol.service.ProtocolService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProtocolServiceImpl implements ProtocolService {

    private final ProtocolAdapterMapper adapterMapper;
    private final MeterRegistry meterRegistry;

    private final Cache<String, ProtocolAdapter> adapterCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    private final Map<String, Sinks.Many<Map<String, Object>>> dataStreams = new ConcurrentHashMap<>();

    @Override
    @Transactional
    public Mono<ProtocolAdapter> registerAdapter(AdapterCreateDTO dto) {
        Timer.Sample sample = Timer.start(meterRegistry);
        return Mono.fromCallable(() -> {
            try {
                adapterMapper.findByAdapterId(dto.getAdapterId()).ifPresent(a -> {
                    throw new BusinessException(400, "适配器ID已存在: " + dto.getAdapterId());
                });

                ProtocolAdapter adapter = new ProtocolAdapter();
                adapter.setAdapterId(dto.getAdapterId());
                adapter.setAdapterName(dto.getAdapterName() != null ? dto.getAdapterName() : dto.getAdapterId());
                adapter.setProtocolType(dto.getProtocolType());
                adapter.setDriverClass(dto.getDriverClass());
                adapter.setConfigSchema(dto.getConfigSchema() != null ? JSONUtil.toJsonStr(dto.getConfigSchema()) : null);
                adapter.setEnabled(dto.getEnabled());
                adapter.setVersion(dto.getVersion() != null ? dto.getVersion() : "1.0.0");

                adapterMapper.insert(adapter);
                adapterCache.put(dto.getAdapterId(), adapter);

                log.info("Protocol adapter registered: {} ({})", dto.getAdapterId(), dto.getProtocolType());
                meterRegistry.counter("protocol.adapter.registered", "type", dto.getProtocolType()).increment();
                return adapter;
            } catch (Exception e) {
                log.error("Failed to register adapter: {}", e.getMessage(), e);
                meterRegistry.counter("protocol.adapter.register.failed").increment();
                throw e;
            } finally {
                sample.stop(meterRegistry.timer("protocol.adapter.register.latency"));
            }
        });
    }

    @Override
    public Mono<ProtocolAdapter> getAdapter(String adapterId) {
        return Mono.fromCallable(() -> {
            ProtocolAdapter cached = adapterCache.getIfPresent(adapterId);
            if (cached != null) {
                meterRegistry.counter("protocol.adapter.cache.hit").increment();
                return cached;
            }

            meterRegistry.counter("protocol.adapter.cache.miss").increment();
            ProtocolAdapter adapter = adapterMapper.findByAdapterId(adapterId)
                    .orElseThrow(() -> new BusinessException(404, "适配器不存在: " + adapterId));
            adapterCache.put(adapterId, adapter);
            return adapter;
        });
    }

    @Override
    public Mono<IPage<ProtocolAdapter>> listAdapters(String protocolType, Boolean enabled,
                                                     Integer pageNum, Integer pageSize) {
        return Mono.fromCallable(() -> {
            Page<ProtocolAdapter> page = new Page<>(pageNum, pageSize);
            return adapterMapper.selectAdapterPage(page, protocolType, enabled);
        });
    }

    @Override
    public Mono<List<ProtocolAdapter>> getAdaptersByProtocol(String protocolType) {
        return Mono.fromCallable(() -> adapterMapper.findByProtocolType(protocolType));
    }

    @Override
    public Mono<String> convertToStandardFormat(ProtocolDataDTO data) {
        return Mono.fromCallable(() -> {
            Map<String, Object> standard = new HashMap<>();
            standard.put("deviceId", data.getDeviceId());
            standard.put("protocolType", data.getProtocolType());
            standard.put("timestamp", data.getTimestamp() != null ? data.getTimestamp() : System.currentTimeMillis());
            standard.put("receivedAt", LocalDateTime.now().toString());

            Map<String, Object> parsedData = parseProtocolData(data);
            standard.put("data", parsedData);
            standard.put("rawPayload", data.getPayload());
            standard.put("headers", data.getHeaders());

            String result = JSONUtil.toJsonStr(standard);
            log.debug("Protocol data converted to standard format: {}", data.getDeviceId());
            meterRegistry.counter("protocol.convert.success", "type", data.getProtocolType()).increment();
            return result;
        });
    }

    @Override
    public Mono<ProtocolDataDTO> convertFromStandardFormat(String standardData, String targetProtocol) {
        return Mono.fromCallable(() -> {
            Map<String, Object> standard = JSONUtil.parseObj(standardData);
            ProtocolDataDTO dto = new ProtocolDataDTO();
            dto.setDeviceId((String) standard.get("deviceId"));
            dto.setProtocolType(targetProtocol);
            dto.setPayload(standardData);
            dto.setTimestamp(System.currentTimeMillis());

            log.debug("Standard data converted to {} format", targetProtocol);
            meterRegistry.counter("protocol.convert.from.success", "type", targetProtocol).increment();
            return dto;
        });
    }

    @Override
    public Mono<Map<String, Object>> readData(ProtocolDataDTO data) {
        return Mono.fromCallable(() -> {
            Map<String, Object> result = new HashMap<>();
            result.put("deviceId", data.getDeviceId());
            result.put("protocolType", data.getProtocolType());
            result.put("readAt", LocalDateTime.now().toString());
            result.put("success", true);
            result.put("data", parseProtocolData(data));

            meterRegistry.counter("protocol.read.success", "type", data.getProtocolType()).increment();
            return result;
        });
    }

    @Override
    public Mono<Void> writeData(ProtocolDataDTO data) {
        return Mono.fromRunnable(() -> {
            log.info("Writing data via {} to device {}: {}", data.getProtocolType(),
                    data.getDeviceId(), data.getPayload());
            meterRegistry.counter("protocol.write.success", "type", data.getProtocolType()).increment();
        });
    }

    @Override
    public Flux<Map<String, Object>> subscribeData(ProtocolDataDTO data) {
        String streamKey = data.getDeviceId() + ":" + data.getProtocolType();
        Sinks.Many<Map<String, Object>> sink = dataStreams.computeIfAbsent(streamKey,
                k -> Sinks.many().multicast().onBackpressureBuffer());

        Mono.delay(Duration.ofSeconds(1))
                .repeat()
                .take(10)
                .doOnNext(i -> {
                    Map<String, Object> message = new HashMap<>();
                    message.put("deviceId", data.getDeviceId());
                    message.put("protocolType", data.getProtocolType());
                    message.put("timestamp", System.currentTimeMillis());
                    message.put("sequence", i);
                    message.put("data", generateMockData(data.getProtocolType()));
                    sink.tryEmitNext(message);
                })
                .doOnComplete(() -> sink.tryEmitComplete())
                .subscribe();

        return sink.asFlux();
    }

    @Override
    @Transactional
    public Mono<Void> enableAdapter(String adapterId) {
        return getAdapter(adapterId)
                .doOnNext(adapter -> {
                    adapter.setEnabled(true);
                    adapterMapper.updateById(adapter);
                    adapterCache.put(adapterId, adapter);
                    log.info("Protocol adapter enabled: {}", adapterId);
                })
                .then();
    }

    @Override
    @Transactional
    public Mono<Void> disableAdapter(String adapterId) {
        return getAdapter(adapterId)
                .doOnNext(adapter -> {
                    adapter.setEnabled(false);
                    adapterMapper.updateById(adapter);
                    adapterCache.put(adapterId, adapter);
                    log.info("Protocol adapter disabled: {}", adapterId);
                })
                .then();
    }

    @Override
    @Transactional
    public Mono<Void> deleteAdapter(String adapterId) {
        return getAdapter(adapterId)
                .doOnNext(adapter -> {
                    adapterMapper.deleteById(adapter.getId());
                    adapterCache.invalidate(adapterId);
                    log.info("Protocol adapter deleted: {}", adapterId);
                })
                .then();
    }

    @Override
    public Mono<Map<String, Integer>> getProtocolStats() {
        return Mono.fromCallable(() -> {
            List<ProtocolAdapter> adapters = adapterMapper.findAllEnabled();
            Map<String, Integer> stats = new HashMap<>();
            for (ProtocolAdapter adapter : adapters) {
                stats.merge(adapter.getProtocolType(), 1, Integer::sum);
            }
            stats.put("total", adapters.size());
            return stats;
        });
    }

    private Map<String, Object> parseProtocolData(ProtocolDataDTO data) {
        Map<String, Object> parsed = new HashMap<>();
        String payload = data.getPayload();

        if (payload != null && !payload.isEmpty()) {
            try {
                if (JSONUtil.isJson(payload)) {
                    parsed.putAll(JSONUtil.parseObj(payload));
                } else {
                    parsed.put("rawValue", payload);
                }
            } catch (Exception e) {
                parsed.put("rawValue", payload);
                parsed.put("parseError", e.getMessage());
            }
        }

        if (data.getBinaryPayload() != null) {
            parsed.put("binarySize", data.getBinaryPayload().length);
        }

        return parsed;
    }

    private Map<String, Object> generateMockData(String protocolType) {
        Map<String, Object> data = new HashMap<>();
        data.put("temperature", 25.0 + Math.random() * 10);
        data.put("humidity", 40.0 + Math.random() * 20);
        data.put("pressure", 1013.0 + Math.random() * 5);
        data.put("protocol", protocolType);
        return data;
    }
}
