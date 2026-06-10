"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.BaseConfigSource = void 0;
class BaseConfigSource {
    async exists(key) {
        const value = await this.get(key);
        return value !== undefined;
    }
    getNestedValue(data, path) {
        const parts = path.split('.');
        let current = data;
        for (const part of parts) {
            if (current === null || current === undefined) {
                return undefined;
            }
            if (typeof current === 'object' && !Array.isArray(current)) {
                current = current[part];
            }
            else {
                return undefined;
            }
        }
        return current;
    }
    setNestedValue(data, path, value) {
        const parts = path.split('.');
        let current = data;
        for (let i = 0; i < parts.length - 1; i++) {
            const part = parts[i];
            if (!(part in current) || typeof current[part] !== 'object' || current[part] === null || Array.isArray(current[part])) {
                current[part] = {};
            }
            current = current[part];
        }
        current[parts[parts.length - 1]] = value;
    }
}
exports.BaseConfigSource = BaseConfigSource;
//# sourceMappingURL=ConfigSource.js.map