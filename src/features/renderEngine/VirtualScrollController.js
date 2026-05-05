const DEFAULT_ITEM_HEIGHT = 80;
const DEFAULT_OVERSCAN = 5;
const VIRTUALIZATION_THRESHOLD = 20;

export class VirtualScrollController {
  constructor(options = {}) {
    this.itemHeight = options.itemHeight || DEFAULT_ITEM_HEIGHT;
    this.overscan = options.overscan || DEFAULT_OVERSCAN;
    this.threshold = options.threshold || VIRTUALIZATION_THRESHOLD;

    this._itemCount = 0;
    this._scrollTop = 0;
    this._containerHeight = 0;
    this._measuredHeights = {};
    this._listeners = {
      visibleRangeChange: [],
      totalHeightChange: [],
      stateChange: [],
    };

    this._lastVisibleRange = { startIndex: 0, endIndex: 0 };
  }

  get itemCount() {
    return this._itemCount;
  }

  get scrollTop() {
    return this._scrollTop;
  }

  get containerHeight() {
    return this._containerHeight;
  }

  get shouldVirtualize() {
    return this._itemCount > this.threshold;
  }

  get totalHeight() {
    if (!this.shouldVirtualize) return 'auto';

    let totalHeight = 0;
    for (let i = 0; i < this._itemCount; i++) {
      totalHeight += this._measuredHeights[i] ?? this.itemHeight;
    }
    return totalHeight;
  }

  subscribe(event, callback) {
    if (this._listeners[event]) {
      this._listeners[event].push(callback);
    }
    return () => this._unsubscribe(event, callback);
  }

  _unsubscribe(event, callback) {
    if (this._listeners[event]) {
      const index = this._listeners[event].indexOf(callback);
      if (index > -1) {
        this._listeners[event].splice(index, 1);
      }
    }
  }

  _emit(event, data) {
    if (this._listeners[event]) {
      this._listeners[event].forEach((callback) => callback(data));
    }
  }

  getItemOffset(index) {
    let offset = 0;
    for (let i = 0; i < index; i++) {
      offset += this._measuredHeights[i] ?? this.itemHeight;
    }
    return offset;
  }

  getItemSize(index) {
    return this._measuredHeights[index] ?? this.itemHeight;
  }

  getItemStyle(index) {
    if (!this.shouldVirtualize) {
      return {};
    }
    return {
      position: 'absolute',
      top: this.getItemOffset(index),
      left: 0,
      right: 0,
    };
  }

  getVisibleRange() {
    if (!this.shouldVirtualize) {
      return {
        startIndex: 0,
        endIndex: Math.max(0, this._itemCount - 1),
        shouldRenderAll: true,
      };
    }

    let startIndex = 0;
    let accumulatedHeight = 0;

    while (startIndex < this._itemCount && accumulatedHeight < this._scrollTop) {
      accumulatedHeight += this.getItemSize(startIndex);
      if (accumulatedHeight <= this._scrollTop) {
        startIndex++;
      }
    }

    let endIndex = startIndex;
    accumulatedHeight = this.getItemOffset(startIndex);

    while (
      endIndex < this._itemCount &&
      accumulatedHeight < this._scrollTop + this._containerHeight
    ) {
      accumulatedHeight += this.getItemSize(endIndex);
      endIndex++;
    }

    const startIndexWithOverscan = Math.max(0, startIndex - this.overscan);
    const endIndexWithOverscan = Math.min(
      this._itemCount - 1,
      endIndex + this.overscan
    );

    return {
      startIndex: startIndexWithOverscan,
      endIndex: endIndexWithOverscan,
      shouldRenderAll: false,
    };
  }

  updateItemCount(count) {
    const oldCount = this._itemCount;
    this._itemCount = count;

    if (oldCount !== count) {
      this._emit('stateChange', {
        type: 'itemCount',
        oldValue: oldCount,
        newValue: count,
      });
      this._emit('totalHeightChange', this.totalHeight);
      this._checkVisibleRangeChange();
    }
  }

  updateScrollTop(scrollTop) {
    if (this._scrollTop === scrollTop) return;

    const oldValue = this._scrollTop;
    this._scrollTop = scrollTop;

    this._emit('stateChange', {
      type: 'scrollTop',
      oldValue,
      newValue: scrollTop,
    });

    this._checkVisibleRangeChange();
  }

  updateContainerHeight(height) {
    if (this._containerHeight === height) return;

    const oldValue = this._containerHeight;
    this._containerHeight = height;

    this._emit('stateChange', {
      type: 'containerHeight',
      oldValue,
      newValue: height,
    });

    this._checkVisibleRangeChange();
  }

  measureItem(index, height) {
    if (this._measuredHeights[index] === height) return;

    const oldHeight = this._measuredHeights[index] ?? this.itemHeight;
    this._measuredHeights[index] = height;

    if (oldHeight !== height) {
      this._emit('stateChange', {
        type: 'itemHeight',
        index,
        oldValue: oldHeight,
        newValue: height,
      });
      this._emit('totalHeightChange', this.totalHeight);
      this._checkVisibleRangeChange();
    }
  }

  resetMeasurements() {
    this._measuredHeights = {};
    this._emit('stateChange', {
      type: 'resetMeasurements',
    });
    this._emit('totalHeightChange', this.totalHeight);
  }

  reset() {
    this._itemCount = 0;
    this._scrollTop = 0;
    this._containerHeight = 0;
    this._measuredHeights = {};
    this._lastVisibleRange = { startIndex: 0, endIndex: 0 };

    this._emit('stateChange', { type: 'reset' });
    this._emit('totalHeightChange', this.totalHeight);
    this._checkVisibleRangeChange();
  }

  _checkVisibleRangeChange() {
    const currentRange = this.getVisibleRange();

    const hasChanged =
      currentRange.startIndex !== this._lastVisibleRange.startIndex ||
      currentRange.endIndex !== this._lastVisibleRange.endIndex;

    if (hasChanged) {
      this._lastVisibleRange = { ...currentRange };
      this._emit('visibleRangeChange', currentRange);
    }
  }

  getRenderIndices() {
    const range = this.getVisibleRange();
    if (range.shouldRenderAll) {
      return {
        indices: Array.from({ length: this._itemCount }, (_, i) => i),
        isVirtualized: false,
      };
    }

    const indices = [];
    for (let i = range.startIndex; i <= range.endIndex; i++) {
      indices.push(i);
    }

    return {
      indices,
      isVirtualized: true,
      startIndex: range.startIndex,
      endIndex: range.endIndex,
    };
  }

  getContainerStyle() {
    return {
      overflow: 'auto',
      position: 'relative',
    };
  }

  getSpacerStyle() {
    if (!this.shouldVirtualize) {
      return {};
    }
    return {
      height: this.totalHeight,
      position: 'relative',
    };
  }

  scrollTo(index) {
    const offset = this.getItemOffset(index);
    return offset;
  }

  scrollToTop() {
    return 0;
  }

  scrollToBottom() {
    return this.totalHeight;
  }

  getState() {
    return {
      itemCount: this._itemCount,
      scrollTop: this._scrollTop,
      containerHeight: this._containerHeight,
      measuredHeights: { ...this._measuredHeights },
      shouldVirtualize: this.shouldVirtualize,
      totalHeight: this.totalHeight,
      visibleRange: this.getVisibleRange(),
      itemHeight: this.itemHeight,
      overscan: this.overscan,
      threshold: this.threshold,
    };
  }
}

export const createVirtualScrollController = (options) => {
  return new VirtualScrollController(options);
};

export default VirtualScrollController;
