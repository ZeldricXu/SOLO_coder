const { v4: uuidv4 } = require('uuid');
const { ethers } = require('ethers');

class TransactionBuilder {
  constructor() {
    this.reset();
  }

  reset() {
    this.chainId = 1;
    this.from = '0x' + '1'.repeat(40);
    this.to = '0x' + '2'.repeat(40);
    this.value = ethers.parseEther('0.1');
    this.data = '0x';
    this.gasLimit = 21000;
    this.gasPrice = 30_000_000_000;
    this.maxFeePerGas = 50_000_000_000;
    this.maxPriorityFeePerGas = 2_000_000_000;
    this.nonce = 0;
    this.accessList = [];
    this.gasOptimization = true;
    this.multiSigConfig = null;
    this.privateKeys = [];
    return this;
  }

  withChainId(chainId) {
    this.chainId = chainId;
    return this;
  }

  withFrom(from) {
    this.from = from;
    return this;
  }

  withTo(to) {
    this.to = to;
    return this;
  }

  withValue(value) {
    this.value = value;
    return this;
  }

  withData(data) {
    this.data = data;
    return this;
  }

  withGasLimit(gasLimit) {
    this.gasLimit = gasLimit;
    return this;
  }

  withGasPrice(gasPrice) {
    this.gasPrice = gasPrice;
    return this;
  }

  withMaxFeePerGas(maxFeePerGas) {
    this.maxFeePerGas = maxFeePerGas;
    return this;
  }

  withMaxPriorityFeePerGas(maxPriorityFeePerGas) {
    this.maxPriorityFeePerGas = maxPriorityFeePerGas;
    return this;
  }

  withNonce(nonce) {
    this.nonce = nonce;
    return this;
  }

  withGasOptimization(enabled) {
    this.gasOptimization = enabled;
    return this;
  }

  withMultiSig(threshold, signers, safeAddress) {
    this.multiSigConfig = {
      threshold,
      signers,
      safe_address: safeAddress
    };
    return this;
  }

  addSigner(privateKey) {
    this.privateKeys.push(privateKey);
    return this;
  }

  generateRandomSigner() {
    const wallet = ethers.Wallet.createRandom();
    this.privateKeys.push(wallet.privateKey);
    return wallet;
  }

  generateMultiSigSigners(count = 5) {
    const signers = [];
    for (let i = 0; i < count; i++) {
      const wallet = ethers.Wallet.createRandom();
      this.privateKeys.push(wallet.privateKey);
      signers.push(wallet.address);
    }
    return signers;
  }

  buildLegacyTransactionRequest(overrides = {}) {
    return {
      chain_id: this.chainId,
      from: this.from,
      to: this.to,
      value: this.value.toString(),
      data: this.data,
      gas_limit: this.gasLimit,
      gas_price: this.gasPrice,
      nonce: this.nonce,
      gas_optimization: this.gasOptimization,
      ...overrides
    };
  }

  buildEIP1559TransactionRequest(overrides = {}) {
    return {
      chain_id: this.chainId,
      from: this.from,
      to: this.to,
      value: this.value.toString(),
      data: this.data,
      gas_limit: this.gasLimit,
      max_fee_per_gas: this.maxFeePerGas,
      max_priority_fee_per_gas: this.maxPriorityFeePerGas,
      nonce: this.nonce,
      access_list: this.accessList,
      gas_optimization: this.gasOptimization,
      ...overrides
    };
  }

  buildMultiSigTransactionRequest(overrides = {}) {
    return {
      chain_id: this.chainId,
      from: this.multiSigConfig?.safe_address || this.from,
      to: this.to,
      value: this.value.toString(),
      data: this.data,
      gas_limit: this.gasLimit,
      max_fee_per_gas: this.maxFeePerGas,
      max_priority_fee_per_gas: this.maxPriorityFeePerGas,
      nonce: this.nonce,
      gas_optimization: this.gasOptimization,
      multi_sig_config: this.multiSigConfig,
      ...overrides
    };
  }

  buildSignedTransaction(overrides = {}) {
    const txHash = '0x' + Buffer.from(uuidv4().replace(/-/g, '')).toString('hex').slice(0, 64);
    const rawTx = '0x' + Buffer.from(uuidv4().replace(/-/g, '') + uuidv4().replace(/-/g, '')).toString('hex');

    return {
      chain_id: this.chainId,
      tx_hash: txHash,
      raw_tx: rawTx,
      from: this.from,
      to: this.to,
      value: this.value.toString(),
      gas_used: this.gasLimit,
      gas_price: this.gasPrice,
      signatures: [],
      ...overrides
    };
  }

  buildSignedTransactionWithSignatures(signatureCount = 1) {
    const signatures = [];
    for (let i = 0; i < signatureCount; i++) {
      signatures.push('0x' + Buffer.from(uuidv4().replace(/-/g, '')).toString('hex').slice(0, 130));
    }

    return this.buildSignedTransaction({ signatures });
  }

  async buildRealSignedTransaction(privateKey) {
    const wallet = new ethers.Wallet(privateKey);
    const tx = {
      to: this.to,
      value: this.value,
      gasLimit: this.gasLimit,
      gasPrice: this.gasPrice,
      nonce: this.nonce,
      chainId: this.chainId
    };

    const signedTx = await wallet.signTransaction(tx);
    const parsedTx = ethers.Transaction.from(signedTx);

    return {
      chain_id: this.chainId,
      tx_hash: parsedTx.hash,
      raw_tx: signedTx,
      from: wallet.address,
      to: this.to,
      value: this.value.toString(),
      gas_used: this.gasLimit,
      gas_price: this.gasPrice,
      signatures: [signedTx.slice(2)]
    };
  }

  buildContractInteractionData(functionSignature, params = []) {
    const iface = new ethers.Interface([functionSignature]);
    const fragment = iface.fragments[0];
    return iface.encodeFunctionData(fragment, params);
  }

  buildERC20TransferData(to, amount) {
    return this.buildContractInteractionData(
      'function transfer(address to, uint256 amount)',
      [to, amount]
    );
  }

  buildERC721TransferData(from, to, tokenId) {
    return this.buildContractInteractionData(
      'function transferFrom(address from, address to, uint256 tokenId)',
      [from, to, tokenId]
    );
  }
}

module.exports = TransactionBuilder;
