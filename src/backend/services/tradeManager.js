const { v4: uuidv4 } = require('uuid');
const dayjs = require('dayjs');

class TradeManager {
  constructor(dbService, configService) {
    this.db = dbService.getDatabase();
    this.configService = configService;
  }

  getCommissionConfig() {
    return this.configService.getCommissionConfig();
  }

  reloadConfig() {
    console.log('TradeManager 佣金配置已重新加载');
  }

  getAllTrades() {
    const stmt = this.db.prepare(`
      SELECT * FROM trades ORDER BY trade_date DESC, created_at DESC
    `);
    return stmt.all();
  }

  getTradeById(tradeId) {
    const stmt = this.db.prepare(`
      SELECT * FROM trades WHERE trade_id = ?
    `);
    return stmt.get(tradeId);
  }

  getTradesByStock(stockCode) {
    const stmt = this.db.prepare(`
      SELECT * FROM trades WHERE stock_code = ? ORDER BY trade_date DESC
    `);
    return stmt.all(stockCode);
  }

  getTradesByDateRange(startDate, endDate) {
    const stmt = this.db.prepare(`
      SELECT * FROM trades 
      WHERE trade_date >= ? AND trade_date <= ? 
      ORDER BY trade_date DESC
    `);
    return stmt.all(startDate, endDate);
  }

  addTrade(trade) {
    const tradeId = uuidv4();
    const now = new Date().toISOString();
    
    const amount = trade.shares * trade.price;
    
    const commission = trade.commission !== undefined && trade.commission !== null
      ? trade.commission
      : this.calculateCommission(trade);
    
    const stampDuty = trade.stamp_duty !== undefined && trade.stamp_duty !== null
      ? trade.stamp_duty
      : this.calculateStampDuty(trade);
    
    const transferFee = trade.transfer_fee !== undefined && trade.transfer_fee !== null
      ? trade.transfer_fee
      : this.calculateTransferFee(trade);
    
    const totalFees = commission + stampDuty + transferFee;
    
    const realizedProfit = this.calculateRealizedProfit(trade);

    const stmt = this.db.prepare(`
      INSERT INTO trades (
        trade_id, stock_code, stock_name, trade_type,
        shares, price, amount, trade_date,
        commission, stamp_duty, transfer_fee, total_fees,
        realized_profit, notes, created_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `);

    stmt.run(
      tradeId,
      trade.stock_code,
      trade.stock_name || null,
      trade.trade_type,
      trade.shares,
      trade.price,
      parseFloat(amount.toFixed(2)),
      trade.trade_date || now.split('T')[0],
      parseFloat(commission.toFixed(2)),
      parseFloat(stampDuty.toFixed(2)),
      parseFloat(transferFee.toFixed(2)),
      parseFloat(totalFees.toFixed(2)),
      parseFloat(realizedProfit.toFixed(2)),
      trade.notes || null,
      now
    );

    return this.getTradeById(tradeId);
  }

  calculateCommission(trade) {
    const config = this.getCommissionConfig();
    const amount = trade.shares * trade.price;
    
    let rate;
    if (trade.trade_type === 'sell') {
      rate = config.sell_rate;
    } else {
      rate = config.buy_rate;
    }
    
    const commission = amount * rate;
    const minFee = config.min_fee;
    
    if (commission > 0 && commission < minFee) {
      return minFee;
    }
    
    return commission;
  }

  calculateStampDuty(trade) {
    if (trade.trade_type === 'sell') {
      const config = this.getCommissionConfig();
      const amount = trade.shares * trade.price;
      const stampDutyRate = config.stamp_duty_rate;
      return amount * stampDutyRate;
    }
    return 0;
  }

  calculateTransferFee(trade) {
    const config = this.getCommissionConfig();
    const amount = trade.shares * trade.price;
    const transferFeeRate = config.transfer_fee_rate;
    const minTransferFee = config.min_transfer_fee;
    
    const fee = amount * transferFeeRate;
    return Math.max(fee, minTransferFee);
  }

  calculateRealizedProfit(sellTrade) {
    if (sellTrade.trade_type !== 'sell') {
      return 0;
    }

    const stockCode = sellTrade.stock_code;
    const allTrades = this.getTradesByStock(stockCode);
    
    const buyTrades = allTrades.filter(t => t.trade_type === 'buy');
    const sellTrades = allTrades.filter(t => t.trade_type === 'sell' && t.trade_id !== null);
    
    if (buyTrades.length === 0) {
      return 0;
    }

    let totalBuyShares = 0;
    let totalBuyAmount = 0;
    let totalBuyFees = 0;

    buyTrades.forEach(trade => {
      totalBuyShares += trade.shares;
      totalBuyAmount += trade.amount;
      totalBuyFees += (trade.commission || 0) + (trade.stamp_duty || 0) + (trade.transfer_fee || 0);
    });

    let totalSoldShares = 0;
    sellTrades.forEach(trade => {
      totalSoldShares += trade.shares;
    });

    const avgBuyPrice = totalBuyShares > 0 ? totalBuyAmount / totalBuyShares : 0;
    const avgBuyFeePerShare = totalBuyShares > 0 ? totalBuyFees / totalBuyShares : 0;

    const sellAmount = sellTrade.amount;
    const sellFees = (sellTrade.commission || 0) + 
                     this.calculateStampDuty(sellTrade) + 
                     this.calculateTransferFee(sellTrade);
    
    const buyCost = sellTrade.shares * avgBuyPrice;
    const buyFees = sellTrade.shares * avgBuyFeePerShare;

    const realizedProfit = sellAmount - buyCost - buyFees - sellFees;

    return realizedProfit;
  }

  deleteTrade(tradeId) {
    const existing = this.getTradeById(tradeId);
    if (!existing) {
      throw new Error(`交易记录不存在: ${tradeId}`);
    }

    const stmt = this.db.prepare(`
      DELETE FROM trades WHERE trade_id = ?
    `);
    stmt.run(tradeId);

    return existing;
  }

  getTradeStatistics() {
    const trades = this.getAllTrades();
    
    const totalBuyAmount = trades
      .filter(t => t.trade_type === 'buy')
      .reduce((sum, t) => sum + t.amount, 0);
    
    const totalSellAmount = trades
      .filter(t => t.trade_type === 'sell')
      .reduce((sum, t) => sum + t.amount, 0);
    
    const totalCommission = trades.reduce((sum, t) => sum + (t.commission || 0), 0);
    const totalStampDuty = trades.reduce((sum, t) => sum + (t.stamp_duty || 0), 0);
    const totalTransferFee = trades.reduce((sum, t) => sum + (t.transfer_fee || 0), 0);
    const totalFees = totalCommission + totalStampDuty + totalTransferFee;
    
    const totalRealizedProfit = trades
      .filter(t => t.trade_type === 'sell')
      .reduce((sum, t) => sum + (t.realized_profit || 0), 0);
    
    const buyTrades = trades.filter(t => t.trade_type === 'buy');
    const sellTrades = trades.filter(t => t.trade_type === 'sell');

    const monthlyStats = this.getMonthlyStatistics(trades);
    const stockStats = this.getStockStatistics(trades);

    return {
      total_trades: trades.length,
      buy_trades: buyTrades.length,
      sell_trades: sellTrades.length,
      total_buy_amount: parseFloat(totalBuyAmount.toFixed(2)),
      total_sell_amount: parseFloat(totalSellAmount.toFixed(2)),
      total_commission: parseFloat(totalCommission.toFixed(2)),
      total_stamp_duty: parseFloat(totalStampDuty.toFixed(2)),
      total_transfer_fee: parseFloat(totalTransferFee.toFixed(2)),
      total_fees: parseFloat(totalFees.toFixed(2)),
      total_realized_profit: parseFloat(totalRealizedProfit.toFixed(2)),
      net_cash_flow: parseFloat((totalSellAmount - totalBuyAmount - totalFees).toFixed(2)),
      monthly_statistics: monthlyStats,
      stock_statistics: stockStats,
      commission_config: this.getCommissionConfig()
    };
  }

  getMonthlyStatistics(trades) {
    const monthlyData = {};
    
    trades.forEach(trade => {
      const monthKey = trade.trade_date.substring(0, 7);
      if (!monthlyData[monthKey]) {
        monthlyData[monthKey] = {
          month: monthKey,
          buy_count: 0,
          sell_count: 0,
          buy_amount: 0,
          sell_amount: 0,
          commission: 0,
          stamp_duty: 0,
          transfer_fee: 0,
          realized_profit: 0
        };
      }
      
      const data = monthlyData[monthKey];
      if (trade.trade_type === 'buy') {
        data.buy_count += 1;
        data.buy_amount += trade.amount;
      } else {
        data.sell_count += 1;
        data.sell_amount += trade.amount;
        data.realized_profit += trade.realized_profit || 0;
      }
      data.commission += trade.commission || 0;
      data.stamp_duty += trade.stamp_duty || 0;
      data.transfer_fee += trade.transfer_fee || 0;
    });

    return Object.values(monthlyData)
      .map(d => ({
        ...d,
        buy_amount: parseFloat(d.buy_amount.toFixed(2)),
        sell_amount: parseFloat(d.sell_amount.toFixed(2)),
        commission: parseFloat(d.commission.toFixed(2)),
        stamp_duty: parseFloat(d.stamp_duty.toFixed(2)),
        transfer_fee: parseFloat(d.transfer_fee.toFixed(2)),
        realized_profit: parseFloat(d.realized_profit.toFixed(2))
      }))
      .sort((a, b) => a.month.localeCompare(b.month));
  }

  getStockStatistics(trades) {
    const stockData = {};
    
    trades.forEach(trade => {
      const stockCode = trade.stock_code;
      if (!stockData[stockCode]) {
        stockData[stockCode] = {
          stock_code: stockCode,
          stock_name: trade.stock_name,
          buy_count: 0,
          sell_count: 0,
          buy_amount: 0,
          sell_amount: 0,
          total_shares_bought: 0,
          total_shares_sold: 0,
          commission: 0,
          stamp_duty: 0,
          transfer_fee: 0,
          realized_profit: 0
        };
      }
      
      const data = stockData[stockCode];
      if (trade.trade_type === 'buy') {
        data.buy_count += 1;
        data.buy_amount += trade.amount;
        data.total_shares_bought += trade.shares;
      } else {
        data.sell_count += 1;
        data.sell_amount += trade.amount;
        data.total_shares_sold += trade.shares;
        data.realized_profit += trade.realized_profit || 0;
      }
      data.commission += trade.commission || 0;
      data.stamp_duty += trade.stamp_duty || 0;
      data.transfer_fee += trade.transfer_fee || 0;
    });

    return Object.values(stockData)
      .map(d => {
        const totalFees = d.commission + d.stamp_duty + d.transfer_fee;
        return {
          ...d,
          buy_amount: parseFloat(d.buy_amount.toFixed(2)),
          sell_amount: parseFloat(d.sell_amount.toFixed(2)),
          commission: parseFloat(d.commission.toFixed(2)),
          stamp_duty: parseFloat(d.stamp_duty.toFixed(2)),
          transfer_fee: parseFloat(d.transfer_fee.toFixed(2)),
          total_fees: parseFloat(totalFees.toFixed(2)),
          realized_profit: parseFloat(d.realized_profit.toFixed(2)),
          net_profit: parseFloat((d.realized_profit).toFixed(2))
        };
      })
      .sort((a, b) => b.buy_amount - a.buy_amount);
  }

  calculateAverageCost(stockCode) {
    const trades = this.getTradesByStock(stockCode);
    const buyTrades = trades.filter(t => t.trade_type === 'buy');
    const sellTrades = trades.filter(t => t.trade_type === 'sell');

    if (buyTrades.length === 0) {
      return { avg_cost: 0, total_shares: 0 };
    }

    const totalBuyShares = buyTrades.reduce((sum, t) => sum + t.shares, 0);
    const totalSellShares = sellTrades.reduce((sum, t) => sum + t.shares, 0);
    const currentShares = totalBuyShares - totalSellShares;

    if (currentShares <= 0) {
      return { avg_cost: 0, total_shares: 0 };
    }

    let totalBuyCost = 0;
    let totalBuyFees = 0;

    buyTrades.forEach(trade => {
      totalBuyCost += trade.shares * trade.price;
      totalBuyFees += (trade.commission || 0) + (trade.stamp_duty || 0) + (trade.transfer_fee || 0);
    });

    const avgCostWithoutFees = totalBuyCost / totalBuyShares;
    const avgCostWithFees = (totalBuyCost + totalBuyFees) / totalBuyShares;

    return {
      avg_cost: parseFloat(avgCostWithoutFees.toFixed(2)),
      avg_cost_with_fees: parseFloat(avgCostWithFees.toFixed(2)),
      total_shares: currentShares,
      total_fees: parseFloat(totalBuyFees.toFixed(2))
    };
  }
}

module.exports = TradeManager;
