package com.supplychain.collaboration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.supplychain.common.entity.SupplierMessage;
import com.supplychain.common.util.IdGenerator;
import com.supplychain.collaboration.mapper.SupplierMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollaborationService {

    private final SupplierMessageMapper messageMapper;

    @Transactional
    public SupplierMessage sendMessage(SupplierMessage message) {
        message.setMessageId(IdGenerator.generateMessageId());
        message.setStatus("sent");
        message.setSentAt(LocalDateTime.now());
        if (message.getMessageType() == null) {
            message.setMessageType("normal");
        }
        messageMapper.insert(message);
        log.info("发送供应商消息: messageId={}, supplierId={}", message.getMessageId(), message.getSupplierId());
        return message;
    }

    public List<SupplierMessage> getMessagesBySupplier(String supplierId) {
        LambdaQueryWrapper<SupplierMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SupplierMessage::getSupplierId, supplierId)
               .orderByDesc(SupplierMessage::getSentAt);
        return messageMapper.selectList(wrapper);
    }

    public List<SupplierMessage> getMessagesByOrder(String orderId) {
        LambdaQueryWrapper<SupplierMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SupplierMessage::getRelatedOrderId, orderId)
               .orderByDesc(SupplierMessage::getSentAt);
        return messageMapper.selectList(wrapper);
    }

    @Transactional
    public SupplierMessage markAsRead(String messageId) {
        SupplierMessage message = messageMapper.selectById(messageId);
        if (message != null && !"read".equals(message.getStatus())) {
            message.setStatus("read");
            message.setReadAt(LocalDateTime.now());
            messageMapper.updateById(message);
        }
        return message;
    }

    public List<SupplierMessage> getUnreadMessages(String receiver) {
        LambdaQueryWrapper<SupplierMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SupplierMessage::getReceiver, receiver)
               .eq(SupplierMessage::getStatus, "sent")
               .orderByDesc(SupplierMessage::getSentAt);
        return messageMapper.selectList(wrapper);
    }

    public long getUnreadCount(String receiver) {
        LambdaQueryWrapper<SupplierMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SupplierMessage::getReceiver, receiver)
               .eq(SupplierMessage::getStatus, "sent");
        return messageMapper.selectCount(wrapper);
    }
}
