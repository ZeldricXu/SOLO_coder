"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = __importDefault(require("express"));
const pino_http_1 = __importDefault(require("pino-http"));
const routes_1 = __importDefault(require("./api/routes"));
const logger_1 = require("./common/logger");
const events_1 = require("./common/events");
const utils_1 = require("./common/utils");
const app = (0, express_1.default)();
const PORT = process.env.PORT || 3000;
app.use(express_1.default.json({ limit: '10mb' }));
app.use(express_1.default.urlencoded({ extended: true }));
app.use((0, pino_http_1.default)({
    logger: logger_1.logger,
    autoLogging: true,
}));
app.use((req, _res, next) => {
    const startTime = Date.now();
    req.on('end', () => {
        const duration = Date.now() - startTime;
        utils_1.metricsCollector.record(`http.${req.method}.${req.path}.duration`, duration);
    });
    next();
});
app.use('/api/v1', routes_1.default);
app.use((_req, res) => {
    res.status(404).json({
        code: 404,
        message: 'Not found',
    });
});
app.use((err, _req, res, _next) => {
    logger_1.logger.error('Unhandled error', err);
    res.status(500).json({
        code: 500,
        message: err.message || 'Internal server error',
    });
});
events_1.eventBus.on(events_1.EVENTS.ERROR, (data) => {
    logger_1.logger.error('System error', data);
});
events_1.eventBus.on(events_1.EVENTS.METRICS, (data) => {
    logger_1.logger.debug('Metrics collected', data);
});
process.on('uncaughtException', (error) => {
    logger_1.logger.error('Uncaught exception', error);
    process.exit(1);
});
process.on('unhandledRejection', (reason, promise) => {
    logger_1.logger.error('Unhandled rejection', reason, { promise: String(promise) });
});
const server = app.listen(PORT, () => {
    logger_1.logger.info(`Contract Audit Platform API server started on port ${PORT}`);
    logger_1.logger.info(`Health check: http://localhost:${PORT}/api/v1/health`);
    logger_1.logger.info(`Available endpoints:`);
    logger_1.logger.info(`  POST /api/v1/resources`);
    logger_1.logger.info(`  GET  /api/v1/resources/:id/status`);
    logger_1.logger.info(`  POST /api/v1/resources/batch`);
    logger_1.logger.info(`  Multi-sig: /api/v1/multisig/*`);
    logger_1.logger.info(`  ZKP: /api/v1/zkp/*`);
    logger_1.logger.info(`  Events: /api/v1/events/*`);
    logger_1.logger.info(`  Transactions: /api/v1/transactions/*`);
    logger_1.logger.info(`  Cross-chain: /api/v1/crosschain/*`);
    logger_1.logger.info(`  HD Wallet: /api/v1/hdwallet/*`);
    logger_1.logger.info(`  Storage: /api/v1/storage/*`);
    logger_1.logger.info(`  Indexer: /api/v1/indexer/*`);
    logger_1.logger.info(`  Chain: /api/v1/chain/*`);
    logger_1.logger.info(`  Gas: /api/v1/gas/*`);
});
process.on('SIGTERM', () => {
    logger_1.logger.info('SIGTERM received, shutting down gracefully');
    server.close(() => {
        logger_1.logger.info('Server closed');
        process.exit(0);
    });
});
process.on('SIGINT', () => {
    logger_1.logger.info('SIGINT received, shutting down gracefully');
    server.close(() => {
        logger_1.logger.info('Server closed');
        process.exit(0);
    });
});
exports.default = app;
//# sourceMappingURL=index.js.map