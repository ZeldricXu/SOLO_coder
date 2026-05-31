"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const express_1 = __importDefault(require("express"));
const logger_1 = __importDefault(require("./common/logger"));
const uuid_1 = require("uuid");
const edge_inference_1 = require("./edge-inference");
const monitoring_1 = require("./monitoring");
const data_access_1 = require("./data-access");
const core_processing_1 = require("./core-processing");
const edge_aggregation_1 = require("./edge-aggregation");
const storage_management_1 = require("./storage-management");
const scheduling_1 = require("./scheduling");
const rule_engine_1 = require("./rule-engine");
const device_lifecycle_1 = require("./device-lifecycle");
const protocol_adapter_1 = require("./protocol-adapter");
const types_1 = require("./types");
class AIEdgePlatform {
    constructor(port = 3000) {
        this.port = port;
        this.app = (0, express_1.default)();
        this.inferenceScheduler = new edge_inference_1.EdgeInferenceScheduler();
        this.monitoring = new monitoring_1.MonitoringService();
        this.dataAccess = new data_access_1.DataAccessLayer();
        this.cacheInvalidation = new data_access_1.CacheInvalidationManager();
        this.pipelineProcessor = new core_processing_1.PipelineProcessor();
        this.dataStandardizer = new core_processing_1.DataStandardizer();
        this.dataAggregator = new edge_aggregation_1.EdgeDataAggregator();
        this.storageManager = new storage_management_1.StorageManager();
        this.taskScheduler = new scheduling_1.TaskScheduler();
        this.ruleEngine = new rule_engine_1.RuleEngine();
        this.deviceManager = new device_lifecycle_1.DeviceLifecycleManager();
        this.protocolAdapter = new protocol_adapter_1.ProtocolAdapterManager();
        this.setupMiddleware();
        this.setupRoutes();
        this.setupModuleInteractions();
    }
    setupMiddleware() {
        this.app.use(express_1.default.json({ limit: '10mb' }));
        this.app.use((req, _res, next) => {
            req.traceId = (0, uuid_1.v4)();
            logger_1.default.info({
                traceId: req.traceId,
                method: req.method,
                path: req.path,
                ip: req.ip
            }, '收到请求');
            next();
        });
        this.app.use((err, _req, res, _next) => {
            logger_1.default.error({ error: err.message, stack: err.stack }, '未处理的异常');
            res.status(500).json({
                code: 500,
                message: '内部服务器错误',
                error: err.message
            });
        });
    }
    setupModuleInteractions() {
        this.inferenceScheduler.setTaskCompleteCallback((task) => {
            this.monitoring.increment('inference.completed', { modelId: task.modelId });
            const event = this.ruleEngine.createEvent('inference.completed', {
                taskId: task.taskId,
                modelId: task.modelId,
                status: task.status
            }, 'inference-scheduler');
            this.ruleEngine.processEvent(event);
        });
        this.deviceManager.setDeviceStatusChangeCallback((device, oldStatus) => {
            if (device.status === types_1.DeviceStatus.OFFLINE || device.status === types_1.DeviceStatus.ERROR) {
                this.monitoring.increment('device.status.error', { deviceId: device.deviceId, status: device.status });
                const event = this.ruleEngine.createEvent('device.alert', {
                    deviceId: device.deviceId,
                    oldStatus,
                    newStatus: device.status,
                    message: device.statusMessage
                }, 'device-manager');
                this.ruleEngine.processEvent(event);
            }
        });
        this.dataAggregator.setUploadCallback((aggregatedData) => {
            this.monitoring.increment('aggregation.upload', { count: aggregatedData.length.toString() });
            for (const data of aggregatedData) {
                const event = this.ruleEngine.createEvent('data.aggregated', {
                    aggregationId: data.aggregationId,
                    metrics: data.metrics,
                    tags: data.tags
                }, 'data-aggregator');
                this.ruleEngine.processEvent(event);
            }
        });
        this.taskScheduler.setJobCompleteCallback((job) => {
            this.monitoring.increment('job.completed', { jobId: job.jobId, status: job.status });
        });
    }
    setupRoutes() {
        this.app.get('/health', (_req, res) => {
            res.json({
                status: 'healthy',
                timestamp: new Date().toISOString(),
                version: '1.0.0'
            });
        });
        this.app.get('/api/v1/stats', (_req, res) => {
            res.json({
                code: 200,
                data: {
                    cache: this.dataAccess.getStats(),
                    monitoring: this.monitoring.getStats(),
                    scheduler: this.taskScheduler.getStats(),
                    device: this.deviceManager.getStats(),
                    inference: this.inferenceScheduler.getQueueStats(),
                    rules: this.ruleEngine.getStats()
                }
            });
        });
        this.app.post('/api/v1/resources', async (req, res) => {
            const traceId = req.traceId;
            const request = req.body;
            await this.monitoring.withTimer('resources.create', async () => {
                const response = {
                    code: 201,
                    data: {
                        id: `rsc_${(0, uuid_1.v4)().slice(0, 8)}`,
                        status: 'provisioning'
                    }
                };
                res.status(201).json(response);
            }, { traceId });
        });
        this.app.get('/api/v1/resources/:id/status', (req, res) => {
            const { id } = req.params;
            const taskStatus = this.inferenceScheduler.getTaskStatus(id);
            const jobStatus = this.taskScheduler.getJobStatus(id);
            const deviceStatus = this.deviceManager.getDevice(id);
            let status = 'unknown';
            let progress = 0;
            if (taskStatus) {
                status = taskStatus.status;
            }
            else if (jobStatus) {
                status = jobStatus.status;
                const completedTasks = jobStatus.tasks.filter(t => t.status === 'completed' || t.status === 'failed').length;
                progress = jobStatus.tasks.length > 0 ? completedTasks / jobStatus.tasks.length : 0;
            }
            else if (deviceStatus) {
                status = deviceStatus.status;
            }
            res.json({
                code: 200,
                data: {
                    id,
                    status,
                    progress
                }
            });
        });
        this.app.post('/api/v1/resources/batch', (req, res) => {
            const request = req.body;
            const results = [];
            for (const op of request.operations) {
                let success = false;
                let message = '';
                try {
                    switch (op.action) {
                        case 'restart':
                            success = this.taskScheduler.cancelTask(op.id);
                            message = success ? '任务已取消' : '任务取消失败';
                            break;
                        case 'delete':
                            success = this.deviceManager.decommissionDevice(op.id);
                            message = success ? '设备已注销' : '设备注销失败';
                            break;
                        default:
                            message = `不支持的操作: ${op.action}`;
                    }
                }
                catch (error) {
                    message = error instanceof Error ? error.message : String(error);
                }
                results.push({ id: op.id, success, message });
            }
            const response = {
                code: 200,
                data: {
                    batch_id: `batch_${(0, uuid_1.v4)().slice(0, 8)}`,
                    results
                }
            };
            res.json(response);
        });
        this.app.post('/api/v1/inference/deploy', async (req, res) => {
            try {
                const { modelId, version, edgeNodeId, resources } = req.body;
                const deployment = await this.inferenceScheduler.deployModel(modelId, version, edgeNodeId, resources);
                res.json({ code: 200, data: deployment });
            }
            catch (error) {
                res.status(500).json({ code: 500, message: error instanceof Error ? error.message : '部署失败' });
            }
        });
        this.app.post('/api/v1/inference/tasks', (req, res) => {
            const { modelId, inputData, priority, callbackUrl } = req.body;
            const task = this.inferenceScheduler.submitInferenceTask(modelId, inputData, priority, callbackUrl);
            res.json({ code: 201, data: task });
        });
        this.app.get('/api/v1/inference/tasks/:taskId', (req, res) => {
            const task = this.inferenceScheduler.getTaskStatus(req.params.taskId);
            if (!task) {
                res.status(404).json({ code: 404, message: '任务不存在' });
                return;
            }
            res.json({ code: 200, data: task });
        });
        this.app.get('/api/v1/inference/models', (_req, res) => {
            res.json({ code: 200, data: this.inferenceScheduler.getDeployedModels() });
        });
        this.app.get('/api/v1/inference/queue-stats', (_req, res) => {
            res.json({ code: 200, data: this.inferenceScheduler.getQueueStats() });
        });
        this.app.get('/api/v1/inference/workers', (_req, res) => {
            res.json({ code: 200, data: this.inferenceScheduler.getWorkerStats() });
        });
        this.app.post('/api/v1/inference/batch', async (req, res) => {
            try {
                const result = await this.inferenceScheduler.submitBatchInference(req.body);
                res.json({ code: 201, data: result });
            }
            catch (error) {
                res.status(400).json({ code: 400, message: error instanceof Error ? error.message : '批量提交失败' });
            }
        });
        this.app.delete('/api/v1/inference/tasks/:taskId', (req, res) => {
            const success = this.inferenceScheduler.cancelTask(req.params.taskId);
            if (!success) {
                res.status(404).json({ code: 404, message: '任务不存在或无法取消' });
                return;
            }
            res.json({ code: 200, data: { success: true } });
        });
        this.app.post('/api/v1/devices', (req, res) => {
            try {
                const request = req.body;
                const result = this.deviceManager.registerDevice(request);
                res.json({ code: 201, data: result });
            }
            catch (error) {
                res.status(400).json({ code: 400, message: error instanceof Error ? error.message : '注册失败' });
            }
        });
        this.app.post('/api/v1/devices/activate', async (req, res) => {
            const { activationCode } = req.body;
            const result = await this.deviceManager.activateDevice(activationCode);
            if (!result.success) {
                res.status(400).json({ code: 400, message: result.error });
                return;
            }
            res.json({ code: 200, data: result });
        });
        this.app.post('/api/v1/devices/heartbeat', (req, res) => {
            const heartbeat = req.body;
            const device = this.deviceManager.processHeartbeat(heartbeat);
            if (!device) {
                res.status(404).json({ code: 404, message: '设备不存在' });
                return;
            }
            res.json({ code: 200, data: { status: 'ok' } });
        });
        this.app.get('/api/v1/devices', (req, res) => {
            const { status, type, manufacturer } = req.query;
            const filter = {
                ...(status ? { status: [status] } : {}),
                ...(type ? { type: type } : {}),
                ...(manufacturer ? { manufacturer: manufacturer } : {})
            };
            const devices = this.deviceManager.listDevices(filter);
            res.json({ code: 200, data: devices });
        });
        this.app.get('/api/v1/devices/:deviceId', (req, res) => {
            const device = this.deviceManager.getDevice(req.params.deviceId);
            if (!device) {
                res.status(404).json({ code: 404, message: '设备不存在' });
                return;
            }
            res.json({ code: 200, data: device });
        });
        this.app.delete('/api/v1/devices/:deviceId', (req, res) => {
            const { reason } = req.body;
            const success = this.deviceManager.decommissionDevice(req.params.deviceId, reason);
            if (!success) {
                res.status(404).json({ code: 404, message: '设备不存在' });
                return;
            }
            res.json({ code: 200, data: { success: true } });
        });
        this.app.post('/api/v1/metrics/batch', (req, res) => {
            const { records } = req.body;
            if (Array.isArray(records)) {
                this.monitoring.recordBatch(records);
                res.json({ code: 200, data: { received: records.length } });
            }
            else {
                res.status(400).json({ code: 400, message: '无效的批量数据' });
            }
        });
        this.app.post('/api/v1/metrics/flush', (_req, res) => {
            const flushed = this.monitoring.forceFlush();
            res.json({ code: 200, data: { flushed } });
        });
        this.app.post('/api/v1/metrics/merge-rules', (req, res) => {
            try {
                const { sourcePattern, targetName, mergeType, tagAggregation } = req.body;
                this.monitoring.addMergeRule({
                    sourcePattern: new RegExp(sourcePattern),
                    targetName,
                    mergeType,
                    tagAggregation
                });
                res.json({ code: 201, data: { success: true } });
            }
            catch (error) {
                res.status(400).json({ code: 400, message: error instanceof Error ? error.message : '添加合并规则失败' });
            }
        });
        this.app.post('/api/v1/metrics/batch-snapshot', (req, res) => {
            const { windowStart, windowEnd, dimensions } = req.body;
            const snapshot = this.monitoring.createBatchSnapshot(windowStart ?? Date.now() - 3600000, windowEnd ?? Date.now(), dimensions ?? {});
            res.json({ code: 200, data: snapshot });
        });
        this.app.get('/api/v1/metrics/query', (req, res) => {
            const { namePattern, startTime, endTime, tags } = req.query;
            const results = this.monitoring.queryMetrics(String(namePattern || ''), Number(startTime) || Date.now() - 3600000, Number(endTime) || Date.now(), tags ? JSON.parse(String(tags)) : undefined);
            res.json({ code: 200, data: results });
        });
        this.app.post('/api/v1/protocol/adapters', async (req, res) => {
            try {
                const { adapterId, config } = req.body;
                await this.protocolAdapter.createAdapter(adapterId, config);
                res.json({ code: 201, data: { adapterId } });
            }
            catch (error) {
                res.status(400).json({ code: 400, message: error instanceof Error ? error.message : '创建适配器失败' });
            }
        });
        this.app.get('/api/v1/protocol/adapters', (_req, res) => {
            res.json({ code: 200, data: this.protocolAdapter.listAdapters() });
        });
        this.app.get('/api/v1/protocol/drivers', (_req, res) => {
            res.json({ code: 200, data: this.protocolAdapter.listDrivers() });
        });
        this.app.get('/api/v1/protocol/read/:adapterId/:address', async (req, res) => {
            try {
                const { adapterId, address } = req.params;
                const data = await this.protocolAdapter.readAndNormalize(adapterId, address);
                res.json({ code: 200, data });
            }
            catch (error) {
                res.status(400).json({ code: 400, message: error instanceof Error ? error.message : '读取失败' });
            }
        });
        this.app.post('/api/v1/metrics/snapshot', (_req, res) => {
            const snapshot = this.monitoring.createSnapshot({
                host: process.env.HOSTNAME || 'localhost',
                region: process.env.REGION || 'default'
            });
            res.json({ code: 200, data: snapshot });
        });
        this.app.post('/api/v1/scheduler/jobs', (req, res) => {
            const { name, tasks, context } = req.body;
            const job = this.taskScheduler.createJob(name, tasks, context);
            this.taskScheduler.submitJob(job.jobId);
            res.json({ code: 201, data: job });
        });
        this.app.get('/api/v1/scheduler/jobs/:jobId', (req, res) => {
            const job = this.taskScheduler.getJobStatus(req.params.jobId);
            if (!job) {
                res.status(404).json({ code: 404, message: '任务编排不存在' });
                return;
            }
            res.json({ code: 200, data: job });
        });
        this.app.post('/api/v1/rules', (req, res) => {
            const rule = this.ruleEngine.createRule(req.body);
            res.json({ code: 201, data: rule });
        });
        this.app.get('/api/v1/rules', (_req, res) => {
            res.json({ code: 200, data: this.ruleEngine.listRules() });
        });
        this.app.post('/api/v1/rules/:ruleId/enable', (req, res) => {
            const success = this.ruleEngine.enableRule(req.params.ruleId);
            if (!success) {
                res.status(404).json({ code: 404, message: '规则不存在' });
                return;
            }
            res.json({ code: 200, data: { success: true } });
        });
        this.app.post('/api/v1/rules/:ruleId/disable', (req, res) => {
            const success = this.ruleEngine.disableRule(req.params.ruleId);
            if (!success) {
                res.status(404).json({ code: 404, message: '规则不存在' });
                return;
            }
            res.json({ code: 200, data: { success: true } });
        });
        this.app.post('/api/v1/aggregate/ingest', (req, res) => {
            const { metricName, value, tags } = req.body;
            this.dataAggregator.ingestDataPoint(metricName, value, tags || {});
            res.json({ code: 200, data: { success: true } });
        });
        this.app.post('/api/v1/aggregate/batch', (req, res) => {
            const { metricName, values } = req.body;
            this.dataAggregator.ingestBatch(metricName, values);
            res.json({ code: 200, data: { success: true } });
        });
        this.app.post('/api/v1/aggregate/upload', (_req, res) => {
            this.dataAggregator.triggerUpload();
            res.json({ code: 200, data: { success: true } });
        });
        this.app.get('/api/v1/cache/stats', (_req, res) => {
            res.json({ code: 200, data: this.dataAccess.getStats() });
        });
        this.app.delete('/api/v1/cache', async (_req, res) => {
            await this.dataAccess.clear();
            res.json({ code: 200, data: { success: true } });
        });
        this.app.post('/api/v1/cache/sources', (req, res) => {
            const { id, name, type, isReadable, isWritable, priority, config } = req.body;
            this.dataAccess.addDataSource({
                id,
                name,
                type,
                isReadable: isReadable ?? true,
                isWritable: isWritable ?? true,
                priority: priority ?? 1,
                health: 'healthy',
                lastHealthCheck: Date.now(),
                config: config || {}
            });
            res.json({ code: 201, data: { id } });
        });
        this.app.get('/api/v1/cache/sources', (_req, res) => {
            const sources = this.dataAccess.getSourceManager().listSources();
            res.json({ code: 200, data: sources });
        });
        this.app.delete('/api/v1/cache/sources/:sourceId', (req, res) => {
            const success = this.dataAccess.removeDataSource(req.params.sourceId);
            if (!success) {
                res.status(404).json({ code: 404, message: '数据源不存在' });
                return;
            }
            res.json({ code: 200, data: { success: true } });
        });
        this.app.post('/api/v1/cache/routing-rules', (req, res) => {
            try {
                const { name, pattern, readSources, writeSources, priority, condition, enabled } = req.body;
                const rule = this.dataAccess.addRoutingRule({
                    name,
                    pattern: new RegExp(pattern),
                    readSources,
                    writeSources,
                    priority: priority ?? 10,
                    enabled: enabled ?? true,
                    condition: condition ? eval(condition) : undefined
                });
                res.json({ code: 201, data: rule });
            }
            catch (error) {
                res.status(400).json({ code: 400, message: error instanceof Error ? error.message : '添加路由规则失败' });
            }
        });
        this.app.get('/api/v1/cache/routing-rules', (_req, res) => {
            const rules = this.dataAccess.getRoutingRules();
            res.json({ code: 200, data: rules });
        });
        this.app.delete('/api/v1/cache/routing-rules/:ruleId', (req, res) => {
            const success = this.dataAccess.removeRoutingRule(req.params.ruleId);
            if (!success) {
                res.status(404).json({ code: 404, message: '路由规则不存在' });
                return;
            }
            res.json({ code: 200, data: { success: true } });
        });
        this.app.post('/api/v1/process/pipeline/:pipelineId', async (req, res) => {
            const traceId = req.traceId;
            const context = (0, core_processing_1.createProcessingContext)(traceId);
            const result = await this.pipelineProcessor.executePipeline(req.params.pipelineId, req.body, context);
            res.json({ code: 200, data: result });
        });
    }
    async start() {
        return new Promise((resolve) => {
            this.app.listen(this.port, () => {
                logger_1.default.info({ port: this.port }, 'AI模型边缘部署平台已启动');
                resolve();
            });
        });
    }
    async stop() {
        this.monitoring.stop();
        this.dataAggregator.stop();
        this.deviceManager.stop();
        this.taskScheduler.stop();
        await this.protocolAdapter.stop();
        await this.inferenceScheduler.stop();
        this.dataAccess.stop();
        logger_1.default.info('AI模型边缘部署平台已停止');
    }
    getApp() {
        return this.app;
    }
}
const platform = new AIEdgePlatform(Number(process.env.PORT) || 3000);
platform.start().catch((error) => {
    logger_1.default.error({ error }, '平台启动失败');
    process.exit(1);
});
process.on('SIGTERM', async () => {
    logger_1.default.info('收到SIGTERM信号，正在关闭...');
    await platform.stop();
    process.exit(0);
});
process.on('SIGINT', async () => {
    logger_1.default.info('收到SIGINT信号，正在关闭...');
    await platform.stop();
    process.exit(0);
});
exports.default = AIEdgePlatform;
//# sourceMappingURL=index.js.map