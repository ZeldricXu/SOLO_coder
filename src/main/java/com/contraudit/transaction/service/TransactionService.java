package com.contraudit.transaction.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.contraudit.common.BusinessException;
import com.contraudit.common.ErrorCode;
import com.contraudit.transaction.entity.PendingTransaction;
import com.contraudit.transaction.entity.SigningPolicy;
import com.contraudit.transaction.entity.TransactionTemplate;
import com.contraudit.transaction.mapper.PendingTransactionMapper;
import com.contraudit.transaction.mapper.SigningPolicyMapper;
import com.contraudit.transaction.mapper.TransactionTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionTemplateMapper templateMapper;
    private final PendingTransactionMapper pendingTxMapper;
    private final SigningPolicyMapper policyMapper;

    @Transactional(rollbackFor = Exception.class)
    public TransactionTemplate createTemplate(TransactionTemplate template) {
        template.setStatus(1);
        templateMapper.insert(template);
        log.info("Created transaction template: {}", template.getId());
        return template;
    }

    public TransactionTemplate getTemplate(String id) {
        TransactionTemplate template = templateMapper.selectById(id);
        if (template == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "template not found");
        }
        return template;
    }

    public List<TransactionTemplate> listTemplates(String chainType, String txType) {
        LambdaQueryWrapper<TransactionTemplate> wrapper = new LambdaQueryWrapper<>();
        if (chainType != null) {
            wrapper.eq(TransactionTemplate::getChainType, chainType);
        }
        if (txType != null) {
            wrapper.eq(TransactionTemplate::getTxType, txType);
        }
        wrapper.eq(TransactionTemplate::getStatus, 1);
        return templateMapper.selectList(wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public PendingTransaction constructTransaction(String fromAddress, String toAddress,
                                                   BigDecimal value, String data,
                                                   Long nonce, Long gasLimit,
                                                   BigDecimal gasPrice, String chainType,
                                                   String policyId, String templateId) {
        SigningPolicy policy = null;
        if (policyId != null) {
            policy = policyMapper.selectById(policyId);
            if (policy == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "signing policy not found");
            }
        }

        PendingTransaction tx = new PendingTransaction();
        tx.setChainType(chainType);
        tx.setFromAddress(fromAddress);
        tx.setToAddress(toAddress);
        tx.setValue(value != null ? value : BigDecimal.ZERO);
        tx.setData(data);
        tx.setNonce(nonce != null ? nonce : 0L);
        tx.setGasLimit(gasLimit != null ? gasLimit : 21000L);
        tx.setGasPrice(gasPrice != null ? gasPrice : BigDecimal.valueOf(20000000000L));
        tx.setTxType(2);
        tx.setStatus("CREATED");
        tx.setTemplateId(templateId);

        pendingTxMapper.insert(tx);
        log.info("Constructed transaction: {}", tx.getId());

        return tx;
    }

    @Transactional(rollbackFor = Exception.class)
    public PendingTransaction constructFromTemplate(String templateId, String fromAddress,
                                                    Map<String, Object> params,
                                                    BigDecimal gasPrice, Long nonce) {
        TransactionTemplate template = getTemplate(templateId);

        String data = encodeFunctionData(template.getMethodAbi(), template.getMethodName(), params);

        return constructTransaction(
                fromAddress,
                template.getContractAddress(),
                template.getValue(),
                data,
                nonce,
                template.getGasLimit(),
                gasPrice != null ? gasPrice : template.getGasPrice(),
                template.getChainType(),
                null,
                templateId
        );
    }

    @Transactional(rollbackFor = Exception.class)
    public PendingTransaction signTransaction(String txId, String privateKey) {
        PendingTransaction tx = getTransaction(txId);

        if (!"CREATED".equals(tx.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "transaction is not in CREATED status");
        }

        try {
            RawTransaction rawTransaction;
            if (tx.getTxType() == 2) {
                rawTransaction = RawTransaction.createTransaction(
                        BigInteger.valueOf(tx.getNonce()),
                        tx.getGasPrice().toBigInteger(),
                        BigInteger.valueOf(tx.getGasLimit()),
                        tx.getToAddress(),
                        tx.getValue().toBigInteger(),
                        tx.getData() != null ? tx.getData() : ""
                );
            } else {
                rawTransaction = RawTransaction.createEtherTransaction(
                        BigInteger.valueOf(tx.getNonce()),
                        tx.getGasPrice().toBigInteger(),
                        BigInteger.valueOf(tx.getGasLimit()),
                        tx.getToAddress(),
                        tx.getValue().toBigInteger()
                );
            }

            org.web3j.crypto.Credentials credentials = org.web3j.crypto.Credentials.create(privateKey);
            byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, credentials);
            String signedTx = Numeric.toHexString(signedMessage);
            String txHash = org.web3j.crypto.Hash.sha3(signedTx);

            tx.setSignedTx(signedTx);
            tx.setTxHash(txHash);
            tx.setStatus("SIGNED");
            pendingTxMapper.updateById(tx);

            log.info("Signed transaction: {}, hash: {}", txId, txHash);

            return tx;
        } catch (Exception e) {
            log.error("Failed to sign transaction", e);
            throw new BusinessException(ErrorCode.TX_SIGNING_FAILED);
        }
    }

    public PendingTransaction getTransaction(String txId) {
        PendingTransaction tx = pendingTxMapper.selectById(txId);
        if (tx == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "transaction not found");
        }
        return tx;
    }

    public List<PendingTransaction> listTransactions(String fromAddress, String status, String chainType) {
        LambdaQueryWrapper<PendingTransaction> wrapper = new LambdaQueryWrapper<>();
        if (fromAddress != null) {
            wrapper.eq(PendingTransaction::getFromAddress, fromAddress);
        }
        if (status != null) {
            wrapper.eq(PendingTransaction::getStatus, status);
        }
        if (chainType != null) {
            wrapper.eq(PendingTransaction::getChainType, chainType);
        }
        wrapper.orderByDesc(PendingTransaction::getCreatedAt);
        return pendingTxMapper.selectList(wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public PendingTransaction updateTransactionStatus(String txId, String status, String txHash, Long blockNumber) {
        PendingTransaction tx = getTransaction(txId);
        tx.setStatus(status);
        if (txHash != null) {
            tx.setTxHash(txHash);
        }
        if (blockNumber != null) {
            tx.setBlockNumber(blockNumber);
        }
        pendingTxMapper.updateById(tx);
        return tx;
    }

    @Transactional(rollbackFor = Exception.class)
    public SigningPolicy createSigningPolicy(SigningPolicy policy) {
        policy.setStatus(1);
        policyMapper.insert(policy);
        log.info("Created signing policy: {}", policy.getId());
        return policy;
    }

    public SigningPolicy getSigningPolicy(String id) {
        SigningPolicy policy = policyMapper.selectById(id);
        if (policy == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "signing policy not found");
        }
        return policy;
    }

    public List<SigningPolicy> listSigningPolicies(String chainType, String policyType) {
        LambdaQueryWrapper<SigningPolicy> wrapper = new LambdaQueryWrapper<>();
        if (chainType != null) {
            wrapper.eq(SigningPolicy::getChainType, chainType);
        }
        if (policyType != null) {
            wrapper.eq(SigningPolicy::getPolicyType, policyType);
        }
        wrapper.eq(SigningPolicy::getStatus, 1);
        return policyMapper.selectList(wrapper);
    }

    private String encodeFunctionData(String abi, String methodName, Map<String, Object> params) {
        try {
            Function function = new Function(
                    methodName,
                    List.of(),
                    List.of(new TypeReference<Type>() {})
            );
            return FunctionEncoder.encode(function);
        } catch (Exception e) {
            log.warn("Failed to encode function data, returning empty", e);
            return "";
        }
    }
}
