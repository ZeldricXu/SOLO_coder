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
exports.TemplateRenderer = void 0;
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
const Handlebars = __importStar(require("handlebars"));
class TemplateRenderer {
    handlebars;
    registeredPartials = new Map();
    registeredHelpers = new Map();
    constructor() {
        this.handlebars = Handlebars.create();
        this.registerBuiltinHelpers();
    }
    registerBuiltinHelpers() {
        this.registerHelper('toUpperCase', (str) => String(str ?? '').toUpperCase());
        this.registerHelper('toLowerCase', (str) => String(str ?? '').toLowerCase());
        this.registerHelper('capitalize', (str) => {
            const s = String(str ?? '');
            return s ? s[0].toUpperCase() + s.slice(1) : '';
        });
        this.registerHelper('trim', (str) => String(str ?? '').trim());
        this.registerHelper('length', (obj) => {
            if (Array.isArray(obj))
                return obj.length;
            if (typeof obj === 'string')
                return obj.length;
            if (typeof obj === 'object' && obj !== null)
                return Object.keys(obj).length;
            return 0;
        });
        this.registerHelper('join', (arr, separator = ',') => {
            return Array.isArray(arr) ? arr.join(String(separator)) : '';
        });
        this.registerHelper('json', (obj, pretty = false) => {
            return pretty ? JSON.stringify(obj, null, 2) : JSON.stringify(obj);
        });
        this.registerHelper('default', (value, defaultValue) => {
            return value === undefined || value === null || value === '' ? defaultValue : value;
        });
        this.registerHelper('eq', (a, b) => a === b);
        this.registerHelper('ne', (a, b) => a !== b);
        this.registerHelper('gt', (a, b) => Number(a) > Number(b));
        this.registerHelper('gte', (a, b) => Number(a) >= Number(b));
        this.registerHelper('lt', (a, b) => Number(a) < Number(b));
        this.registerHelper('lte', (a, b) => Number(a) <= Number(b));
        this.registerHelper('and', (...args) => args.slice(0, -1).every(Boolean));
        this.registerHelper('or', (...args) => args.slice(0, -1).some(Boolean));
        this.registerHelper('not', (a) => !a);
        this.registerHelper('date', (format = 'YYYY-MM-DD HH:mm:ss') => {
            const now = new Date();
            return String(format)
                .replace('YYYY', String(now.getFullYear()))
                .replace('MM', String(now.getMonth() + 1).padStart(2, '0'))
                .replace('DD', String(now.getDate()).padStart(2, '0'))
                .replace('HH', String(now.getHours()).padStart(2, '0'))
                .replace('mm', String(now.getMinutes()).padStart(2, '0'))
                .replace('ss', String(now.getSeconds()).padStart(2, '0'));
        });
        this.registerHelper('sanitizeYaml', (str) => {
            if (str === undefined || str === null)
                return '';
            const s = String(str);
            if (/[:#&*!|>'"%@`[\]{}\n]/.test(s)) {
                return `"${s.replace(/"/g, '\\"')}"`;
            }
            return s;
        });
        this.registerHelper('indent', (str, spaces = 2) => {
            if (!str)
                return '';
            const indent = ' '.repeat(Number(spaces) || 2);
            return String(str).split('\n').map((l) => indent + l).join('\n');
        });
    }
    registerHelper(name, fn) {
        this.handlebars.registerHelper(name, fn);
        this.registeredHelpers.set(name, fn);
    }
    registerPartial(name, template) {
        this.handlebars.registerPartial(name, template);
        this.registeredPartials.set(name, template);
    }
    registerPartialFromFile(name, filePath) {
        const content = fs.readFileSync(path.resolve(filePath), 'utf-8');
        this.registerPartial(name, content);
    }
    loadPartialsFromDirectory(dirPath) {
        const registered = [];
        const absPath = path.resolve(dirPath);
        if (!fs.existsSync(absPath) || !fs.statSync(absPath).isDirectory()) {
            return registered;
        }
        const files = fs.readdirSync(absPath);
        for (const file of files) {
            const filePath = path.join(absPath, file);
            const stat = fs.statSync(filePath);
            if (stat.isDirectory()) {
                const sub = this.loadPartialsFromDirectory(filePath);
                registered.push(...sub);
            }
            else if (stat.isFile()) {
                const name = path.basename(file, path.extname(file));
                try {
                    this.registerPartialFromFile(name, filePath);
                    registered.push(name);
                }
                catch (error) {
                    console.warn(`Failed to register partial ${file}:`, error);
                }
            }
        }
        return registered;
    }
    render(config) {
        const start = Date.now();
        const templatePath = path.resolve(config.templatePath);
        try {
            if (!fs.existsSync(templatePath)) {
                throw new Error(`Template not found: ${templatePath}`);
            }
            const templateContent = fs.readFileSync(templatePath, 'utf-8');
            const compiled = this.handlebars.compile(templateContent, {
                strict: false,
                noEscape: true,
            });
            const fullContext = {
                ...config.context,
                _meta: {
                    environment: config.environment,
                    renderedAt: new Date().toISOString(),
                    template: path.basename(templatePath),
                },
            };
            const content = compiled(fullContext);
            const outputPath = path.resolve(config.outputPath);
            const outputDir = path.dirname(outputPath);
            if (!fs.existsSync(outputDir)) {
                fs.mkdirSync(outputDir, { recursive: true });
            }
            fs.writeFileSync(outputPath, content);
            return {
                outputPath,
                content,
                renderedAt: start,
                environment: config.environment,
                templatePath,
                success: true,
            };
        }
        catch (error) {
            return {
                outputPath: config.outputPath,
                content: '',
                renderedAt: start,
                environment: config.environment,
                templatePath,
                success: false,
                error: error.message,
            };
        }
    }
    renderString(template, context, environment = 'default') {
        try {
            const compiled = this.handlebars.compile(template, {
                strict: false,
                noEscape: true,
            });
            const fullContext = {
                ...context,
                _meta: {
                    environment,
                    renderedAt: new Date().toISOString(),
                },
            };
            return {
                content: compiled(fullContext),
                success: true,
            };
        }
        catch (error) {
            return {
                content: '',
                success: false,
                error: error.message,
            };
        }
    }
    renderBatch(config) {
        const results = [];
        for (const template of config.templates) {
            const renderConfig = {
                ...template,
                outputPath: config.outputDir
                    ? path.join(config.outputDir, template.environment, path.basename(template.outputPath))
                    : template.outputPath,
            };
            results.push(this.render(renderConfig));
        }
        return results;
    }
    renderForEnvironments(templatePath, environments) {
        return environments.map((env) => this.render({
            templatePath,
            outputPath: env.outputPath,
            context: env.context,
            environment: env.name,
        }));
    }
    getRegisteredHelpers() {
        return Array.from(this.registeredHelpers.keys());
    }
    getRegisteredPartials() {
        return Array.from(this.registeredPartials.keys());
    }
}
exports.TemplateRenderer = TemplateRenderer;
//# sourceMappingURL=TemplateRenderer.js.map