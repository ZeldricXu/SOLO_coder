const nock = require('nock');

class MockServerBuilder {
  constructor(baseUrl) {
    this.baseUrl = baseUrl || 'http://localhost:8080';
    this.scopes = [];
  }

  reset() {
    nock.cleanAll();
    this.scopes = [];
    return this;
  }

  mockHealthCheck(statusCode = 200, body = { status: 'ok' }) {
    const scope = nock(this.baseUrl)
      .get('/api/v1/health')
      .reply(statusCode, body);
    this.scopes.push(scope);
    return this;
  }

  mockGasEstimate(chainId, response, statusCode = 200, delay = 0) {
    const scope = nock(this.baseUrl)
      .get(`/api/v1/gas/estimate/${chainId}`)
      .delay(delay)
      .reply(statusCode, {
        code: statusCode,
        data: response
      });
    this.scopes.push(scope);
    return this;
  }

  mockGasHistory(chainId, records, statusCode = 200) {
    const scope = nock(this.baseUrl)
      .get(`/api/v1/gas/history/${chainId}`)
      .query(true)
      .reply(statusCode, {
        code: statusCode,
        data: records
      });
    this.scopes.push(scope);
    return this;
  }

  mockBuildTransaction(response, statusCode = 200) {
    const scope = nock(this.baseUrl)
      .post('/api/v1/transactions/build')
      .reply(statusCode, {
        code: statusCode,
        data: response
      });
    this.scopes.push(scope);
    return this;
  }

  mockSignTransaction(response, statusCode = 200) {
    const scope = nock(this.baseUrl)
      .post('/api/v1/transactions/sign')
      .reply(statusCode, {
        code: statusCode,
        data: response
      });
    this.scopes.push(scope);
    return this;
  }

  mockSendTransaction(txHash, statusCode = 200) {
    const scope = nock(this.baseUrl)
      .post('/api/v1/transactions/send')
      .reply(statusCode, {
        code: statusCode,
        data: { tx_hash: txHash }
      });
    this.scopes.push(scope);
    return this;
  }

  mockGetBlock(chainId, blockNumber, blockData, statusCode = 200) {
    const scope = nock(this.baseUrl)
      .get(`/api/v1/chain/block/${chainId}/${blockNumber}`)
      .reply(statusCode, {
        code: statusCode,
        data: blockData
      });
    this.scopes.push(scope);
    return this;
  }

  mockGetTransaction(chainId, txHash, txData, statusCode = 200) {
    const scope = nock(this.baseUrl)
      .get(`/api/v1/chain/transaction/${chainId}/${txHash}`)
      .reply(statusCode, {
        code: statusCode,
        data: txData
      });
    this.scopes.push(scope);
    return this;
  }

  mockGetBalance(chainId, address, balance, statusCode = 200) {
    const scope = nock(this.baseUrl)
      .get(`/api/v1/chain/balance/${chainId}/${address}`)
      .reply(statusCode, {
        code: statusCode,
        data: { address, balance }
      });
    this.scopes.push(scope);
    return this;
  }

  mockRPCError(path, statusCode, errorCode, errorMessage) {
    const scope = nock(this.baseUrl)
      .post(path)
      .reply(statusCode, {
        error: {
          code: errorCode,
          message: errorMessage
        }
      });
    this.scopes.push(scope);
    return this;
  }

  mockTimeout(path, method = 'get', timeoutMs = 10000) {
    const scope = nock(this.baseUrl)
      [method](path)
      .delay(timeoutMs)
      .reply(504, {
        code: 504,
        message: 'Gateway Timeout'
      });
    this.scopes.push(scope);
    return this;
  }

  mockRateLimit(path, method = 'get') {
    const scope = nock(this.baseUrl)
      [method](path)
      .times(2)
      .reply(429, {
        code: 429,
        message: 'Rate Limit Exceeded'
      });
    this.scopes.push(scope);
    return this;
  }

  mockFallbackResponse(fallbackData) {
    const scope = nock(this.baseUrl)
      .get(/.*/)
      .reply(200, {
        code: 200,
        data: fallbackData,
        from_cache: true
      });
    this.scopes.push(scope);
    return this;
  }

  mockRPCServer(rpcUrl) {
    return new RPCMockBuilder(rpcUrl);
  }

  done() {
    return this.scopes.every(scope => scope.isDone());
  }
}

class RPCMockBuilder {
  constructor(rpcUrl) {
    this.rpcUrl = rpcUrl;
    this.scope = nock(rpcUrl);
  }

  mockEthBlockNumber(blockNumber, delay = 0) {
    this.scope
      .post('/')
      .filteringRequestBody(body => {
        const parsed = JSON.parse(body);
        return parsed.method === 'eth_blockNumber';
      })
      .delay(delay)
      .reply(200, {
        jsonrpc: '2.0',
        id: 1,
        result: '0x' + blockNumber.toString(16)
      });
    return this;
  }

  mockEthGetBlockByNumber(blockData, delay = 0) {
    this.scope
      .post('/')
      .filteringRequestBody(body => {
        const parsed = JSON.parse(body);
        return parsed.method === 'eth_getBlockByNumber';
      })
      .delay(delay)
      .reply(200, {
        jsonrpc: '2.0',
        id: 1,
        result: blockData
      });
    return this;
  }

  mockEthGasPrice(gasPrice) {
    this.scope
      .post('/')
      .filteringRequestBody(body => {
        const parsed = JSON.parse(body);
        return parsed.method === 'eth_gasPrice';
      })
      .reply(200, {
        jsonrpc: '2.0',
        id: 1,
        result: '0x' + gasPrice.toString(16)
      });
    return this;
  }

  mockEthMaxPriorityFeePerGas(fee) {
    this.scope
      .post('/')
      .filteringRequestBody(body => {
        const parsed = JSON.parse(body);
        return parsed.method === 'eth_maxPriorityFeePerGas';
      })
      .reply(200, {
        jsonrpc: '2.0',
        id: 1,
        result: '0x' + fee.toString(16)
      });
    return this;
  }

  mockEthSendRawTransaction(txHash) {
    this.scope
      .post('/')
      .filteringRequestBody(body => {
        const parsed = JSON.parse(body);
        return parsed.method === 'eth_sendRawTransaction';
      })
      .reply(200, {
        jsonrpc: '2.0',
        id: 1,
        result: txHash
      });
    return this;
  }

  mockRPCError(method, errorCode, errorMessage) {
    this.scope
      .post('/')
      .filteringRequestBody(body => {
        const parsed = JSON.parse(body);
        return parsed.method === method;
      })
      .reply(200, {
        jsonrpc: '2.0',
        id: 1,
        error: {
          code: errorCode,
          message: errorMessage
        }
      });
    return this;
  }

  mockNetworkError() {
    this.scope
      .post('/')
      .replyWithError('ECONNREFUSED');
    return this;
  }

  mockTimeout(delayMs = 30000) {
    this.scope
      .post('/')
      .delay(delayMs)
      .reply(200, {});
    return this;
  }
}

module.exports = MockServerBuilder;
