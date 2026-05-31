package com.didauth.module.hdwallet.enhanced;

import com.didauth.common.enums.ChainType;
import com.didauth.common.exception.BusinessException;
import com.didauth.core.entity.AddressBook;
import com.didauth.core.entity.HdWallet;
import com.didauth.core.mapper.AddressBookMapper;
import com.didauth.core.mapper.HdWalletMapper;
import com.didauth.module.hdwallet.dto.DeriveAddressResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnhancedHdWalletService {

    private final HdWalletMapper hdWalletMapper;
    private final AddressBookMapper addressBookMapper;
    private final MeterRegistry meterRegistry;

    public Mono<List<DeriveAddressResponse>> batchDeriveAddresses(BatchDeriveRequest request) {
        if (request.getCount() > 1000) {
            throw BusinessException.paramError("Batch count cannot exceed 1000");
        }
        if (request.getCount() < 1) {
            throw BusinessException.paramError("Batch count must be at least 1");
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        ChainType chainType = ChainType.fromCode(request.getChainType());
        String basePath = request.getBaseDerivationPath() != null ? request.getBaseDerivationPath()
                : getDefaultBasePath(chainType);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        return Flux.range(request.getStartIndex(), request.getCount())
                .flatMap(index -> Mono.fromCallable(() -> {
                            try {
                                String derivationPath = basePath + index;
                                String[] keys = generateKeyPair(chainType, derivationPath, request.getMnemonic());

                                String walletId = "wallet_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                                String label = request.getLabelPrefix() != null
                                        ? request.getLabelPrefix() + "_" + index
                                        : null;

                                HdWallet wallet = new HdWallet();
                                wallet.setWalletId(walletId);
                                wallet.setChainType(chainType.getCode());
                                wallet.setDerivationPath(derivationPath);
                                wallet.setAddress(keys[0]);
                                wallet.setPublicKey(keys[1]);
                                wallet.setPrivateKeyEncrypted(encryptPrivateKey(keys[2]));
                                wallet.setLabel(label);
                                wallet.setTags(request.getTags() != null ? String.join(",", request.getTags()) : null);
                                wallet.setUserId(request.getUserId());
                                wallet.setStatus("ACTIVE");

                                hdWalletMapper.insert(wallet);

                                DeriveAddressResponse response = new DeriveAddressResponse();
                                response.setWalletId(walletId);
                                response.setChainType(chainType.getCode());
                                response.setAddress(keys[0]);
                                response.setPublicKey(keys[1]);
                                response.setDerivationPath(derivationPath);
                                response.setLabel(label);

                                successCount.incrementAndGet();
                                return response;
                            } catch (Exception e) {
                                errorCount.incrementAndGet();
                                log.warn("Failed to derive address at index {}", index, e);
                                return null;
                            }
                        })
                        .subscribeOn(Schedulers.boundedElastic()),
                        request.getBatchSize() != null ? request.getBatchSize() : 10
                )
                .filter(Objects::nonNull)
                .collectList()
                .doOnSuccess(results -> {
                    long durationMs = sample.stop(Timer.builder("hdwallet.batch.derive.duration")
                            .tag("chain", chainType.getCode())
                            .register(meterRegistry)) / 1_000_000;

                    meterRegistry.counter("hdwallet.batch.derive.count",
                            "chain", chainType.getCode(),
                            "success", String.valueOf(successCount.get()),
                            "errors", String.valueOf(errorCount.get())
                    ).increment(results.size());

                    log.info("Batch derive completed: chain={}, total={}, success={}, errors={}, duration={}ms",
                            chainType.getCode(), request.getCount(), successCount.get(), errorCount.get(), durationMs);
                });
    }

    @Transactional
    public Mono<Map<String, Object>> batchAddAddressBook(BatchAddressBookRequest request) {
        if (request.getEntries().size() > 500) {
            throw BusinessException.paramError("Batch size cannot exceed 500");
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        Map<String, Object> result = new HashMap<>();
        List<String> successIds = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();

        for (int i = 0; i < request.getEntries().size(); i++) {
            BatchAddressBookRequest.BatchAddressBookEntry entry = request.getEntries().get(i);
            try {
                AddressBook existing = addressBookMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AddressBook>()
                                .eq(AddressBook::getAddress, entry.getAddress())
                                .eq(AddressBook::getChainType, entry.getChainType())
                                .eq(AddressBook::getUserId, request.getUserId()));

                if (existing != null) {
                    duplicates.add(entry.getAddress());
                    continue;
                }

                AddressBook addressBook = new AddressBook();
                addressBook.setAddress(entry.getAddress());
                addressBook.setChainType(entry.getChainType());
                addressBook.setName(entry.getName());
                addressBook.setLabel(entry.getLabel());
                addressBook.setTags(entry.getTags() != null ? String.join(",", entry.getTags()) : null);
                addressBook.setUserId(request.getUserId());
                addressBook.setIsWhitelist(entry.getIsWhitelist());
                addressBook.setIsBlacklist(entry.getIsBlacklist());

                addressBookMapper.insert(addressBook);
                successIds.add(addressBook.getId());
            } catch (Exception e) {
                Map<String, Object> error = new HashMap<>();
                error.put("index", i);
                error.put("address", entry.getAddress());
                error.put("error", e.getMessage());
                errors.add(error);
            }
        }

        long durationMs = sample.stop(Timer.builder("hdwallet.batch.addressbook.duration")
                .register(meterRegistry)) / 1_000_000;

        result.put("total", request.getEntries().size());
        result.put("success", successIds.size());
        result.put("successIds", successIds);
        result.put("duplicates", duplicates);
        result.put("errors", errors);
        result.put("durationMs", durationMs);

        meterRegistry.counter("hdwallet.batch.addressbook.count",
                "success", String.valueOf(successIds.size()),
                "errors", String.valueOf(errors.size())
        ).increment(successIds.size());

        log.info("Batch address book add completed: total={}, success={}, duplicates={}, errors={}, duration={}ms",
                request.getEntries().size(), successIds.size(), duplicates.size(), errors.size(), durationMs);

        return Mono.just(result);
    }

    public Mono<Map<String, Object>> batchDeleteAddressBook(List<String> ids) {
        if (ids.size() > 500) {
            throw BusinessException.paramError("Batch delete size cannot exceed 500");
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        Map<String, Object> result = new HashMap<>();
        int deletedCount = 0;
        List<String> failedIds = new ArrayList<>();

        for (String id : ids) {
            try {
                int deleted = addressBookMapper.deleteById(id);
                if (deleted > 0) {
                    deletedCount++;
                } else {
                    failedIds.add(id);
                }
            } catch (Exception e) {
                failedIds.add(id);
                log.warn("Failed to delete address book entry: {}", id, e);
            }
        }

        long durationMs = sample.stop(Timer.builder("hdwallet.batch.delete.duration")
                .register(meterRegistry)) / 1_000_000;

        result.put("total", ids.size());
        result.put("deleted", deletedCount);
        result.put("failed", failedIds);
        result.put("durationMs", durationMs);

        return Mono.just(result);
    }

    public Mono<List<HdWallet>> batchGetWallets(List<String> walletIds) {
        return Mono.fromCallable(() -> {
            if (walletIds.size() > 100) {
                throw BusinessException.paramError("Batch get size cannot exceed 100");
            }

            List<HdWallet> wallets = new ArrayList<>();
            for (String walletId : walletIds) {
                HdWallet wallet = hdWalletMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HdWallet>()
                                .eq(HdWallet::getWalletId, walletId));
                if (wallet != null) {
                    wallets.add(wallet);
                }
            }
            return wallets;
        });
    }

    public Mono<Map<String, Object>> getBatchMetrics() {
        return Mono.fromCallable(() -> {
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("maxBatchDeriveSize", 1000);
            metrics.put("maxBatchAddressBookSize", 500);
            metrics.put("maxBatchDeleteSize", 500);
            metrics.put("maxBatchGetSize", 100);
            metrics.put("defaultBatchConcurrency", 10);
            return metrics;
        });
    }

    private String getDefaultBasePath(ChainType chainType) {
        return switch (chainType) {
            case ETH, POLYGON, BSC, ARBITRUM, OPTIMISM -> "m/44'/60'/0'/0/";
            case BTC -> "m/44'/0'/0'/0/";
        };
    }

    private String[] generateKeyPair(ChainType chainType, String derivationPath, String mnemonic) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] seed = new byte[32];
        random.nextBytes(seed);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] addressBytes = digest.digest((derivationPath + (mnemonic != null ? mnemonic : "")).getBytes());
        StringBuilder address = new StringBuilder();

        if (chainType == ChainType.BTC) {
            address.append("bc1");
        } else {
            address.append("0x");
        }

        for (int i = 0; i < 20; i++) {
            String hex = Integer.toHexString(0xff & addressBytes[i]);
            if (hex.length() == 1) address.append('0');
            address.append(hex);
        }

        byte[] pubKeyBytes = digest.digest(("pub" + derivationPath).getBytes());
        StringBuilder pubKey = new StringBuilder("04");
        for (int i = 0; i < 64; i++) {
            String hex = Integer.toHexString(0xff & pubKeyBytes[i % pubKeyBytes.length]);
            if (hex.length() == 1) pubKey.append('0');
            pubKey.append(hex);
        }

        byte[] privKeyBytes = digest.digest(("priv" + derivationPath).getBytes());
        StringBuilder privKey = new StringBuilder();
        for (byte b : privKeyBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) privKey.append('0');
            privKey.append(hex);
        }

        return new String[]{address.toString(), pubKey.toString(), privKey.toString()};
    }

    private String encryptPrivateKey(String privateKey) {
        return "enc_" + privateKey;
    }
}
