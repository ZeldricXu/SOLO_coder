const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  getPortfolioSummary: () => ipcRenderer.invoke('get-portfolio-summary'),
  
  getAllHoldings: () => ipcRenderer.invoke('get-all-holdings'),
  addHolding: (holding) => ipcRenderer.invoke('add-holding', holding),
  updateHolding: (holdingId, updates) => ipcRenderer.invoke('update-holding', holdingId, updates),
  deleteHolding: (holdingId) => ipcRenderer.invoke('delete-holding', holdingId),
  
  getQuotes: (stockCodes) => ipcRenderer.invoke('get-quotes', stockCodes),
  refreshQuotesManual: () => ipcRenderer.invoke('refresh-quotes-manual'),
  getRefreshStatus: () => ipcRenderer.invoke('get-refresh-status'),
  
  getAllTrades: () => ipcRenderer.invoke('get-all-trades'),
  getTradesByStock: (stockCode) => ipcRenderer.invoke('get-trades-by-stock', stockCode),
  addTrade: (trade) => ipcRenderer.invoke('add-trade', trade),
  deleteTrade: (tradeId) => ipcRenderer.invoke('delete-trade', tradeId),
  getTradeStatistics: () => ipcRenderer.invoke('get-trade-statistics'),
  calculateRealizedProfit: (stockCode) => ipcRenderer.invoke('calculate-realized-profit', stockCode),
  
  getAlerts: () => ipcRenderer.invoke('get-alerts'),
  addAlert: (alert) => ipcRenderer.invoke('add-alert', alert),
  deleteAlert: (alertId) => ipcRenderer.invoke('delete-alert', alertId),
  
  calculateProfit: (holdings, quotes) => ipcRenderer.invoke('calculate-profit', holdings, quotes),
  
  exportData: (dataType) => ipcRenderer.invoke('export-data', dataType),
  
  getConfig: (key, defaultValue) => ipcRenderer.invoke('get-config', key, defaultValue),
  getAllConfigs: () => ipcRenderer.invoke('get-all-configs'),
  getConfigDetails: () => ipcRenderer.invoke('get-config-details'),
  setConfig: (key, value, description) => ipcRenderer.invoke('set-config', key, value, description),
  deleteConfig: (key) => ipcRenderer.invoke('delete-config', key),
  
  getDataSourceConfig: () => ipcRenderer.invoke('get-data-source-config'),
  getCommissionConfig: () => ipcRenderer.invoke('get-commission-config'),
  getRefreshConfig: () => ipcRenderer.invoke('get-refresh-config'),
  
  reloadQuoteConfig: () => ipcRenderer.invoke('reload-quote-config'),
  setRefreshStrategy: (strategyType) => ipcRenderer.invoke('set-refresh-strategy', strategyType),
  getRefreshStrategyTypes: () => ipcRenderer.invoke('get-refresh-strategy-types'),
  
  onQuotesUpdated: (callback) => {
    ipcRenderer.on('quotes-updated', (event, quotes) => callback(quotes));
  },
  
  removeQuotesUpdatedListener: (callback) => {
    ipcRenderer.removeListener('quotes-updated', callback);
  },
  
  onRefreshIntervalChanged: (callback) => {
    ipcRenderer.on('refresh-interval-changed', (event, data) => callback(data));
  },
  
  removeRefreshIntervalChangedListener: (callback) => {
    ipcRenderer.removeListener('refresh-interval-changed', callback);
  }
});
