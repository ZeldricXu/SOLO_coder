package com.web3platform.addressmanagement.service;

import com.web3platform.addressmanagement.util.AddressChecksumUtil;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Hash;

@Service
public class AddressValidator {

    public boolean validate(String address, String chainType) {
        if (address == null || address.isEmpty()) {
            return false;
        }

        return switch (chainType.toUpperCase()) {
            case "ETH", "BSC", "POLYGON", "MATIC" -> validateEvmAddress(address);
            case "BTC", "LTC", "DOGE" -> validateBase58Address(address, chainType);
            default -> throw new IllegalArgumentException("Unsupported chain type: " + chainType);
        };
    }

    public String normalize(String address, String chainType) {
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("Address cannot be null or empty");
        }

        return switch (chainType.toUpperCase()) {
            case "ETH", "BSC", "POLYGON", "MATIC" -> normalizeEvmAddress(address);
            case "BTC", "LTC", "DOGE" -> normalizeBase58Address(address);
            default -> throw new IllegalArgumentException("Unsupported chain type: " + chainType);
        };
    }

    private boolean validateEvmAddress(String address) {
        if (!AddressChecksumUtil.isValidAddressFormat(address)) {
            return false;
        }

        String addr = address.startsWith("0x") ? address : "0x" + address;

        if (addr.equals(addr.toLowerCase()) || addr.equals(addr.toUpperCase())) {
            return true;
        }

        return AddressChecksumUtil.isValidChecksum(addr);
    }

    private String normalizeEvmAddress(String address) {
        if (!validateEvmAddress(address)) {
            throw new IllegalArgumentException("Invalid EVM address format: " + address);
        }
        return AddressChecksumUtil.normalize(address);
    }

    private boolean validateBase58Address(String address, String chainType) {
        if (address == null || address.length() < 26 || address.length() > 35) {
            return false;
        }

        try {
            byte[] decoded = decodeBase58(address);
            if (decoded.length != 25) {
                return false;
            }

            byte[] payload = new byte[21];
            System.arraycopy(decoded, 0, payload, 0, 21);

            byte[] checksum = new byte[4];
            System.arraycopy(decoded, 21, checksum, 0, 4);

            byte[] hash1 = Hash.sha256(payload);
            byte[] hash2 = Hash.sha256(hash1);

            for (int i = 0; i < 4; i++) {
                if (hash2[i] != checksum[i]) {
                    return false;
                }
            }

            byte expectedVersion = switch (chainType.toUpperCase()) {
                case "BTC" -> 0x00;
                case "LTC" -> 0x30;
                case "DOGE" -> 0x1E;
                default -> throw new IllegalArgumentException("Unsupported chain type: " + chainType);
            };

            return decoded[0] == expectedVersion;
        } catch (Exception e) {
            return false;
        }
    }

    private String normalizeBase58Address(String address) {
        if (!address.startsWith("1") && !address.startsWith("3") && !address.startsWith("L")) {
            throw new IllegalArgumentException("Invalid base58 address format: " + address);
        }
        return address.trim();
    }

    private static final String BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    private byte[] decodeBase58(String input) {
        if (input.isEmpty()) {
            return new byte[0];
        }

        byte[] inputBytes = new byte[input.length()];
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int digit = BASE58_ALPHABET.indexOf(c);
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid character in Base58 string: " + c);
            }
            inputBytes[i] = (byte) digit;
        }

        int zeros = 0;
        while (zeros < inputBytes.length && inputBytes[zeros] == 0) {
            zeros++;
        }

        byte[] decoded = new byte[input.length()];
        int outputStart = decoded.length;

        for (int inputStart = zeros; inputStart < inputBytes.length; inputStart++) {
            int carry = inputBytes[inputStart] & 0xFF;
            int i = decoded.length - 1;

            while (carry != 0 || i >= outputStart) {
                carry += (decoded[i] & 0xFF) * 58;
                decoded[i] = (byte) (carry % 256);
                carry /= 256;
                i--;
            }
            outputStart = i + 1;
        }

        while (outputStart < decoded.length && decoded[outputStart] == 0) {
            outputStart++;
        }

        byte[] result = new byte[zeros + decoded.length - outputStart];
        System.arraycopy(decoded, outputStart, result, zeros, decoded.length - outputStart);

        return result;
    }
}
