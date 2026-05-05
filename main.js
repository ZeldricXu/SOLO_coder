const { app, BrowserWindow, ipcMain, Notification, dialog } = require('electron');
const path = require('path');
const isDev = require('electron-is-dev');

const DatabaseService = require('./src/backend/services/databaseService');
const QuoteFetcher = require('./src/backend/services/quoteFetcher');
const HoldingManager = require('./src/backend/services/holdingManager');
const TradeManager = require('./src/backend/services/tradeManager');
const ProfitCalculator = require('./src/backend/services/profitCalculator');
const AlertService = require('./src/backend/services/alertService');
const ConfigService = require('./src/backend/services/configService');
const { RefreshStrategy, FixedIntervalRefreshStrategy, TimeBasedRefreshStrategy } = require('./src/backend/services/refreshStrategy');

let mainWindow;
let dbService;
let configService;
let quoteFetcher;
let holdingManager;
let tradeManager;
let profitCalculator;
let alertService;
let refreshStrategy;
let quoteRefreshInterval = null;

let currentRefreshIntervalMs = 60000;
let currentRefreshMode = 'normal';
let manualRefreshInProgress = false;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 1200,
    minHeight: 700,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: path.join(__dirname, 'preload.js')
    },
    icon: path.join(__dirname, 'assets', 'icon.png'),
    title: 'StockTracker - 投资组合管理'
  });

  const startURL = isDev
    ? 'http://localhost:3000'
    : `file://${path.join(__dirname, 'build', 'index.html')}`;

  mainWindow.loadURL(startURL);

  if (isDev) {
    mainWindow.webContents.openDevTools();
  }

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

function initializeServices() {
  const dbPath = isDev 
    ? path.join(__dirname, 'data', 'stocktracker.db')
    : path.join(app.getPath('userData'), 'stocktracker.db');
  
  dbService = new DatabaseService(dbPath);
  configService = new ConfigService(dbService);
  configService.initDefaultConfigs();
  
  quoteFetcher = new QuoteFetcher(configService);
  holdingManager = new HoldingManager(dbService);
  tradeManager = new TradeManager(dbService, configService);
  profitCalculator = new ProfitCalculator();
  alertService = new AlertService(dbService);
  
  const refreshConfig = configService.getRefreshConfig();
  currentRefreshIntervalMs = refreshConfig.default_interval_ms;
  
  refreshStrategy = new RefreshStrategy(configService);
  refreshStrategy.setOnIntervalChangeCallback(handleRefreshIntervalChange);
  
  console.log('刷新策略已初始化，当前刷新间隔:', currentRefreshIntervalMs / 1000, '秒');
  
  setupQuoteRefresh();
}

function handleRefreshIntervalChange(data) {
  const { old_mode, old_interval_ms, new_mode, new_interval_ms, volatility_status } = data;
  
  currentRefreshMode = new_mode;
  currentRefreshIntervalMs = new_interval_ms;
  
  setupQuoteRefresh();
  
  if (mainWindow) {
    mainWindow.webContents.send('refresh-interval-changed', {
      old_mode: old_mode,
      old_interval_ms: old_interval_ms,
      new_mode: new_mode,
      interval_ms: new_interval_ms,
      is_high_volatility: new_mode === 'high_volatility',
      volatility_status: volatility_status
    });
  }
  
  console.log(`刷新间隔已变更: ${old_mode}(${old_interval_ms}ms) -> ${new_mode}(${new_interval_ms}ms)`);
}

function setupQuoteRefresh() {
  if (quoteRefreshInterval) {
    clearInterval(quoteRefreshInterval);
  }
  
  console.log(`设置行情刷新间隔: ${currentRefreshIntervalMs / 1000}秒, 模式: ${currentRefreshMode}`);
  
  quoteRefreshInterval = setInterval(async () => {
    if (!manualRefreshInProgress) {
      await refreshQuotes();
    }
  }, currentRefreshIntervalMs);
  
  refreshQuotes();
}

function evaluateRefreshStrategy(quotes) {
  if (!refreshStrategy) {
    return;
  }
  
  const result = refreshStrategy.evaluateAndUpdate(quotes);
  return result;
}

async function refreshQuotes() {
  try {
    const holdings = holdingManager.getAllHoldings();
    if (holdings.length === 0) return;
    
    const stockCodes = holdings.map(h => h.stock_code);
    const quotes = await quoteFetcher.getQuotes(stockCodes);
    
    holdingManager.updateQuotePrices(quotes, tradeManager);
    
    evaluateRefreshStrategy(quotes);
    
    const alerts = alertService.checkAlerts(quotes, holdings);
    alerts.forEach(alert => {
      sendNotification(alert);
    });
    
    if (mainWindow) {
      mainWindow.webContents.send('quotes-updated', quotes);
    }
  } catch (error) {
    console.error('行情刷新失败:', error);
  }
}

function sendNotification(alert) {
  if (Notification.isSupported()) {
    const notification = new Notification({
      title: alert.title,
      body: alert.body,
      icon: path.join(__dirname, 'assets', 'icon.png')
    });
    notification.show();
  }
}

app.whenReady().then(() => {
  initializeServices();
  createWindow();
  
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  if (quoteRefreshInterval) {
    clearInterval(quoteRefreshInterval);
  }
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

ipcMain.handle('get-portfolio-summary', async () => {
  const holdings = holdingManager.getAllHoldings();
  const stockCodes = holdings.map(h => h.stock_code);
  const quotes = await quoteFetcher.getQuotes(stockCodes);
  return profitCalculator.calculatePortfolio(holdings, quotes);
});

ipcMain.handle('get-all-holdings', () => {
  return holdingManager.getAllHoldings();
});

ipcMain.handle('add-holding', (event, holding) => {
  return holdingManager.addHolding(holding);
});

ipcMain.handle('update-holding', (event, holdingId, updates) => {
  return holdingManager.updateHolding(holdingId, updates);
});

ipcMain.handle('delete-holding', (event, holdingId) => {
  return holdingManager.deleteHolding(holdingId);
});

ipcMain.handle('get-quotes', async (event, stockCodes) => {
  return await quoteFetcher.getQuotes(stockCodes);
});

ipcMain.handle('refresh-quotes-manual', async () => {
  if (manualRefreshInProgress) {
    return { status: 'already_in_progress' };
  }

  manualRefreshInProgress = true;
  
  try {
    const holdings = holdingManager.getAllHoldings();
    if (holdings.length === 0) {
      return { status: 'no_holdings' };
    }
    
    const stockCodes = holdings.map(h => h.stock_code);
    const quotes = await quoteFetcher.getQuotes(stockCodes);
    
    holdingManager.updateQuotePrices(quotes, tradeManager);
    
    const alerts = alertService.checkAlerts(quotes, holdings);
    alerts.forEach(alert => {
      sendNotification(alert);
    });
    
    if (mainWindow) {
      mainWindow.webContents.send('quotes-updated', quotes);
    }
    
    return { 
      status: 'success', 
      quotes_count: quotes.length,
      timestamp: new Date().toISOString()
    };
  } catch (error) {
    console.error('手动刷新行情失败:', error.message);
    return { status: 'error', message: error.message };
  } finally {
    manualRefreshInProgress = false;
  }
});

ipcMain.handle('get-refresh-status', () => {
  const strategyStatus = refreshStrategy ? refreshStrategy.getStatus() : {};
  return {
    current_mode: currentRefreshMode,
    current_interval_ms: currentRefreshIntervalMs,
    is_high_volatility: currentRefreshMode === 'high_volatility',
    strategy_status: strategyStatus
  };
});

ipcMain.handle('get-all-trades', () => {
  return tradeManager.getAllTrades();
});

ipcMain.handle('get-trades-by-stock', (event, stockCode) => {
  return tradeManager.getTradesByStock(stockCode);
});

ipcMain.handle('add-trade', (event, trade) => {
  const result = tradeManager.addTrade(trade);
  
  if (trade.trade_type === 'buy' || trade.trade_type === 'sell') {
    const holding = holdingManager.getHoldingByStockCode(trade.stock_code);
    if (holding) {
      const commissionInfo = holdingManager.calculateCommissionFromTrades(
        trade.stock_code,
        tradeManager
      );
      
      holdingManager.updateHolding(holding.holding_id, {
        total_commission: commissionInfo.total_commission,
        total_cost_with_commission: commissionInfo.total_cost_with_commission,
        realized_profit: commissionInfo.realized_profit
      });
    }
  }
  
  return result;
});

ipcMain.handle('delete-trade', (event, tradeId) => {
  return tradeManager.deleteTrade(tradeId);
});

ipcMain.handle('get-trade-statistics', () => {
  return tradeManager.getTradeStatistics();
});

ipcMain.handle('get-alerts', () => {
  return alertService.getAllAlerts();
});

ipcMain.handle('add-alert', (event, alert) => {
  return alertService.addAlert(alert);
});

ipcMain.handle('delete-alert', (event, alertId) => {
  return alertService.deleteAlert(alertId);
});

ipcMain.handle('calculate-profit', (event, holdings, quotes) => {
  return profitCalculator.calculatePortfolio(holdings, quotes);
});

ipcMain.handle('calculate-realized-profit', (event, stockCode) => {
  const trades = tradeManager.getTradesByStock(stockCode);
  return profitCalculator.calculateRealizedProfitFromTrades(trades);
});

ipcMain.handle('export-data', (event, dataType) => {
  const result = dialog.showSaveDialogSync(mainWindow, {
    title: '导出数据',
    defaultPath: `stocktracker_${dataType}_${Date.now()}.json`,
    filters: [{ name: 'JSON', extensions: ['json'] }]
  });
  return result;
});

ipcMain.handle('get-config', (event, key, defaultValue) => {
  return configService.getConfig(key, defaultValue);
});

ipcMain.handle('get-all-configs', () => {
  return configService.getAllConfigs();
});

ipcMain.handle('get-config-details', () => {
  return configService.getConfigDetails();
});

ipcMain.handle('set-config', (event, key, value, description) => {
  const result = configService.setConfig(key, value, description);
  
  if (key.startsWith('data_source.') || key.startsWith('quote.')) {
    quoteFetcher.reloadConfig();
  }
  if (key.startsWith('commission.')) {
    tradeManager.reloadConfig();
  }
  if (key.startsWith('refresh.')) {
    if (refreshStrategy) {
      refreshStrategy.reset();
    }
  }
  
  return result;
});

ipcMain.handle('delete-config', (event, key) => {
  return configService.deleteConfig(key);
});

ipcMain.handle('get-data-source-config', () => {
  return configService.getDataSourceConfig();
});

ipcMain.handle('get-commission-config', () => {
  return configService.getCommissionConfig();
});

ipcMain.handle('get-refresh-config', () => {
  return configService.getRefreshConfig();
});

ipcMain.handle('reload-quote-config', () => {
  quoteFetcher.reloadConfig();
  return { success: true };
});

ipcMain.handle('set-refresh-strategy', (event, strategyType) => {
  try {
    switch (strategyType) {
      case 'fixed':
        refreshStrategy = new FixedIntervalRefreshStrategy(configService);
        break;
      case 'time_based':
        refreshStrategy = new TimeBasedRefreshStrategy(configService);
        break;
      case 'volatility':
      default:
        refreshStrategy = new RefreshStrategy(configService);
    }
    
    refreshStrategy.setOnIntervalChangeCallback(handleRefreshIntervalChange);
    refreshStrategy.reset();
    
    return { success: true, strategy_type: strategyType };
  } catch (error) {
    console.error('设置刷新策略失败:', error);
    return { success: false, error: error.message };
  }
});

ipcMain.handle('get-refresh-strategy-types', () => {
  return {
    types: [
      { id: 'volatility', name: '波动率策略', description: '根据市场波动率动态调整刷新间隔' },
      { id: 'fixed', name: '固定间隔策略', description: '使用固定的刷新间隔' },
      { id: 'time_based', name: '时段策略', description: '根据交易时段调整刷新间隔' }
    ]
  };
});
