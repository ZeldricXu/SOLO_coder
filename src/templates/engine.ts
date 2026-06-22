import fs from 'fs-extra';
import path from 'path';
import Handlebars from 'handlebars';
import { glob } from 'glob';
import { execa } from 'execa';
import axios from 'axios';
import ora from 'ora';
import type { ProjectConfig, TemplateConfig, TemplateFile } from '../types.js';
import { getBuiltinTemplate } from './builtin.js';
import { globalState } from '../state.js';
import { templateRegistry } from './registry.js';

const TEMPLATE_NAME_PREFIX = 'create-';
const TEMPLATE_NAME_SUFFIX = '-template';

export class TemplateEngine {
  private config: ProjectConfig;

  constructor(config: ProjectConfig) {
    this.config = config;
  }

  async render(): Promise<TemplateConfig> {
    const templateConfig = await this.loadTemplate();
    await this.renderTemplateFiles(templateConfig);
    return templateConfig;
  }

  private async loadTemplate(): Promise<TemplateConfig> {
    if (this.config.template) {
      return this.loadCustomTemplate(this.config.template);
    }
    return getBuiltinTemplate(this.config.framework);
  }

  private async loadCustomTemplate(templatePath: string): Promise<TemplateConfig> {
    const spinner = ora(`加载自定义模板: ${templatePath}`).start();

    try {
      let localPath: string;

      if (this.isNpmPackageName(templatePath)) {
        localPath = await this.loadNpmTemplate(templatePath);
      } else if (templatePath.startsWith('http') || templatePath.startsWith('git@') || templatePath.endsWith('.git')) {
        localPath = await this.cloneRemoteTemplate(templatePath);
      } else if (await fs.pathExists(templatePath)) {
        localPath = path.resolve(templatePath);
      } else {
        throw new Error(`模板路径不存在: ${templatePath}`);
      }

      const configPath = path.join(localPath, 'template.json');
      if (!await fs.pathExists(configPath)) {
        throw new Error('模板目录中缺少 template.json 配置文件');
      }

      const templateConfig: TemplateConfig = await fs.readJson(configPath);

      spinner.succeed('自定义模板加载完成');
      return {
        ...templateConfig,
        files: templateConfig.files.map(f => ({
          ...f,
          source: path.join(localPath, f.source),
        })),
      };
    } catch (error) {
      spinner.fail(`模板加载失败: ${(error as Error).message}`);
      throw error;
    }
  }

  private isNpmPackageName(name: string): boolean {
    if (name.startsWith('@')) {
      return name.includes('/') && name.endsWith(TEMPLATE_NAME_SUFFIX);
    }
    return name.startsWith(TEMPLATE_NAME_PREFIX) && name.endsWith(TEMPLATE_NAME_SUFFIX);
  }

  private async loadNpmTemplate(packageName: string): Promise<string> {
    await templateRegistry.init();
    const version = this.config.templateVersion ?? undefined;

    const cachedPath = await templateRegistry.getTemplatePath(packageName, version);
    if (cachedPath) {
      return cachedPath;
    }

    const entry = await templateRegistry.installTemplate(packageName, version);
    return entry.path;
  }

  private async cloneRemoteTemplate(url: string): Promise<string> {
    const tempDir = path.join(globalState.getTemplateCacheDir(), `temp-${Date.now()}`);
    await fs.ensureDir(tempDir);

    try {
      await execa('git', ['clone', '--depth', '1', url, tempDir], {
        stdio: 'ignore',
      });
    } catch {
      try {
        const response = await axios.get(url, { responseType: 'arraybuffer' });
        await fs.writeFile(path.join(tempDir, 'template.zip'), response.data);
        await execa('unzip', ['-o', path.join(tempDir, 'template.zip'), '-d', tempDir], {
          stdio: 'ignore',
        });
      } catch (zipError) {
        throw new Error(`无法获取远程模板: ${(zipError as Error).message}`);
      }
    }

    return tempDir;
  }

  private async renderTemplateFiles(templateConfig: TemplateConfig): Promise<void> {
    const spinner = ora('渲染模板文件...').start();

    try {
      const templateData = this.getTemplateData();

      for (const file of templateConfig.files) {
        await this.renderSingleFile(file, templateData);
      }

      spinner.succeed('模板文件渲染完成');
    } catch (error) {
      spinner.fail(`模板渲染失败: ${(error as Error).message}`);
      throw error;
    }
  }

  private async renderSingleFile(file: TemplateFile, data: Record<string, unknown>): Promise<void> {
    const targetPath = path.join(this.config.targetDir, file.target);
    await fs.ensureDir(path.dirname(targetPath));

    if (!file.isTemplate) {
      await fs.copy(file.source, targetPath);
    } else {
      let content: string;
      if (file.source.startsWith('builtin:')) {
        const builtinName = file.source.replace('builtin:', '');
        content = await this.getBuiltinTemplateContent(builtinName);
      } else {
        content = await fs.readFile(file.source, 'utf-8');
      }

      const template = Handlebars.compile(content);
      const rendered = template(data);
      await fs.writeFile(targetPath, rendered, 'utf-8');
    }

    if (file.perm) {
      await fs.chmod(targetPath, file.perm);
    }
  }

  private async getBuiltinTemplateContent(name: string): Promise<string> {
    const distPath = path.join(__dirname, 'content', name);
    const srcPath = path.join(process.cwd(), 'src', 'templates', 'content', name);

    if (await fs.pathExists(distPath)) {
      return fs.readFile(distPath, 'utf-8');
    }

    if (await fs.pathExists(srcPath)) {
      return fs.readFile(srcPath, 'utf-8');
    }

    return this.getFallbackTemplate(name);
  }

  private getFallbackTemplate(name: string): string {
    const templates: Record<string, string> = {
      'index.ts': `import { cli } from './cli.js';\n\ncli(process.argv);\n`,
    };

    return templates[name] ?? '';
  }

  private getTemplateData(): Record<string, unknown> {
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

  private toCamelCase(str: string): string {
    return str.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
  }

  private toPascalCase(str: string): string {
    const camel = this.toCamelCase(str);
    return camel.charAt(0).toUpperCase() + camel.slice(1);
  }

  async renderGlob(pattern: string, data: Record<string, unknown>): Promise<void> {
    const files = await glob(pattern, { cwd: this.config.targetDir, nodir: true });

    for (const file of files) {
      const filePath = path.join(this.config.targetDir, file);
      const content = await fs.readFile(filePath, 'utf-8');
      const template = Handlebars.compile(content);
      const rendered = template(data);
      await fs.writeFile(filePath, rendered, 'utf-8');
    }
  }
}
