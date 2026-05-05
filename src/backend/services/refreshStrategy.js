class RefreshStrategy {
  constructor(configService) {
    this.configService = configService;
    this.lastQuotes = new Map();
    this.currentMode = 'normal';
    this.currentIntervalMs = 60000;
    this.onIntervalChangeCallback = null;
  }

  setOnIntervalChangeCallback(callback) {
    this.onIntervalChangeCallback = callback;
  }

  getCurrentMode() {
    return this.currentMode;
  }

  getCurrentIntervalMs() {
    return this.currentIntervalMs;
  }

  getConfig() {
    return this.configService.getRefreshConfig();
  }

  checkVolatility(quotes) {
    if (!quotes || quotes.length === 0) {
      return {
        is_high_volatility: false,
        is_stable: false,
        high_volatility_count: 0,
        stable_count: 0,
        total_count: 0,
        mode: this.currentMode
      };
    }

    const config = this.getConfig();
    const volatilityThreshold = config.volatility_threshold;
    const highVolatilityRatio = config.high_volatility_ratio;
    const stableThreshold = config.stable_threshold;

    let highVolatilityCount = 0;
    let stableCount = 0;

    for (const quote of quotes) {
      const lastQuote = this.lastQuotes.get(quote.stock_code);
      const changeRate = quote.change_rate || 0;
      const absChangeRate = Math.abs(changeRate);

      if (lastQuote && lastQuote.prev_close > 0) {
        const changeFromLast = Math.abs(changeRate - lastQuote.change_rate);
        if (absChangeRate > volatilityThreshold || changeFromLast > volatilityThreshold) {
          highVolatilityCount++;
        }
      } else {
        if (absChangeRate > volatilityThreshold) {
          highVolatilityCount++;
        }
      }

      if (absChangeRate < stableThreshold) {
        stableCount++;
      }

      this.lastQuotes.set(quote.stock_code, {
        change_rate: changeRate,
        current_price: quote.current_price,
        prev_close: quote.prev_close
      });
    }

    const volatilityRatio = quotes.length > 0 ? highVolatilityCount / quotes.length : 0;
    const stableRatio = quotes.length > 0 ? stableCount / quotes.length : 0;

    const isHighVolatility = volatilityRatio > highVolatilityRatio;
    const isStable = stableRatio === 1;

    return {
      is_high_volatility: isHighVolatility,
      is_stable: isStable,
      high_volatility_count: highVolatilityCount,
      stable_count: stableCount,
      total_count: quotes.length,
      volatility_ratio: volatilityRatio,
      stable_ratio: stableRatio,
      volatility_threshold: volatilityThreshold,
      high_volatility_ratio_threshold: highVolatilityRatio,
      stable_threshold: stableThreshold
    };
  }

  determineRefreshMode(volatilityStatus) {
    const config = this.getConfig();
    let newMode = this.currentMode;
    let newIntervalMs = this.currentIntervalMs;

    if (volatilityStatus.is_high_volatility) {
      newMode = 'high_volatility';
      newIntervalMs = config.high_volatility_interval_ms;
    } else if (volatilityStatus.is_stable) {
      newMode = 'low_volatility';
      newIntervalMs = config.low_volatility_interval_ms;
    } else {
      newMode = 'normal';
      newIntervalMs = config.default_interval_ms;
    }

    return {
      mode: newMode,
      interval_ms: newIntervalMs,
      mode_changed: newMode !== this.currentMode
    };
  }

  evaluateAndUpdate(quotes) {
    const volatilityStatus = this.checkVolatility(quotes);
    const refreshDecision = this.determineRefreshMode(volatilityStatus);

    if (refreshDecision.mode_changed) {
      const oldMode = this.currentMode;
      const oldIntervalMs = this.currentIntervalMs;

      this.currentMode = refreshDecision.mode;
      this.currentIntervalMs = refreshDecision.interval_ms;

      console.log(`刷新策略变更: ${oldMode}(${oldIntervalMs}ms) -> ${this.currentMode}(${this.currentIntervalMs}ms)`);

      if (this.onIntervalChangeCallback) {
        this.onIntervalChangeCallback({
          old_mode: oldMode,
          old_interval_ms: oldIntervalMs,
          new_mode: this.currentMode,
          new_interval_ms: this.currentIntervalMs,
          volatility_status: volatilityStatus
        });
      }
    }

    return {
      mode: this.currentMode,
      interval_ms: this.currentIntervalMs,
      mode_changed: refreshDecision.mode_changed,
      volatility_status: volatilityStatus
    };
  }

  getStatus() {
    const config = this.getConfig();
    return {
      current_mode: this.currentMode,
      current_interval_ms: this.currentIntervalMs,
      config: config,
      last_quotes_count: this.lastQuotes.size
    };
  }

  reset() {
    this.lastQuotes.clear();
    const config = this.getConfig();
    this.currentMode = 'normal';
    this.currentIntervalMs = config.default_interval_ms;
  }

  static getStrategyTypes() {
    return {
      VOLATILITY_BASED: 'volatility_based',
      FIXED_INTERVAL: 'fixed_interval',
      TIME_BASED: 'time_based'
    };
  }

  static getModeNames() {
    return {
      high_volatility: '高波动模式',
      normal: '正常模式',
      low_volatility: '平稳模式'
    };
  }
}

class FixedIntervalRefreshStrategy extends RefreshStrategy {
  constructor(configService, fixedIntervalMs = 60000) {
    super(configService);
    this.fixedIntervalMs = fixedIntervalMs;
    this.currentMode = 'fixed';
    this.currentIntervalMs = fixedIntervalMs;
  }

  evaluateAndUpdate(quotes) {
    return {
      mode: this.currentMode,
      interval_ms: this.currentIntervalMs,
      mode_changed: false,
      volatility_status: {
        is_high_volatility: false,
        is_stable: false,
        high_volatility_count: 0,
        stable_count: 0,
        total_count: quotes ? quotes.length : 0
      }
    };
  }
}

class TimeBasedRefreshStrategy extends RefreshStrategy {
  constructor(configService) {
    super(configService);
    this.timeSlots = this.getDefaultTimeSlots();
  }

  getDefaultTimeSlots() {
    return [
      { start: '09:30', end: '11:30', interval_ms: 30000 },
      { start: '13:00', end: '15:00', interval_ms: 30000 },
      { start: '00:00', end: '24:00', interval_ms: 300000 }
    ];
  }

  setTimeSlots(timeSlots) {
    this.timeSlots = timeSlots;
  }

  getCurrentTimeSlot() {
    const now = new Date();
    const currentTime = now.getHours() * 60 + now.getMinutes();

    for (const slot of this.timeSlots) {
      const [startHour, startMin] = slot.start.split(':').map(Number);
      const [endHour, endMin] = slot.end.split(':').map(Number);
      const startTime = startHour * 60 + startMin;
      const endTime = endHour * 60 + endMin;

      if (currentTime >= startTime && currentTime < endTime) {
        return slot;
      }
    }

    return this.timeSlots[this.timeSlots.length - 1];
  }

  evaluateAndUpdate(quotes) {
    const timeSlot = this.getCurrentTimeSlot();
    const oldIntervalMs = this.currentIntervalMs;

    this.currentIntervalMs = timeSlot.interval_ms;
    this.currentMode = `time_slot_${timeSlot.start}_${timeSlot.end}`;

    const modeChanged = oldIntervalMs !== this.currentIntervalMs;

    if (modeChanged && this.onIntervalChangeCallback) {
      this.onIntervalChangeCallback({
        old_mode: 'time_based',
        old_interval_ms: oldIntervalMs,
        new_mode: this.currentMode,
        new_interval_ms: this.currentIntervalMs,
        time_slot: timeSlot
      });
    }

    return {
      mode: this.currentMode,
      interval_ms: this.currentIntervalMs,
      mode_changed: modeChanged,
      time_slot: timeSlot,
      volatility_status: {
        is_high_volatility: false,
        is_stable: false,
        high_volatility_count: 0,
        stable_count: 0,
        total_count: quotes ? quotes.length : 0
      }
    };
  }
}

module.exports = {
  RefreshStrategy,
  FixedIntervalRefreshStrategy,
  TimeBasedRefreshStrategy
};
