import path from 'path';
import fs from 'fs-extra';
import { execa } from 'execa';
import axios from 'axios';
import ora from 'ora';
import type { NpmTemplateInfo, TemplateCacheEntry } from '../types.js';
import { globalState } from '../state.js';

const TEMPLATE_NAME_PREFIX = 'create-';
const TEMPLATE_NAME_SUFFIX = '-template';
const NPM_REGISTRY_URL = 'https://registry.npmjs.org';
const SEARCH_SIZE = 50;

export class TemplateRegistry {
  private cacheDir: string;
  private cacheManifestPath: string;
  private manifest: Record<string, TemplateCacheEntry> = {};

  constructor() {
    this.cacheDir = path.join(globalState.getCacheDir(), 'templates');
    this.cacheManifestPath = path.join(this.cacheDir, 'manifest.json');
  }

  async init(): Promise<void> {
    await fs.ensureDir(this.cacheDir);
    await this.loadManifest();
  }

  private async loadManifest(): Promise<void> {
    try {
      if (await fs.pathExists(this.cacheManifestPath)) {
        const content = await fs.readFile(this.cacheManifestPath, 'utf-8');
        this.manifest = JSON.parse(content) as Record<string, TemplateCacheEntry>;
      }
    } catch {
      this.manifest = {};
    }
  }

  private async saveManifest(): Promise<void> {
    await fs.writeJson(this.cacheManifestPath, this.manifest, { spaces: 2 });
  }

  async searchTemplates(keyword = ''): Promise<NpmTemplateInfo[]> {
    const spinner = ora('搜索可用模板...').start();

    try {
      const searchQuery = `${TEMPLATE_NAME_PREFIX}*${TEMPLATE_NAME_SUFFIX}${keyword ? ` ${keyword}` : ''}`;
      const url = `${NPM_REGISTRY_URL}/-/v1/search?text=${encodeURIComponent(searchQuery)}&size=${SEARCH_SIZE}`;

      const response = await axios.get(url, { timeout: 10000 });

      const packages = (response.data as { objects: Array<{ package: NpmPackageInfo }> }).objects.map(obj => {
        const info: NpmTemplateInfo = {
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

      const filtered = packages.filter(pkg =>
        pkg.name.startsWith(TEMPLATE_NAME_PREFIX) &&
        pkg.name.endsWith(TEMPLATE_NAME_SUFFIX)
      );

      spinner.succeed(`找到 ${filtered.length} 个可用模板`);
      return filtered;
    } catch (error) {
      spinner.warn('无法从 npm registry 搜索模板，将仅使用内置模板');
      return [];
    }
  }

  async installTemplate(packageName: string, version?: string): Promise<TemplateCacheEntry> {
    const packageSpec = version ? `${packageName}@${version}` : packageName;
    const spinner = ora(`安装模板: ${packageSpec}`).start();

    try {
      const existingEntry = this.manifest[packageName];
      if (existingEntry && (!version || existingEntry.version === version)) {
        spinner.succeed(`模板 ${packageName} 已缓存 (v${existingEntry.version})`);
        return existingEntry;
      }

      const installDir = path.join(this.cacheDir, packageName.replace(/\//g, '_'));
      await fs.ensureDir(installDir);

      if (!await fs.pathExists(path.join(installDir, 'package.json'))) {
        await fs.writeJson(path.join(installDir, 'package.json'), {
          name: 'template-cache',
          version: '1.0.0',
          private: true,
        }, { spaces: 2 });
      }

      await execa('npm', ['install', packageSpec, '--no-save', '--prefix', installDir], {
        cwd: installDir,
        timeout: 120000,
      });

      const installedPkg = await fs.readJson(
        path.join(installDir, 'node_modules', packageName, 'package.json')
      ) as { name: string; version: string };

      const templatePath = path.join(installDir, 'node_modules', packageName);

      const entry: TemplateCacheEntry = {
        name: packageName,
        version: installedPkg.version,
        installedAt: Date.now(),
        path: templatePath,
      };

      this.manifest[packageName] = entry;
      await this.saveManifest();

      spinner.succeed(`模板 ${packageName} 安装完成 (v${installedPkg.version})`);
      return entry;
    } catch (error) {
      spinner.fail(`模板安装失败: ${packageSpec}`);
      throw error;
    }
  }

  getCachedTemplate(packageName: string): TemplateCacheEntry | null {
    return this.manifest[packageName] ?? null;
  }

  async getTemplatePath(packageName: string, version?: string): Promise<string | null> {
    const entry = this.manifest[packageName];
    if (!entry) return null;

    if (version && entry.version !== version) {
      return null;
    }

    if (await fs.pathExists(entry.path)) {
      return entry.path;
    }

    delete this.manifest[packageName];
    await this.saveManifest();
    return null;
  }

  async clearCache(): Promise<void> {
    await fs.emptyDir(this.cacheDir);
    this.manifest = {};
    await this.saveManifest();
  }

  async listCachedTemplates(): Promise<TemplateCacheEntry[]> {
    return Object.values(this.manifest);
  }

  getCacheDir(): string {
    return this.cacheDir;
  }
}

interface NpmPackageInfo {
  name: string;
  version: string;
  description: string;
  author?: { name: string };
  keywords?: string[];
  date: string;
  links?: {
    npm: string;
    homepage: string;
    repository: string;
  };
}

export const templateRegistry = new TemplateRegistry();
