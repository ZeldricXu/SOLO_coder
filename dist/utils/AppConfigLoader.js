"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.findConfigFile = findConfigFile;
exports.loadConfig = loadConfig;
exports.saveConfig = saveConfig;
exports.generateSampleConfig = generateSampleConfig;
exports.generateSampleSchema = generateSampleSchema;
exports.validateCliConfig = validateCliConfig;
exports.configToAppConfig = configToAppConfig;
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
const yaml = __importStar(require("js-yaml"));
const DEFAULT_CONFIG_NAME = 'config-flow.yaml';
function findConfigFile(customPath) {
    if (customPath) {
        const abs = path.resolve(customPath);
        return fs.existsSync(abs) ? abs : null;
    }
    const candidates = [
        DEFAULT_CONFIG_NAME,
        '.config-flow.yaml',
        'config-flow.yml',
        '.config-flow.yml',
    ];
    let currentDir = process.cwd();
    let found = false;
    while (!found) {
        for (const candidate of candidates) {
            const full = path.join(currentDir, candidate);
            if (fs.existsSync(full))
                return full;
        }
        const parent = path.dirname(currentDir);
        if (parent === currentDir) {
            found = true;
        }
        else {
            currentDir = parent;
        }
    }
    return null;
}
function loadConfig(customPath) {
    const configPath = findConfigFile(customPath);
    if (!configPath) {
        return getDefaultConfig();
    }
    const projectRoot = path.dirname(configPath);
    const content = fs.readFileSync(configPath, 'utf-8');
    const rawConfig = (yaml.load(content) || {});
    return parseConfig(rawConfig, projectRoot);
}
function getDefaultConfig() {
    const projectRoot = process.cwd();
    return {
        projectRoot,
        storagePath: path.join(projectRoot, '.config-flow', 'history.db'),
        gitRepoPath: path.join(projectRoot, '.config-flow', 'git-repo'),
        schemaPath: path.join(projectRoot, 'config-schema.json'),
        environments: [],
        defaultOperator: process.env.USER || 'system',
    };
}
function parseConfig(raw, projectRoot) {
    const config = {
        projectRoot,
        storagePath: path.resolve(projectRoot, String(raw.storagePath || '.config-flow/history.db')),
        gitRepoPath: path.resolve(projectRoot, String(raw.gitRepoPath || '.config-flow/git-repo')),
        schemaPath: path.resolve(projectRoot, String(raw.schemaPath || 'config-schema.json')),
        environments: parseEnvironments(raw.environments, projectRoot),
        notifications: parseNotifications(raw.notifications),
        defaultOperator: raw.defaultOperator,
    };
    return config;
}
function parseEnvironments(raw, projectRoot) {
    if (!Array.isArray(raw))
        return [];
    return raw.map((envRaw) => {
        const env = {
            name: String(envRaw.name),
            sources: [],
            labels: envRaw.labels,
        };
        if (Array.isArray(envRaw.sources)) {
            env.sources = envRaw.sources.map((sRaw) => {
                const source = {
                    type: String(sRaw.type),
                    priority: Number(sRaw.priority || 0),
                    options: sRaw.options && typeof sRaw.options === 'object' ? { ...sRaw.options } : {},
                };
                if (source.type === 'env' && source.options.filePath) {
                    source.options.filePath = path.resolve(projectRoot, String(source.options.filePath));
                }
                return source;
            });
        }
        return env;
    });
}
function parseNotifications(raw) {
    if (!Array.isArray(raw))
        return undefined;
    return raw.map((nRaw) => ({
        type: String(nRaw.type),
        config: (nRaw.config || {}),
    }));
}
function saveConfig(config, outputPath) {
    const targetPath = outputPath || path.join(config.projectRoot, DEFAULT_CONFIG_NAME);
    const serializable = {
        storagePath: path.relative(config.projectRoot, config.storagePath),
        gitRepoPath: path.relative(config.projectRoot, config.gitRepoPath),
        schemaPath: path.relative(config.projectRoot, config.schemaPath),
        environments: config.environments.map((env) => ({
            name: env.name,
            labels: env.labels,
            sources: env.sources.map((s) => {
                const opt = { ...s.options };
                if (s.type === 'env' && opt.filePath) {
                    opt.filePath = path.relative(config.projectRoot, String(opt.filePath));
                }
                return {
                    type: s.type,
                    priority: s.priority,
                    options: opt,
                };
            }),
        })),
        notifications: config.notifications,
        defaultOperator: config.defaultOperator,
    };
    const yamlContent = yaml.dump(serializable, { lineWidth: -1, quotingType: '"', forceQuotes: true });
    fs.writeFileSync(targetPath, yamlContent);
    return targetPath;
}
function generateSampleConfig(outputDir) {
    const sample = {
        storagePath: '.config-flow/history.db',
        gitRepoPath: '.config-flow/git-repo',
        schemaPath: 'config-schema.json',
        defaultOperator: process.env.USER || 'system',
        environments: [
            {
                name: 'development',
                labels: { tier: 'dev' },
                sources: [
                    { type: 'default', priority: 10, options: { defaults: { app: { port: 3000, debug: true } } } },
                    { type: 'env', priority: 100, options: { filePath: '.env.dev', useProcessEnv: true, prefix: 'APP_' } },
                ],
            },
            {
                name: 'staging',
                labels: { tier: 'staging' },
                sources: [
                    { type: 'default', priority: 10, options: { defaults: { app: { port: 3000 } } } },
                    { type: 'configmap', priority: 50, options: { namespace: 'staging', name: 'app-config', dataKey: 'app.json' } },
                    { type: 'vault', priority: 80, options: { path: 'secret/data/staging/app' } },
                ],
            },
            {
                name: 'production',
                labels: { tier: 'prod' },
                sources: [
                    { type: 'default', priority: 10, options: { defaults: { app: { port: 3000 } } } },
                    { type: 'ssm', priority: 50, options: { region: 'us-east-1', pathPrefix: '/prod/app/' } },
                    { type: 'vault', priority: 80, options: { path: 'secret/data/prod/app' } },
                ],
            },
        ],
        notifications: [
            {
                type: 'slack',
                config: {
                    webhookUrl: 'https://hooks.slack.com/services/XXX/YYY/ZZZ',
                    username: 'ConfigFlow Bot',
                    channel: '#config-alerts',
                },
            },
        ],
    };
    const targetPath = path.join(outputDir, DEFAULT_CONFIG_NAME);
    fs.writeFileSync(targetPath, yaml.dump(sample, { lineWidth: -1, quotingType: '"', forceQuotes: true }));
    return targetPath;
}
function generateSampleSchema(outputDir) {
    const schema = {
        $schema: 'https://github.com/config-flow/schema/v1',
        version: '1.0.0',
        fields: [
            {
                key: 'app',
                type: 'object',
                required: true,
                description: 'Application configuration',
                properties: [
                    { key: 'port', type: 'integer', required: true, min: 1, max: 65535, description: 'Server port' },
                    { key: 'debug', type: 'boolean', required: false, default: false, description: 'Debug mode' },
                    { key: 'name', type: 'string', required: true, min: 1, max: 100, description: 'App name' },
                    { key: 'environment', type: 'string', required: true, enum: ['development', 'staging', 'production'] },
                ],
            },
            {
                key: 'database',
                type: 'object',
                required: true,
                description: 'Database configuration',
                properties: [
                    { key: 'host', type: 'string', required: true, pattern: '^[a-zA-Z0-9._-]+$' },
                    { key: 'port', type: 'integer', required: true, min: 1, max: 65535, default: 5432 },
                    { key: 'username', type: 'string', required: true, min: 1 },
                    { key: 'password', type: 'string', required: true, min: 8 },
                    { key: 'name', type: 'string', required: true, min: 1 },
                ],
            },
            {
                key: 'rateLimit',
                type: 'object',
                required: false,
                description: 'Rate limiting configuration',
                properties: [
                    { key: 'maxRequests', type: 'integer', required: false, min: 1, max: 1000000, default: 100 },
                    { key: 'windowMs', type: 'integer', required: false, min: 1000, default: 60000 },
                ],
            },
        ],
    };
    const targetPath = path.join(outputDir, 'config-schema.json');
    fs.writeFileSync(targetPath, JSON.stringify(schema, null, 2) + '\n');
    return targetPath;
}
function validateCliConfig(config) {
    const errors = [];
    if (!config.projectRoot)
        errors.push('projectRoot is required');
    if (!config.storagePath)
        errors.push('storagePath is required');
    if (!config.gitRepoPath)
        errors.push('gitRepoPath is required');
    const envNames = new Set();
    for (const env of config.environments) {
        if (!env.name) {
            errors.push('Environment name is required');
            continue;
        }
        if (envNames.has(env.name)) {
            errors.push(`Duplicate environment name: ${env.name}`);
        }
        envNames.add(env.name);
        if (!env.sources || env.sources.length === 0) {
            errors.push(`Environment ${env.name} has no sources configured`);
            continue;
        }
        for (const source of env.sources) {
            if (!['vault', 'ssm', 'configmap', 'env', 'default'].includes(source.type)) {
                errors.push(`Environment ${env.name}: unknown source type ${source.type}`);
            }
        }
    }
    return errors;
}
function configToAppConfig(config) {
    return {
        projectRoot: config.projectRoot,
        storagePath: config.storagePath,
        gitRepoPath: config.gitRepoPath,
        environments: config.environments,
        notifications: config.notifications,
        schemaPath: config.schemaPath,
    };
}
//# sourceMappingURL=AppConfigLoader.js.map