class ProfitCalculator {
  constructor() {}

  calculatePortfolio(holdings, quotes) {
    if (!holdings || holdings.length === 0) {
      return this.getEmptySummary();
    }

    const quoteMap = new Map();
    if (quotes && quotes.length > 0) {
      quotes.forEach(q => quoteMap.set(q.stock_code, q));
    }

    const updatedHoldings = holdings.map(holding => {
      const quote = quoteMap.get(holding.stock_code);
      return this.calculateSingleHolding(holding, quote);
    });

    const totalMarketValue = updatedHoldings.reduce((sum, h) => sum + (h.market_value || 0), 0);
    const totalCost = updatedHoldings.reduce((sum, h) => {
      const costWithCommission = h.total_cost_with_commission || 0;
      if (costWithCommission > 0) {
        return sum + costWithCommission;
      }
      const costWithoutCommission = h.shares * h.avg_cost;
      const commission = h.total_commission || 0;
      return sum + costWithoutCommission + commission;
    }, 0);
    
    const totalCostWithoutCommission = updatedHoldings.reduce(
      (sum, h) => sum + (h.shares * h.avg_cost), 0
    );
    
    const totalProfit = updatedHoldings.reduce((sum, h) => sum + (h.profit || 0), 0);
    const totalCommission = updatedHoldings.reduce((sum, h) => sum + (h.total_commission || 0), 0);
    const totalRealizedProfit = updatedHoldings.reduce((sum, h) => sum + (h.realized_profit || 0), 0);
    const totalProfitRate = totalCost > 0 ? (totalProfit / totalCost) * 100 : 0;

    const sectorBreakdown = this.calculateSectorBreakdown(updatedHoldings, totalMarketValue);
    const profitDistribution = this.calculateProfitDistribution(updatedHoldings);
    const topHoldings = this.getTopHoldings(updatedHoldings);

    return {
      total_holdings: updatedHoldings.length,
      total_market_value: parseFloat(totalMarketValue.toFixed(2)),
      total_cost: parseFloat(totalCost.toFixed(2)),
      total_cost_without_commission: parseFloat(totalCostWithoutCommission.toFixed(2)),
      total_commission: parseFloat(totalCommission.toFixed(2)),
      total_profit: parseFloat(totalProfit.toFixed(2)),
      total_realized_profit: parseFloat(totalRealizedProfit.toFixed(2)),
      total_profit_rate: parseFloat(totalProfitRate.toFixed(2)),
      total_up: updatedHoldings.filter(h => (h.profit || 0) > 0).length,
      total_down: updatedHoldings.filter(h => (h.profit || 0) < 0).length,
      total_flat: updatedHoldings.filter(h => (h.profit || 0) === 0).length,
      holdings: updatedHoldings,
      sector_breakdown: sectorBreakdown,
      profit_distribution: profitDistribution,
      top_holdings: topHoldings
    };
  }

  calculateSingleHolding(holding, quote) {
    const currentPrice = quote ? quote.current_price : holding.current_price;
    
    if (!currentPrice) {
      const costWithCommission = holding.total_cost_with_commission || 
        (holding.shares * holding.avg_cost) + (holding.total_commission || 0);
      
      return {
        ...holding,
        market_value: holding.shares * holding.avg_cost,
        profit: 0,
        profit_rate: 0,
        change_rate: 0,
        total_cost_with_commission: costWithCommission
      };
    }

    const marketValue = holding.shares * currentPrice;
    
    let totalCostWithCommission = holding.total_cost_with_commission || 0;
    let totalCommission = holding.total_commission || 0;
    
    if (totalCostWithCommission === 0) {
      const costWithoutCommission = holding.shares * holding.avg_cost;
      totalCostWithCommission = costWithoutCommission + totalCommission;
    }

    const profit = marketValue - totalCostWithCommission;
    const profitRate = totalCostWithCommission > 0 
      ? (profit / totalCostWithCommission) * 100 
      : 0;
    const changeRate = quote ? quote.change_rate : 0;

    return {
      ...holding,
      current_price: currentPrice,
      market_value: parseFloat(marketValue.toFixed(2)),
      profit: parseFloat(profit.toFixed(2)),
      profit_rate: parseFloat(profitRate.toFixed(2)),
      profit_rate_without_commission: holding.avg_cost > 0 
        ? parseFloat((( (marketValue - holding.shares * holding.avg_cost) / (holding.shares * holding.avg_cost) ) * 100).toFixed(2))
        : 0,
      change_rate: changeRate,
      total_cost_with_commission: parseFloat(totalCostWithCommission.toFixed(2)),
      total_commission: parseFloat(totalCommission.toFixed(2))
    };
  }

  calculateRealizedProfitFromTrades(trades) {
    if (!trades || trades.length === 0) {
      return {
        total_realized_profit: 0,
        total_buy_amount: 0,
        total_sell_amount: 0,
        total_fees: 0
      };
    }

    const buyTrades = trades.filter(t => t.trade_type === 'buy');
    const sellTrades = trades.filter(t => t.trade_type === 'sell');

    let totalBuyAmount = 0;
    let totalBuyShares = 0;
    let totalBuyFees = 0;

    buyTrades.forEach(trade => {
      totalBuyAmount += trade.amount;
      totalBuyShares += trade.shares;
      totalBuyFees += (trade.commission || 0) + (trade.stamp_duty || 0) + (trade.transfer_fee || 0);
    });

    let totalSellAmount = 0;
    let totalSellShares = 0;
    let totalSellFees = 0;
    let totalRealizedProfit = 0;

    if (buyTrades.length > 0 && sellTrades.length > 0) {
      const avgBuyPrice = totalBuyAmount / totalBuyShares;
      const avgBuyFeePerShare = totalBuyFees / totalBuyShares;

      sellTrades.forEach(trade => {
        totalSellAmount += trade.amount;
        totalSellShares += trade.shares;
        
        const sellFees = (trade.commission || 0) + 
                        (trade.stamp_duty || 0) + 
                        (trade.transfer_fee || 0);
        totalSellFees += sellFees;
        
        const buyCost = trade.shares * avgBuyPrice;
        const buyFees = trade.shares * avgBuyFeePerShare;
        
        const realizedProfit = trade.amount - buyCost - buyFees - sellFees;
        totalRealizedProfit += realizedProfit;
      });
    }

    return {
      total_realized_profit: parseFloat(totalRealizedProfit.toFixed(2)),
      total_buy_amount: parseFloat(totalBuyAmount.toFixed(2)),
      total_sell_amount: parseFloat(totalSellAmount.toFixed(2)),
      total_fees: parseFloat((totalBuyFees + totalSellFees).toFixed(2)),
      total_buy_fees: parseFloat(totalBuyFees.toFixed(2)),
      total_sell_fees: parseFloat(totalSellFees.toFixed(2))
    };
  }

  calculateSectorBreakdown(holdings, totalMarketValue) {
    const sectorMap = new Map();

    holdings.forEach(holding => {
      const sector = holding.sector || '未分类';
      if (!sectorMap.has(sector)) {
        sectorMap.set(sector, {
          sector,
          market_value: 0,
          count: 0,
          profit: 0,
          cost: 0,
          commission: 0
        });
      }

      const data = sectorMap.get(sector);
      data.market_value += holding.market_value || 0;
      data.profit += holding.profit || 0;
      data.commission += holding.total_commission || 0;
      
      const costWithCommission = holding.total_cost_with_commission || 0;
      if (costWithCommission > 0) {
        data.cost += costWithCommission;
      } else {
        data.cost += (holding.shares * holding.avg_cost) + (holding.total_commission || 0);
      }
      
      data.count += 1;
    });

    return Array.from(sectorMap.values()).map(data => ({
      ...data,
      market_value: parseFloat(data.market_value.toFixed(2)),
      profit: parseFloat(data.profit.toFixed(2)),
      cost: parseFloat(data.cost.toFixed(2)),
      commission: parseFloat(data.commission.toFixed(2)),
      percentage: totalMarketValue > 0 
        ? parseFloat(((data.market_value / totalMarketValue) * 100).toFixed(2)) 
        : 0,
      profit_rate: data.cost > 0 
        ? parseFloat(((data.profit / data.cost) * 100).toFixed(2)) 
        : 0
    })).sort((a, b) => b.market_value - a.market_value);
  }

  calculateProfitDistribution(holdings) {
    let positiveCount = 0;
    let negativeCount = 0;
    let flatCount = 0;
    let positiveValue = 0;
    let negativeValue = 0;
    let positiveCommission = 0;
    let negativeCommission = 0;

    holdings.forEach(h => {
      const profit = h.profit || 0;
      const commission = h.total_commission || 0;
      
      if (profit > 0) {
        positiveCount++;
        positiveValue += profit;
        positiveCommission += commission;
      } else if (profit < 0) {
        negativeCount++;
        negativeValue += Math.abs(profit);
        negativeCommission += commission;
      } else {
        flatCount++;
      }
    });

    return {
      positive_count: positiveCount,
      negative_count: negativeCount,
      flat_count: flatCount,
      positive_value: parseFloat(positiveValue.toFixed(2)),
      negative_value: parseFloat(negativeValue.toFixed(2)),
      positive_commission: parseFloat(positiveCommission.toFixed(2)),
      negative_commission: parseFloat(negativeCommission.toFixed(2))
    };
  }

  getTopHoldings(holdings) {
    const byMarketValue = [...holdings].sort((a, b) => (b.market_value || 0) - (a.market_value || 0));
    const byProfitRate = [...holdings].sort((a, b) => (b.profit_rate || 0) - (a.profit_rate || 0));
    const byProfit = [...holdings].sort((a, b) => (b.profit || 0) - (a.profit || 0));

    return {
      by_market_value: byMarketValue.slice(0, 5),
      by_profit_rate: byProfitRate.slice(0, 5),
      by_profit: byProfit.slice(0, 5)
    };
  }

  calculateReturnRate(cost, currentValue) {
    if (cost <= 0) return 0;
    return ((currentValue - cost) / cost) * 100;
  }

  calculateAnnualizedReturn(totalReturn, days) {
    if (days <= 0 || totalReturn <= 0) return 0;
    return Math.pow(1 + totalReturn, 365 / days) - 1;
  }

  getEmptySummary() {
    return {
      total_holdings: 0,
      total_market_value: 0,
      total_cost: 0,
      total_cost_without_commission: 0,
      total_commission: 0,
      total_profit: 0,
      total_realized_profit: 0,
      total_profit_rate: 0,
      total_up: 0,
      total_down: 0,
      total_flat: 0,
      holdings: [],
      sector_breakdown: [],
      profit_distribution: {
        positive_count: 0,
        negative_count: 0,
        flat_count: 0,
        positive_value: 0,
        negative_value: 0,
        positive_commission: 0,
        negative_commission: 0
      },
      top_holdings: {
        by_market_value: [],
        by_profit_rate: [],
        by_profit: []
      }
    };
  }
}

module.exports = ProfitCalculator;
