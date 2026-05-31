package com.web3platform.txbuilder.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.rlp.RlpDecoder;
import org.web3j.rlp.RlpEncoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;
import org.web3j.rlp.RlpType;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionEncoder {

    public byte[] encode(RawTransaction rawTx) {
        log.info("Encoding transaction");
        try {
            return org.web3j.crypto.TransactionEncoder.encode(rawTx);
        } catch (Exception e) {
            log.error("Failed to encode transaction", e);
            throw new RuntimeException("Failed to encode transaction: " + e.getMessage(), e);
        }
    }

    public String encodeToString(RawTransaction rawTx) {
        return Numeric.toHexString(encode(rawTx));
    }

    public RawTransaction decode(String signedTxHex) {
        log.info("Decoding signed transaction");
        try {
            byte[] txBytes = Numeric.hexStringToByteArray(signedTxHex);
            return decode(txBytes);
        } catch (Exception e) {
            log.error("Failed to decode signed transaction", e);
            throw new RuntimeException("Failed to decode signed transaction: " + e.getMessage(), e);
        }
    }

    public RawTransaction decode(byte[] encodedTx) {
        try {
            RlpList rlpList = RlpDecoder.decode(encodedTx);
            List<RlpType> values = ((RlpList) rlpList.getValues().get(0)).getValues();

            if (values.size() < 6) {
                throw new IllegalArgumentException("Invalid transaction encoding: insufficient fields");
            }

            BigInteger nonce = ((RlpString) values.get(0)).asPositiveBigInteger();
            BigInteger gasPrice = ((RlpString) values.get(1)).asPositiveBigInteger();
            BigInteger gasLimit = ((RlpString) values.get(2)).asPositiveBigInteger();
            String to = ((RlpString) values.get(3)).asString();
            BigInteger value = ((RlpString) values.get(4)).asPositiveBigInteger();
            String data = ((RlpString) values.get(5)).asString();

            return RawTransaction.createTransaction(
                    nonce,
                    gasPrice,
                    gasLimit,
                    to,
                    value,
                    data
            );

        } catch (Exception e) {
            log.error("Failed to decode transaction bytes", e);
            throw new RuntimeException("Failed to decode transaction: " + e.getMessage(), e);
        }
    }

    public String hash(RawTransaction rawTx) {
        log.info("Calculating transaction hash");
        try {
            byte[] encoded = encode(rawTx);
            byte[] hash = Hash.sha3(encoded);
            return Numeric.toHexString(hash);
        } catch (Exception e) {
            log.error("Failed to calculate transaction hash", e);
            throw new RuntimeException("Failed to calculate transaction hash: " + e.getMessage(), e);
        }
    }

    public String hash(String signedTxHex) {
        try {
            byte[] txBytes = Numeric.hexStringToByteArray(signedTxHex);
            byte[] hash = Hash.sha3(txBytes);
            return Numeric.toHexString(hash);
        } catch (Exception e) {
            log.error("Failed to calculate hash from signed transaction hex", e);
            throw new RuntimeException("Failed to calculate hash: " + e.getMessage(), e);
        }
    }

    public boolean isEip1559Transaction(String signedTxHex) {
        try {
            byte[] txBytes = Numeric.hexStringToByteArray(signedTxHex);
            return txBytes.length > 0 && txBytes[0] == 0x02;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isEip2930Transaction(String signedTxHex) {
        try {
            byte[] txBytes = Numeric.hexStringToByteArray(signedTxHex);
            return txBytes.length > 0 && txBytes[0] == 0x01;
        } catch (Exception e) {
            return false;
        }
    }

    public String getTransactionType(String signedTxHex) {
        if (isEip1559Transaction(signedTxHex)) {
            return "EIP1559";
        } else if (isEip2930Transaction(signedTxHex)) {
            return "EIP2930";
        } else {
            return "LEGACY";
        }
    }

    public BigInteger getChainIdFromSignedTx(String signedTxHex) {
        try {
            byte[] txBytes = Numeric.hexStringToByteArray(signedTxHex);

            if (isEip1559Transaction(signedTxHex) || isEip2930Transaction(signedTxHex)) {
                byte[] txData = new byte[txBytes.length - 1];
                System.arraycopy(txBytes, 1, txData, 0, txData.length);
                RlpList rlpList = RlpDecoder.decode(txData);
                List<RlpType> values = ((RlpList) rlpList.getValues().get(0)).getValues();
                if (!values.isEmpty()) {
                    return ((RlpString) values.get(0)).asPositiveBigInteger();
                }
            } else {
                RlpList rlpList = RlpDecoder.decode(txBytes);
                List<RlpType> values = ((RlpList) rlpList.getValues().get(0)).getValues();

                if (values.size() > 6) {
                    byte v = ((RlpString) values.get(6)).getBytes()[0];
                    long chainId = extractChainIdFromV(v);
                    if (chainId > 0) {
                        return BigInteger.valueOf(chainId);
                    }
                }
            }

            return BigInteger.ZERO;
        } catch (Exception e) {
            log.warn("Failed to extract chainId from signed transaction", e);
            return BigInteger.ZERO;
        }
    }

    public long extractChainIdFromV(byte v) {
        int vInt = v & 0xFF;
        if (vInt >= 35) {
            return (vInt - 35) / 2;
        } else if (vInt == 27 || vInt == 28) {
            return 0;
        }
        return 0;
    }

    public byte[] encodeEip1559Transaction(BigInteger chainId,
                                            BigInteger nonce,
                                            BigInteger maxPriorityFeePerGas,
                                            BigInteger maxFeePerGas,
                                            BigInteger gasLimit,
                                            String to,
                                            BigInteger value,
                                            String data,
                                            List<Object> accessList) {
        List<RlpType> result = new ArrayList<>();
        result.add(RlpString.create(chainId));
        result.add(RlpString.create(nonce));
        result.add(RlpString.create(maxPriorityFeePerGas));
        result.add(RlpString.create(maxFeePerGas));
        result.add(RlpString.create(gasLimit));
        result.add(RlpString.create(to));
        result.add(RlpString.create(value));
        result.add(RlpString.create(data));

        if (accessList != null && !accessList.isEmpty()) {
            result.add(encodeAccessList(accessList));
        } else {
            result.add(new RlpList());
        }

        byte[] encodedRlp = RlpEncoder.encode(new RlpList(result));
        byte[] txType = new byte[]{0x02};
        byte[] resultBytes = new byte[txType.length + encodedRlp.length];
        System.arraycopy(txType, 0, resultBytes, 0, txType.length);
        System.arraycopy(encodedRlp, 0, resultBytes, txType.length, encodedRlp.length);

        return resultBytes;
    }

    private RlpList encodeAccessList(List<Object> accessList) {
        List<RlpType> rlpAccessList = new ArrayList<>();
        for (Object item : accessList) {
            if (item instanceof List) {
                List<RlpType> rlpItem = new ArrayList<>();
                for (Object subItem : (List<?>) item) {
                    if (subItem instanceof String str) {
                        rlpItem.add(RlpString.create(Numeric.hexStringToByteArray(str)));
                    } else if (subItem instanceof byte[] bytes) {
                        rlpItem.add(RlpString.create(bytes));
                    }
                }
                rlpAccessList.add(new RlpList(rlpItem));
            }
        }
        return new RlpList(rlpAccessList);
    }

    private byte[] zeroPadded(byte[] value, int length) {
        if (value.length == length) {
            return value;
        }
        byte[] result = new byte[length];
        int offset = length - value.length;
        System.arraycopy(value, 0, result, offset, value.length);
        return result;
    }
}
