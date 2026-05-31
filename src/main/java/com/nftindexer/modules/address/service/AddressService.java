package com.nftindexer.modules.address.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nftindexer.common.JsonUtils;
import com.nftindexer.common.OptimisticRetry;
import com.nftindexer.common.TraceContext;
import com.nftindexer.entity.AddressBook;
import com.nftindexer.event.DomainEvent;
import com.nftindexer.exception.BusinessException;
import com.nftindexer.mapper.AddressBookMapper;
import com.nftindexer.modules.address.dto.AddressBookRequest;
import com.nftindexer.modules.address.dto.AddressDeriveRequest;
import com.nftindexer.modules.address.dto.HDWalletCreateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.Bip32ECKeyPair;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.MnemonicUtils;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.web3j.crypto.Bip32ECKeyPair.HARDENED_BIT;

@Slf4j
@Service
@RequiredArgsConstructor
public class AddressService {

    private final AddressBookMapper addressBookMapper;
    private final Sinks.Many<DomainEvent> eventSink;

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<Map<String, Object>> createHDWallet(HDWalletCreateRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    String mnemonic = request.getMnemonic();
                    if (mnemonic == null || mnemonic.isEmpty()) {
                        mnemonic = generateMnemonic();
                    }

                    String passphrase = request.getPassphrase() != null ? request.getPassphrase() : "";
                    String purpose = request.getPurpose() != null ? request.getPurpose() : "44";
                    String coinType = request.getCoinType() != null ? request.getCoinType() : "60";

                    String walletId = "hdw-" + UUID.randomUUID().toString().substring(0, 8);
                    String rootPath = "m/" + purpose + "'/" + coinType + "'/0'/0";

                    byte[] seed = MnemonicUtils.generateSeed(mnemonic, passphrase);
                    Bip32ECKeyPair rootKeyPair = Bip32ECKeyPair.generateKeyPair(seed);
                    String rootPublicKey = "0x" + rootKeyPair.getPublicKey().toString(16);

                    Map<String, Object> result = new HashMap<>();
                    result.put("walletId", walletId);
                    result.put("mnemonic", mnemonic);
                    result.put("rootPath", rootPath);
                    result.put("rootPublicKey", rootPublicKey);
                    result.put("createdAt", LocalDateTime.now());
                    result.put("purpose", purpose);
                    result.put("coinType", coinType);

                    emitEvent("hdwallet.created", walletId, "hd_wallet",
                            Map.of("rootPath", rootPath, "createdBy", request.getCreatedBy()), traceId);

                    log.info("Created HD wallet: {} with path: {}", walletId, rootPath);
                    return result;
                }));
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<Map<String, Object>> deriveAddress(AddressDeriveRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    String mnemonic = request.getMnemonic();
                    String passphrase = request.getPassphrase() != null ? request.getPassphrase() : "";
                    String derivationPath = request.getDerivationPath();

                    if (!isValidDerivationPath(derivationPath)) {
                        throw BusinessException.validationError("无效的派生路径: " + derivationPath);
                    }

                    byte[] seed = MnemonicUtils.generateSeed(mnemonic, passphrase);
                    Bip32ECKeyPair rootKeyPair = Bip32ECKeyPair.generateKeyPair(seed);
                    int[] path = parseDerivationPath(derivationPath);
                    Bip32ECKeyPair derivedKeyPair = Bip32ECKeyPair.deriveKeyPair(rootKeyPair, path);

                    Credentials credentials = Credentials.create(derivedKeyPair);
                    String address = credentials.getAddress();
                    String publicKey = "0x" + derivedKeyPair.getPublicKey().toString(16);
                    String privateKey = "0x" + derivedKeyPair.getPrivateKey().toString(16);

                    LambdaQueryWrapper<AddressBook> existingWrapper = new LambdaQueryWrapper<>();
                    existingWrapper.eq(AddressBook::getAddress, address);
                    existingWrapper.eq(AddressBook::getChainId, request.getChainId());
                    if (addressBookMapper.selectCount(existingWrapper) > 0) {
                        throw BusinessException.conflict("地址已存在于地址簿中: " + address);
                    }

                    String entryId = "add-" + UUID.randomUUID().toString().substring(0, 8);
                    AddressBook addressBook = new AddressBook();
                    addressBook.setEntryId(entryId);
                    addressBook.setAddress(address);
                    addressBook.setChainId(request.getChainId());
                    addressBook.setLabel(request.getLabel());
                    addressBook.setCategory(request.getCategory());
                    addressBook.setDescription(request.getDescription());
                    addressBook.setTags(JsonUtils.toJson(request.getTags()));
                    addressBook.setCreatedBy(request.getCreatedBy());
                    addressBook.setMetadata(Map.of(
                            "derivationPath", derivationPath,
                            "publicKey", publicKey
                    ));
                    addressBookMapper.insert(addressBook);

                    Map<String, Object> result = new HashMap<>();
                    result.put("entryId", entryId);
                    result.put("address", address);
                    result.put("publicKey", publicKey);
                    result.put("privateKey", privateKey);
                    result.put("derivationPath", derivationPath);
                    result.put("chainId", request.getChainId());

                    emitEvent("address.derived", entryId, "address_book", addressBook, traceId);
                    log.info("Derived address: {} with path: {}", address, derivationPath);

                    return result;
                }));
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<AddressBook> addAddressBook(AddressBookRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<AddressBook> existingWrapper = new LambdaQueryWrapper<>();
                    existingWrapper.eq(AddressBook::getAddress, request.getAddress());
                    existingWrapper.eq(AddressBook::getChainId, request.getChainId());
                    if (addressBookMapper.selectCount(existingWrapper) > 0) {
                        throw BusinessException.conflict("地址已存在于地址簿中");
                    }

                    String entryId = "add-" + UUID.randomUUID().toString().substring(0, 8);
                    AddressBook addressBook = new AddressBook();
                    addressBook.setEntryId(entryId);
                    addressBook.setAddress(request.getAddress());
                    addressBook.setChainId(request.getChainId());
                    addressBook.setLabel(request.getLabel());
                    addressBook.setCategory(request.getCategory());
                    addressBook.setDescription(request.getDescription());
                    addressBook.setTags(JsonUtils.toJson(request.getTags()));
                    addressBook.setCreatedBy(request.getCreatedBy());
                    addressBook.setMetadata(request.getMetadata());

                    addressBookMapper.insert(addressBook);

                    emitEvent("addressbook.added", entryId, "address_book", addressBook, traceId);
                    log.info("Added address to book: {} for chain: {}", request.getAddress(), request.getChainId());

                    return addressBook;
                }));
    }

    @Cacheable(value = "addressBook", key = "#entryId", unless = "#result == null")
    public Mono<AddressBook> getAddressBook(String entryId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<AddressBook> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AddressBook::getEntryId, entryId);
            AddressBook addressBook = addressBookMapper.selectOne(wrapper);

            if (addressBook == null) {
                throw BusinessException.notFound("地址簿条目不存在: " + entryId);
            }
            return addressBook;
        });
    }

    public Mono<AddressBook> getAddressByAddress(String address, String chainId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<AddressBook> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AddressBook::getAddress, address);
            wrapper.eq(AddressBook::getChainId, chainId);
            AddressBook addressBook = addressBookMapper.selectOne(wrapper);

            if (addressBook == null) {
                throw BusinessException.notFound("地址不存在: " + address);
            }
            return addressBook;
        });
    }

    public Mono<Page<AddressBook>> listAddressBook(String chainId, String category,
                                                   String label, String tag,
                                                   int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<AddressBook> wrapper = new LambdaQueryWrapper<>();
            if (chainId != null && !chainId.isEmpty()) {
                wrapper.eq(AddressBook::getChainId, chainId);
            }
            if (category != null && !category.isEmpty()) {
                wrapper.eq(AddressBook::getCategory, category);
            }
            if (label != null && !label.isEmpty()) {
                wrapper.like(AddressBook::getLabel, label);
            }
            if (tag != null && !tag.isEmpty()) {
                wrapper.like(AddressBook::getTags, tag);
            }
            wrapper.orderByDesc(AddressBook::getCreatedAt);
            return addressBookMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        });
    }

    @Transactional
    @CacheEvict(value = "addressBook", key = "#entryId")
    @OptimisticRetry(maxAttempts = 3)
    public Mono<AddressBook> updateAddressBook(String entryId, AddressBookRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<AddressBook> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(AddressBook::getEntryId, entryId);
                    AddressBook addressBook = addressBookMapper.selectOne(wrapper);

                    if (addressBook == null) {
                        throw BusinessException.notFound("地址簿条目不存在: " + entryId);
                    }

                    if (request.getLabel() != null) {
                        addressBook.setLabel(request.getLabel());
                    }
                    if (request.getCategory() != null) {
                        addressBook.setCategory(request.getCategory());
                    }
                    if (request.getDescription() != null) {
                        addressBook.setDescription(request.getDescription());
                    }
                    if (request.getTags() != null) {
                        addressBook.setTags(JsonUtils.toJson(request.getTags()));
                    }
                    if (request.getMetadata() != null) {
                        Map<String, Object> merged = new HashMap<>();
                        if (addressBook.getMetadata() != null) {
                            merged.putAll(addressBook.getMetadata());
                        }
                        merged.putAll(request.getMetadata());
                        addressBook.setMetadata(merged);
                    }

                    addressBookMapper.updateById(addressBook);

                    emitEvent("addressbook.updated", entryId, "address_book", addressBook, traceId);
                    log.info("Updated address book entry: {}", entryId);

                    return addressBook;
                }));
    }

    @Transactional
    @CacheEvict(value = "addressBook", key = "#entryId")
    public Mono<Void> deleteAddressBook(String entryId) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<AddressBook> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(AddressBook::getEntryId, entryId);
                    AddressBook addressBook = addressBookMapper.selectOne(wrapper);

                    if (addressBook == null) {
                        throw BusinessException.notFound("地址簿条目不存在: " + entryId);
                    }

                    addressBookMapper.delete(wrapper);

                    emitEvent("addressbook.deleted", entryId, "address_book",
                            Map.of("address", addressBook.getAddress()), traceId);
                    log.info("Deleted address book entry: {}", entryId);

                    return null;
                }));
    }

    public Mono<List<String>> getAddressTags() {
        return Mono.fromCallable(() -> {
            List<AddressBook> all = addressBookMapper.selectList(null);
            return all.stream()
                    .filter(a -> a.getTags() != null)
                    .flatMap(a -> {
                        try {
                            String[] tags = JsonUtils.fromJson(a.getTags(), String[].class);
                            return Arrays.stream(tags);
                        } catch (Exception e) {
                            return Arrays.stream(new String[0]);
                        }
                    })
                    .distinct()
                    .sorted()
                    .toList();
        });
    }

    public Mono<Map<String, Long>> getAddressByChainStats() {
        return Mono.fromCallable(() -> {
            List<AddressBook> all = addressBookMapper.selectList(null);
            Map<String, Long> stats = new HashMap<>();
            for (AddressBook addr : all) {
                stats.merge(addr.getChainId(), 1L, Long::sum);
            }
            return stats;
        });
    }

    private String generateMnemonic() {
        byte[] entropy = new byte[16];
        new SecureRandom().nextBytes(entropy);
        return MnemonicUtils.generateMnemonic(entropy);
    }

    private boolean isValidDerivationPath(String path) {
        return path != null && path.matches("^m(/\\d+'?)*$");
    }

    private int[] parseDerivationPath(String path) {
        String[] parts = path.replaceFirst("^m/", "").split("/");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            boolean hardened = part.endsWith("'");
            int value = Integer.parseInt(hardened ? part.substring(0, part.length() - 1) : part);
            result[i] = hardened ? value | HARDENED_BIT : value;
        }
        return result;
    }

    private void emitEvent(String eventType, String aggregateId, String aggregateType,
                           Object payload, String traceId) {
        DomainEvent event = new DomainEvent();
        event.setEventId("evt-" + UUID.randomUUID().toString().substring(0, 8));
        event.setEventType(eventType);
        event.setAggregateId(aggregateId);
        event.setAggregateType(aggregateType);
        event.setPayload(Map.of("data", payload));
        event.setTimestamp(LocalDateTime.now());
        event.setTraceId(traceId);
        eventSink.tryEmitNext(event);
    }
}
