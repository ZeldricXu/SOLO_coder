package com.paycenter.service.impl;

import com.paycenter.dto.RefundRequest;
import com.paycenter.dto.RefundResponse;
import com.paycenter.entity.Refund;
import com.paycenter.entity.Transaction;
import com.paycenter.enums.RefundStatus;
import com.paycenter.enums.TransactionStatus;
import com.paycenter.exception.BusinessException;
import com.paycenter.repository.RefundRepository;
import com.paycenter.repository.TransactionRepository;
import com.paycenter.service.AccountService;
import com.paycenter.service.RefundService;
import com.paycenter.service.TransactionStatService;
import com.paycenter.service.TransactionStatusService;
import com.paycenter.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class RefundServiceImpl implements RefundService {

    private static final Logger logger = LoggerFactory.getLogger(RefundServiceImpl.class);

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransactionStatusService transactionStatusService;

    @Autowired
    private TransactionStatService transactionStatService;

    @Override
    @Transactional
    public RefundResponse createRefund(RefundRequest request) {
        Transaction transaction = transactionRepository.findById(request.getTransactionId())
                .orElseThrow(() -> new BusinessException("交易不存在"));

        if (transaction.getStatus() != TransactionStatus.SUCCESS && 
            transaction.getStatus() != TransactionStatus.PARTIAL_REFUND) {
            throw new BusinessException("交易状态不支持退款");
        }

        BigDecimal maxRefundable = transaction.getAmount().subtract(transaction.getRefundedAmount());
        if (request.getRefundAmount().compareTo(maxRefundable) > 0) {
            throw new BusinessException("退款金额超过可退款金额，最多可退: " + maxRefundable);
        }

        String refundId = IdGenerator.generateRefundId();
        
        Refund refund = Refund.builder()
                .refundId(refundId)
                .transactionId(request.getTransactionId())
                .refundAmount(request.getRefundAmount())
                .refundReason(request.getRefundReason())
                .refundStatus(RefundStatus.PENDING)
                .build();

        refundRepository.save(refund);

        return RefundResponse.builder()
                .refundId(refundId)
                .status("processing")
                .build();
    }

    @Override
    @Transactional
    public Refund executeRefund(String refundId, boolean success) {
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new BusinessException("退款记录不存在"));

        if (refund.getRefundStatus() != RefundStatus.PENDING) {
            throw new BusinessException("退款已处理，不允许重复执行");
        }

        refund.setRefundStatus(RefundStatus.PROCESSING);
        refundRepository.save(refund);

        Transaction transaction = transactionRepository.findById(refund.getTransactionId())
                .orElseThrow(() -> new BusinessException("关联交易不存在"));

        try {
            if (success) {
                refund.setRefundStatus(RefundStatus.SUCCESS);
                refund.setRefundedAt(LocalDateTime.now());

                BigDecimal newRefundedAmount = transaction.getRefundedAmount().add(refund.getRefundAmount());
                transaction.setRefundedAmount(newRefundedAmount);

                if (newRefundedAmount.compareTo(transaction.getAmount()) == 0) {
                    TransactionStatus oldStatus = transaction.getStatus();
                    transaction.setStatus(TransactionStatus.FULL_REFUND);
                    transactionStatusService.logStatusChange(
                            transaction.getTransactionId(), oldStatus, TransactionStatus.FULL_REFUND, "全额退款");
                } else {
                    TransactionStatus oldStatus = transaction.getStatus();
                    transaction.setStatus(TransactionStatus.PARTIAL_REFUND);
                    transactionStatusService.logStatusChange(
                            transaction.getTransactionId(), oldStatus, TransactionStatus.PARTIAL_REFUND, "部分退款");
                }

                accountService.withdraw(transaction.getMerchantId(), refund.getRefundAmount(), "退款扣减");

                transactionStatService.updateRefundStats(transaction.getMerchantId(), LocalDate.now());

                logger.info("退款成功: refundId={}, transactionId={}, amount={}", 
                        refundId, refund.getTransactionId(), refund.getRefundAmount());
            } else {
                refund.setRefundStatus(RefundStatus.FAILED);
                refund.setFailureReason("渠道退款失败");
                logger.warn("退款失败: refundId={}", refundId);
            }

        } catch (Exception e) {
            logger.error("退款执行异常: refundId={}", refundId, e);
            refund.setRefundStatus(RefundStatus.FAILED);
            refund.setFailureReason(e.getMessage());
            throw new BusinessException("退款执行失败: " + e.getMessage());
        }

        transactionRepository.save(transaction);
        return refundRepository.save(refund);
    }

    @Override
    public Optional<Refund> getRefundById(String refundId) {
        return refundRepository.findById(refundId);
    }

    @Override
    public List<Refund> getRefundsByTransaction(String transactionId) {
        return refundRepository.findByTransactionId(transactionId);
    }
}
