package com.solocoder.platform.address.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.solocoder.platform.persistence.entity.AddressBookEntity;
import com.solocoder.platform.persistence.entity.HdWalletEntity;
import com.solocoder.platform.persistence.entity.WalletAddressEntity;
import com.solocoder.platform.persistence.mapper.AddressBookMapper;
import com.solocoder.platform.persistence.mapper.HdWalletMapper;
import com.solocoder.platform.persistence.mapper.WalletAddressMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AddressManagerService {

    private final HdWalletMapper hdWalletMapper;
    private final WalletAddressMapper walletAddressMapper;
    private final AddressBookMapper addressBookMapper;

    @Transactional(rollbackFor = Exception.class)
    public HdWalletEntity createHdWallet(String name, String mnemonicEncrypted,
                                          String seedEncrypted, String derivationPath,
                                          String curveType, boolean passphraseProtected) {
        HdWalletEntity entity = new HdWalletEntity();
        entity.setWalletId(UUID.randomUUID().toString());
        entity.setName(name);
        entity.setMnemonicEncrypted(mnemonicEncrypted);
        entity.setSeedEncrypted(seedEncrypted);
        entity.setDerivationPath(derivationPath != null ? derivationPath : "m/44'/60'/0'/0");
        entity.setCurveType(curveType != null ? curveType : "SECP256K1");
        entity.setPassphraseProtected(passphraseProtected ? 1 : 0);
        entity.setAddressCount(0);
        entity.setLastDerivedIndex(-1);
        entity.setCreatedBy("system");
        hdWalletMapper.insert(entity);
        return entity;
    }

    @Transactional(rollbackFor = Exception.class)
    public WalletAddressEntity deriveAddress(String walletId, String chainId,
                                              String name, List<String> labels,
                                              boolean isReceive, int accountIndex, int addressIndex) {
        HdWalletEntity wallet = hdWalletMapper.selectById(walletId);
        if (wallet == null) {
            throw new RuntimeException("钱包不存在: " + walletId);
        }

        String derivationPath = wallet.getDerivationPath() + "/" + accountIndex + "/" + addressIndex;
        String address = deriveAddressFromPath(wallet, derivationPath, chainId);
        String publicKey = derivePublicKey(wallet, derivationPath);

        WalletAddressEntity entity = new WalletAddressEntity();
        entity.setAddressId(UUID.randomUUID().toString());
        entity.setWalletId(walletId);
        entity.setChainId(chainId);
        entity.setAddress(address);
        entity.setDerivationPath(derivationPath);
        entity.setDerivationIndex(addressIndex);
        entity.setPublicKey(publicKey);
        entity.setName(name);
        entity.setLabels(labels != null ? JSON.toJSONString(labels) : null);
        entity.setIsReceive(isReceive ? 1 : 0);
        entity.setIsArchived(0);
        walletAddressMapper.insert(entity);

        wallet.setAddressCount(wallet.getAddressCount() + 1);
        wallet.setLastDerivedIndex(Math.max(wallet.getLastDerivedIndex(), addressIndex));
        hdWalletMapper.updateById(wallet);

        return entity;
    }

    private String deriveAddressFromPath(HdWalletEntity wallet, String derivationPath, String chainId) {
        return "0x" + UUID.randomUUID().toString().replace("-", "").substring(0, 40);
    }

    private String derivePublicKey(HdWalletEntity wallet, String derivationPath) {
        return "0x" + UUID.randomUUID().toString().replace("-", "");
    }

    @Transactional(rollbackFor = Exception.class)
    public AddressBookEntity addAddressBookEntry(String chainId, String address,
                                                  String name, String description,
                                                  List<String> labels, String addressType,
                                                  Map<String, Object> metadata) {
        AddressBookEntity entity = new AddressBookEntity();
        entity.setEntryId(UUID.randomUUID().toString());
        entity.setChainId(chainId);
        entity.setAddress(address);
        entity.setName(name);
        entity.setDescription(description);
        entity.setLabels(labels != null ? JSON.toJSONString(labels) : null);
        entity.setAddressType(addressType != null ? addressType : "EOA");
        entity.setVerified(0);
        entity.setRiskLevel("LOW");
        entity.setMetadata(metadata != null ? JSON.toJSONString(metadata) : null);
        entity.setCreatedBy("system");
        addressBookMapper.insert(entity);
        return entity;
    }

    public List<WalletAddressEntity> getWalletAddresses(String walletId, String chainId) {
        LambdaQueryWrapper<WalletAddressEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WalletAddressEntity::getWalletId, walletId);
        if (chainId != null) {
            wrapper.eq(WalletAddressEntity::getChainId, chainId);
        }
        wrapper.orderByAsc(WalletAddressEntity::getDerivationIndex);
        return walletAddressMapper.selectList(wrapper);
    }

    public List<AddressBookEntity> searchAddressBook(String chainId, String keyword, String addressType) {
        LambdaQueryWrapper<AddressBookEntity> wrapper = new LambdaQueryWrapper<>();
        if (chainId != null) {
            wrapper.eq(AddressBookEntity::getChainId, chainId);
        }
        if (addressType != null) {
            wrapper.eq(AddressBookEntity::getAddressType, addressType);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(AddressBookEntity::getName, keyword)
                    .or()
                    .like(AddressBookEntity::getAddress, keyword));
        }
        return addressBookMapper.selectList(wrapper);
    }
}
