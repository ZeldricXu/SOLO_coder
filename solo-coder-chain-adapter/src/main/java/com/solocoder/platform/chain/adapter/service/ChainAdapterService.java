package com.solocoder.platform.chain.adapter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.solocoder.platform.persistence.entity.RpcNodeEntity;
import com.solocoder.platform.persistence.mapper.RpcNodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChainAdapterService {

    private final RpcNodeMapper rpcNodeMapper;

    public RpcNodeEntity getAvailableNode(String chainId) {
        LambdaQueryWrapper<RpcNodeEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RpcNodeEntity::getChainId, chainId)
                .eq(RpcNodeEntity::getIsEnabled, 1)
                .eq(RpcNodeEntity::getHealthStatus, "HEALTHY")
                .orderByAsc(RpcNodeEntity::getPriority)
                .last("LIMIT 1");
        return rpcNodeMapper.selectOne(wrapper);
    }

    public List<RpcNodeEntity> getAllNodes(String chainId) {
        LambdaQueryWrapper<RpcNodeEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RpcNodeEntity::getChainId, chainId)
                .orderByAsc(RpcNodeEntity::getPriority);
        return rpcNodeMapper.selectList(wrapper);
    }

    public BigInteger getBlockNumber(String chainId) {
        return BigInteger.valueOf(18000000L);
    }

    public Map<String, Object> getBlockByNumber(String chainId, Long blockNumber) {
        return Map.of(
                "number", "0x" + Long.toHexString(blockNumber),
                "hash", "0x" + "blockhash" + blockNumber,
                "timestamp", "0x" + Long.toHexString(System.currentTimeMillis() / 1000),
                "gasUsed", "0xe4e1c0",
                "gasLimit", "0x1c9c380"
        );
    }

    public Map<String, Object> getTransactionByHash(String chainId, String txHash) {
        return Map.of(
                "hash", txHash,
                "from", "0xabc123...",
                "to", "0xdef456...",
                "value", "0x0",
                "gasPrice", "0x3b9aca00"
        );
    }

    public String sendRawTransaction(String chainId, String signedTx) {
        return "0x" + System.currentTimeMillis();
    }

    public BigInteger getTransactionCount(String chainId, String address) {
        return BigInteger.valueOf(100);
    }

    public BigDecimal getBalance(String chainId, String address) {
        return new BigDecimal("1000000000000000000");
    }

    public Map<String, Object> callContract(String chainId, String to, String data) {
        return Map.of("result", "0x");
    }
}
