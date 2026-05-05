const axios = require('axios');

class QuoteFetcher {
  constructor(configService) {
    this.configService = configService;
    this.cache = new Map();
    this.cacheTimestamps = new Map();
    
    this.reloadConfig();
  }

  reloadConfig() {
    const dataSourceConfig = this.configService.getDataSourceConfig();
    const quoteConfig = this.configService.getQuoteConfig();
    
    this.dataSourceType = dataSourceConfig.type;
    this.batchSize = dataSourceConfig.batch_size;
    this.concurrentRequests = dataSourceConfig.concurrent_requests;
    this.tushareToken = dataSourceConfig.tushare_token;
    this.sinaApiUrl = dataSourceConfig.sina_api_url;
    
    this.maxRetries = quoteConfig.max_retries;
    this.retryDelay = quoteConfig.retry_delay_ms;
    this.cacheTtlMs = quoteConfig.cache_ttl_ms;
    
    console.log(`QuoteFetcher 配置已加载: 数据源=${this.dataSourceType}, 批量大小=${this.batchSize}, 并发=${this.concurrentRequests}`);
  }

  getCurrentConfig() {
    return {
      data_source_type: this.dataSourceType,
      batch_size: this.batchSize,
      concurrent_requests: this.concurrentRequests,
      max_retries: this.maxRetries,
      retry_delay_ms: this.retryDelay,
      cache_ttl_ms: this.cacheTtlMs
    };
  }

  isCacheValid(stockCode) {
    if (!this.cacheTimestamps.has(stockCode)) {
      return false;
    }
    const timestamp = this.cacheTimestamps.get(stockCode);
    return Date.now() - timestamp < this.cacheTtlMs;
  }

  async getQuotes(stockCodes) {
    if (!stockCodes || stockCodes.length === 0) {
      return [];
    }

    const uniqueCodes = [...new Set(stockCodes)];
    const results = [];
    const cachedCodes = [];
    const needFetchCodes = [];

    for (const code of uniqueCodes) {
      if (this.isCacheValid(code)) {
        cachedCodes.push(code);
      } else {
        needFetchCodes.push(code);
      }
    }

    for (const code of cachedCodes) {
      const cachedQuote = this.cache.get(code);
      if (cachedQuote) {
        results.push({
          ...cachedQuote,
          isCached: true,
          update_time: new Date().toISOString()
        });
      }
    }

    if (needFetchCodes.length > 0) {
      try {
        const fetchResults = await this.fetchQuotesFromSource(needFetchCodes);
        
        for (const result of fetchResults) {
          if (result) {
            results.push(result);
            this.cache.set(result.stock_code, result);
            this.cacheTimestamps.set(result.stock_code, Date.now());
          }
        }

        const failedCodes = needFetchCodes.filter(code => 
          !results.some(r => r.stock_code === code)
        );

        for (const code of failedCodes) {
          const cachedQuote = this.cache.get(code);
          if (cachedQuote) {
            results.push({
              ...cachedQuote,
              isCached: true,
              isFallback: true,
              update_time: new Date().toISOString()
            });
          }
        }
      } catch (error) {
        console.error('批量请求完全失败，使用缓存数据:', error.message);
        for (const code of needFetchCodes) {
          const cachedQuote = this.cache.get(code);
          if (cachedQuote) {
            results.push({
              ...cachedQuote,
              isCached: true,
              isFallback: true,
              update_time: new Date().toISOString()
            });
          }
        }
      }
    }

    return results;
  }

  async fetchQuotesFromSource(stockCodes) {
    switch (this.dataSourceType) {
      case 'tushare':
        return this.fetchFromTushare(stockCodes);
      case 'sina':
        return this.fetchFromSina(stockCodes);
      case 'mock':
      default:
        return this.fetchMockQuotes(stockCodes);
    }
  }

  async fetchFromTushare(stockCodes) {
    if (!this.tushareToken) {
      console.warn('Tushare Token 未配置，使用 mock 数据');
      return this.fetchMockQuotes(stockCodes);
    }

    try {
      const results = [];
      
      for (let i = 0; i < stockCodes.length; i += this.batchSize) {
        const batch = stockCodes.slice(i, i + this.batchSize);
        const batchResults = await this.fetchTushareBatch(batch);
        results.push(...batchResults);
      }

      return results;
    } catch (error) {
      console.error('Tushare 请求失败:', error.message);
      throw error;
    }
  }

  async fetchTushareBatch(stockCodes) {
    const codesParam = stockCodes.join(',');
    
    try {
      const response = await axios.post('http://api.tushare.pro', {
        api_name: 'daily',
        token: this.tushareToken,
        params: {
          ts_code: codesParam,
          trade_date: new Date().toISOString().split('T')[0].replace(/-/g, '')
        },
        fields: 'ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg,vol,amount'
      });

      const data = response.data.data || {};
      const items = data.items || [];
      const fields = data.fields || [];

      const results = [];
      for (const item of items) {
        const quote = this.parseTushareQuote(fields, item);
        if (quote) {
          results.push(quote);
        }
      }

      return results;
    } catch (error) {
      console.error('Tushare 批量请求失败:', error.message);
      throw error;
    }
  }

  parseTushareQuote(fields, item) {
    const fieldMap = {};
    fields.forEach((field, index) => {
      fieldMap[field] = item[index];
    });

    const tsCode = fieldMap.ts_code;
    const stockCode = tsCode ? tsCode.split('.')[0] : '';
    const changeRate = fieldMap.pct_chg || 0;
    const currentPrice = fieldMap.close || 0;
    const prevClose = fieldMap.pre_close || 0;

    return {
      stock_code: stockCode,
      stock_name: stockCode,
      current_price: parseFloat(currentPrice),
      open_price: parseFloat(fieldMap.open || 0),
      high_price: parseFloat(fieldMap.high || 0),
      low_price: parseFloat(fieldMap.low || 0),
      prev_close: parseFloat(prevClose),
      change_rate: parseFloat(changeRate),
      change: parseFloat(fieldMap.change || 0),
      volume: parseInt(fieldMap.vol || 0) * 100,
      turnover_rate: 0,
      update_time: new Date().toISOString(),
      isCached: false,
      source: 'tushare'
    };
  }

  async fetchFromSina(stockCodes) {
    try {
      const results = [];
      
      for (let i = 0; i < stockCodes.length; i += this.batchSize) {
        const batch = stockCodes.slice(i, i + this.batchSize);
        const batchResults = await this.fetchSinaBatch(batch);
        results.push(...batchResults);
      }

      return results;
    } catch (error) {
      console.error('Sina 请求失败:', error.message);
      throw error;
    }
  }

  async fetchSinaBatch(stockCodes) {
    const sinaCodes = stockCodes.map(code => {
      if (code.startsWith('6')) {
        return `sh${code}`;
      }
      return `sz${code}`;
    });

    const url = `${this.sinaApiUrl}/list=${sinaCodes.join(',')}`;

    try {
      const response = await axios.get(url, {
        responseType: 'text',
        headers: {
          'Referer': 'https://finance.sina.com.cn'
        }
      });

      const results = this.parseSinaResponse(response.data);
      return results;
    } catch (error) {
      console.error('Sina 批量请求失败:', error.message);
      throw error;
    }
  }

  parseSinaResponse(responseText) {
    const results = [];
    const lines = responseText.split('\n');

    for (const line of lines) {
      if (!line.trim()) continue;

      const match = line.match(/var hq_str_([a-z]+)(\d+)="([^"]+)"/);
      if (!match) continue;

      const [, , stockCode, data] = match;
      const values = data.split(',');

      if (values.length < 32) continue;

      const stockName = values[0];
      const openPrice = parseFloat(values[1]) || 0;
      const prevClose = parseFloat(values[2]) || 0;
      const currentPrice = parseFloat(values[3]) || 0;
      const highPrice = parseFloat(values[4]) || 0;
      const lowPrice = parseFloat(values[5]) || 0;
      const volume = parseFloat(values[8]) || 0;
      const turnoverRate = parseFloat(values[38]) || 0;

      const change = currentPrice - prevClose;
      const changeRate = prevClose > 0 ? (change / prevClose) * 100 : 0;

      results.push({
        stock_code: stockCode,
        stock_name: stockName,
        current_price: parseFloat(currentPrice.toFixed(2)),
        open_price: parseFloat(openPrice.toFixed(2)),
        high_price: parseFloat(highPrice.toFixed(2)),
        low_price: parseFloat(lowPrice.toFixed(2)),
        prev_close: parseFloat(prevClose.toFixed(2)),
        change_rate: parseFloat(changeRate.toFixed(2)),
        change: parseFloat(change.toFixed(2)),
        volume: parseInt(volume),
        turnover_rate: parseFloat(turnoverRate.toFixed(2)),
        update_time: new Date().toISOString(),
        isCached: false,
        source: 'sina'
      });
    }

    return results;
  }

  async fetchMockQuotes(stockCodes) {
    const batches = [];
    for (let i = 0; i < stockCodes.length; i += this.batchSize) {
      batches.push(stockCodes.slice(i, i + this.batchSize));
    }

    const allResults = [];

    for (let i = 0; i < batches.length; i += this.concurrentRequests) {
      const batchGroup = batches.slice(i, i + this.concurrentRequests);
      const promises = batchGroup.map(batch => this.fetchSingleBatch(batch));
      const results = await Promise.allSettled(promises);
      
      for (const result of results) {
        if (result.status === 'fulfilled' && result.value) {
          allResults.push(...result.value);
        }
      }
    }

    return allResults;
  }

  async fetchSingleBatch(stockCodes) {
    console.log(`批量请求 ${stockCodes.length} 只股票 (mock): ${stockCodes.join(', ')}`);
    
    const mockQuotes = [];
    for (const code of stockCodes) {
      const quote = this.generateMockQuote(code);
      mockQuotes.push(quote);
      await this.delay(10);
    }

    return mockQuotes;
  }

  generateMockQuote(stockCode) {
    const stockNames = {
      '600519': '贵州茅台',
      '601318': '中国平安',
      '000858': '五粮液',
      '600036': '招商银行',
      '000001': '平安银行',
      '002415': '海康威视',
      '600276': '恒瑞医药',
      '601166': '兴业银行',
      '000333': '美的集团',
      '600887': '伊利股份'
    };

    const basePrices = {
      '600519': 2050,
      '601318': 45,
      '000858': 150,
      '600036': 35,
      '000001': 12,
      '002415': 40,
      '600276': 50,
      '601166': 20,
      '000333': 60,
      '600887': 30
    };

    const basePrice = basePrices[stockCode] || 50;
    const stockName = stockNames[stockCode] || `股票${stockCode}`;
    
    const prevClose = this.cache.has(stockCode) 
      ? this.cache.get(stockCode).current_price 
      : basePrice;
    
    const changePercent = (Math.random() - 0.5) * 6;
    const changeAmount = prevClose * (changePercent / 100);
    const currentPrice = prevClose + changeAmount;
    
    const openPrice = prevClose + (Math.random() - 0.5) * prevClose * 0.02;
    const highPrice = Math.max(currentPrice, openPrice) * (1 + Math.random() * 0.01);
    const lowPrice = Math.min(currentPrice, openPrice) * (1 - Math.random() * 0.01);

    const quote = {
      stock_code: stockCode,
      stock_name: stockName,
      current_price: parseFloat(currentPrice.toFixed(2)),
      open_price: parseFloat(openPrice.toFixed(2)),
      high_price: parseFloat(highPrice.toFixed(2)),
      low_price: parseFloat(lowPrice.toFixed(2)),
      prev_close: parseFloat(prevClose.toFixed(2)),
      change_rate: parseFloat(changePercent.toFixed(2)),
      change: parseFloat(changeAmount.toFixed(2)),
      volume: Math.floor(Math.random() * 10000000) + 1000000,
      turnover_rate: parseFloat((Math.random() * 5).toFixed(2)),
      update_time: new Date().toISOString(),
      isCached: false,
      source: 'mock'
    };

    return quote;
  }

  getCachedQuote(stockCode) {
    return this.cache.get(stockCode) || null;
  }

  getAllCachedQuotes() {
    return Array.from(this.cache.values());
  }

  clearCache() {
    this.cache.clear();
    this.cacheTimestamps.clear();
  }

  async fetchQuotesIndividually(stockCodes) {
    const results = [];
    
    for (const code of stockCodes) {
      const quote = await this.fetchQuoteWithRetry(code);
      if (quote) {
        results.push(quote);
      }
    }

    return results;
  }

  async fetchQuoteWithRetry(stockCode) {
    let lastError = null;
    
    for (let attempt = 0; attempt < this.maxRetries; attempt++) {
      try {
        return await this.fetchQuote(stockCode);
      } catch (error) {
        lastError = error;
        console.log(`获取 ${stockCode} 行情失败，第 ${attempt + 1} 次重试...`);
        
        if (attempt < this.maxRetries - 1) {
          await this.delay(this.retryDelay * (attempt + 1));
        }
      }
    }
    
    console.error(`获取 ${stockCode} 行情最终失败:`, lastError?.message);
    return null;
  }

  async fetchQuote(stockCode) {
    const results = await this.fetchQuotesFromSource([stockCode]);
    return results[0] || null;
  }

  delay(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
  }
}

module.exports = QuoteFetcher;
