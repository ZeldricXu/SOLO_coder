"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.BaseChangeDetector = void 0;
class BaseChangeDetector {
    constructor() {
        this.isRunning = false;
        this.listeners = new Set();
    }
    onEvent(listener) {
        this.listeners.add(listener);
        return () => this.listeners.delete(listener);
    }
    emit(event) {
        for (const listener of this.listeners) {
            try {
                listener(event);
            }
            catch {
            }
        }
    }
}
exports.BaseChangeDetector = BaseChangeDetector;
//# sourceMappingURL=base-detector.js.map