import { TemplateManager } from './templateManager';
import { ScaffoldGenerator } from './generator';
import { ScaffoldConfig } from './types';
import { logger } from '../utils/common';

export class Scaffolder {
  private templateManager: TemplateManager;
  private generator: ScaffoldGenerator;

  constructor() {
    this.templateManager = new TemplateManager();
    this.generator = new ScaffoldGenerator();
    this.templateManager.registerDefaultTemplates();
  }

  getTemplateManager(): TemplateManager {
    return this.templateManager;
  }

  getGenerator(): ScaffoldGenerator {
    return this.generator;
  }

  async scaffold(config: ScaffoldConfig) {
    const template = this.templateManager.getTemplate(config.templateId)
      || this.templateManager.getTemplateByName(config.templateId);

    if (!template) {
      throw new Error(`Template not found: ${config.templateId}`);
    }

    const validation = this.generator.validateParameters(template, config.parameters);
    if (!validation.valid) {
      throw new Error(`Parameter validation failed: ${validation.errors.join(', ')}`);
    }

    return this.generator.generate(config, template);
  }

  listTemplates(filters?: {
    language?: string;
    framework?: string;
    category?: string;
    tags?: string[];
  }) {
    return this.templateManager.listTemplates(filters);
  }

  getPrompts(templateId: string) {
    const template = this.templateManager.getTemplate(templateId)
      || this.templateManager.getTemplateByName(templateId);

    if (!template) {
      throw new Error(`Template not found: ${templateId}`);
    }

    return this.generator.generatePrompts(template);
  }

  async interactiveScaffold(
    templateId: string,
    outputDir: string,
    answers: Record<string, unknown>,
    options: { overwrite?: boolean; installDeps?: boolean; initGit?: boolean } = {}
  ) {
    const template = this.templateManager.getTemplate(templateId)
      || this.templateManager.getTemplateByName(templateId);

    if (!template) {
      throw new Error(`Template not found: ${templateId}`);
    }

    const config: ScaffoldConfig = {
      templateId: template.id,
      outputDir,
      parameters: answers,
      overwriteExisting: options.overwrite || false,
      installDependencies: options.installDeps !== false,
      initializeGit: options.initGit || false,
    };

    const validation = this.generator.validateParameters(template, config.parameters);
    if (!validation.valid) {
      throw new Error(`Parameter validation failed: ${validation.errors.join(', ')}`);
    }

    logger.info(`Starting interactive scaffold`, { template: template.name, outputDir });
    return this.generator.generate(config, template);
  }
}

export const scaffolder = new Scaffolder();
