const { v4: uuidv4 } = require('uuid');

class AlertService {
  constructor(dbService) {
    this.db = dbService ? dbService.getDatabase() : null;
    this.alerts = new Map();
    this.initAlerts();
  }

  initAlerts() {
    if (this.db) {
      const stmt = this.db.prepare('SELECT * FROM alerts WHERE is_active = 1');
      const activeAlerts = stmt.all();
      activeAlerts.forEach(alert => {
        this.alerts.set(alert.alert_id, alert);
      });
    }
  }

  getAllAlerts() {
    if (this.db) {
      const stmt = this.db.prepare('SELECT * FROM alerts ORDER BY created_at DESC');
      return stmt.all();
    }
    return Array.from(this.alerts.values());
  }

  getActiveAlerts() {
    if (this.db) {
      const stmt = this.db.prepare('SELECT * FROM alerts WHERE is_active = 1 ORDER BY created_at DESC');
      return stmt.all();
    }
    return Array.from(this.alerts.values()).filter(a => a.is_active);
  }

  addAlert(alert) {
    const alertId = uuidv4();
    const now = new Date().toISOString();

    const newAlert = {
      alert_id: alertId,
      stock_code: alert.stock_code,
      alert_type: alert.alert_type || 'price',
      target_price: alert.target_price,
      condition: alert.condition,
      is_active: 1,
      last_triggered: null,
      created_at: now
    };

    if (this.db) {
      const stmt = this.db.prepare(`
        INSERT INTO alerts (
          alert_id, stock_code, alert_type, target_price,
          condition, is_active, last_triggered, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      `);
      stmt.run(
        alertId,
        newAlert.stock_code,
        newAlert.alert_type,
        newAlert.target_price,
        newAlert.condition,
        newAlert.is_active,
        newAlert.last_triggered,
        newAlert.created_at
      );
    }

    this.alerts.set(alertId, newAlert);
    return newAlert;
  }

  deleteAlert(alertId) {
    const alert = this.getAlertById(alertId);
    if (!alert) {
      throw new Error(`预警不存在: ${alertId}`);
    }

    if (this.db) {
      const stmt = this.db.prepare('DELETE FROM alerts WHERE alert_id = ?');
      stmt.run(alertId);
    }

    this.alerts.delete(alertId);
    return alert;
  }

  getAlertById(alertId) {
    if (this.db) {
      const stmt = this.db.prepare('SELECT * FROM alerts WHERE alert_id = ?');
      return stmt.get(alertId);
    }
    return this.alerts.get(alertId) || null;
  }

  updateAlert(alertId, updates) {
    const existing = this.getAlertById(alertId);
    if (!existing) {
      throw new Error(`预警不存在: ${alertId}`);
    }

    const allowedFields = ['target_price', 'condition', 'is_active'];
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

    updateFields.push('last_triggered = ?');
    updateValues.push(updates.last_triggered || existing.last_triggered);
    updateValues.push(alertId);

    if (this.db) {
      const stmt = this.db.prepare(`
        UPDATE alerts SET ${updateFields.join(', ')} WHERE alert_id = ?
      `);
      stmt.run(...updateValues);
    }

    return this.getAlertById(alertId);
  }

  checkAlerts(quotes, holdings) {
    const activeAlerts = this.getActiveAlerts();
    const triggeredAlerts = [];

    const quoteMap = new Map();
    quotes.forEach(q => quoteMap.set(q.stock_code, q));

    activeAlerts.forEach(alert => {
      const quote = quoteMap.get(alert.stock_code);
      if (!quote) return;

      const isTriggered = this.checkCondition(
        alert.condition,
        quote.current_price,
        alert.target_price
      );

      if (isTriggered) {
        const holding = holdings.find(h => h.stock_code === alert.stock_code);
        const notification = this.createNotification(alert, quote, holding);
        triggeredAlerts.push(notification);

        this.updateAlert(alert.alert_id, {
          last_triggered: new Date().toISOString()
        });
      }
    });

    return triggeredAlerts;
  }

  checkCondition(condition, currentPrice, targetPrice) {
    switch (condition) {
      case 'above':
        return currentPrice >= targetPrice;
      case 'below':
        return currentPrice <= targetPrice;
      case 'rise_percent':
        return currentPrice >= targetPrice;
      case 'fall_percent':
        return currentPrice <= targetPrice;
      default:
        return false;
    }
  }

  createNotification(alert, quote, holding) {
    const priceChange = quote.current_price - alert.target_price;
    const changeDirection = priceChange >= 0 ? '上涨' : '下跌';
    
    let title = '';
    let body = '';

    switch (alert.condition) {
      case 'above':
        title = `价格预警：${quote.stock_name}(${quote.stock_code})`;
        body = `当前价格 ${quote.current_price} 元，已超过预警价格 ${alert.target_price} 元`;
        break;
      case 'below':
        title = `价格预警：${quote.stock_name}(${quote.stock_code})`;
        body = `当前价格 ${quote.current_price} 元，已跌破预警价格 ${alert.target_price} 元`;
        break;
      default:
        title = `价格预警：${quote.stock_name}(${quote.stock_code})`;
        body = `当前价格 ${quote.current_price} 元`;
    }

    if (holding) {
      body += `\n持仓 ${holding.shares} 股，当前市值 ${(holding.shares * quote.current_price).toFixed(2)} 元`;
    }

    return {
      title,
      body,
      stock_code: alert.stock_code,
      stock_name: quote.stock_name,
      current_price: quote.current_price,
      target_price: alert.target_price,
      alert_id: alert.alert_id,
      timestamp: new Date().toISOString()
    };
  }

  createDefaultAlertsForHolding(holding, currentPrice) {
    const alerts = [];
    
    const rise10 = {
      stock_code: holding.stock_code,
      alert_type: 'price',
      target_price: parseFloat((holding.avg_cost * 1.1).toFixed(2)),
      condition: 'above'
    };
    
    const fall10 = {
      stock_code: holding.stock_code,
      alert_type: 'price',
      target_price: parseFloat((holding.avg_cost * 0.9).toFixed(2)),
      condition: 'below'
    };

    alerts.push(this.addAlert(rise10));
    alerts.push(this.addAlert(fall10));

    return alerts;
  }
}

module.exports = AlertService;
