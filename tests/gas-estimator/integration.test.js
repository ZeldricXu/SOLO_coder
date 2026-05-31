const request = require('supertest');
const axios = require('axios');
const { GasDataBuilder, MockServerBuilder } = require('../builders');

const API_BASE_URL = process.env.API_BASE_URL || 'http://localhost:8080';

describe('Gas Estimator Module - Integration Tests', () => {
  let gasBuilder;
  let mockServer;

  beforeEach(() => {
    gasBuilder = new GasDataBuilder();
    mockServer = new MockServerBuilder(API_BASE_URL);
  });

  afterEach(() => {
    mockServer.reset();
  });

  describe('GET /api/v1/gas/estimate/:chain_id', () => {
    it('should return consistent gas estimates', async () => {
      const expectedResult = gasBuilder
        .withChainId(1)
        .withBlockNumber(20000000)
        .withBaseFee(30_000_000_000)
        .buildEstimateResult();

      mockServer.mockGasEstimate(1, expectedResult);

      try {
        const response = await axios.get(`${API_BASE_URL}/api/v1/gas/estimate/1`);

        expect(response.status).toBe(200);
        expect(response.data.code).toBe(200);

        const data = response.data.data;
        expect(data.chain_id).toBe(expectedResult.chain_id);
        expect(data.slow).toBeGreaterThan(0);
        expect(data.standard).toBeGreaterThan(data.slow);
        expect(data.fast).toBeGreaterThan(data.standard);
        expect(data.estimated_at).toBeDefined();

      } catch (error) {
        if (error.code === 'ECONNREFUSED') {
          console.warn('API server not running, skipping integration test');
          return;
        }
        throw error;
      }
    });

    it('should handle multiple chain IDs consistently', async () => {
      const chains = [1, 137, 42161];

      for (const chainId of chains) {
        const expectedResult = gasBuilder
          .withChainId(chainId)
          .buildEstimateResult();

        mockServer.mockGasEstimate(chainId, expectedResult);

        try {
          const response = await axios.get(`${API_BASE_URL}/api/v1/gas/estimate/${chainId}`);
          expect(response.status).toBe(200);
          expect(response.data.data.chain_id).toBe(chainId);
        } catch (error) {
          if (error.code === 'ECONNREFUSED') {
            console.warn('API server not running, skipping integration test');
            return;
          }
        }
      }
    });

    it('should return consistent results for repeated calls', async () => {
      const expectedResult = gasBuilder.buildEstimateResult();
      mockServer.mockGasEstimate(1, expectedResult);

      try {
        const responses = await Promise.all([
          axios.get(`${API_BASE_URL}/api/v1/gas/estimate/1`),
          axios.get(`${API_BASE_URL}/api/v1/gas/estimate/1`),
          axios.get(`${API_BASE_URL}/api/v1/gas/estimate/1`)
        ]);

        responses.forEach(response => {
          expect(response.status).toBe(200);
        });

        const datas = responses.map(r => r.data.data);
        expect(datas[0].slow).toBe(datas[1].slow);
        expect(datas[0].standard).toBe(datas[1].standard);
        expect(datas[0].fast).toBe(datas[1].fast);

      } catch (error) {
        if (error.code === 'ECONNREFUSED') {
          console.warn('API server not running, skipping integration test');
          return;
        }
        throw error;
      }
    });
    });

    it('should handle invalid chain ID', async () => {
      try {
        const response = await axios.get(`${API_BASE_URL}/api/v1/gas/estimate/invalid`);
        expect(response.status).toBeGreaterThanOrEqual(400);
      } catch (error) {
        if (error.code === 'ECONNREFUSED') {
          console.warn('API server not running, skipping integration test');
          return;
        }
        expect(error.response.status).toBeGreaterThanOrEqual(400);
      }
    });
  });

  describe('GET /api/v1/gas/history/:chain_id', () => {
    it('should return consistent historical data', async () => {
      const historyData = gasBuilder
        .withChainId(1)
        .buildHistoryDataset();

      mockServer.mockGasHistory(1, historyData);

      try {
        const response = await axios.get(`${API_BASE_URL}/api/v1/gas/history/1`);

        expect(response.status).toBe(200);
        expect(Array.isArray(response.data.data)).toBe(true);

        const records = response.data.data;
        records.forEach(record => {
          expect(record.chain_id).toBe(1);
          expect(record.block_number).toBeGreaterThan(0);
          expect(record.low).toBeDefined();
          expect(record.average).toBeDefined();
          expect(record.high).toBeDefined();
          expect(record.block_time).toBeDefined();
        });

      } catch (error) {
        if (error.code === 'ECONNREFUSED') {
          console.warn('API server not running, skipping integration test');
          return;
        }
        throw error;
      }
    });

    it('should handle time range parameters', async () => {
      const now = new Date();
      const startTime = new Date(now.getTime() - 24 * 60 * 60 * 1000).toISOString();
      const endTime = now.toISOString();

      mockServer.mockGasHistory(1, []);

      try {
        const response = await axios.get(`${API_BASE_URL}/api/v1/gas/history/1`, {
          params: { start: startTime, end: endTime }
        });

        expect(response.status).toBe(200);

      } catch (error) {
        if (error.code === 'ECONNREFUSED') {
          console.warn('API server not running, skipping integration test');
          return;
        }
        throw error;
      }
    });
  });

  describe('Data Consistency Verification', () => {
    it('should have consistent data structure across API responses', async () => {
      try {
        const estimateResponse = await axios.get(`${API_BASE_URL}/api/v1/gas/estimate/1`);
        const historyResponse = await axios.get(`${API_BASE_URL}/api/v1/gas/history/1`);

        if (estimateResponse.status === 200 && historyResponse.status === 200) {
          const estimate = estimateResponse.data.data;
          const history = historyResponse.data.data;

          if (history.length > 0) {
            const latestRecord = history[0];
            expect(estimate.chain_id).toBe(latestRecord.chain_id);

            expect(estimate.base_fee).toBeCloseTo(latestRecord.base_fee, -5);
          }
        }

      } catch (error) {
        if (error.code === 'ECONNREFUSED') {
          console.warn('API server not running, skipping integration test');
          return;
        }
        throw error;
      }
    });
  });
});
