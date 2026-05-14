package com.paycenter.service.impl;

import com.paycenter.dto.PaymentRequest;
import com.paycenter.dto.PaymentResponse;
import com.paycenter.entity.MerchantConfig;
import com.paycenter.entity.PaymentChannel;
import com.paycenter.entity.Transaction;
import com.paycenter.enums.ChannelType;
import com.paycenter.enums.TransactionStatus;
import com.paycenter.enums.TransactionType;
import com.paycenter.exception.BusinessException;
import com.paycenter.repository.TransactionRepository;
import com.paycenter.service.*;
import com.paycenter.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class TransactionServiceImpl implements TransactionService {

    private static final Logger logger = LoggerFactory.getLogger(TransactionServiceImpl.class);

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private PaymentChannelService paymentChannelService;

    @Autowired
    private TransactionStatusService transactionStatusService;

    @Autowired
    private ChannelFailoverService channelFailoverService;

    @Autowired
    private MerchantConfigService merchantConfigService;

    @Autowired
    private PaymentTaskQueueService paymentTaskQueueService;

    @Override
    @Transactional
    public PaymentResponse createPayment(PaymentRequest request) {
        if (transactionRepository.findByOrderNo(request.getOrderNo()).isPresent()) {
            throw new BusinessException("订单号已存在");
        }

        ChannelType channelType;
        try {
            channelType = ChannelType.valueOf(request.getChannel().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的支付渠道类型");
        }

        PaymentChannel channel = selectChannel(request.getMerchantId(), channelType);
        
        String transactionId = IdGenerator.generateTransactionId();
        
        Transaction transaction = Transaction.builder()
                .transactionId(transactionId)
                .merchantId(request.getMerchantId())
                .orderNo(request.getOrderNo())
                .amount(request.getAmount())
                .channelId(channel.getChannelId())
                .transactionType(TransactionType.PAYMENT)
                .status(TransactionStatus.PENDING)
                .build();

        transactionRepository.save(transaction);
        transactionStatusService.logStatusChange(transactionId, null, TransactionStatus.PENDING, "创建支付订单");

        submitPaymentTask(transaction, channel, request);

        return PaymentResponse.builder()
                .transactionId(transactionId)
                .status("pending")
                .build();
    }

    private PaymentChannel selectChannel(String merchantId, ChannelType channelType) {
        Optional<PaymentChannel> primaryChannel = channelFailoverService.getPrimaryChannel(merchantId, channelType);
        
        if (primaryChannel.isPresent()) {
            logger.debug("选择主渠道: merchantId={}, channelType={}, channelId={}",
                    merchantId, channelType, primaryChannel.get().getChannelId());
            return primaryChannel.get();
        }

        if (channelFailoverService.shouldSwitchChannel(merchantId, channelType)) {
            Optional<PaymentChannel> backupChannel = channelFailoverService.getBackupChannel(merchantId, channelType);
            if (backupChannel.isPresent()) {
                logger.info("切换到备用渠道: merchantId={}, channelType={}, channelId={}",
                        merchantId, channelType, backupChannel.get().getChannelId());
                return backupChannel.get();
            }
        }

        throw new BusinessException("支付渠道未配置");
    }

    private void submitPaymentTask(Transaction transaction, PaymentChannel channel, PaymentRequest request) {
        MerchantConfig config = merchantConfigService.getOrCreateDefaultConfig(transaction.getMerchantId());
        
        boolean useAsync = paymentTaskQueueService.isAsyncEnabled(config);
        
        if (useAsync) {
            logger.info("提交支付任务到Redis队列: transactionId={}, merchantId={}",
                    transaction.getTransactionId(), transaction.getMerchantId());
            paymentTaskQueueService.submitPaymentTask(transaction, channel, request);
        } else {
            logger.debug("使用同步支付模式: transactionId={}", transaction.getTransactionId());
        }
    }

    public void handleChannelFailure(String merchantId, String transactionId, ChannelType channelType, 
                                     String failedChannelId, String reason) {
        logger.warn("处理渠道故障: merchantId={}, transactionId={}, channelType={}, channelId={}",
                merchantId, transactionId, channelType, failedChannelId);
        
        channelFailoverService.recordChannelFailure(merchantId, transactionId, channelType, failedChannelId, reason);
        
        if (channelFailoverService.shouldSwitchChannel(merchantId, channelType)) {
            Optional<PaymentChannel> backupChannel = channelFailoverService.getBackupChannel(merchantId, channelType);
            if (backupChannel.isPresent()) {
                logger.info("渠道故障，切换到备用渠道: merchantId={}, transactionId={}, backupChannelId={}",
                        merchantId, transactionId, backupChannel.get().getChannelId());
                
                Optional<Transaction> transactionOpt = transactionRepository.findById(transactionId);
                if (transactionOpt.isPresent()) {
                    Transaction transaction = transactionOpt.get();
                    transaction.setChannelId(backupChannel.get().getChannelId());
                    transactionRepository.save(transaction);
                }
            }
        }
    }

    public void handleChannelRecovery(String merchantId, ChannelType channelType, String channelId) {
        channelFailoverService.recordChannelRecovery(merchantId, channelType, channelId);
        logger.info("渠道恢复: merchantId={}, channelType={}, channelId={}",
                merchantId, channelType, channelId);
    }

    @Override
    @Transactional
    public Transaction confirmPayment(String transactionId, boolean success, String notifyData) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new BusinessException("交易不存在"));

        if (transaction.getStatus() != TransactionStatus.PENDING) {
            throw new BusinessException("交易状态不允许确认");
        }

        TransactionStatus oldStatus = transaction.getStatus();
        TransactionStatus newStatus = success ? TransactionStatus.SUCCESS : TransactionStatus.FAILED;
        
        transaction.setStatus(newStatus);
        transaction.setNotifyReceived(true);
        transaction.setNotifyData(notifyData);
        
        if (success) {
            transaction.setPaidAt(LocalDateTime.now());
            handleChannelRecovery(transaction.getMerchantId(), 
                    getChannelTypeFromChannelId(transaction.getChannelId()), 
                    transaction.getChannelId());
        } else {
            transaction.setFailureReason("支付失败");
            handleChannelFailure(transaction.getMerchantId(), transactionId,
                    getChannelTypeFromChannelId(transaction.getChannelId()),
                    transaction.getChannelId(), "支付失败");
        }

        transactionRepository.save(transaction);
        transactionStatusService.logStatusChange(transactionId, oldStatus, newStatus, 
                success ? "支付成功" : "支付失败");

        return transaction;
    }

    private ChannelType getChannelTypeFromChannelId(String channelId) {
        Optional<PaymentChannel> channel = paymentChannelService.getChannelById(channelId);
        return channel.map(PaymentChannel::getChannelType).orElse(ChannelType.ALIPAY);
    }

    @Override
    public Optional<Transaction> getTransactionById(String transactionId) {
        return transactionRepository.findById(transactionId);
    }

    @Override
    public Optional<Transaction> getTransactionByOrderNo(String orderNo) {
        return transactionRepository.findByOrderNo(orderNo);
    }

    @Override
    public List<Transaction> getTransactionsByMerchant(String merchantId, LocalDateTime start, LocalDateTime end) {
        return transactionRepository.findByMerchantIdAndCreatedAtBetween(merchantId, start, end);
    }

    @Override
    @Transactional
    public Transaction updateTransactionStatus(String transactionId, TransactionStatus status) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new BusinessException("交易不存在"));
        
        TransactionStatus oldStatus = transaction.getStatus();
        transaction.setStatus(status);
        
        transactionRepository.save(transaction);
        transactionStatusService.logStatusChange(transactionId, oldStatus, status, "状态更新");
        
        return transaction;
    }
}
