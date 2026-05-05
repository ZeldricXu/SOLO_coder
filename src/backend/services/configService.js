const { v4: uuidv4 } = require('uuid');

class ConfigService {
  constructor(dbService) {
    this.db = dbService.getDatabase();
    this.initConfigTable();
    this.cache = new Map();
    this.loadAllConfigs();
  }

  initConfigTable() {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS app_config (
        config_id TEXT PRIMARY KEY,
        config_key TEXT NOT NULL UNIQUE,
        config_value TEXT,
        config_type TEXT DEFAULT 'string',
        description TEXT,
        is_editable INTEGER DEFAULT 1,
        created_at TEXT DEFAULT CURRENT_TIMESTAMP,
        updated_at TEXT DEFAULT CURRENT_TIMESTAMP
      );
      
      CREATE INDEX IF NOT EXISTS idx_app_config_key ON app_config(config_key);
    `);
  }

  loadAllConfigs() {
    try {
      const stmt = this.db.prepare('SELECT * FROM app_config');
      const rows = stmt.all();
      
      for (const row of rows) {
        this.cache.set(row.config_key, {
          value: this.parseValue(row.config_value, row.config_type),
          type: row.config_type,
          description: row.description,
          editable: row.is_editable === 1
        });
      }
    } catch (error) {
      console.error('加载配置失败:', error.message);
    }
  }

  parseValue(value, type) {
    if (value === null || value === undefined) {
      return null;
    }
    
    switch (type) {
      case 'integer':
        return parseInt(value, 10);
      case 'float':
      case 'number':
        return parseFloat(value);
      case 'boolean':
        return value === 'true' || value === true || value === 1;
      case 'json':
      case 'object':
        try {
          return JSON.parse(value);
        } catch {
          return value;
        }
      default:
        return value;
    }
  }

  serializeValue(value) {
    if (value === null || value === undefined) {
      return null;
    }
    
    const type = typeof value;
    
    if (type === 'object') {
      return JSON.stringify(value);
    }
    
    return String(value);
  }

  getConfigType(value) {
    if (value === null || value === undefined) {
      return 'string';
    }
    
    if (Number.isInteger(value)) {
      return 'integer';
    }
    
    if (typeof value === 'number') {
      return 'float';
    }
    
    if (typeof value === 'boolean') {
      return 'boolean';
    }
    
    if (typeof value === 'object') {
      return 'json';
    }
    
    return 'string';
  }

  setConfig(key, value, description = null, editable = true) {
    const serializedValue = this.serializeValue(value);
    const configType = this.getConfigType(value);
    const now = new Date().toISOString();
    
    const existing = this.db.prepare('SELECT * FROM app_config WHERE config_key = ?').get(key);
    
    if (existing) {
      const stmt = this.db.prepare(`
        UPDATE app_config SET 
          config_value = ?,
          config_type = ?,
          description = COALESCE(?, description),
          updated_at = ?
        WHERE config_key = ?
      `);
      stmt.run(serializedValue, configType, description, now, key);
    } else {
      const configId = uuidv4();
      const stmt = this.db.prepare(`
        INSERT INTO app_config (
          config_id, config_key, config_value, config_type, description, is_editable, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      `);
      stmt.run(configId, key, serializedValue, configType, description, editable ? 1 : 0, now, now);
    }
    
    this.cache.set(key, {
      value,
      type: configType,
      description,
      editable
    });
    return value;
  }

  getConfig(key, defaultValue = null) {
    if (this.cache.has(key)) {
      return this.cache.get(key).value;
    }
    return defaultValue;
  }

  getAllConfigs() {
    const result = {};
    for (const [key, config] of this.cache.entries()) {
      result[key] = config.value;
    }
    return result;
  }

  getConfigDetails() {
    const result = {};
    for (const [key, config] of this.cache.entries()) {
      result[key] = { ...config };
    }
    return result;
  }

  deleteConfig(key) {
    const stmt = this.db.prepare('DELETE FROM app_config WHERE config_key = ?');
    const result = stmt.run(key);
    this.cache.delete(key);
    return result.changes > 0;
  }

  initDefaultConfigs() {
    const defaultConfigs = [
      {
        key: 'data_source.type',
        value: 'mock',
        description: '数据源类型: mock/tushare/sina',
        editable: true
      },
      {
        key: 'data_source.batch_size',
        value: 20,
        description: '批量请求每批股票数量',
        editable: true
      },
      {
        key: 'data_source.concurrent_requests',
        value: 3,
        description: '并发请求批次数量',
        editable: true
      },
      {
        key: 'data_source.tushare_token',
        value: '',
        description: 'Tushare API Token',
        editable: true
      },
      {
        key: 'data_source.sina_api_url',
        value: 'https://hq.sinajs.cn',
        description: '新浪行情API地址',
        editable: false
      },
      {
        key: 'commission.buy_rate',
        value: 0.0003,
        description: '买入佣金费率 (默认万3)',
        editable: true
      },
      {
        key: 'commission.sell_rate',
        value: 0.0003,
        description: '卖出佣金费率 (默认万3)',
        editable: true
      },
      {
        key: 'commission.min_fee',
        value: 5.0,
        description: '最低佣金 (每笔交易最低佣金)',
        editable: true
      },
      {
        key: 'commission.stamp_duty_rate',
        value: 0.001,
        description: '印花税率 (仅卖出时收取，默认千1)',
        editable: true
      },
      {
        key: 'commission.transfer_fee_rate',
        value: 0.00002,
        description: '过户费率 (默认十万分之2)',
        editable: true
      },
      {
        key: 'commission.min_transfer_fee',
        value: 1.0,
        description: '最低过户费',
        editable: true
      },
      {
        key: 'refresh.default_interval_ms',
        value: 60000,
        description: '默认刷新间隔 (毫秒)',
        editable: true
      },
      {
        key: 'refresh.high_volatility_interval_ms',
        value: 30000,
        description: '高波动模式刷新间隔 (毫秒)',
        editable: true
      },
      {
        key: 'refresh.low_volatility_interval_ms',
        value: 120000,
        description: '低波动模式刷新间隔 (毫秒)',
        editable: true
      },
      {
        key: 'refresh.volatility_threshold',
        value: 2.0,
        description: '波动率阈值 (百分比)',
        editable: true
      },
      {
        key: 'refresh.high_volatility_ratio',
        value: 0.3,
        description: '高波动股票比例阈值 (0-1)',
        editable: true
      },
      {
        key: 'refresh.stable_threshold',
        value: 0.5,
        description: '平稳模式阈值 (百分比)',
        editable: true
      },
      {
        key: 'quote.cache_ttl_ms',
        value: 300000,
        description: '行情缓存有效期 (毫秒)',
        editable: true
      },
      {
        key: 'quote.max_retries',
        value: 3,
        description: '最大重试次数',
        editable: true
      },
      {
        key: 'quote.retry_delay_ms',
        value: 1000,
        description: '重试间隔 (毫秒)',
        editable: true
      }
    ];

    for (const config of defaultConfigs) {
      if (!this.cache.has(config.key)) {
        this.setConfig(config.key, config.value, config.description, config.editable);
      }
    }

    console.log('默认配置已初始化');
  }

  getDataSourceConfig() {
    return {
      type: this.getConfig('data_source.type', 'mock'),
      batch_size: this.getConfig('data_source.batch_size', 20),
      concurrent_requests: this.getConfig('data_source.concurrent_requests', 3),
      tushare_token: this.getConfig('data_source.tushare_token', ''),
      sina_api_url: this.getConfig('data_source.sina_api_url', 'https://hq.sinajs.cn')
    };
  }

  getCommissionConfig() {
    return {
      buy_rate: this.getConfig('commission.buy_rate', 0.0003),
      sell_rate: this.getConfig('commission.sell_rate', 0.0003),
      min_fee: this.getConfig('commission.min_fee', 5.0),
      stamp_duty_rate: this.getConfig('commission.stamp_duty_rate', 0.001),
      transfer_fee_rate: this.getConfig('commission.transfer_fee_rate', 0.00002),
      min_transfer_fee: this.getConfig('commission.min_transfer_fee', 1.0)
    };
  }

  getRefreshConfig() {
    return {
      default_interval_ms: this.getConfig('refresh.default_interval_ms', 60000),
      high_volatility_interval_ms: this.getConfig('refresh.high_volatility_interval_ms', 30000),
      low_volatility_interval_ms: this.getConfig('refresh.low_volatility_interval_ms', 120000),
      volatility_threshold: this.getConfig('refresh.volatility_threshold', 2.0),
      high_volatility_ratio: this.getConfig('refresh.high_volatility_ratio', 0.3),
      stable_threshold: this.getConfig('refresh.stable_threshold', 0.5)
    };
  }

  getQuoteConfig() {
    return {
      cache_ttl_ms: this.getConfig('quote.cache_ttl_ms', 300000),
      max_retries: this.getConfig('quote.max_retries', 3),
      retry_delay_ms: this.getConfig('quote.retry_delay_ms', 1000)
    };
  }
}

module.exports = ConfigService;
