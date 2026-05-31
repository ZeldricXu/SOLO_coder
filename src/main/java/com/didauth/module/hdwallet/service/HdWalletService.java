package com.didauth.module.hdwallet.service;

import com.didauth.common.enums.ChainType;
import com.didauth.common.exception.BusinessException;
import com.didauth.core.entity.AddressBook;
import com.didauth.core.entity.HdWallet;
import com.didauth.core.mapper.AddressBookMapper;
import com.didauth.core.mapper.HdWalletMapper;
import com.didauth.module.hdwallet.dto.AddressBookRequest;
import com.didauth.module.hdwallet.dto.DeriveAddressRequest;
import com.didauth.module.hdwallet.dto.DeriveAddressResponse;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class HdWalletService {

    private final HdWalletMapper hdWalletMapper;
    private final AddressBookMapper addressBookMapper;
    private final MeterRegistry meterRegistry;

    private static final String DEFAULT_ETH_PATH = "m/44'/60'/0'/0/";
    private static final String DEFAULT_BTC_PATH = "m/44'/0'/0'/0/";

    public Mono<DeriveAddressResponse> deriveAddress(DeriveAddressRequest request) {
        return Mono.fromCallable(() -> {
            ChainType chainType = ChainType.fromCode(request.getChainType());
            String walletId = "wallet_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

            String derivationPath = request.getDerivationPath();
            if (derivationPath == null || derivationPath.isEmpty()) {
                derivationPath = getDefaultPath(chainType) + request.getIndex();
            }

            String[] keys = generateKeyPair(chainType, derivationPath, request.getMnemonic());

            HdWallet wallet = new HdWallet();
            wallet.setWalletId(walletId);
            wallet.setChainType(chainType.getCode());
            wallet.setDerivationPath(derivationPath);
            wallet.setAddress(keys[0]);
            wallet.setPublicKey(keys[1]);
            wallet.setPrivateKeyEncrypted(encryptPrivateKey(keys[2]));
            wallet.setLabel(request.getLabel());
            wallet.setTags(request.getTags() != null ? String.join(",", request.getTags()) : null);
            wallet.setUserId(request.getUserId());
            wallet.setStatus("ACTIVE");

            hdWalletMapper.insert(wallet);

            meterRegistry.counter("hdwallet.derive.count", "chain", chainType.getCode()).increment();

            DeriveAddressResponse response = new DeriveAddressResponse();
            response.setWalletId(walletId);
            response.setChainType(chainType.getCode());
            response.setAddress(keys[0]);
            response.setPublicKey(keys[1]);
            response.setDerivationPath(derivationPath);
            response.setLabel(request.getLabel());

            return response;
        });
    }

    private String getDefaultPath(ChainType chainType) {
        return switch (chainType) {
            case ETH, POLYGON, BSC, ARBITRUM, OPTIMISM -> DEFAULT_ETH_PATH;
            case BTC -> DEFAULT_BTC_PATH;
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

    public Mono<List<HdWallet>> listWallets(String userId, String chainType) {
        return Mono.fromCallable(() -> {
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HdWallet>();
            if (userId != null) wrapper.eq(HdWallet::getUserId, userId);
            if (chainType != null) wrapper.eq(HdWallet::getChainType, chainType);
            wrapper.orderByDesc(HdWallet::getCreatedAt);
            return hdWalletMapper.selectList(wrapper);
        });
    }

    public Mono<HdWallet> getWallet(String walletId) {
        return Mono.fromCallable(() -> {
            HdWallet wallet = hdWalletMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HdWallet>()
                            .eq(HdWallet::getWalletId, walletId));
            if (wallet == null) {
                throw BusinessException.notFound("Wallet not found: " + walletId);
            }
            return wallet;
        });
    }

    public Mono<String> addAddressBook(AddressBookRequest request) {
        return Mono.fromCallable(() -> {
            AddressBook existing = addressBookMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AddressBook>()
                            .eq(AddressBook::getAddress, request.getAddress())
                            .eq(AddressBook::getChainType, request.getChainType())
                            .eq(AddressBook::getUserId, request.getUserId()));

            if (existing != null) {
                throw BusinessException.paramError("Address already exists in address book");
            }

            AddressBook addressBook = new AddressBook();
            addressBook.setAddress(request.getAddress());
            addressBook.setChainType(request.getChainType());
            addressBook.setName(request.getName());
            addressBook.setLabel(request.getLabel());
            addressBook.setTags(request.getTags() != null ? String.join(",", request.getTags()) : null);
            addressBook.setUserId(request.getUserId());
            addressBook.setIsWhitelist(request.getIsWhitelist() != null ? request.getIsWhitelist() : false);
            addressBook.setIsBlacklist(request.getIsBlacklist() != null ? request.getIsBlacklist() : false);

            addressBookMapper.insert(addressBook);

            meterRegistry.counter("addressbook.add.count", "chain", request.getChainType()).increment();

            return addressBook.getId();
        });
    }

    public Mono<List<AddressBook>> listAddressBook(String userId, String chainType) {
        return Mono.fromCallable(() -> {
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AddressBook>();
            if (userId != null) wrapper.eq(AddressBook::getUserId, userId);
            if (chainType != null) wrapper.eq(AddressBook::getChainType, chainType);
            wrapper.orderByDesc(AddressBook::getCreatedAt);
            return addressBookMapper.selectList(wrapper);
        });
    }

    public Mono<Void> deleteAddressBook(String id) {
        return Mono.fromCallable(() -> {
            addressBookMapper.deleteById(id);
            return null;
        });
    }
}
