import fs from 'fs-extra';
import path from 'path';
import os from 'os';
import semver from 'semver';
import axios from 'axios';
import type { UserPreferences, TemplateInfo, FrameworkType, PackageManagerType, CiProviderType } from './types.js';

const CONFIG_DIR = path.join(os.homedir(), '.create-solo-project');
const PREFERENCES_FILE = path.join(CONFIG_DIR, 'preferences.json');
const CACHE_DIR = path.join(CONFIG_DIR, 'cache');
const TEMPLATE_CACHE_DIR = path.join(CACHE_DIR, 'templates');
const TEMPLATE_INDEX_FILE = path.join(CACHE_DIR, 'template-index.json');

const TEMPLATE_REGISTRY_URL = 'https://raw.githubusercontent.com/solocoder-team/templates/main/index.json';

export class GlobalState {
  private preferences: UserPreferences;
  private initialized = false;

  constructor() {
    this.preferences = {};
  }

  async init(): Promise<void> {
    if (this.initialized) return;
    await fs.ensureDir(CONFIG_DIR);
    await fs.ensureDir(CACHE_DIR);
    await fs.ensureDir(TEMPLATE_CACHE_DIR);
    await this.loadPreferences();
    this.initialized = true;
  }

  private async loadPreferences(): Promise<void> {
    try {
      if (await fs.pathExists(PREFERENCES_FILE)) {
        const data = await fs.readJson(PREFERENCES_FILE);
        this.preferences = data;
      }
    } catch {
      this.preferences = {};
    }
  }

  private async savePreferences(): Promise<void> {
    await fs.writeJson(PREFERENCES_FILE, this.preferences, { spaces: 2 });
  }

  getPreferences(): UserPreferences {
    return { ...this.preferences };
  }

  async setFramework(framework: FrameworkType): Promise<void> {
    this.preferences.lastFramework = framework;
    await this.savePreferences();
  }

  async setPackageManager(pm: PackageManagerType): Promise<void> {
    this.preferences.lastPackageManager = pm;
    await this.savePreferences();
  }

  async setCiProvider(provider: CiProviderType): Promise<void> {
    this.preferences.lastCiProvider = provider;
    await this.savePreferences();
  }

  async setAuthor(author: string): Promise<void> {
    this.preferences.lastAuthor = author;
    await this.savePreferences();
  }

  async setUseDocker(useDocker: boolean): Promise<void> {
    this.preferences.lastUseDocker = useDocker;
    await this.savePreferences();
  }

  async setUsePreCommitHook(use: boolean): Promise<void> {
    this.preferences.lastUsePreCommitHook = use;
    await this.savePreferences();
  }

  async setTemplateVersion(version: string): Promise<void> {
    this.preferences.templateVersion = version;
    await this.savePreferences();
  }

  async updateLastCheckTime(): Promise<void> {
    this.preferences.lastUpdateCheck = Date.now();
    await this.savePreferences();
  }

  async checkForUpdates(force = false): Promise<TemplateInfo | null> {
    const now = Date.now();
    const oneDay = 24 * 60 * 60 * 1000;

    if (!force && this.preferences.lastUpdateCheck && now - this.preferences.lastUpdateCheck < oneDay) {
      return null;
    }

    try {
      const response = await axios.get<TemplateInfo[]>(TEMPLATE_REGISTRY_URL, { timeout: 5000 });
      const templates = response.data;

      if (templates.length === 0) {
        return null;
      }

      const latest = templates.reduce((a, b) => semver.gt(a.version, b.version) ? a : b);

      if (!this.preferences.templateVersion || semver.gt(latest.version, this.preferences.templateVersion)) {
        await this.updateLastCheckTime();
        return latest;
      }

      await this.updateLastCheckTime();
      return null;
    } catch {
      return null;
    }
  }

  async updateTemplates(): Promise<boolean> {
    try {
      const response = await axios.get<TemplateInfo[]>(TEMPLATE_REGISTRY_URL, { timeout: 10000 });
      const templates = response.data;

      const latest = templates.reduce((a, b) => semver.gt(a.version, b.version) ? a : b);

      await fs.emptyDir(TEMPLATE_CACHE_DIR);
      await fs.writeJson(TEMPLATE_INDEX_FILE, templates, { spaces: 2 });
      await this.setTemplateVersion(latest.version);
      return true;
    } catch {
      return false;
    }
  }

  getTemplateCacheDir(): string {
    return TEMPLATE_CACHE_DIR;
  }

  getCacheDir(): string {
    return CACHE_DIR;
  }

  getConfigDir(): string {
    return CONFIG_DIR;
  }

  async clearCache(): Promise<void> {
    await fs.emptyDir(CACHE_DIR);
  }
}

export const globalState = new GlobalState();
