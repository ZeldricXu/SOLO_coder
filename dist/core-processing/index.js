"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.createProcessingContext = exports.PipelineProcessor = exports.DataStandardizer = void 0;
const logger_1 = __importDefault(require("../common/logger"));
const errors_1 = require("../common/errors");
class DataStandardizer {
    constructor() {
        this.schemas = new Map();
    }
    registerSchema(schema) {
        const key = `${schema.name}:v${schema.version}`;
        this.schemas.set(key, schema);
        logger_1.default.info({ schemaName: schema.name, version: schema.version }, '注册数据标准化Schema');
    }
    getSchema(name, version) {
        return this.schemas.get(`${name}:v${version}`);
    }
    async standardize(input, schemaName, schemaVersion) {
        const schema = this.getSchema(schemaName, schemaVersion);
        if (!schema) {
            throw new errors_1.ValidationError(`Schema未找到: ${schemaName}:v${schemaVersion}`);
        }
        const output = {};
        const errors = [];
        for (const rule of schema.rules) {
            let value = input[rule.field];
            if (value === undefined || value === null) {
                if (rule.required) {
                    errors.push(`必填字段缺失: ${rule.field}`);
                    continue;
                }
                if (rule.defaultValue !== undefined) {
                    value = rule.defaultValue;
                }
                else {
                    continue;
                }
            }
            try {
                value = this.convertType(value, rule.type);
                if (rule.transform) {
                    value = rule.transform(value);
                }
                if (rule.validators) {
                    for (const validator of rule.validators) {
                        if (!validator(value)) {
                            errors.push(`字段验证失败: ${rule.field}`);
                        }
                    }
                }
                output[rule.field] = value;
            }
            catch (error) {
                errors.push(`字段转换失败: ${rule.field}, ${error instanceof Error ? error.message : String(error)}`);
            }
        }
        if (errors.length > 0) {
            throw new errors_1.ValidationError('数据标准化失败', errors);
        }
        logger_1.default.debug({ schemaName, schemaVersion, fields: Object.keys(output) }, '数据标准化完成');
        return output;
    }
    convertType(value, targetType) {
        switch (targetType) {
            case 'string':
                return String(value);
            case 'number': {
                const num = Number(value);
                if (isNaN(num))
                    throw new Error(`无法转换为数字: ${value}`);
                return num;
            }
            case 'boolean':
                return Boolean(value);
            case 'date': {
                const date = new Date(String(value));
                if (isNaN(date.getTime()))
                    throw new Error(`无效日期: ${value}`);
                return date.toISOString();
            }
            case 'object':
                if (typeof value !== 'object' || value === null || Array.isArray(value)) {
                    throw new Error('不是有效的对象');
                }
                return value;
            case 'array':
                if (!Array.isArray(value)) {
                    throw new Error('不是有效的数组');
                }
                return value;
            default:
                return value;
        }
    }
    validateWithZod(input, schema) {
        const result = schema.safeParse(input);
        if (!result.success) {
            throw new errors_1.ValidationError('数据验证失败', result.error.errors);
        }
        return result.data;
    }
}
exports.DataStandardizer = DataStandardizer;
class PipelineProcessor {
    constructor() {
        this.pipelines = new Map();
        this.transformers = new Map();
    }
    registerTransformer(transformer) {
        const key = `${transformer.name}:v${transformer.version}`;
        this.transformers.set(key, transformer);
        logger_1.default.info({ name: transformer.name, version: transformer.version }, '注册数据转换器');
    }
    registerPipeline(pipeline) {
        this.pipelines.set(pipeline.id, pipeline);
        logger_1.default.info({ pipelineId: pipeline.id, name: pipeline.name, stages: pipeline.stages.length }, '注册处理流水线');
    }
    getTransformer(name, version) {
        return this.transformers.get(`${name}:v${version}`);
    }
    getPipeline(pipelineId) {
        return this.pipelines.get(pipelineId);
    }
    async executePipeline(pipelineId, input, context) {
        const pipeline = this.pipelines.get(pipelineId);
        if (!pipeline) {
            throw new errors_1.ValidationError(`流水线未找到: ${pipelineId}`);
        }
        const startTime = Date.now();
        const stageResults = new Map();
        const errors = [];
        let currentData = input;
        let success = true;
        logger_1.default.info({ pipelineId, traceId: context.traceId }, '开始执行流水线');
        for (const stage of pipeline.stages) {
            const stageStart = Date.now();
            try {
                logger_1.default.debug({ stageId: stage.id, stageName: stage.name }, '执行流水线阶段');
                const transformer = this.transformers.get(`${stage.transformer.name}:v${stage.transformer.version}`);
                if (!transformer) {
                    throw new Error(`转换器未找到: ${stage.transformer.name}:v${stage.transformer.version}`);
                }
                if (transformer.validate && !transformer.validate(currentData)) {
                    throw new Error('输入数据验证失败');
                }
                const timeoutMs = stage.timeoutMs ?? 30000;
                const result = await this.withTimeout(transformer.transform(currentData, { ...context, metadata: { ...context.metadata, stageConfig: stage.config } }), timeoutMs, `阶段超时: ${stage.name}`);
                stageResults.set(stage.id, result);
                currentData = result;
                const stageTime = Date.now() - stageStart;
                logger_1.default.debug({ stageId: stage.id, stageName: stage.name, timeMs: stageTime }, '阶段执行完成');
            }
            catch (error) {
                const errorMessage = error instanceof Error ? error.message : String(error);
                errors.push({ stage: stage.name, message: errorMessage, details: error });
                logger_1.default.error({ stageId: stage.id, stageName: stage.name, error: errorMessage }, '阶段执行失败');
                if (!stage.skipOnError) {
                    success = false;
                    break;
                }
            }
        }
        const totalTimeMs = Date.now() - startTime;
        logger_1.default.info({ pipelineId, traceId: context.traceId, success, totalTimeMs, errorCount: errors.length }, '流水线执行完成');
        return {
            success,
            data: success ? currentData : undefined,
            errors,
            stageResults,
            totalTimeMs
        };
    }
    async withTimeout(promise, timeoutMs, errorMessage) {
        return Promise.race([
            promise,
            new Promise((_, reject) => setTimeout(() => reject(new Error(errorMessage)), timeoutMs))
        ]);
    }
    async executeHandler(handler, request, context) {
        try {
            logger_1.default.info({ traceId: context.traceId }, '开始处理请求');
            const result = await handler(request, context);
            logger_1.default.info({ traceId: context.traceId, success: result.success }, '请求处理完成');
            return result;
        }
        catch (error) {
            logger_1.default.error({ traceId: context.traceId, error }, '请求处理异常');
            return {
                success: false,
                error: {
                    code: 500,
                    message: error instanceof Error ? error.message : '内部处理错误',
                    details: error
                }
            };
        }
    }
    listPipelines() {
        return Array.from(this.pipelines.values());
    }
    listTransformers() {
        return Array.from(this.transformers.values());
    }
}
exports.PipelineProcessor = PipelineProcessor;
const createProcessingContext = (traceId, namespace = 'default') => ({
    traceId: traceId ?? Math.random().toString(36).substring(2, 15),
    startTime: Date.now(),
    namespace,
    metadata: {}
});
exports.createProcessingContext = createProcessingContext;
//# sourceMappingURL=index.js.map