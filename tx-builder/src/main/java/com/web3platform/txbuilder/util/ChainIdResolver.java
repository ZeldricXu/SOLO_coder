package com.web3platform.txbuilder.util;

import com.google.common.collect.ImmutableMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.Map;

@Slf4j
@Component
public class ChainIdResolver {

    private static final Map<String, BigInteger> CHAIN_NAME_TO_ID = ImmutableMap.<String, BigInteger>builder()
            .put("ethereum", BigInteger.valueOf(1))
            .put("eth", BigInteger.valueOf(1))
            .put("mainnet", BigInteger.valueOf(1))
            .put("sepolia", BigInteger.valueOf(11155111))
            .put("goerli", BigInteger.valueOf(5))
            .put("polygon", BigInteger.valueOf(137))
            .put("matic", BigInteger.valueOf(137))
            .put("mumbai", BigInteger.valueOf(80001))
            .put("amoy", BigInteger.valueOf(80002))
            .put("bsc", BigInteger.valueOf(56))
            .put("binance", BigInteger.valueOf(56))
            .put("bsctest", BigInteger.valueOf(97))
            .put("arbitrum", BigInteger.valueOf(42161))
            .put("arbitrum-one", BigInteger.valueOf(42161))
            .put("arbitrum-goerli", BigInteger.valueOf(421613))
            .put("optimism", BigInteger.valueOf(10))
            .put("optimism-mainnet", BigInteger.valueOf(10))
            .put("optimism-goerli", BigInteger.valueOf(420))
            .put("avalanche", BigInteger.valueOf(43114))
            .put("avax", BigInteger.valueOf(43114))
            .put("fuji", BigInteger.valueOf(43113))
            .put("base", BigInteger.valueOf(8453))
            .put("base-mainnet", BigInteger.valueOf(8453))
            .put("base-goerli", BigInteger.valueOf(84531))
            .put("linea", BigInteger.valueOf(59144))
            .put("zksync", BigInteger.valueOf(324))
            .put("zksync-era", BigInteger.valueOf(324))
            .put("scroll", BigInteger.valueOf(534352))
            .put("mantle", BigInteger.valueOf(5000))
            .put("berachain", BigInteger.valueOf(80085))
            .put("holesky", BigInteger.valueOf(17000))
            .build();

    private static final Map<BigInteger, String> CHAIN_ID_TO_NAME = ImmutableMap.<BigInteger, String>builder()
            .put(BigInteger.valueOf(1), "ethereum")
            .put(BigInteger.valueOf(11155111), "sepolia")
            .put(BigInteger.valueOf(5), "goerli")
            .put(BigInteger.valueOf(137), "polygon")
            .put(BigInteger.valueOf(80001), "mumbai")
            .put(BigInteger.valueOf(80002), "amoy")
            .put(BigInteger.valueOf(56), "bsc")
            .put(BigInteger.valueOf(97), "bsctest")
            .put(BigInteger.valueOf(42161), "arbitrum")
            .put(BigInteger.valueOf(421613), "arbitrum-goerli")
            .put(BigInteger.valueOf(10), "optimism")
            .put(BigInteger.valueOf(420), "optimism-goerli")
            .put(BigInteger.valueOf(43114), "avalanche")
            .put(BigInteger.valueOf(43113), "fuji")
            .put(BigInteger.valueOf(8453), "base")
            .put(BigInteger.valueOf(84531), "base-goerli")
            .put(BigInteger.valueOf(59144), "linea")
            .put(BigInteger.valueOf(324), "zksync")
            .put(BigInteger.valueOf(534352), "scroll")
            .put(BigInteger.valueOf(5000), "mantle")
            .put(BigInteger.valueOf(80085), "berachain")
            .put(BigInteger.valueOf(17000), "holesky")
            .build();

    public BigInteger resolveToBigInteger(String chainIdOrName) {
        if (chainIdOrName == null || chainIdOrName.trim().isEmpty()) {
            throw new IllegalArgumentException("ChainId cannot be null or empty");
        }

        String normalized = chainIdOrName.trim().toLowerCase();

        try {
            return new BigInteger(normalized);
        } catch (NumberFormatException e) {
            BigInteger chainId = CHAIN_NAME_TO_ID.get(normalized);
            if (chainId == null) {
                log.warn("Unknown chain name: {}, returning as numeric value", chainIdOrName);
                throw new IllegalArgumentException("Unknown chain name: " + chainIdOrName);
            }
            return chainId;
        }
    }

    public long resolveToLong(String chainIdOrName) {
        return resolveToBigInteger(chainIdOrName).longValue();
    }

    public String resolveToName(String chainIdOrName) {
        BigInteger chainId = resolveToBigInteger(chainIdOrName);
        return CHAIN_ID_TO_NAME.getOrDefault(chainId, chainId.toString());
    }

    public String resolveToName(BigInteger chainId) {
        return CHAIN_ID_TO_NAME.getOrDefault(chainId, chainId.toString());
    }

    public boolean isEip1559Supported(String chainIdOrName) {
        BigInteger chainId = resolveToBigInteger(chainIdOrName);
        return isEip1559Supported(chainId);
    }

    public boolean isEip1559Supported(BigInteger chainId) {
        long id = chainId.longValue();
        return id == 1 || id == 5 || id == 11155111 || id == 137 || id == 42161
                || id == 10 || id == 8453 || id == 59144 || id == 324
                || id == 534352 || id == 5000 || id == 17000;
    }

    public boolean isValidChainId(String chainIdOrName) {
        try {
            resolveToBigInteger(chainIdOrName);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
