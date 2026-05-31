package com.web3platform.crosschainbridge.service;

import com.web3platform.crosschainbridge.model.CrossChainMessage;
import com.web3platform.crosschainbridge.model.VerificationResult;

public interface MessageVerifier {

    VerificationResult verifyMessage(CrossChainMessage message, String proof);
}
