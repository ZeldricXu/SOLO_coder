"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = __importDefault(require("express"));
const cors_1 = __importDefault(require("cors"));
const helmet_1 = __importDefault(require("helmet"));
const compression_1 = __importDefault(require("compression"));
const gateway_1 = require("./gateway");
const monitoring_1 = require("./monitoring");
const logging_1 = require("./logging");
const config_1 = require("./config");
const core_1 = require("./core");
const health_1 = __importDefault(require("./routes/health"));
const auth_1 = __importDefault(require("./routes/auth"));
const config_2 = __importDefault(require("./routes/config"));
const tasks_1 = __importDefault(require("./routes/tasks"));
const documents_1 = __importDefault(require("./routes/documents"));
const evaluation_1 = __importDefault(require("./routes/evaluation"));
const prompts_1 = __importDefault(require("./routes/prompts"));
const features_1 = __importDefault(require("./routes/features"));
const logging_2 = __importDefault(require("./routes/logging"));
const app = (0, express_1.default)();
const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 3000;
app.use((0, helmet_1.default)());
app.use((0, cors_1.default)());
app.use((0, compression_1.default)());
app.use(express_1.default.json({ limit: '10mb' }));
app.use(express_1.default.urlencoded({ extended: true }));
app.use((req, res, next) => {
    const start = Date.now();
    const method = req.method;
    const path = req.path;
    const ip = req.ip;
    logging_1.logger.info('Request started', { method, path, ip });
    res.on('finish', () => {
        const duration = Date.now() - start;
        const statusCode = res.statusCode;
        monitoring_1.monitoring.recordLatency('latency', duration, { endpoint: path });
        if (statusCode >= 400) {
            monitoring_1.monitoring.incrementCounter('error_rate', 1, { endpoint: path });
        }
        logging_1.logger.info('Request completed', { method, path, statusCode, duration_ms: duration });
    });
    next();
});
const authMiddleware = async (req, res, next) => {
    const publicPaths = ['/api/v1/auth/login', '/api/v1/auth/users', '/health', '/metrics', '/api/v1/logging/level'];
    if (publicPaths.some(p => req.path.startsWith(p)) || req.path === '/') {
        next();
        return;
    }
    try {
        const authHeader = req.headers.authorization;
        if (!authHeader) {
            res.status(401).json({ code: 401, error: 'Unauthorized' });
            return;
        }
        const token = authHeader.startsWith('Bearer ') ? authHeader.slice(7) : authHeader;
        const authResult = gateway_1.apiGateway.validateToken(token);
        if (!authResult) {
            res.status(401).json({ code: 401, error: 'Unauthorized' });
            return;
        }
        req.auth = authResult;
        next();
    }
    catch (error) {
        logging_1.logger.warn('Authentication failed', { path: req.path, error: error.message });
        res.status(401).json({ code: 401, error: 'Unauthorized' });
    }
};
const rateLimitMiddleware = (req, res, next) => {
    const auth = req.auth;
    const key = auth?.user_id || auth?.api_key || req.ip || 'unknown';
    const result = gateway_1.apiGateway.checkRateLimit(key);
    if (result.allowed) {
        next();
    }
    else {
        res.status(429).json({ code: 429, error: 'Rate limit exceeded' });
    }
};
app.use(authMiddleware);
app.use(rateLimitMiddleware);
app.get('/', (req, res) => {
    res.json({
        code: 200,
        data: {
            service: 'Model Evaluation & Automation Engine',
            version: '1.0.0',
            status: 'running',
            uptime: process.uptime(),
            modules: [
                'model_evaluation',
                'configuration_management',
                'api_gateway',
                'prompt_experiment',
                'monitoring',
                'logging',
                'core_processing',
                'document_pipeline',
                'feature_storage'
            ]
        }
    });
});
app.use('/api/v1/auth', auth_1.default);
app.use('/api/v1/config', config_2.default);
app.use('/api/v1/tasks', tasks_1.default);
app.use('/api/v1/documents', documents_1.default);
app.use('/api/v1/evaluation', evaluation_1.default);
app.use('/api/v1/prompts', prompts_1.default);
app.use('/api/v1/features', features_1.default);
app.use('/api/v1/logging', logging_2.default);
app.use('/', health_1.default);
app.use((err, req, res, next) => {
    logging_1.logger.error('Unhandled error', { error: err.message, stack: err.stack, path: req.path });
    res.status(500).json({ code: 500, error: 'Internal server error' });
});
app.use((req, res) => {
    res.status(404).json({ code: 404, error: 'Not found' });
});
async function bootstrap() {
    try {
        logging_1.logger.info('Starting application bootstrap...');
        config_1.configManager.createConfig('default', {
            'app.name': 'Model Evaluation Engine',
            'app.version': '1.0.0',
            'features.task_scheduling': true,
            'limits.max_concurrent_tasks': 100,
        });
        core_1.coreProcessor.registerHandler({
            name: 'document.parse',
            handler: async (payload) => ({ status: 'completed', result: payload }),
            timeout_ms: 30000,
        });
        core_1.coreProcessor.registerHandler({
            name: 'model.evaluate',
            handler: async () => ({ status: 'completed', metrics: { accuracy: 0.95 } }),
            timeout_ms: 60000,
        });
        app.listen(PORT, () => {
            logging_1.logger.info(`Server running on port ${PORT}`, { port: PORT });
            console.log(`🚀 Server running at http://localhost:${PORT}`);
            console.log(`📊 Health check: http://localhost:${PORT}/health`);
            console.log(`📈 Metrics: http://localhost:${PORT}/metrics`);
            console.log(`📚 API docs: POST /api/v1/auth/login for authentication`);
        });
    }
    catch (error) {
        logging_1.logger.fatal('Bootstrap failed', { error: error.message });
        process.exit(1);
    }
}
process.on('SIGTERM', async () => {
    logging_1.logger.info('SIGTERM received, shutting down gracefully...');
    await core_1.coreProcessor.shutdown();
    process.exit(0);
});
process.on('SIGINT', async () => {
    logging_1.logger.info('SIGINT received, shutting down gracefully...');
    await core_1.coreProcessor.shutdown();
    process.exit(0);
});
process.on('unhandledRejection', (reason, promise) => {
    logging_1.logger.error('Unhandled rejection', { reason: String(reason), promise: String(promise) });
});
process.on('uncaughtException', (err) => {
    logging_1.logger.fatal('Uncaught exception', { error: err.message, stack: err.stack });
    process.exit(1);
});
bootstrap();
exports.default = app;
//# sourceMappingURL=server.js.map