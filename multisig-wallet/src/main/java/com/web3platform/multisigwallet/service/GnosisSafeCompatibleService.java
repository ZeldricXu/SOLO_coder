package com.web3platform.multisigwallet.service;

import com.web3platform.multisigwallet.config.MultisigWalletConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;

@Slf4j
@Service
@RequiredArgsConstructor
public class GnosisSafeCompatibleService {

    private final MultisigWalletConfig walletConfig;

    public String buildExecTransactionData(
            String to,
            BigInteger value,
            String data,
            String signatures,
            int threshold
    ) {
        log.debug("Building execTransaction data for Gnosis Safe compatible execution");

        if (!walletConfig.getGnosisSafe().isEnabled()) {
            log.warn("Gnosis Safe compatibility is disabled, returning raw data");
            return data != null ? data : "0x";
        }

        try {
            byte[] dataBytes = data != null && !data.isEmpty() ? Numeric.hexStringToByteArray(data) : new byte[0];
            byte[] signatureBytes = signatures != null && !signatures.isEmpty() ? Numeric.hexStringToByteArray(signatures) : new byte[0];

            Function function = new Function(
                    "execTransaction",
                    Arrays.asList(
                            new Address(to),
                            new Uint256(value),
                            new DynamicBytes(dataBytes),
                            new Uint8(0),
                            new Uint256(0),
                            new Uint256(0),
                            new Uint256(0),
                            new Address("0x0000000000000000000000000000000000000000"),
                            new Address("0x0000000000000000000000000000000000000000"),
                            new DynamicBytes(signatureBytes)
                    ),
                    Collections.<TypeReference<?>>emptyList()
            );

            String encoded = FunctionEncoder.encode(function);
            log.debug("execTransaction data encoded successfully, length: {}", encoded.length());
            return encoded;

        } catch (Exception e) {
            log.error("Failed to build execTransaction data", e);
            return data != null ? data : "0x";
        }
    }

    public String getGnosisSafeSingletonAddress(String chainType) {
        if (walletConfig.getGnosisSafe().getSingletonAddress() == null) {
            return null;
        }
        return walletConfig.getGnosisSafe().getSingletonAddress().get(chainType);
    }

    public boolean isGnosisSafeEnabled() {
        return walletConfig.getGnosisSafe().isEnabled();
    }
}
