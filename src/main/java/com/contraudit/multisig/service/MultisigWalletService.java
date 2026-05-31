package com.contraudit.multisig.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.contraudit.common.BusinessException;
import com.contraudit.common.ErrorCode;
import com.contraudit.multisig.dto.CreateMultisigWalletRequest;
import com.contraudit.multisig.entity.MultisigSigner;
import com.contraudit.multisig.entity.MultisigWallet;
import com.contraudit.multisig.mapper.MultisigSignerMapper;
import com.contraudit.multisig.mapper.MultisigWalletMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultisigWalletService {

    private final MultisigWalletMapper multisigWalletMapper;
    private final MultisigSignerMapper multisigSignerMapper;

    @Value("${multisig.max-signers:10}")
    private Integer maxSigners;

    @Transactional(rollbackFor = Exception.class)
    public MultisigWallet createWallet(CreateMultisigWalletRequest request) {
        List<CreateMultisigWalletRequest.SignerInfo> signers = request.getSigners();
        
        if (signers.size() > maxSigners) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "signers count exceeds maximum " + maxSigners);
        }

        if (request.getThreshold() > signers.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "threshold cannot exceed signers count");
        }

        if (request.getThreshold() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "threshold must be greater than 0");
        }

        List<String> sortedAddresses = signers.stream()
                .map(CreateMultisigWalletRequest.SignerInfo::getAddress)
                .sorted()
                .collect(Collectors.toList());

        String walletAddress = generateMultisigAddress(sortedAddresses, request.getThreshold());

        MultisigWallet wallet = new MultisigWallet();
        wallet.setWalletName(request.getWalletName());
        wallet.setChainType(request.getChainType());
        wallet.setWalletAddress(walletAddress);
        wallet.setThreshold(request.getThreshold());
        wallet.setTotalSigners(signers.size());
        wallet.setStatus(1);

        multisigWalletMapper.insert(wallet);

        List<MultisigSigner> signerEntities = new ArrayList<>();
        for (int i = 0; i < signers.size(); i++) {
            CreateMultisigWalletRequest.SignerInfo signer = signers.get(i);
            MultisigSigner signerEntity = new MultisigSigner();
            signerEntity.setWalletId(wallet.getId());
            signerEntity.setSignerAddress(signer.getAddress());
            signerEntity.setSignerIndex(i);
            signerEntity.setPublicKey(signer.getPublicKey());
            signerEntities.add(signerEntity);
        }
        signerEntities.forEach(multisigSignerMapper::insert);

        log.info("Created multisig wallet: {} with {} signers, threshold: {}",
                wallet.getId(), signers.size(), request.getThreshold());

        return wallet;
    }

    public MultisigWallet getWallet(String walletId) {
        MultisigWallet wallet = multisigWalletMapper.selectById(walletId);
        if (wallet == null) {
            throw new BusinessException(ErrorCode.MULTISIG_WALLET_NOT_FOUND);
        }
        return wallet;
    }

    public List<MultisigWallet> listWallets(String chainType, Integer status) {
        LambdaQueryWrapper<MultisigWallet> wrapper = new LambdaQueryWrapper<>();
        if (chainType != null) {
            wrapper.eq(MultisigWallet::getChainType, chainType);
        }
        if (status != null) {
            wrapper.eq(MultisigWallet::getStatus, status);
        }
        return multisigWalletMapper.selectList(wrapper);
    }

    public List<MultisigSigner> getWalletSigners(String walletId) {
        LambdaQueryWrapper<MultisigSigner> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MultisigSigner::getWalletId, walletId);
        wrapper.orderByAsc(MultisigSigner::getSignerIndex);
        return multisigSignerMapper.selectList(wrapper);
    }

    public boolean isSigner(String walletId, String address) {
        LambdaQueryWrapper<MultisigSigner> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MultisigSigner::getWalletId, walletId);
        wrapper.eq(MultisigSigner::getSignerAddress, address);
        return multisigSignerMapper.selectCount(wrapper) > 0;
    }

    private String generateMultisigAddress(List<String> sortedAddresses, int threshold) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("0x");
            for (String address : sortedAddresses) {
                sb.append(address.toLowerCase().replace("0x", ""));
            }
            sb.append(threshold);
            byte[] hash = org.web3j.crypto.Hash.sha3(Numeric.hexStringToByteArray(
                    Numeric.toHexString(sb.toString().getBytes())));
            return "0x" + Numeric.toHexStringNoPrefix(hash).substring(24);
        } catch (Exception e) {
            log.error("Failed to generate multisig address", e);
            return "0x" + Keys.createEcKeyPair().getAddress();
        }
    }
}
