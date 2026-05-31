package com.web3platform.txbuilder.util;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.http.HttpService;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class NonceManager {

    private final ChainIdResolver chainIdResolver;

    private final Map<String, Web3j> web3jClients = new ConcurrentHashMap<>();

    private final Cache<String, BigInteger> nonceCache = CacheBuilder.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    private final Cache<String, String> rpcUrlCache = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();

    public BigInteger getNonce(String chainId, String address) {
        String cacheKey = chainId + ":" + address.toLowerCase();
        try {
            return nonceCache.get(cacheKey, () -> fetchNonceFromChain(chainId, address));
        } catch (ExecutionException e) {
            log.warn("Failed to get nonce from cache for address {} on chain {}", address, chainId, e);
            return fetchNonceFromChain(chainId, address);
        }
    }

    public BigInteger getNextNonce(String chainId, String address) {
        String cacheKey = chainId + ":" + address.toLowerCase();
        BigInteger currentNonce = getNonce(chainId, address);
        BigInteger nextNonce = currentNonce.add(BigInteger.ONE);
        nonceCache.put(cacheKey, nextNonce);
        return currentNonce;
    }

    public void incrementNonce(String chainId, String address) {
        String cacheKey = chainId + ":" + address.toLowerCase();
        BigInteger currentNonce = nonceCache.getIfPresent(cacheKey);
        if (currentNonce != null) {
            nonceCache.put(cacheKey, currentNonce.add(BigInteger.ONE));
        }
    }

    public void resetNonce(String chainId, String address) {
        String cacheKey = chainId + ":" + address.toLowerCase();
        nonceCache.invalidate(cacheKey);
    }

    public void registerRpcUrl(String chainId, String rpcUrl) {
        rpcUrlCache.put(chainId, rpcUrl);
        web3jClients.put(chainId, Web3j.build(new HttpService(rpcUrl)));
    }

    private BigInteger fetchNonceFromChain(String chainId, String address) {
        try {
            Web3j web3j = getWeb3j(chainId);
            return web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING)
                    .send()
                    .getTransactionCount();
        } catch (IOException e) {
            log.error("Failed to fetch nonce from chain {} for address {}", chainId, address, e);
            throw new RuntimeException("Failed to fetch nonce from chain: " + e.getMessage(), e);
        }
    }

    private Web3j getWeb3j(String chainId) {
        Web3j web3j = web3jClients.get(chainId);
        if (web3j == null) {
            String rpcUrl = rpcUrlCache.getIfPresent(chainId);
            if (rpcUrl == null) {
                rpcUrl = getDefaultRpcUrl(chainId);
            }
            if (rpcUrl != null) {
                web3j = Web3j.build(new HttpService(rpcUrl));
                web3jClients.put(chainId, web3j);
            } else {
                throw new RuntimeException("No RPC URL configured for chainId: " + chainId);
            }
        }
        return web3j;
    }

    private String getDefaultRpcUrl(String chainId) {
        try {
            long id = chainIdResolver.resolveToLong(chainId);
            return switch ((int) id) {
                case 1 -> "https://eth.llamarpc.com";
                case 5 -> "https://goerli.infura.io/v3/9aa3d95b3bc440fa88ea12eaa4456161";
                case 11155111 -> "https://sepolia.infura.io/v3/9aa3d95b3bc440fa88ea12eaa4456161";
                case 137 -> "https://polygon.llamarpc.com";
                case 56 -> "https://bsc-dataseed.binance.org";
                case 42161 -> "https://arbitrum.llamarpc.com";
                case 10 -> "https://optimism.llamarpc.com";
                default -> null;
            };
        } catch (Exception e) {
            log.warn("Cannot resolve default RPC URL for chainId: {}", chainId);
            return null;
        }
    }
}
