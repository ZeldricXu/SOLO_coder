import * as fs from 'fs/promises';
import * as path from 'path';
import { Worker } from 'worker_threads';
import type { PluginManifest, PluginInfo, PluginPermission, ExtensionPointType } from '@shared/types';
import { joinPaths } from '@shared/utils/path';

export interface LoadedPlugin {
  manifest: PluginManifest;
  info: PluginInfo;
  enabled: boolean;
  permissions: PluginPermission[];
  extensionPoints: Map<ExtensionPointType, any[]>;
  settings: Record<string, any>;
  loadedAt: Date;
}

interface PluginWorker {
  worker: Worker;
  pluginId: string;
  timeoutTimer: ReturnType<typeof setTimeout> | null;
  pendingRequests: Map<number, { resolve: Function; reject: Function }>;
}

const PLUGIN_MEMORY_LIMIT_MB = 64;
const PLUGIN_EXECUTION_TIMEOUT_MS = 30000;

export class PluginService {
  private repoPath: string;
  private pluginsDir: string;
  private plugins: Map<string, LoadedPlugin> = new Map();
  private activeWorkers: Map<string, PluginWorker> = new Map();

  constructor(repoPath: string) {
    this.repoPath = repoPath;
    this.pluginsDir = joinPaths(repoPath, '.plugins');
  }

  async initialize(): Promise<void> {
    try {
      await fs.access(this.pluginsDir);
    } catch {
      await fs.mkdir(this.pluginsDir, { recursive: true });
    }
    await this.loadAllPlugins();
  }

  private async loadAllPlugins(): Promise<void> {
    try {
      const entries = await fs.readdir(this.pluginsDir, { withFileTypes: true });
      
      for (const entry of entries) {
        if (entry.isDirectory()) {
          try {
            await this.loadPlugin(entry.name);
          } catch (error) {
            console.error(`Failed to load plugin ${entry.name}:`, error);
          }
        }
      }
    } catch (error) {
      console.error('Failed to load plugins:', error);
    }
  }

  private async loadPlugin(pluginId: string): Promise<LoadedPlugin | null> {
    const pluginDir = joinPaths(this.pluginsDir, pluginId);
    const manifestPath = joinPaths(pluginDir, 'manifest.json');

    try {
      const manifestContent = await fs.readFile(manifestPath, 'utf-8');
      const manifest = JSON.parse(manifestContent) as PluginManifest;

      if (manifest.id !== pluginId) {
        throw new Error(`Plugin ID mismatch: expected ${pluginId}, got ${manifest.id}`);
      }

      const packageJsonPath = joinPaths(pluginDir, 'package.json');
      let version = '1.0.0';
      let description = '';
      let author = '';

      try {
        const packageJson = JSON.parse(await fs.readFile(packageJsonPath, 'utf-8'));
        version = packageJson.version || version;
        description = packageJson.description || description;
        author = packageJson.author || author;
      } catch {}

      const settings = await this.loadPluginSettings(pluginId);
      const enabled = this.getPluginEnabledState(pluginId);

      const loadedPlugin: LoadedPlugin = {
        manifest,
        info: {
          id: manifest.id,
          name: manifest.name,
          version,
          description: manifest.description || description,
          author: manifest.author || author,
          icon: manifest.icon || '🧩',
          homepage: manifest.homepage,
          repository: manifest.repository,
        },
        enabled,
        permissions: manifest.permissions || [],
        extensionPoints: new Map(),
        settings,
        loadedAt: new Date(),
      };

      if (manifest.extensionPoints) {
        for (const [type, extensions] of Object.entries(manifest.extensionPoints)) {
          loadedPlugin.extensionPoints.set(type as ExtensionPointType, extensions);
        }
      }

      this.plugins.set(pluginId, loadedPlugin);
      return loadedPlugin;
    } catch (error) {
      throw new Error(`Failed to load plugin ${pluginId}: ${error}`);
    }
  }

  async listPlugins(): Promise<PluginInfo[]> {
    const plugins: PluginInfo[] = [];
    for (const plugin of this.plugins.values()) {
      plugins.push({
        ...plugin.info,
        enabled: plugin.enabled,
        permissions: plugin.permissions,
      });
    }
    return plugins;
  }

  async getPlugin(pluginId: string): Promise<LoadedPlugin | null> {
    return this.plugins.get(pluginId) || null;
  }

  async enablePlugin(pluginId: string): Promise<boolean> {
    const plugin = this.plugins.get(pluginId);
    if (!plugin) return false;

    plugin.enabled = true;
    await this.savePluginEnabledState(pluginId, true);
    await this.activatePlugin(plugin);
    return true;
  }

  async disablePlugin(pluginId: string): Promise<boolean> {
    const plugin = this.plugins.get(pluginId);
    if (!plugin) return false;

    plugin.enabled = false;
    await this.savePluginEnabledState(pluginId, false);
    await this.deactivatePlugin(plugin);
    return true;
  }

  private async activatePlugin(plugin: LoadedPlugin): Promise<void> {
    if (!plugin.enabled) return;
    console.log(`Activating plugin: ${plugin.info.name}`);
  }

  private async deactivatePlugin(plugin: LoadedPlugin): Promise<void> {
    console.log(`Deactivating plugin: ${plugin.info.name}`);
    await this.terminateWorker(plugin.manifest.id);
  }

  private async terminateWorker(pluginId: string): Promise<void> {
    const pluginWorker = this.activeWorkers.get(pluginId);
    if (!pluginWorker) return;

    if (pluginWorker.timeoutTimer) {
      clearTimeout(pluginWorker.timeoutTimer);
    }

    pluginWorker.worker.removeAllListeners();
    pluginWorker.pendingRequests.clear();
    
    await new Promise<void>((resolve) => {
      const timeout = setTimeout(() => {
        pluginWorker.worker.terminate();
        resolve();
      }, 5000);
      
      pluginWorker.worker.on('exit', () => {
        clearTimeout(timeout);
        resolve();
      });
      
      pluginWorker.worker.terminate();
    });

    this.activeWorkers.delete(pluginId);
  }

  async executePlugin(pluginId: string, method: string, args: any[] = []): Promise<any> {
    const plugin = this.plugins.get(pluginId);
    if (!plugin || !plugin.enabled) {
      throw new Error(`Plugin ${pluginId} is not enabled or not found`);
    }

    let pluginWorker = this.activeWorkers.get(pluginId);
    if (!pluginWorker) {
      pluginWorker = await this.createWorker(pluginId);
    }

    return new Promise<any>((resolve, reject) => {
      const requestId = Date.now() + Math.random();
      
      const timeout = setTimeout(() => {
        pluginWorker!.pendingRequests.delete(requestId);
        this.terminateWorker(pluginId);
        reject(new Error(`Plugin ${pluginId} execution timed out after ${PLUGIN_EXECUTION_TIMEOUT_MS}ms`));
      }, PLUGIN_EXECUTION_TIMEOUT_MS);

      pluginWorker!.pendingRequests.set(requestId, {
        resolve: (result: any) => {
          clearTimeout(timeout);
          resolve(result);
        },
        reject: (error: any) => {
          clearTimeout(timeout);
          reject(error);
        },
      });

      pluginWorker!.worker.postMessage({
        type: 'execute',
        requestId,
        method,
        args,
      });
    });
  }

  private async createWorker(pluginId: string): Promise<PluginWorker> {
    const pluginDir = joinPaths(this.pluginsDir, pluginId);
    const mainFile = joinPaths(pluginDir, 'index.js');

    try {
      await fs.access(mainFile);
    } catch {
      throw new Error(`Plugin ${pluginId} does not have an index.js entry file`);
    }

    const workerCode = `
const { parentPort } = require('worker_threads');

const originalRequire = require;
const dangerousModules = ['fs', 'child_process', 'os', 'cluster', 'dgram', 'dns', 'net', 'tls', 'crypto', 'https', 'http'];
const allowedModules = ['path', 'url', 'util', 'events', 'stream', 'string_decoder', 'querystring'];

function sandboxedRequire(moduleName) {
  if (dangerousModules.includes(moduleName)) {
    throw new Error('Access denied: module "' + moduleName + '" is not allowed in plugin sandbox');
  }
  return originalRequire(moduleName);
}

const pluginGlobal = {
  require: sandboxedRequire,
  console: console,
  setTimeout: setTimeout,
  setInterval: setInterval,
  clearTimeout: clearTimeout,
  clearInterval: clearInterval,
  JSON: JSON,
  Math: Math,
  Date: Date,
  RegExp: RegExp,
  Array: Array,
  Object: Object,
  String: String,
  Number: Number,
  Boolean: Boolean,
  Map: Map,
  Set: Set,
  Promise: Promise,
  Error: Error,
  TypeError: TypeError,
  RangeError: RangeError,
};

let pluginModule = null;
let pluginMethods = {};

try {
  const pluginFactory = originalRequire(${JSON.stringify(mainFile)});
  if (typeof pluginFactory === 'function') {
    const api = {
      on: (method, handler) => { pluginMethods[method] = handler; },
      postMessage: (msg) => { parentPort.postMessage({ type: 'plugin-message', ...msg }); },
    };
    pluginFactory(api, pluginGlobal);
  } else if (typeof pluginFactory === 'object') {
    pluginMethods = pluginFactory;
  }
} catch (e) {
  parentPort.postMessage({ type: 'error', error: e.message });
}

parentPort.on('message', async (msg) => {
  if (msg.type === 'execute') {
    try {
      const handler = pluginMethods[msg.method];
      if (!handler) {
        parentPort.postMessage({ type: 'result', requestId: msg.requestId, error: 'Method not found: ' + msg.method });
        return;
      }
      const result = await handler(...(msg.args || []));
      parentPort.postMessage({ type: 'result', requestId: msg.requestId, result });
    } catch (e) {
      parentPort.postMessage({ type: 'result', requestId: msg.requestId, error: e.message });
    }
  } else if (msg.type === 'shutdown') {
    process.exit(0);
  }
});

parentPort.postMessage({ type: 'ready' });
`;

    const worker = new Worker(workerCode, {
      eval: true,
      resourceLimits: {
        maxOldGenerationSizeMb: PLUGIN_MEMORY_LIMIT_MB,
        maxYoungGenerationSizeMb: PLUGIN_MEMORY_LIMIT_MB / 4,
        stackSizeMb: 4,
      },
    });

    const pluginWorker: PluginWorker = {
      worker,
      pluginId,
      timeoutTimer: null,
      pendingRequests: new Map(),
    };

    worker.on('message', (msg: any) => {
      if (msg.type === 'result' && msg.requestId !== undefined) {
        const pending = pluginWorker.pendingRequests.get(msg.requestId);
        if (pending) {
          pluginWorker.pendingRequests.delete(msg.requestId);
          if (msg.error) {
            pending.reject(new Error(msg.error));
          } else {
            pending.resolve(msg.result);
          }
        }
      } else if (msg.type === 'error') {
        console.error(`Plugin ${pluginId} error:`, msg.error);
      }
    });

    worker.on('error', (error) => {
      console.error(`Plugin ${pluginId} worker error:`, error);
      for (const [, pending] of pluginWorker.pendingRequests) {
        pending.reject(error);
      }
      pluginWorker.pendingRequests.clear();
      this.activeWorkers.delete(pluginId);
    });

    worker.on('exit', (code) => {
      if (code !== 0) {
        console.warn(`Plugin ${pluginId} worker exited with code ${code}`);
      }
      for (const [, pending] of pluginWorker.pendingRequests) {
        pending.reject(new Error(`Plugin worker exited with code ${code}`));
      }
      pluginWorker.pendingRequests.clear();
      this.activeWorkers.delete(pluginId);
    });

    this.activeWorkers.set(pluginId, pluginWorker);

    return pluginWorker;
  }

  async installPlugin(pluginPath: string): Promise<PluginInfo | null> {
    try {
      const stat = await fs.stat(pluginPath);
      if (!stat.isDirectory()) {
        throw new Error('Plugin path must be a directory');
      }

      const manifestPath = joinPaths(pluginPath, 'manifest.json');
      const manifestContent = await fs.readFile(manifestPath, 'utf-8');
      const manifest = JSON.parse(manifestContent) as PluginManifest;

      const targetDir = joinPaths(this.pluginsDir, manifest.id);
      await this.copyDirectory(pluginPath, targetDir);

      const plugin = await this.loadPlugin(manifest.id);
      if (plugin) {
        await this.enablePlugin(manifest.id);
        return {
          ...plugin.info,
          enabled: true,
          permissions: plugin.permissions,
        };
      }

      return null;
    } catch (error) {
      console.error('Failed to install plugin:', error);
      throw error;
    }
  }

  async uninstallPlugin(pluginId: string): Promise<boolean> {
    try {
      const plugin = this.plugins.get(pluginId);
      if (!plugin) return false;

      await this.deactivatePlugin(plugin);

      const pluginDir = joinPaths(this.pluginsDir, pluginId);
      await fs.rm(pluginDir, { recursive: true, force: true });

      this.plugins.delete(pluginId);
      await this.deletePluginSettings(pluginId);
      await this.deletePluginEnabledState(pluginId);

      return true;
    } catch (error) {
      console.error('Failed to uninstall plugin:', error);
      return false;
    }
  }

  async getPluginSettings(pluginId: string): Promise<Record<string, any>> {
    const plugin = this.plugins.get(pluginId);
    return plugin?.settings || {};
  }

  async setPluginSettings(pluginId: string, settings: Record<string, any>): Promise<void> {
    const plugin = this.plugins.get(pluginId);
    if (plugin) {
      plugin.settings = { ...plugin.settings, ...settings };
      await this.savePluginSettings(pluginId, plugin.settings);
    }
  }

  private async loadPluginSettings(pluginId: string): Promise<Record<string, any>> {
    const settingsDir = joinPaths(this.repoPath, '.knowledgeforge', 'plugin-settings');
    const settingsPath = joinPaths(settingsDir, `${pluginId}.json`);
    
    try {
      await fs.access(settingsPath);
      const content = await fs.readFile(settingsPath, 'utf-8');
      return JSON.parse(content);
    } catch {
      return {};
    }
  }

  private async savePluginSettings(pluginId: string, settings: Record<string, any>): Promise<void> {
    const settingsDir = joinPaths(this.repoPath, '.knowledgeforge', 'plugin-settings');
    try {
      await fs.access(settingsDir);
    } catch {
      await fs.mkdir(settingsDir, { recursive: true });
    }

    const settingsPath = joinPaths(settingsDir, `${pluginId}.json`);
    await fs.writeFile(settingsPath, JSON.stringify(settings, null, 2), 'utf-8');
  }

  private async deletePluginSettings(pluginId: string): Promise<void> {
    const settingsPath = joinPaths(this.repoPath, '.knowledgeforge', 'plugin-settings', `${pluginId}.json`);
    try {
      await fs.unlink(settingsPath);
    } catch {}
  }

  private getPluginEnabledState(pluginId: string): boolean {
    try {
      const enabledPlugins = localStorage.getItem('enabledPlugins');
      if (enabledPlugins) {
        const enabled = JSON.parse(enabledPlugins);
        return enabled[pluginId] !== false;
      }
    } catch {}
    return true;
  }

  private async savePluginEnabledState(pluginId: string, enabled: boolean): Promise<void> {
    try {
      const enabledPluginsStr = localStorage.getItem('enabledPlugins');
      const enabledPlugins = enabledPluginsStr ? JSON.parse(enabledPluginsStr) : {};
      enabledPlugins[pluginId] = enabled;
      localStorage.setItem('enabledPlugins', JSON.stringify(enabledPlugins));
    } catch {}
  }

  private async deletePluginEnabledState(pluginId: string): Promise<void> {
    try {
      const enabledPluginsStr = localStorage.getItem('enabledPlugins');
      if (enabledPluginsStr) {
        const enabledPlugins = JSON.parse(enabledPluginsStr);
        delete enabledPlugins[pluginId];
        localStorage.setItem('enabledPlugins', JSON.stringify(enabledPlugins));
      }
    } catch {}
  }

  getExtensionPoints<T>(type: ExtensionPointType): T[] {
    const extensions: T[] = [];
    for (const plugin of this.plugins.values()) {
      if (!plugin.enabled) continue;
      const pluginExtensions = plugin.extensionPoints.get(type);
      if (pluginExtensions) {
        extensions.push(...pluginExtensions);
      }
    }
    return extensions;
  }

  private async copyDirectory(source: string, target: string): Promise<void> {
    await fs.mkdir(target, { recursive: true });
    const entries = await fs.readdir(source, { withFileTypes: true });

    for (const entry of entries) {
      const sourcePath = joinPaths(source, entry.name);
      const targetPath = joinPaths(target, entry.name);

      if (entry.isDirectory()) {
        await this.copyDirectory(sourcePath, targetPath);
      } else {
        await fs.copyFile(sourcePath, targetPath);
      }
    }
  }

  async shutdown(): Promise<void> {
    const pluginIds = Array.from(this.activeWorkers.keys());
    await Promise.all(pluginIds.map(id => this.terminateWorker(id)));
  }
}
