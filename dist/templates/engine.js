"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.TemplateEngine = void 0;
const fs_extra_1 = __importDefault(require("fs-extra"));
const path_1 = __importDefault(require("path"));
const handlebars_1 = __importDefault(require("handlebars"));
const glob_1 = require("glob");
const execa_1 = require("execa");
const axios_1 = __importDefault(require("axios"));
const ora_1 = __importDefault(require("ora"));
const builtin_js_1 = require("./builtin.js");
const state_js_1 = require("../state.js");
class TemplateEngine {
    config;
    constructor(config) {
        this.config = config;
    }
    async render() {
        const templateConfig = await this.loadTemplate();
        await this.renderTemplateFiles(templateConfig);
        return templateConfig;
    }
    async loadTemplate() {
        if (this.config.template) {
            return this.loadCustomTemplate(this.config.template);
        }
        return (0, builtin_js_1.getBuiltinTemplate)(this.config.framework);
    }
    async loadCustomTemplate(templatePath) {
        const spinner = (0, ora_1.default)(`加载自定义模板: ${templatePath}`).start();
        try {
            let localPath;
            if (templatePath.startsWith('http') || templatePath.startsWith('git@') || templatePath.endsWith('.git')) {
                localPath = await this.cloneRemoteTemplate(templatePath);
            }
            else if (await fs_extra_1.default.pathExists(templatePath)) {
                localPath = path_1.default.resolve(templatePath);
            }
            else {
                throw new Error(`模板路径不存在: ${templatePath}`);
            }
            const configPath = path_1.default.join(localPath, 'template.json');
            if (!await fs_extra_1.default.pathExists(configPath)) {
                throw new Error('模板目录中缺少 template.json 配置文件');
            }
            const templateConfig = await fs_extra_1.default.readJson(configPath);
            spinner.succeed('自定义模板加载完成');
            return {
                ...templateConfig,
                files: templateConfig.files.map(f => ({
                    ...f,
                    source: path_1.default.join(localPath, f.source),
                })),
            };
        }
        catch (error) {
            spinner.fail(`模板加载失败: ${error.message}`);
            throw error;
        }
    }
    async cloneRemoteTemplate(url) {
        const tempDir = path_1.default.join(state_js_1.globalState.getTemplateCacheDir(), `temp-${Date.now()}`);
        await fs_extra_1.default.ensureDir(tempDir);
        try {
            await (0, execa_1.execa)('git', ['clone', '--depth', '1', url, tempDir], {
                stdio: 'ignore',
            });
        }
        catch {
            try {
                const response = await axios_1.default.get(url, { responseType: 'arraybuffer' });
                await fs_extra_1.default.writeFile(path_1.default.join(tempDir, 'template.zip'), response.data);
                await (0, execa_1.execa)('unzip', ['-o', path_1.default.join(tempDir, 'template.zip'), '-d', tempDir], {
                    stdio: 'ignore',
                });
            }
            catch (zipError) {
                throw new Error(`无法获取远程模板: ${zipError.message}`);
            }
        }
        return tempDir;
    }
    async renderTemplateFiles(templateConfig) {
        const spinner = (0, ora_1.default)('渲染模板文件...').start();
        try {
            const templateData = this.getTemplateData();
            for (const file of templateConfig.files) {
                await this.renderSingleFile(file, templateData);
            }
            spinner.succeed('模板文件渲染完成');
        }
        catch (error) {
            spinner.fail(`模板渲染失败: ${error.message}`);
            throw error;
        }
    }
    async renderSingleFile(file, data) {
        const targetPath = path_1.default.join(this.config.targetDir, file.target);
        await fs_extra_1.default.ensureDir(path_1.default.dirname(targetPath));
        if (!file.isTemplate) {
            await fs_extra_1.default.copy(file.source, targetPath);
        }
        else {
            let content;
            if (file.source.startsWith('builtin:')) {
                const builtinName = file.source.replace('builtin:', '');
                content = await this.getBuiltinTemplateContent(builtinName);
            }
            else {
                content = await fs_extra_1.default.readFile(file.source, 'utf-8');
            }
            const template = handlebars_1.default.compile(content);
            const rendered = template(data);
            await fs_extra_1.default.writeFile(targetPath, rendered, 'utf-8');
        }
        if (file.perm) {
            await fs_extra_1.default.chmod(targetPath, file.perm);
        }
    }
    async getBuiltinTemplateContent(name) {
        const distPath = path_1.default.join(__dirname, 'content', name);
        const srcPath = path_1.default.join(process.cwd(), 'src', 'templates', 'content', name);
        if (await fs_extra_1.default.pathExists(distPath)) {
            return fs_extra_1.default.readFile(distPath, 'utf-8');
        }
        if (await fs_extra_1.default.pathExists(srcPath)) {
            return fs_extra_1.default.readFile(srcPath, 'utf-8');
        }
        return this.getFallbackTemplate(name);
    }
    getFallbackTemplate(name) {
        const templates = {
            'index.ts': `import { cli } from './cli.js';\n\ncli(process.argv);\n`,
        };
        return templates[name] ?? '';
    }
    getTemplateData() {
        return {
            projectName: this.config.projectName,
            projectNameKebab: this.config.projectName,
            projectNameCamel: this.toCamelCase(this.config.projectName),
            projectNamePascal: this.toPascalCase(this.config.projectName),
            projectNameUpper: this.config.projectName.toUpperCase().replace(/-/g, '_'),
            description: this.config.description,
            author: this.config.author,
            version: this.config.projectVersion,
            year: new Date().getFullYear(),
            framework: this.config.framework,
            useDocker: this.config.useDocker,
            useCI: this.config.useCI,
            hasPostgres: this.config.useDocker,
            hasRedis: this.config.useDocker,
        };
    }
    toCamelCase(str) {
        return str.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
    }
    toPascalCase(str) {
        const camel = this.toCamelCase(str);
        return camel.charAt(0).toUpperCase() + camel.slice(1);
    }
    async renderGlob(pattern, data) {
        const files = await (0, glob_1.glob)(pattern, { cwd: this.config.targetDir, nodir: true });
        for (const file of files) {
            const filePath = path_1.default.join(this.config.targetDir, file);
            const content = await fs_extra_1.default.readFile(filePath, 'utf-8');
            const template = handlebars_1.default.compile(content);
            const rendered = template(data);
            await fs_extra_1.default.writeFile(filePath, rendered, 'utf-8');
        }
    }
}
exports.TemplateEngine = TemplateEngine;
//# sourceMappingURL=engine.js.map