"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.templateRegistry = exports.TemplateRegistry = void 0;
const path_1 = __importDefault(require("path"));
const fs_extra_1 = __importDefault(require("fs-extra"));
const execa_1 = require("execa");
const axios_1 = __importDefault(require("axios"));
const ora_1 = __importDefault(require("ora"));
const state_js_1 = require("../state.js");
const TEMPLATE_NAME_PREFIX = 'create-';
const TEMPLATE_NAME_SUFFIX = '-template';
const NPM_REGISTRY_URL = 'https://registry.npmjs.org';
const SEARCH_SIZE = 50;
class TemplateRegistry {
    cacheDir;
    cacheManifestPath;
    manifest = {};
    constructor() {
        this.cacheDir = path_1.default.join(state_js_1.globalState.getCacheDir(), 'templates');
        this.cacheManifestPath = path_1.default.join(this.cacheDir, 'manifest.json');
    }
    async init() {
        await fs_extra_1.default.ensureDir(this.cacheDir);
        await this.loadManifest();
    }
    async loadManifest() {
        try {
            if (await fs_extra_1.default.pathExists(this.cacheManifestPath)) {
                const content = await fs_extra_1.default.readFile(this.cacheManifestPath, 'utf-8');
                this.manifest = JSON.parse(content);
            }
        }
        catch {
            this.manifest = {};
        }
    }
    async saveManifest() {
        await fs_extra_1.default.writeJson(this.cacheManifestPath, this.manifest, { spaces: 2 });
    }
    async searchTemplates(keyword = '') {
        const spinner = (0, ora_1.default)('搜索可用模板...').start();
        try {
            const searchQuery = `${TEMPLATE_NAME_PREFIX}*${TEMPLATE_NAME_SUFFIX}${keyword ? ` ${keyword}` : ''}`;
            const url = `${NPM_REGISTRY_URL}/-/v1/search?text=${encodeURIComponent(searchQuery)}&size=${SEARCH_SIZE}`;
            const response = await axios_1.default.get(url, { timeout: 10000 });
            const packages = response.data.objects.map(obj => {
                const info = {
                    name: obj.package.name,
                    version: obj.package.version,
                    description: obj.package.description,
                };
                if (obj.package.author?.name !== undefined) {
                    info.author = obj.package.author.name;
                }
                if (obj.package.keywords !== undefined) {
                    info.keywords = obj.package.keywords;
                }
                if (obj.package.date !== undefined) {
                    info.date = obj.package.date;
                }
                if (obj.package.links) {
                    info.links = {};
                    if (obj.package.links.npm !== undefined) {
                        info.links.npm = obj.package.links.npm;
                    }
                    if (obj.package.links.homepage !== undefined) {
                        info.links.homepage = obj.package.links.homepage;
                    }
                    if (obj.package.links.repository !== undefined) {
                        info.links.repository = obj.package.links.repository;
                    }
                }
                return info;
            });
            const filtered = packages.filter(pkg => pkg.name.startsWith(TEMPLATE_NAME_PREFIX) &&
                pkg.name.endsWith(TEMPLATE_NAME_SUFFIX));
            spinner.succeed(`找到 ${filtered.length} 个可用模板`);
            return filtered;
        }
        catch (error) {
            spinner.warn('无法从 npm registry 搜索模板，将仅使用内置模板');
            return [];
        }
    }
    async installTemplate(packageName, version) {
        const packageSpec = version ? `${packageName}@${version}` : packageName;
        const spinner = (0, ora_1.default)(`安装模板: ${packageSpec}`).start();
        try {
            const existingEntry = this.manifest[packageName];
            if (existingEntry && (!version || existingEntry.version === version)) {
                spinner.succeed(`模板 ${packageName} 已缓存 (v${existingEntry.version})`);
                return existingEntry;
            }
            const installDir = path_1.default.join(this.cacheDir, packageName.replace(/\//g, '_'));
            await fs_extra_1.default.ensureDir(installDir);
            if (!await fs_extra_1.default.pathExists(path_1.default.join(installDir, 'package.json'))) {
                await fs_extra_1.default.writeJson(path_1.default.join(installDir, 'package.json'), {
                    name: 'template-cache',
                    version: '1.0.0',
                    private: true,
                }, { spaces: 2 });
            }
            await (0, execa_1.execa)('npm', ['install', packageSpec, '--no-save', '--prefix', installDir], {
                cwd: installDir,
                timeout: 120000,
            });
            const installedPkg = await fs_extra_1.default.readJson(path_1.default.join(installDir, 'node_modules', packageName, 'package.json'));
            const templatePath = path_1.default.join(installDir, 'node_modules', packageName);
            const entry = {
                name: packageName,
                version: installedPkg.version,
                installedAt: Date.now(),
                path: templatePath,
            };
            this.manifest[packageName] = entry;
            await this.saveManifest();
            spinner.succeed(`模板 ${packageName} 安装完成 (v${installedPkg.version})`);
            return entry;
        }
        catch (error) {
            spinner.fail(`模板安装失败: ${packageSpec}`);
            throw error;
        }
    }
    getCachedTemplate(packageName) {
        return this.manifest[packageName] ?? null;
    }
    async getTemplatePath(packageName, version) {
        const entry = this.manifest[packageName];
        if (!entry)
            return null;
        if (version && entry.version !== version) {
            return null;
        }
        if (await fs_extra_1.default.pathExists(entry.path)) {
            return entry.path;
        }
        delete this.manifest[packageName];
        await this.saveManifest();
        return null;
    }
    async clearCache() {
        await fs_extra_1.default.emptyDir(this.cacheDir);
        this.manifest = {};
        await this.saveManifest();
    }
    async listCachedTemplates() {
        return Object.values(this.manifest);
    }
    getCacheDir() {
        return this.cacheDir;
    }
}
exports.TemplateRegistry = TemplateRegistry;
exports.templateRegistry = new TemplateRegistry();
//# sourceMappingURL=registry.js.map