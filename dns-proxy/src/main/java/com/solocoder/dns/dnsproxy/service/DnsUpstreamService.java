package com.solocoder.dns.dnsproxy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.solocoder.dns.common.exception.ResourceNotFoundException;
import com.solocoder.dns.common.util.IdGenerator;
import com.solocoder.dns.dnsproxy.model.DnsUpstream;
import com.solocoder.dns.persistence.entity.DnsUpstreamPO;
import com.solocoder.dns.persistence.mapper.DnsUpstreamMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DnsUpstreamService {
    private final DnsUpstreamMapper mapper;

    public DnsUpstream createUpstream(DnsUpstream upstream) {
        upstream.setId(IdGenerator.generateId("upstream"));
        upstream.setCreatedAt(LocalDateTime.now());
        upstream.setUpdatedAt(LocalDateTime.now());
        mapper.insert(toPO(upstream));
        log.info("DNS upstream created: {} ({})", upstream.getId(), upstream.getName());
        return upstream;
    }

    public DnsUpstream updateUpstream(DnsUpstream upstream) {
        DnsUpstreamPO existing = mapper.selectById(upstream.getId());
        if (existing == null) {
            throw new ResourceNotFoundException("DNS Upstream", upstream.getId());
        }
        upstream.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(toPO(upstream));
        log.info("DNS upstream updated: {}", upstream.getId());
        return upstream;
    }

    public DnsUpstream getUpstream(String id) {
        DnsUpstreamPO po = mapper.selectById(id);
        if (po == null) {
            throw new ResourceNotFoundException("DNS Upstream", id);
        }
        return toDomain(po);
    }

    public List<DnsUpstream> getAllUpstreams() {
        return mapper.selectList(null).stream().map(this::toDomain).collect(Collectors.toList());
    }

    public List<DnsUpstream> getEnabledUpstreams() {
        LambdaQueryWrapper<DnsUpstreamPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DnsUpstreamPO::getEnabled, true);
        wrapper.orderByAsc(DnsUpstreamPO::getPriority);
        return mapper.selectList(wrapper).stream().map(this::toDomain).collect(Collectors.toList());
    }

    public void deleteUpstream(String id) {
        mapper.deleteById(id);
        log.info("DNS upstream deleted: {}", id);
    }

    private DnsUpstreamPO toPO(DnsUpstream upstream) {
        DnsUpstreamPO po = new DnsUpstreamPO();
        po.setId(upstream.getId());
        po.setName(upstream.getName());
        po.setHost(upstream.getHost());
        po.setPort(upstream.getPort());
        po.setPriority(upstream.getPriority());
        po.setWeight(upstream.getWeight());
        po.setProtocol(upstream.getProtocol());
        po.setEnabled(upstream.getEnabled());
        po.setTimeoutMs(upstream.getTimeoutMs());
        po.setMaxRetries(upstream.getMaxRetries());
        po.setCreatedAt(upstream.getCreatedAt());
        po.setUpdatedAt(upstream.getUpdatedAt());
        return po;
    }

    private DnsUpstream toDomain(DnsUpstreamPO po) {
        DnsUpstream upstream = new DnsUpstream();
        upstream.setId(po.getId());
        upstream.setName(po.getName());
        upstream.setHost(po.getHost());
        upstream.setPort(po.getPort());
        upstream.setPriority(po.getPriority());
        upstream.setWeight(po.getWeight());
        upstream.setProtocol(po.getProtocol());
        upstream.setEnabled(po.getEnabled());
        upstream.setTimeoutMs(po.getTimeoutMs());
        upstream.setMaxRetries(po.getMaxRetries());
        upstream.setCreatedAt(po.getCreatedAt());
        upstream.setUpdatedAt(po.getUpdatedAt());
        return upstream;
    }
}
