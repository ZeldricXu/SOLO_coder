package com.web3platform.addressmanagement.service;

import com.web3platform.addressmanagement.model.AddressResponse;
import com.web3platform.addressmanagement.model.HdWallet;
import com.web3platform.addressmanagement.util.AddressChecksumUtil;
import com.web3platform.addressmanagement.util.Bip44PathUtil;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Bip32ECKeyPair;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Hash;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

@Service
public class AddressDeriver {

    private final HdWalletService hdWalletService;

    public AddressDeriver(HdWalletService hdWalletService) {
        this.hdWalletService = hdWalletService;
    }

    public AddressResponse deriveAddress(String walletId, String chainType, String path, int index) {
        HdWallet wallet = hdWalletService.getWalletInternal(walletId);

        String actualPath;
        if (path == null || path.isEmpty()) {
            actualPath = Bip44PathUtil.buildStandardPath(chainType, 0, index);
        } else {
            actualPath = path;
        }

        byte[] seed = hexToBytes(wallet.getSeedHex());
        Bip32ECKeyPair masterKeyPair = Bip32ECKeyPair.generateKeyPair(seed);

        List<Integer> pathSegments = Bip44PathUtil.parsePath(actualPath);
        int[] pathInts = new int[pathSegments.size()];
        for (int i = 0; i < pathSegments.size(); i++) {
            pathInts[i] = pathSegments.get(i);
        }

        Bip32ECKeyPair derivedKeyPair = Bip32ECKeyPair.deriveKeyPair(masterKeyPair, pathInts);

        String address = deriveAddressByChainType(derivedKeyPair, chainType);
        String publicKey = bytesToHex(derivedKeyPair.getPublicKey().toByteArray());

        return AddressResponse.builder()
                .address(address)
                .path(actualPath)
                .index(index)
                .chainType(chainType)
                .publicKey(publicKey)
                .build();
    }

    public List<AddressResponse> deriveBatch(String walletId, String chainType, int startIndex, int count) {
        List<AddressResponse> addresses = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = startIndex + i;
            String path = Bip44PathUtil.buildStandardPath(chainType, 0, index);
            addresses.add(deriveAddress(walletId, chainType, path, index));
        }
        return addresses;
    }

    public String getPublicKey(String walletId, String path) {
        HdWallet wallet = hdWalletService.getWalletInternal(walletId);

        byte[] seed = hexToBytes(wallet.getSeedHex());
        Bip32ECKeyPair masterKeyPair = Bip32ECKeyPair.generateKeyPair(seed);

        List<Integer> pathSegments = Bip44PathUtil.parsePath(path);
        int[] pathInts = new int[pathSegments.size()];
        for (int i = 0; i < pathSegments.size(); i++) {
            pathInts[i] = pathSegments.get(i);
        }

        Bip32ECKeyPair derivedKeyPair = Bip32ECKeyPair.deriveKeyPair(masterKeyPair, pathInts);
        return bytesToHex(derivedKeyPair.getPublicKey().toByteArray());
    }

    private String deriveAddressByChainType(ECKeyPair keyPair, String chainType) {
        return switch (chainType.toUpperCase()) {
            case "ETH", "BSC", "POLYGON", "MATIC" -> {
                String address = Keys.getAddress(keyPair.getPublicKey());
                yield AddressChecksumUtil.toChecksumAddress(address);
            }
            case "BTC" -> deriveBtcAddress(keyPair);
            case "LTC" -> deriveLtcAddress(keyPair);
            default -> throw new IllegalArgumentException("Unsupported chain type: " + chainType);
        };
    }

    private String deriveBtcAddress(ECKeyPair keyPair) {
        byte[] pubKey = keyPair.getPublicKey().toByteArray();
        byte[] sha256 = sha256(pubKey);
        byte[] ripeMd160 = ripemd160(sha256);

        byte[] versionedPayload = new byte[21];
        versionedPayload[0] = 0x00;
        System.arraycopy(ripeMd160, 0, versionedPayload, 1, 20);

        byte[] checksum = sha256(sha256(versionedPayload));

        byte[] finalPayload = new byte[25];
        System.arraycopy(versionedPayload, 0, finalPayload, 0, 21);
        System.arraycopy(checksum, 0, finalPayload, 21, 4);

        return encodeBase58(finalPayload);
    }

    private String deriveLtcAddress(ECKeyPair keyPair) {
        byte[] pubKey = keyPair.getPublicKey().toByteArray();
        byte[] sha256 = sha256(pubKey);
        byte[] ripeMd160 = ripemd160(sha256);

        byte[] versionedPayload = new byte[21];
        versionedPayload[0] = 0x30;
        System.arraycopy(ripeMd160, 0, versionedPayload, 1, 20);

        byte[] checksum = sha256(sha256(versionedPayload));

        byte[] finalPayload = new byte[25];
        System.arraycopy(versionedPayload, 0, finalPayload, 0, 21);
        System.arraycopy(checksum, 0, finalPayload, 21, 4);

        return encodeBase58(finalPayload);
    }

    private byte[] sha256(byte[] input) {
        return Hash.sha256(input);
    }

    private byte[] ripemd160(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("RIPEMD160", new org.bouncycastle.jce.provider.BouncyCastleProvider());
            return md.digest(input);
        } catch (Exception e) {
            throw new RuntimeException("RIPEMD160 not available", e);
        }
    }

    private static final String BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    private String encodeBase58(byte[] input) {
        if (input.length == 0) {
            return "";
        }

        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) {
            zeros++;
        }

        byte[] temp = new byte[input.length * 2];
        int j = temp.length;

        for (int i = zeros; i < input.length; i++) {
            int carry = input[i] & 0xFF;
            int k = temp.length - 1;

            while (carry != 0 || k >= j) {
                carry += (temp[k] & 0xFF) * 256;
                temp[k] = (byte) (carry % 58);
                carry /= 58;
                k--;
            }
            j = k + 1;
        }

        while (j < temp.length && temp[j] == 0) {
            j++;
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < zeros; i++) {
            result.append('1');
        }

        for (; j < temp.length; j++) {
            result.append(BASE58_ALPHABET.charAt(temp[j]));
        }

        return result.toString();
    }

    private byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
