const Database = require('better-sqlite3');
const path = require('path');
const fs = require('fs');

class DatabaseService {
  constructor(dbPath) {
    this.dbPath = dbPath;
    this.initDatabase();
  }

  initDatabase() {
    const dir = path.dirname(this.dbPath);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    
    this.db = new Database(this.dbPath);
    this.createTables();
    this.migrateData();
  }

  createTables() {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS holdings (
        holding_id TEXT PRIMARY KEY,
        stock_code TEXT NOT NULL,
        stock_name TEXT NOT NULL,
        shares INTEGER NOT NULL,
        avg_cost REAL NOT NULL,
        current_price REAL,
        market_value REAL,
        profit REAL,
        profit_rate REAL,
        buy_date TEXT,
        sector TEXT,
        total_commission REAL DEFAULT 0,
        total_cost_with_commission REAL DEFAULT 0,
        realized_profit REAL DEFAULT 0,
        realized_profit_rate REAL DEFAULT 0,
        created_at TEXT DEFAULT CURRENT_TIMESTAMP,
        updated_at TEXT DEFAULT CURRENT_TIMESTAMP
      );

      CREATE TABLE IF NOT EXISTS trades (
        trade_id TEXT PRIMARY KEY,
        stock_code TEXT NOT NULL,
        stock_name TEXT,
        trade_type TEXT NOT NULL,
        shares INTEGER NOT NULL,
        price REAL NOT NULL,
        amount REAL NOT NULL,
        trade_date TEXT NOT NULL,
        commission REAL DEFAULT 0,
        stamp_duty REAL DEFAULT 0,
        transfer_fee REAL DEFAULT 0,
        total_fees REAL DEFAULT 0,
        realized_profit REAL DEFAULT 0,
        notes TEXT,
        created_at TEXT DEFAULT CURRENT_TIMESTAMP
      );

      CREATE TABLE IF NOT EXISTS quotes_cache (
        stock_code TEXT PRIMARY KEY,
        stock_name TEXT,
        current_price REAL,
        open_price REAL,
        high_price REAL,
        low_price REAL,
        prev_close REAL,
        change_rate REAL,
        volume INTEGER,
        turnover_rate REAL,
        update_time TEXT,
        created_at TEXT DEFAULT CURRENT_TIMESTAMP
      );

      CREATE TABLE IF NOT EXISTS alerts (
        alert_id TEXT PRIMARY KEY,
        stock_code TEXT NOT NULL,
        alert_type TEXT NOT NULL,
        target_price REAL NOT NULL,
        condition TEXT NOT NULL,
        is_active INTEGER DEFAULT 1,
        last_triggered TEXT,
        created_at TEXT DEFAULT CURRENT_TIMESTAMP
      );

      CREATE INDEX IF NOT EXISTS idx_holdings_stock_code ON holdings(stock_code);
      CREATE INDEX IF NOT EXISTS idx_trades_stock_code ON trades(stock_code);
      CREATE INDEX IF NOT EXISTS idx_trades_trade_date ON trades(trade_date);
      CREATE INDEX IF NOT EXISTS idx_alerts_stock_code ON alerts(stock_code);
    `);
  }

  migrateData() {
    try {
      const tableInfo = this.db.pragma('table_info(holdings)');
      const columns = tableInfo.map(col => col.name);
      
      const migrations = [
        { column: 'sector', type: 'TEXT', default: null },
        { column: 'total_commission', type: 'REAL', default: 0 },
        { column: 'total_cost_with_commission', type: 'REAL', default: 0 },
        { column: 'realized_profit', type: 'REAL', default: 0 },
        { column: 'realized_profit_rate', type: 'REAL', default: 0 }
      ];

      for (const migration of migrations) {
        if (!columns.includes(migration.column)) {
          const defaultClause = migration.default !== null 
            ? ` DEFAULT ${migration.default}` 
            : '';
          this.db.exec(`ALTER TABLE holdings ADD COLUMN ${migration.column} ${migration.type}${defaultClause}`);
          console.log(`数据库迁移: 已添加字段 ${migration.column}`);
        }
      }

      const tradesTableInfo = this.db.pragma('table_info(trades)');
      const tradesColumns = tradesTableInfo.map(col => col.name);
      
      const tradesMigrations = [
        { column: 'stamp_duty', type: 'REAL', default: 0 },
        { column: 'transfer_fee', type: 'REAL', default: 0 },
        { column: 'total_fees', type: 'REAL', default: 0 },
        { column: 'realized_profit', type: 'REAL', default: 0 }
      ];

      for (const migration of tradesMigrations) {
        if (!tradesColumns.includes(migration.column)) {
          const defaultClause = migration.default !== null 
            ? ` DEFAULT ${migration.default}` 
            : '';
          this.db.exec(`ALTER TABLE trades ADD COLUMN ${migration.column} ${migration.type}${defaultClause}`);
          console.log(`数据库迁移: 已添加 trades 表字段 ${migration.column}`);
        }
      }
    } catch (error) {
      console.log('数据库迁移跳过:', error.message);
    }
  }

  getDatabase() {
    return this.db;
  }

  close() {
    if (this.db) {
      this.db.close();
    }
  }

  beginTransaction() {
    this.db.exec('BEGIN TRANSACTION');
  }

  commit() {
    this.db.exec('COMMIT');
  }

  rollback() {
    this.db.exec('ROLLBACK');
  }
}

module.exports = DatabaseService;
