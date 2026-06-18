"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.HttpApiConnector = void 0;
const axios_1 = require("axios");
const base_connector_1 = require("./base.connector");
class HttpApiConnector extends base_connector_1.BaseConnector {
    constructor(config) {
        super();
        this.axiosInstance = null;
        this.config = config;
    }
    async connect() {
        this.axiosInstance = axios_1.default.create({
            baseURL: this.config.url,
            headers: this.config.headers,
            timeout: this.config.queryTimeout ?? 30000,
        });
    }
    async query(sql, params) {
        if (!this.axiosInstance) {
            throw new Error('HTTP API connector not connected');
        }
        const method = (this.config.method ?? 'GET').toUpperCase();
        const requestConfig = {
            method,
            url: sql,
        };
        if (method === 'GET' && params) {
            requestConfig.params = params[0];
        }
        else if (method === 'POST') {
            requestConfig.data = params?.[0] ?? this.config.body;
        }
        const response = await this.axiosInstance.request(requestConfig);
        const data = response.data;
        const rows = Array.isArray(data) ? data : [data];
        return {
            rows,
            rowCount: rows.length,
        };
    }
    async testConnection() {
        if (!this.axiosInstance) {
            await this.connect();
        }
        try {
            const response = await this.axiosInstance.request({
                method: (this.config.method ?? 'GET').toUpperCase(),
                url: '/',
            });
            return response.status >= 200 && response.status < 400;
        }
        catch (error) {
            if (axios_1.default.isAxiosError(error)) {
                return !!error.response;
            }
            return false;
        }
    }
    async inferSchema() {
        if (!this.axiosInstance) {
            throw new Error('HTTP API connector not connected');
        }
        try {
            const method = (this.config.method ?? 'GET').toUpperCase();
            const response = await this.axiosInstance.request({
                method,
                url: '/',
                ...(method === 'POST' ? { data: this.config.body } : {}),
            });
            const data = response.data;
            if (!data || typeof data !== 'object') {
                return [];
            }
            const sample = Array.isArray(data) ? data[0] : data;
            if (!sample || typeof sample !== 'object') {
                return [];
            }
            const columns = Object.entries(sample).map(([key, value]) => ({
                name: key,
                type: typeof value === 'object' ? (value === null ? 'null' : Array.isArray(value) ? 'array' : 'object') : typeof value,
                nullable: value === null,
            }));
            return [{ table: this.config.url, columns }];
        }
        catch {
            return [];
        }
    }
    async disconnect() {
        this.axiosInstance = null;
    }
}
exports.HttpApiConnector = HttpApiConnector;
//# sourceMappingURL=http-api.connector.js.map