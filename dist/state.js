"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.globalState = exports.GlobalState = void 0;
const fs_extra_1 = __importDefault(require("fs-extra"));
const path_1 = __importDefault(require("path"));
const os_1 = __importDefault(require("os"));
const semver_1 = __importDefault(require("semver"));
const axios_1 = __importDefault(require("axios"));
const CONFIG_DIR = path_1.default.join(os_1.default.homedir(), '.create-solo-project');
const PREFERENCES_FILE = path_1.default.join(CONFIG_DIR, 'preferences.json');
const CACHE_DIR = path_1.default.join(CONFIG_DIR, 'cache');
const TEMPLATE_CACHE_DIR = path_1.default.join(CACHE_DIR, 'templates');
const TEMPLATE_INDEX_FILE = path_1.default.join(CACHE_DIR, 'template-index.json');
const TEMPLATE_REGISTRY_URL = 'https://raw.githubusercontent.com/solocoder-team/templates/main/index.json';
class GlobalState {
    preferences;
    initialized = false;
    constructor() {
        this.preferences = {};
    }
    async init() {
        if (this.initialized)
            return;
        await fs_extra_1.default.ensureDir(CONFIG_DIR);
        await fs_extra_1.default.ensureDir(CACHE_DIR);
        await fs_extra_1.default.ensureDir(TEMPLATE_CACHE_DIR);
        await this.loadPreferences();
        this.initialized = true;
    }
    async loadPreferences() {
        try {
            if (await fs_extra_1.default.pathExists(PREFERENCES_FILE)) {
                const data = await fs_extra_1.default.readJson(PREFERENCES_FILE);
                this.preferences = data;
            }
        }
        catch {
            this.preferences = {};
        }
    }
    async savePreferences() {
        await fs_extra_1.default.writeJson(PREFERENCES_FILE, this.preferences, { spaces: 2 });
    }
    getPreferences() {
        return { ...this.preferences };
    }
    async setFramework(framework) {
        this.preferences.lastFramework = framework;
        await this.savePreferences();
    }
    async setPackageManager(pm) {
        this.preferences.lastPackageManager = pm;
        await this.savePreferences();
    }
    async setCiProvider(provider) {
        this.preferences.lastCiProvider = provider;
        await this.savePreferences();
    }
    async setAuthor(author) {
        this.preferences.lastAuthor = author;
        await this.savePreferences();
    }
    async setUseDocker(useDocker) {
        this.preferences.lastUseDocker = useDocker;
        await this.savePreferences();
    }
    async setUsePreCommitHook(use) {
        this.preferences.lastUsePreCommitHook = use;
        await this.savePreferences();
    }
    async setTemplateVersion(version) {
        this.preferences.templateVersion = version;
        await this.savePreferences();
    }
    async updateLastCheckTime() {
        this.preferences.lastUpdateCheck = Date.now();
        await this.savePreferences();
    }
    async checkForUpdates(force = false) {
        const now = Date.now();
        const oneDay = 24 * 60 * 60 * 1000;
        if (!force && this.preferences.lastUpdateCheck && now - this.preferences.lastUpdateCheck < oneDay) {
            return null;
        }
        try {
            const response = await axios_1.default.get(TEMPLATE_REGISTRY_URL, { timeout: 5000 });
            const templates = response.data;
            if (templates.length === 0) {
                return null;
            }
            const latest = templates.reduce((a, b) => semver_1.default.gt(a.version, b.version) ? a : b);
            if (!this.preferences.templateVersion || semver_1.default.gt(latest.version, this.preferences.templateVersion)) {
                await this.updateLastCheckTime();
                return latest;
            }
            await this.updateLastCheckTime();
            return null;
        }
        catch {
            return null;
        }
    }
    async updateTemplates() {
        try {
            const response = await axios_1.default.get(TEMPLATE_REGISTRY_URL, { timeout: 10000 });
            const templates = response.data;
            const latest = templates.reduce((a, b) => semver_1.default.gt(a.version, b.version) ? a : b);
            await fs_extra_1.default.emptyDir(TEMPLATE_CACHE_DIR);
            await fs_extra_1.default.writeJson(TEMPLATE_INDEX_FILE, templates, { spaces: 2 });
            await this.setTemplateVersion(latest.version);
            return true;
        }
        catch {
            return false;
        }
    }
    getTemplateCacheDir() {
        return TEMPLATE_CACHE_DIR;
    }
    getConfigDir() {
        return CONFIG_DIR;
    }
    async clearCache() {
        await fs_extra_1.default.emptyDir(CACHE_DIR);
    }
}
exports.GlobalState = GlobalState;
exports.globalState = new GlobalState();
//# sourceMappingURL=state.js.map