package com.solocoder.platform.multisig.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.solocoder.platform.persistence.entity.MultisigProposalEntity;
import com.solocoder.platform.persistence.entity.MultisigWalletEntity;
import com.solocoder.platform.persistence.mapper.MultisigProposalMapper;
import com.solocoder.platform.persistence.mapper.MultisigWalletMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultisigWalletService {

    private final MultisigWalletMapper multisigWalletMapper;
    private final MultisigProposalMapper multisigProposalMapper;

    @Transactional(rollbackFor = Exception.class)
    public MultisigWalletEntity createWallet(String chainId, String walletType, String name,
                                              Integer threshold, List<String> owners) {
        MultisigWalletEntity entity = new MultisigWalletEntity();
        entity.setWalletId(UUID.randomUUID().toString());
        entity.setChainId(chainId);
        entity.setWalletAddress("0x" + UUID.randomUUID().toString().replace("-", "").substring(0, 40));
        entity.setWalletType(walletType);
        entity.setName(name);
        entity.setThreshold(threshold);
        entity.setOwners(JSON.toJSONString(owners));
        entity.setOwnerCount(owners.size());
        entity.setNonce(0L);
        entity.setStatus("ACTIVE");
        entity.setCreatedBy("system");
        multisigWalletMapper.insert(entity);
        return entity;
    }

    @Transactional(rollbackFor = Exception.class)
    public MultisigProposalEntity createProposal(String walletId, String proposalType,
                                                  String title, String description,
                                                  String toAddress, BigDecimal value,
                                                  String data, Map<String, Object> gasSettings) {
        MultisigWalletEntity wallet = multisigWalletMapper.selectById(walletId);
        if (wallet == null) {
            throw new RuntimeException("钱包不存在: " + walletId);
        }

        MultisigProposalEntity proposal = new MultisigProposalEntity();
        proposal.setProposalId(UUID.randomUUID().toString());
        proposal.setWalletId(walletId);
        proposal.setChainId(wallet.getChainId());
        proposal.setProposalType(proposalType);
        proposal.setTitle(title);
        proposal.setDescription(description);
        proposal.setToAddress(toAddress);
        proposal.setValue(value);
        proposal.setData(data);
        proposal.setNonce(wallet.getNonce());
        proposal.setThreshold(wallet.getThreshold());
        proposal.setSignatureCount(0);
        proposal.setStatus("PENDING");
        proposal.setCreatedBy("system");
        multisigProposalMapper.insert(proposal);
        return proposal;
    }

    @Transactional(rollbackFor = Exception.class)
    public MultisigProposalEntity addSignature(String proposalId, String signer, String signature) {
        MultisigProposalEntity proposal = multisigProposalMapper.selectById(proposalId);
        if (proposal == null) {
            throw new RuntimeException("提案不存在: " + proposalId);
        }

        List<Map<String, String>> signatures = proposal.getSignatures() != null ?
                JSON.parseArray(proposal.getSignatures(), Map.class) : new java.util.ArrayList<>();
        signatures.add(Map.of("signer", signer, "signature", signature));
        proposal.setSignatures(JSON.toJSONString(signatures));
        proposal.setSignatureCount(signatures.size());

        if (signatures.size() >= proposal.getThreshold()) {
            proposal.setStatus("READY_TO_EXECUTE");
        }
        multisigProposalMapper.updateById(proposal);
        return proposal;
    }

    @Transactional(rollbackFor = Exception.class)
    public MultisigProposalEntity executeProposal(String proposalId) {
        MultisigProposalEntity proposal = multisigProposalMapper.selectById(proposalId);
        if (proposal == null) {
            throw new RuntimeException("提案不存在: " + proposalId);
        }

        if (proposal.getSignatureCount() < proposal.getThreshold()) {
            throw new RuntimeException("签名数量不足");
        }

        proposal.setStatus("EXECUTED");
        proposal.setTxHash("0x" + UUID.randomUUID().toString().replace("-", ""));
        proposal.setExecutedAt(LocalDateTime.now());
        multisigProposalMapper.updateById(proposal);

        MultisigWalletEntity wallet = multisigWalletMapper.selectById(proposal.getWalletId());
        if (wallet != null) {
            wallet.setNonce(wallet.getNonce() + 1);
            multisigWalletMapper.updateById(wallet);
        }
        return proposal;
    }

    public List<MultisigProposalEntity> getProposalsByWallet(String walletId, String status) {
        LambdaQueryWrapper<MultisigProposalEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MultisigProposalEntity::getWalletId, walletId);
        if (status != null) {
            wrapper.eq(MultisigProposalEntity::getStatus, status);
        }
        wrapper.orderByDesc(MultisigProposalEntity::getCreatedAt);
        return multisigProposalMapper.selectList(wrapper);
    }
}
