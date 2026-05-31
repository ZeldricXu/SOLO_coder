package com.web3platform.addressmanagement.util;

import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.web3j.utils.Numeric;

import java.nio.charset.StandardCharsets;

public class AddressChecksumUtil {

    public static String toChecksumAddress(String address) {
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("Address cannot be null or empty");
        }

        String lowercaseAddress = address.toLowerCase();
        if (lowercaseAddress.startsWith("0x")) {
            lowercaseAddress = lowercaseAddress.substring(2);
        }

        Keccak.Digest256 keccak = new Keccak.Digest256();
        byte[] hash = keccak.digest(lowercaseAddress.getBytes(StandardCharsets.UTF_8));
        String hashHex = Numeric.toHexStringNoPrefix(hash);

        StringBuilder checksumAddress = new StringBuilder("0x");
        for (int i = 0; i < lowercaseAddress.length(); i++) {
            char c = lowercaseAddress.charAt(i);
            if (Character.isLetter(c)) {
                int hashValue = Character.digit(hashHex.charAt(i), 16);
                if (hashValue >= 8) {
                    checksumAddress.append(Character.toUpperCase(c));
                } else {
                    checksumAddress.append(c);
                }
            } else {
                checksumAddress.append(c);
            }
        }

        return checksumAddress.toString();
    }

    public static boolean isValidChecksum(String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }

        if (!address.startsWith("0x")) {
            address = "0x" + address;
        }

        if (address.length() != 42) {
            return false;
        }

        try {
            String checksumAddress = toChecksumAddress(address);
            return address.equals(checksumAddress);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isValidAddressFormat(String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }

        String addr = address.startsWith("0x") ? address.substring(2) : address;

        if (addr.length() != 40) {
            return false;
        }

        return addr.matches("^[0-9a-fA-F]{40}$");
    }

    public static String normalize(String address) {
        if (address == null || address.isEmpty()) {
            return null;
        }

        String addr = address.toLowerCase();
        if (!addr.startsWith("0x")) {
            addr = "0x" + addr;
        }

        return toChecksumAddress(addr);
    }
}
