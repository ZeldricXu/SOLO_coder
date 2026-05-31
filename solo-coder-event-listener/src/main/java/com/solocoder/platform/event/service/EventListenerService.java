package com.solocoder.platform.event.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solocoder.platform.persistence.entity.EventListenerConfigEntity;
import com.solocoder.platform.persistence.entity.EventLogEntity;
import com.solocoder.platform.persistence.mapper.EventListenerConfigMapper;
import com.solocoder.platform.persistence.mapper.EventLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventListenerService {

    private final EventListenerConfigMapper eventListenerConfigMapper;
    private final EventLogMapper eventLogMapper;

    @Transactional(rollbackFor = Exception.class)
    public EventListenerConfigEntity createListener(String chainId, String name,
                                                    String contractAddress, String contractAbi,
                                                    List<String> eventNames,
                                                    String callbackUrl, String callbackType,
                                                    Map<String, String> callbackHeaders,
                                                    Long startBlock) {
        EventListenerConfigEntity entity = new EventListenerConfigEntity();
        entity.setListenerId(UUID.randomUUID().toString());
        entity.setChainId(chainId);
        entity.setName(name);
        entity.setContractAddress(contractAddress);
        entity.setContractAbi(contractAbi);
        entity.setEventNames(JSON.toJSONString(eventNames));
        entity.setCallbackUrl(callbackUrl);
        entity.setCallbackType(callbackType);
        entity.setCallbackHeaders(JSON.toJSONString(callbackHeaders));
        entity.setStartBlock(startBlock);
        entity.setCurrentBlock(startBlock);
        entity.setMaxRetries(3);
        entity.setRetryInterval(5000);
        entity.setBatchSize(100);
        entity.setIsEnabled(1);
        entity.setStatus("ACTIVE");
        entity.setCreatedBy("system");
        eventListenerConfigMapper.insert(entity);
        return entity;
    }

    @Transactional(rollbackFor = Exception.class)
    public EventLogEntity saveEventLog(String chainId, String contractAddress,
                                        String eventName, String eventSignature,
                                        List<String> topics, String data,
                                        String txHash, Long blockNumber,
                                        String blockHash, Integer logIndex,
                                        Long timestamp, Map<String, Object> decodedData) {
        EventLogEntity entity = new EventLogEntity();
        entity.setChainId(chainId);
        entity.setContractAddress(contractAddress);
        entity.setEventName(eventName);
        entity.setEventSignature(eventSignature);
        if (topics != null && !topics.isEmpty()) {
            entity.setTopic0(topics.size() > 0 ? topics.get(0) : null);
            entity.setTopic1(topics.size() > 1 ? topics.get(1) : null);
            entity.setTopic2(topics.size() > 2 ? topics.get(2) : null);
            entity.setTopic3(topics.size() > 3 ? topics.get(3) : null);
        }
        entity.setData(data);
        entity.setDecodedData(JSON.toJSONString(decodedData));
        entity.setTxHash(txHash);
        entity.setBlockNumber(blockNumber);
        entity.setBlockHash(blockHash);
        entity.setLogIndex(logIndex);
        entity.setTimestamp(timestamp);
        entity.setProcessed(0);
        eventLogMapper.insert(entity);
        return entity;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean processEvent(Long eventId) {
        EventLogEntity event = eventLogMapper.selectById(eventId);
        if (event == null) {
            throw new RuntimeException("事件不存在: " + eventId);
        }

        try {
            executeCallback(event);
            event.setProcessed(1);
            event.setProcessedAt(LocalDateTime.now());
            event.setCallbackStatus("SUCCESS");
            eventLogMapper.updateById(event);
            return true;
        } catch (Exception e) {
            event.setCallbackStatus("FAILED");
            event.setCallbackError(e.getMessage());
            eventLogMapper.updateById(event);
            return false;
        }
    }

    private void executeCallback(EventLogEntity event) {
        log.info("执行回调: eventId={}, txHash={}", event.getId(), event.getTxHash());
    }

    public List<EventListenerConfigEntity> getActiveListeners(String chainId) {
        LambdaQueryWrapper<EventListenerConfigEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EventListenerConfigEntity::getChainId, chainId)
                .eq(EventListenerConfigEntity::getIsEnabled, 1)
                .eq(EventListenerConfigEntity::getStatus, "ACTIVE");
        return eventListenerConfigMapper.selectList(wrapper);
    }

    public List<EventLogEntity> getUnprocessedEvents(int limit) {
        Page<EventLogEntity> page = new Page<>(1, limit);
        LambdaQueryWrapper<EventLogEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EventLogEntity::getProcessed, 0)
                .orderByAsc(EventLogEntity::getBlockNumber);
        return eventLogMapper.selectPage(page, wrapper).getRecords();
    }
}
