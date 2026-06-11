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
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const core_1 = require("@oclif/core");
const list_1 = require("./env/list");
const TemplateRenderer_1 = require("../renderer/TemplateRenderer");
const renderer_1 = require("../renderer");
const fs = __importStar(require("fs"));
const path = __importStar(require("path"));
const chalk_1 = __importDefault(require("chalk"));
class RenderCommand extends core_1.Command {
    static description = 'Render configuration templates using environment context';
    static aliases = ['render:template', 'tpl'];
    static args = {
        template: core_1.Args.string({ description: 'Template path or directory' }),
        environment: core_1.Args.string({ description: 'Environment to render (all if omitted)' }),
    };
    static flags = {
        config: core_1.Flags.string({ char: 'c', description: 'Path to config file' }),
        template: core_1.Flags.string({ char: 't', description: 'Template path (alternative to arg)' }),
        output: core_1.Flags.string({ char: 'o', description: 'Output path or directory', required: false }),
        templatesDir: core_1.Flags.string({ description: 'Directory containing additional partials' }),
        data: core_1.Flags.string({ char: 'd', description: 'Additional JSON context data', multiple: true }),
        stdin: core_1.Flags.boolean({ description: 'Read template from stdin' }),
        listHelpers: core_1.Flags.boolean({ description: 'List available helpers and exit' }),
        listPartials: core_1.Flags.boolean({ description: 'List registered partials and exit' }),
        dryRun: core_1.Flags.boolean({ char: 'n', description: 'Render to stdout only' }),
        json: core_1.Flags.boolean({ description: 'Output results as JSON' }),
        verbose: core_1.Flags.boolean({ char: 'v', description: 'Verbose output' }),
        templateEngine: core_1.Flags.string({ char: 'e', description: 'Template engine: handlebars|go-template|jinja2', options: renderer_1.SUPPORTED_ENGINES, default: 'handlebars' }),
    };
    async run() {
        const { args, flags } = await this.parse(RenderCommand);
        const ctx = await (0, list_1.loadContext)(flags.config);
        const engine = (0, renderer_1.createTemplateEngine)(flags.templateEngine);
        const renderer = new TemplateRenderer_1.TemplateRenderer();
        if (flags.templatesDir) {
            const absDir = path.resolve(flags.templatesDir);
            if (fs.existsSync(absDir)) {
                const loaded = renderer.loadPartialsFromDirectory(absDir);
                if (flags.verbose && !flags.json) {
                    this.log(`Loaded ${loaded.length} partials from ${absDir}`);
                }
            }
        }
        if (flags.listHelpers) {
            const helpers = renderer.getRegisteredHelpers();
            if (flags.json) {
                this.log(JSON.stringify({ engine: engine.name, helpers }, null, 2));
            }
            else {
                this.log(`Available helpers (${engine.name} engine):`);
                for (const h of helpers.sort())
                    this.log(`  - ${h}`);
            }
            return;
        }
        if (flags.listPartials) {
            const partials = renderer.getRegisteredPartials();
            if (flags.json) {
                this.log(JSON.stringify(partials, null, 2));
            }
            else {
                if (partials.length === 0)
                    this.log('No partials registered.');
                else {
                    this.log('Registered partials:');
                    for (const p of partials.sort())
                        this.log(`  - ${p}`);
                }
            }
            return;
        }
        if (flags.stdin) {
            const templateStr = await this.readStdin();
            const environments = args.environment
                ? [args.environment]
                : ctx.configManager.listEnvironments();
            const results = [];
            for (const envName of environments) {
                const env = ctx.configManager.getEnvironment(envName);
                if (!env)
                    continue;
                const context = await env.loadAll();
                const mergedContext = this.applyDataOverrides(context, flags.data || []);
                const fullContext = { ...mergedContext, _meta: { environment: envName, renderedAt: new Date().toISOString() } };
                const result = engine.render(templateStr, fullContext);
                results.push({ environment: envName, ...result });
            }
            if (flags.json) {
                this.log(JSON.stringify(results, null, 2));
            }
            else {
                for (const r of results) {
                    if (environments.length > 1)
                        this.log(`\n=== ${r.environment} ===`);
                    if (r.success)
                        this.log(r.content);
                    else
                        this.log(chalk_1.default.red(`Error: ${r.error}`));
                }
            }
            return;
        }
        const templatePath = flags.template || args.template;
        if (!templatePath) {
            this.error('Template path is required (--template or positional arg)');
        }
        const absTemplate = path.resolve(templatePath);
        const environments = args.environment
            ? [args.environment]
            : ctx.configManager.listEnvironments();
        const allResults = [];
        for (const envName of environments) {
            const env = ctx.configManager.getEnvironment(envName);
            if (!env) {
                this.warn(`Environment not found: ${envName}, skipping`);
                continue;
            }
            const context = await env.loadAll();
            const mergedContext = this.applyDataOverrides(context, flags.data || []);
            const stat = fs.existsSync(absTemplate) ? fs.statSync(absTemplate) : null;
            if (stat && stat.isDirectory()) {
                const templateFiles = fs.readdirSync(absTemplate)
                    .filter((f) => f.endsWith('.hbs') || f.endsWith('.handlebars') || f.endsWith('.tpl'));
                for (const tplFile of templateFiles) {
                    const tplFullPath = path.join(absTemplate, tplFile);
                    const outputName = tplFile.replace(/\.(hbs|handlebars|tpl)$/, '');
                    let outputFile;
                    if (flags.output) {
                        outputFile = path.resolve(flags.output, envName, outputName);
                    }
                    else {
                        outputFile = path.resolve(absTemplate, '..', 'output', envName, outputName);
                    }
                    if (flags.dryRun) {
                        const content = fs.readFileSync(tplFullPath, 'utf-8');
                        const result = engine.render(content, mergedContext);
                        allResults.push({
                            outputPath: outputFile,
                            content: result.content,
                            renderedAt: Date.now(),
                            environment: envName,
                            templatePath: tplFullPath,
                            success: result.success,
                            error: result.error,
                        });
                    }
                    else {
                        const result = renderer.render({
                            templatePath: tplFullPath,
                            outputPath: outputFile,
                            context: mergedContext,
                            environment: envName,
                        });
                        allResults.push(result);
                    }
                }
            }
            else {
                let outputFile = flags.output;
                if (!outputFile) {
                    const baseName = path.basename(absTemplate).replace(/\.(hbs|handlebars|tpl)$/, '');
                    outputFile = path.resolve(absTemplate, '..', 'output', envName, baseName);
                }
                else if (environments.length > 1 && flags.output && fs.existsSync(flags.output) && fs.statSync(flags.output).isDirectory()) {
                    const baseName = path.basename(absTemplate).replace(/\.(hbs|handlebars|tpl)$/, '');
                    outputFile = path.resolve(flags.output, envName, baseName);
                }
                if (flags.dryRun) {
                    const content = fs.existsSync(absTemplate) ? fs.readFileSync(absTemplate, 'utf-8') : absTemplate;
                    const result = engine.render(content, mergedContext);
                    allResults.push({
                        outputPath: outputFile,
                        content: result.content,
                        renderedAt: Date.now(),
                        environment: envName,
                        templatePath: absTemplate,
                        success: result.success,
                        error: result.error,
                    });
                }
                else {
                    const result = renderer.render({
                        templatePath: absTemplate,
                        outputPath: outputFile,
                        context: mergedContext,
                        environment: envName,
                    });
                    allResults.push(result);
                }
            }
        }
        if (flags.json) {
            this.log(JSON.stringify(allResults, null, 2));
            return;
        }
        const success = allResults.filter((r) => r.success).length;
        const failed = allResults.filter((r) => !r.success).length;
        this.log(`\nRender complete: ${chalk_1.default.green(success)} success, ${chalk_1.default.red(failed)} failed`);
        for (const r of allResults) {
            const icon = r.success ? chalk_1.default.green('✓') : chalk_1.default.red('✗');
            if (flags.dryRun || flags.verbose) {
                this.log(`\n${icon} ${r.templatePath} → ${r.outputPath} [${r.environment}]`);
                if (!r.success)
                    this.log(`  Error: ${r.error}`);
                else if (flags.dryRun) {
                    this.log('─'.repeat(50));
                    this.log(r.content);
                    this.log('─'.repeat(50));
                }
            }
            else {
                this.log(`  ${icon} ${path.basename(r.templatePath)} → ${r.outputPath} [${r.environment}]`);
                if (!r.success)
                    this.log(`    Error: ${r.error}`);
            }
        }
        if (failed > 0)
            this.exit(1);
    }
    async readStdin() {
        return new Promise((resolve, reject) => {
            let data = '';
            process.stdin.setEncoding('utf8');
            process.stdin.on('data', (chunk) => (data += chunk));
            process.stdin.on('end', () => resolve(data));
            process.stdin.on('error', reject);
        });
    }
    applyDataOverrides(context, dataFlags) {
        const merged = { ...context };
        for (const dataFlag of dataFlags) {
            try {
                if (fs.existsSync(path.resolve(dataFlag))) {
                    const fileContent = fs.readFileSync(path.resolve(dataFlag), 'utf-8');
                    const parsed = JSON.parse(fileContent);
                    Object.assign(merged, parsed);
                }
                else if (dataFlag.includes('=')) {
                    const eq = dataFlag.indexOf('=');
                    const key = dataFlag.slice(0, eq);
                    const value = dataFlag.slice(eq + 1);
                    this.setByPath(merged, key, this.parseValue(value));
                }
                else {
                    const parsed = JSON.parse(dataFlag);
                    Object.assign(merged, parsed);
                }
            }
            catch (error) {
                this.warn(`Failed to apply data override "${dataFlag}": ${error.message}`);
            }
        }
        return merged;
    }
    setByPath(obj, pathStr, value) {
        const parts = pathStr.split('.');
        let current = obj;
        for (let i = 0; i < parts.length - 1; i++) {
            if (!current[parts[i]] || typeof current[parts[i]] !== 'object' || Array.isArray(current[parts[i]])) {
                current[parts[i]] = {};
            }
            current = current[parts[i]];
        }
        current[parts[parts.length - 1]] = value;
    }
    parseValue(v) {
        if (v === 'true')
            return true;
        if (v === 'false')
            return false;
        if (v === 'null')
            return null;
        if (v === '')
            return '';
        const num = Number(v);
        if (!isNaN(num) && v.trim() !== '')
            return num;
        try {
            return JSON.parse(v);
        }
        catch {
            return v;
        }
    }
}
exports.default = RenderCommand;
//# sourceMappingURL=render.js.map