package com.iotplatform.protocol.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.iotplatform.common.dto.PageQuery;
import com.iotplatform.common.dto.PageResult;
import com.iotplatform.common.dto.Result;
import com.iotplatform.protocol.dto.AdapterCreateDTO;
import com.iotplatform.protocol.dto.ProtocolDataDTO;
import com.iotplatform.protocol.entity.ProtocolAdapter;
import com.iotplatform.protocol.service.ProtocolService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/protocol")
@RequiredArgsConstructor
public class ProtocolController {

    private final ProtocolService protocolService;

    @PostMapping("/adapters")
    public Mono<Result<ProtocolAdapter>> registerAdapter(@Valid @RequestBody AdapterCreateDTO dto) {
        return protocolService.registerAdapter(dto)
                .map(Result::success);
    }

    @GetMapping("/adapters/{adapterId}")
    public Mono<Result<ProtocolAdapter>> getAdapter(@PathVariable String adapterId) {
        return protocolService.getAdapter(adapterId)
                .map(Result::success);
    }

    @GetMapping("/adapters")
    public Mono<Result<PageResult<ProtocolAdapter>>> listAdapters(
            @RequestParam(required = false) String protocolType,
            @RequestParam(required = false) Boolean enabled,
            @ModelAttribute PageQuery pageQuery) {
        return protocolService.listAdapters(protocolType, enabled,
                        pageQuery.getPageNum(), pageQuery.getPageSize())
                .map(page -> {
                    PageResult<ProtocolAdapter> pageResult = new PageResult<>(
                            page.getRecords(),
                            page.getTotal(),
                            page.getPages(),
                            page.getCurrent(),
                            page.getSize()
                    );
                    return Result.success(pageResult);
                });
    }

    @GetMapping("/adapters/type/{protocolType}")
    public Mono<Result<List<ProtocolAdapter>>> getAdaptersByProtocol(@PathVariable String protocolType) {
        return protocolService.getAdaptersByProtocol(protocolType)
                .map(Result::success);
    }

    @PostMapping("/convert/standard")
    public Mono<Result<String>> convertToStandardFormat(@Valid @RequestBody ProtocolDataDTO data) {
        return protocolService.convertToStandardFormat(data)
                .map(Result::success);
    }

    @PostMapping("/convert/from-standard")
    public Mono<Result<ProtocolDataDTO>> convertFromStandardFormat(
            @RequestParam String standardData,
            @RequestParam String targetProtocol) {
        return protocolService.convertFromStandardFormat(standardData, targetProtocol)
                .map(Result::success);
    }

    @PostMapping("/read")
    public Mono<Result<Map<String, Object>>> readData(@Valid @RequestBody ProtocolDataDTO data) {
        return protocolService.readData(data)
                .map(Result::success);
    }

    @PostMapping("/write")
    public Mono<Result<Void>> writeData(@Valid @RequestBody ProtocolDataDTO data) {
        return protocolService.writeData(data)
                .then(Mono.just(Result.success(null)));
    }

    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> subscribeData(
            @RequestParam String deviceId,
            @RequestParam String protocolType) {
        ProtocolDataDTO dto = new ProtocolDataDTO();
        dto.setDeviceId(deviceId);
        dto.setProtocolType(protocolType);
        return protocolService.subscribeData(dto);
    }

    @PostMapping("/adapters/{adapterId}/enable")
    public Mono<Result<Void>> enableAdapter(@PathVariable String adapterId) {
        return protocolService.enableAdapter(adapterId)
                .then(Mono.just(Result.success(null)));
    }

    @PostMapping("/adapters/{adapterId}/disable")
    public Mono<Result<Void>> disableAdapter(@PathVariable String adapterId) {
        return protocolService.disableAdapter(adapterId)
                .then(Mono.just(Result.success(null)));
    }

    @DeleteMapping("/adapters/{adapterId}")
    public Mono<Result<Void>> deleteAdapter(@PathVariable String adapterId) {
        return protocolService.deleteAdapter(adapterId)
                .then(Mono.just(Result.success(null)));
    }

    @GetMapping("/stats")
    public Mono<Result<Map<String, Integer>>> getProtocolStats() {
        return protocolService.getProtocolStats()
                .map(Result::success);
    }
}
