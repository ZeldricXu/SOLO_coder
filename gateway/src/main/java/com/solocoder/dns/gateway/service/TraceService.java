package com.solocoder.dns.gateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solocoder.dns.common.model.PageResult;
import com.solocoder.dns.common.util.IdGenerator;
import com.solocoder.dns.common.util.JsonUtils;
import com.solocoder.dns.gateway.model.RequestLog;
import com.solocoder.dns.persistence.entity.RequestTracePO;
import com.solocoder.dns.persistence.mapper.RequestTraceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TraceService {
    private final RequestTraceMapper traceMapper;

    public void saveTrace(RequestLog requestLog) {
        RequestTracePO po = new RequestTracePO();
        po.setTraceId(requestLog.getTraceId());
        po.setSpanId(requestLog.getSpanId());
        po.setParentSpanId(requestLog.getParentSpanId());
        po.setServiceName(requestLog.getServiceName());
        po.setOperation(requestLog.getOperation());
        po.setStartTime(requestLog.getStartTime());
        po.setDurationMs(requestLog.getDurationMs());
        po.setStatusCode(requestLog.getStatusCode());
        po.setErrorMessage(requestLog.getErrorMessage());
        po.setTags(JsonUtils.toJson(requestLog.getHeaders()));
        traceMapper.insert(po);
    }

    public RequestLog getTrace(String traceId) {
        LambdaQueryWrapper<RequestTracePO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RequestTracePO::getTraceId, traceId);
        List<RequestTracePO> traces = traceMapper.selectList(wrapper);
        if (traces.isEmpty()) {
            return null;
        }
        return toDomain(traces.get(0));
    }

    public PageResult<RequestLog> listTraces(int page, int size, String serviceName) {
        LambdaQueryWrapper<RequestTracePO> wrapper = new LambdaQueryWrapper<>();
        if (serviceName != null && !serviceName.isEmpty()) {
            wrapper.eq(RequestTracePO::getServiceName, serviceName);
        }
        wrapper.orderByDesc(RequestTracePO::getStartTime);
        Page<RequestTracePO> poPage = traceMapper.selectPage(new Page<>(page, size), wrapper);

        List<RequestLog> items = poPage.getRecords().stream()
                .map(this::toDomain)
                .collect(Collectors.toList());

        return new PageResult<>(items, poPage.getTotal(), page, size);
    }

    private RequestLog toDomain(RequestTracePO po) {
        RequestLog log = new RequestLog();
        log.setTraceId(po.getTraceId());
        log.setSpanId(po.getSpanId());
        log.setParentSpanId(po.getParentSpanId());
        log.setServiceName(po.getServiceName());
        log.setOperation(po.getOperation());
        log.setStartTime(po.getStartTime());
        log.setDurationMs(po.getDurationMs());
        log.setStatusCode(po.getStatusCode());
        log.setErrorMessage(po.getErrorMessage());
        if (po.getTags() != null) {
            log.setHeaders(JsonUtils.fromJson(po.getTags(), Map.class));
        }
        return log;
    }
}
