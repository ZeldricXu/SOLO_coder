package com.contraudit.wallet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.contraudit.common.BusinessException;
import com.contraudit.common.ErrorCode;
import com.contraudit.wallet.dto.CreateWalletRequest;
import com.contraudit.wallet.dto.DeriveAddressRequest;
import com.contraudit.wallet.entity.DerivedAddress;
import com.contraudit.wallet.entity.HdWallet;
import com.contraudit.wallet.mapper.DerivedAddressMapper;
import com.contraudit.wallet.mapper.HdWalletMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.crypto.*;
import org.bitcoinj.utils.MnemonicCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class HdWalletService {

    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_INACTIVE = 2;
    public static final int STATUS_ARCHIVED = 9;

    private static final Set<Integer> ALLOWED_DERIVE_STATUSES = Set.of(STATUS_ACTIVE);
    private static final Set<Integer> ALLOWED_VIEW_STATUSES = Set.of(STATUS_DRAFT, STATUS_ACTIVE, STATUS_INACTIVE);

    private static final Map<Integer, Set<Integer>> VALID_TRANSITIONS = Map.of(
            STATUS_DRAFT, Set.of(STATUS_ACTIVE, STATUS_ARCHIVED),
            STATUS_ACTIVE, Set.of(STATUS_INACTIVE, STATUS_ARCHIVED),
            STATUS_INACTIVE, Set.of(STATUS_ACTIVE, STATUS_ARCHIVED),
            STATUS_ARCHIVED, Set.of()
    );

    private final HdWalletMapper hdWalletMapper;
    private final DerivedAddressMapper derivedAddressMapper;

    @Value("${wallet.hd.derivation-path:m/44'/60'/0'/0}")
    private String defaultDerivationPath;

    @Value("${wallet.hd.mnemonic-length:12}")
    private Integer mnemonicLength;

    @Transactional(rollbackFor = Exception.class)
    public HdWallet createWallet(CreateWalletRequest request) {
        try {
            String mnemonic = request.getMnemonic();
            if (mnemonic == null || mnemonic.isEmpty()) {
                mnemonic = generateMnemonic();
            }

            byte[] seed = MnemonicCode.toSeed(mnemonic, "");
            DeterministicKey rootKey = HDKeyDerivation.createMasterPrivateKey(seed);

            String derivationPath = request.getDerivationPath() != null ?
                    request.getDerivationPath() : defaultDerivationPath;

            HdWallet wallet = new HdWallet();
            wallet.setWalletName(request.getWalletName());
            wallet.setMnemonic(encryptMnemonic(mnemonic));
            wallet.setRootXpub(rootKey.serializePubB58());
            wallet.setRootXpriv(encryptPrivateKey(rootKey.serializePrivB58()));
            wallet.setDerivationPath(derivationPath);
            wallet.setChainType(request.getChainType());
            wallet.setStatus(STATUS_DRAFT);

            hdWalletMapper.insert(wallet);
            log.info("Created HD wallet: {} with status DRAFT", wallet.getId());

            return wallet;
        } catch (Exception e) {
            log.error("Failed to create wallet", e);
            throw new BusinessException(ErrorCode.WALLET_CREATE_FAILED);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public HdWallet activateWallet(String walletId) {
        HdWallet wallet = getWalletInternal(walletId);
        validateStateTransition(wallet, STATUS_ACTIVE);
        wallet.setStatus(STATUS_ACTIVE);
        hdWalletMapper.updateById(wallet);
        log.info("Activated wallet: {}", walletId);
        return wallet;
    }

    @Transactional(rollbackFor = Exception.class)
    public HdWallet deactivateWallet(String walletId) {
        HdWallet wallet = getWalletInternal(walletId);
        validateStateTransition(wallet, STATUS_INACTIVE);
        wallet.setStatus(STATUS_INACTIVE);
        hdWalletMapper.updateById(wallet);
        log.info("Deactivated wallet: {}", walletId);
        return wallet;
    }

    @Transactional(rollbackFor = Exception.class)
    public HdWallet archiveWallet(String walletId) {
        HdWallet wallet = getWalletInternal(walletId);
        validateStateTransition(wallet, STATUS_ARCHIVED);
        wallet.setStatus(STATUS_ARCHIVED);
        hdWalletMapper.updateById(wallet);
        log.info("Archived wallet: {}", walletId);
        return wallet;
    }

    @Transactional(rollbackFor = Exception.class)
    public DerivedAddress deriveAddress(DeriveAddressRequest request) {
        HdWallet wallet = getWalletInternal(request.getWalletId());

        if (!ALLOWED_DERIVE_STATUSES.contains(wallet.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Cannot derive address from wallet with status: " + wallet.getStatus());
        }

        try {
            byte[] seed = MnemonicCode.toSeed(decryptMnemonic(wallet.getMnemonic()), "");
            DeterministicKey rootKey = HDKeyDerivation.createMasterPrivateKey(seed);

            String fullPath = wallet.getDerivationPath() + "/" + request.getAddressIndex();
            DeterministicKey derivedKey = deriveKey(rootKey, fullPath);

            ECKeyPair ecKeyPair = ECKeyPair.create(derivedKey.getPrivKeyBytes());
            String address = "0x" + Keys.getAddress(ecKeyPair);

            DerivedAddress derivedAddress = new DerivedAddress();
            derivedAddress.setWalletId(wallet.getId());
            derivedAddress.setAddress(address);
            derivedAddress.setAddressIndex(request.getAddressIndex());
            derivedAddress.setDerivationPath(fullPath);
            derivedAddress.setPublicKey(ecKeyPair.getPublicKey().toString(16));
            derivedAddress.setChainType(wallet.getChainType());
            derivedAddress.setStatus(STATUS_ACTIVE);

            derivedAddressMapper.insert(derivedAddress);
            log.info("Derived address: {} for wallet: {}", address, wallet.getId());

            return derivedAddress;
        } catch (Exception e) {
            log.error("Failed to derive address", e);
            throw new BusinessException(ErrorCode.ADDRESS_DERIVE_FAILED);
        }
    }

    public HdWallet getWallet(String walletId) {
        HdWallet wallet = getWalletInternal(walletId);
        if (!ALLOWED_VIEW_STATUSES.contains(wallet.getStatus())) {
            throw new BusinessException(ErrorCode.WALLET_NOT_FOUND);
        }
        wallet.setMnemonic(null);
        wallet.setRootXpriv(null);
        return wallet;
    }

    public List<HdWallet> listWallets(String chainType, Integer status) {
        LambdaQueryWrapper<HdWallet> wrapper = new LambdaQueryWrapper<>();
        if (chainType != null) {
            wrapper.eq(HdWallet::getChainType, chainType);
        }
        if (status != null) {
            wrapper.eq(HdWallet::getStatus, status);
        } else {
            wrapper.in(HdWallet::getStatus, ALLOWED_VIEW_STATUSES);
        }
        wrapper.orderByDesc(HdWallet::getCreatedAt);
        List<HdWallet> wallets = hdWalletMapper.selectList(wrapper);
        wallets.forEach(w -> {
            w.setMnemonic(null);
            w.setRootXpriv(null);
        });
        return wallets;
    }

    public List<DerivedAddress> listDerivedAddresses(String walletId) {
        LambdaQueryWrapper<DerivedAddress> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DerivedAddress::getWalletId, walletId);
        wrapper.ne(DerivedAddress::getStatus, STATUS_ARCHIVED);
        wrapper.orderByAsc(DerivedAddress::getAddressIndex);
        return derivedAddressMapper.selectList(wrapper);
    }

    private HdWallet getWalletInternal(String walletId) {
        HdWallet wallet = hdWalletMapper.selectById(walletId);
        if (wallet == null) {
            throw new BusinessException(ErrorCode.WALLET_NOT_FOUND);
        }
        return wallet;
    }

    private void validateStateTransition(HdWallet wallet, int targetStatus) {
        int currentStatus = wallet.getStatus();
        Set<Integer> allowedTransitions = VALID_TRANSITIONS.get(currentStatus);
        if (allowedTransitions == null || !allowedTransitions.contains(targetStatus)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    String.format("Invalid state transition from %d to %d for wallet %s",
                            currentStatus, targetStatus, wallet.getId()));
        }
    }

    private String generateMnemonic() throws Exception {
        MnemonicCode mnemonicCode = new MnemonicCode();
        byte[] entropy = new byte[mnemonicLength == 12 ? 16 : 32];
        new SecureRandom().nextBytes(entropy);
        return String.join(" ", mnemonicCode.toMnemonic(entropy));
    }

    private DeterministicKey deriveKey(DeterministicKey rootKey, String path) {
        List<ChildNumber> childNumbers = HDUtils.parsePath(path);
        DeterministicKey currentKey = rootKey;
        for (ChildNumber childNumber : childNumbers) {
            currentKey = HDKeyDerivation.deriveChildKey(currentKey, childNumber);
        }
        return currentKey;
    }

    private String encryptMnemonic(String mnemonic) {
        return mnemonic;
    }

    private String decryptMnemonic(String encryptedMnemonic) {
        return encryptedMnemonic;
    }

    private String encryptPrivateKey(String privateKey) {
        return privateKey;
    }
}
