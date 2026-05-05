const { v4: uuidv4 } = require('uuid');

class HoldingManager {
  constructor(dbService) {
    this.db = dbService.getDatabase();
  }

  getAllHoldings() {
    const stmt = this.db.prepare(`
      SELECT * FROM holdings ORDER BY created_at DESC
    `);
    return stmt.all();
  }

  getHoldingById(holdingId) {
    const stmt = this.db.prepare(`
      SELECT * FROM holdings WHERE holding_id = ?
    `);
    return stmt.get(holdingId);
  }

  getHoldingByStockCode(stockCode) {
    const stmt = this.db.prepare(`
      SELECT * FROM holdings WHERE stock_code = ?
    `);
    return stmt.get(stockCode);
  }

  addHolding(holding) {
    const existing = this.getHoldingByStockCode(holding.stock_code);
    if (existing) {
      throw new Error(`该股票 ${holding.stock_code} 已存在持仓`);
    }

    const holdingId = uuidv4();
    const now = new Date().toISOString();
    
    const marketValue = holding.shares * (holding.current_price || holding.avg_cost);
    const costWithoutCommission = holding.shares * holding.avg_cost;
    const totalCommission = holding.total_commission || 0;
    const totalCostWithCommission = costWithoutCommission + totalCommission;
    const profit = holding.current_price 
      ? marketValue - totalCostWithCommission
      : 0;
    const profitRate = totalCostWithCommission > 0 && holding.current_price
      ? ((profit / totalCostWithCommission) * 100)
      : 0;

    const stmt = this.db.prepare(`
      INSERT INTO holdings (
        holding_id, stock_code, stock_name, shares, avg_cost,
        current_price, market_value, profit, profit_rate,
        buy_date, sector, total_commission, total_cost_with_commission,
        realized_profit, realized_profit_rate, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `);

    stmt.run(
      holdingId,
      holding.stock_code,
      holding.stock_name,
      holding.shares,
      holding.avg_cost,
      holding.current_price || null,
      marketValue,
      profit,
      parseFloat(profitRate.toFixed(2)),
      holding.buy_date || now.split('T')[0],
      holding.sector || null,
      totalCommission,
      totalCostWithCommission,
      holding.realized_profit || 0,
      holding.realized_profit_rate || 0,
      now,
      now
    );

    return this.getHoldingById(holdingId);
  }

  updateHolding(holdingId, updates) {
    const existing = this.getHoldingById(holdingId);
    if (!existing) {
      throw new Error(`持仓记录不存在: ${holdingId}`);
    }

    const allowedFields = [
      'shares', 'avg_cost', 'current_price', 'market_value',
      'profit', 'profit_rate', 'buy_date', 'sector', 'stock_name',
      'total_commission', 'total_cost_with_commission',
      'realized_profit', 'realized_profit_rate'
    ];

    const updateFields = [];
    const updateValues = [];

    allowedFields.forEach(field => {
      if (updates[field] !== undefined) {
        updateFields.push(`${field} = ?`);
        updateValues.push(updates[field]);
      }
    });

    if (updateFields.length === 0) {
      return existing;
    }

    updateFields.push('updated_at = ?');
    updateValues.push(new Date().toISOString());
    updateValues.push(holdingId);

    const stmt = this.db.prepare(`
      UPDATE holdings SET ${updateFields.join(', ')} WHERE holding_id = ?
    `);

    stmt.run(...updateValues);
    return this.getHoldingById(holdingId);
  }

  deleteHolding(holdingId) {
    const existing = this.getHoldingById(holdingId);
    if (!existing) {
      throw new Error(`持仓记录不存在: ${holdingId}`);
    }

    const stmt = this.db.prepare(`
      DELETE FROM holdings WHERE holding_id = ?
    `);
    stmt.run(holdingId);

    return existing;
  }

  updateQuotePrices(quotes, tradeManager = null) {
    const updateStmt = this.db.prepare(`
      UPDATE holdings SET 
        current_price = ?,
        market_value = ?,
        profit = ?,
        profit_rate = ?,
        total_commission = ?,
        total_cost_with_commission = ?,
        updated_at = ?
      WHERE stock_code = ?
    `);

    const transactions = this.db.transaction((quoteList) => {
      for (const quote of quoteList) {
        const holding = this.getHoldingByStockCode(quote.stock_code);
        if (holding) {
          const marketValue = holding.shares * quote.current_price;
          
          let totalCommission = holding.total_commission || 0;
          let totalCostWithCommission = holding.total_cost_with_commission || 0;
          
          if (tradeManager) {
            const commissionInfo = this.calculateCommissionFromTrades(
              holding.stock_code, 
              tradeManager
            );
            totalCommission = commissionInfo.total_commission;
            totalCostWithCommission = commissionInfo.total_cost_with_commission;
          }

          if (totalCostWithCommission === 0) {
            const costWithoutCommission = holding.shares * holding.avg_cost;
            totalCostWithCommission = costWithoutCommission + totalCommission;
          }

          const profit = marketValue - totalCostWithCommission;
          const profitRate = totalCostWithCommission > 0 
            ? (profit / totalCostWithCommission) * 100 
            : 0;

          updateStmt.run(
            quote.current_price,
            marketValue,
            parseFloat(profit.toFixed(2)),
            parseFloat(profitRate.toFixed(2)),
            totalCommission,
            totalCostWithCommission,
            new Date().toISOString(),
            quote.stock_code
          );
        }
      }
    });

    transactions(quotes);
  }

  calculateCommissionFromTrades(stockCode, tradeManager) {
    const trades = tradeManager.getTradesByStock(stockCode);
    
    const buyTrades = trades.filter(t => t.trade_type === 'buy');
    const sellTrades = trades.filter(t => t.trade_type === 'sell');
    
    let totalBuyCommission = 0;
    let totalBuyAmount = 0;
    let totalBuyShares = 0;
    let totalSellCommission = 0;
    let totalRealizedProfit = 0;

    buyTrades.forEach(trade => {
      totalBuyAmount += trade.amount;
      totalBuyShares += trade.shares;
      totalBuyCommission += (trade.commission || 0) + (trade.stamp_duty || 0) + (trade.transfer_fee || 0);
    });

    if (sellTrades.length > 0 && buyTrades.length > 0) {
      const avgBuyPrice = totalBuyAmount / totalBuyShares;
      
      sellTrades.forEach(trade => {
        totalSellCommission += (trade.commission || 0) + (trade.stamp_duty || 0) + (trade.transfer_fee || 0);
        
        const sellAmount = trade.amount;
        const buyCost = trade.shares * avgBuyPrice;
        const buyCommission = (totalBuyCommission / totalBuyShares) * trade.shares;
        const sellCommission = (trade.commission || 0) + (trade.stamp_duty || 0) + (trade.transfer_fee || 0);
        
        const realizedProfit = sellAmount - buyCost - buyCommission - sellCommission;
        totalRealizedProfit += realizedProfit;
      });
    }

    const totalCommission = totalBuyCommission + totalSellCommission;
    const totalCostWithCommission = totalBuyAmount + totalBuyCommission;

    return {
      total_commission: totalCommission,
      total_cost_with_commission: totalCostWithCommission,
      realized_profit: totalRealizedProfit,
      total_buy_commission: totalBuyCommission,
      total_sell_commission: totalSellCommission
    };
  }

  getHoldingSummary() {
    const holdings = this.getAllHoldings();
    
    const totalMarketValue = holdings.reduce((sum, h) => sum + (h.market_value || 0), 0);
    
    const totalCost = holdings.reduce((sum, h) => {
      const costWithCommission = h.total_cost_with_commission || 0;
      if (costWithCommission > 0) {
        return sum + costWithCommission;
      }
      return sum + (h.shares * h.avg_cost) + (h.total_commission || 0);
    }, 0);
    
    const totalProfit = holdings.reduce((sum, h) => sum + (h.profit || 0), 0);
    const totalCommission = holdings.reduce((sum, h) => sum + (h.total_commission || 0), 0);
    const totalRealizedProfit = holdings.reduce((sum, h) => sum + (h.realized_profit || 0), 0);
    const totalProfitRate = totalCost > 0 ? (totalProfit / totalCost) * 100 : 0;

    const sectorStats = {};
    holdings.forEach(h => {
      const sector = h.sector || '未分类';
      if (!sectorStats[sector]) {
        sectorStats[sector] = {
          sector,
          market_value: 0,
          count: 0,
          holdings: []
        };
      }
      sectorStats[sector].market_value += h.market_value || 0;
      sectorStats[sector].count += 1;
      sectorStats[sector].holdings.push(h);
    });

    const sectorBreakdown = Object.values(sectorStats).map(s => ({
      ...s,
      percentage: totalMarketValue > 0 ? parseFloat(((s.market_value / totalMarketValue) * 100).toFixed(2)) : 0
    }));

    return {
      total_holdings: holdings.length,
      total_market_value: parseFloat(totalMarketValue.toFixed(2)),
      total_cost: parseFloat(totalCost.toFixed(2)),
      total_profit: parseFloat(totalProfit.toFixed(2)),
      total_realized_profit: parseFloat(totalRealizedProfit.toFixed(2)),
      total_commission: parseFloat(totalCommission.toFixed(2)),
      total_profit_rate: parseFloat(totalProfitRate.toFixed(2)),
      sector_breakdown: sectorBreakdown
    };
  }
}

module.exports = HoldingManager;
