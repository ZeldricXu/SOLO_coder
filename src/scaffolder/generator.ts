import { Template, ScaffoldConfig, ScaffoldResult, InteractivePrompt } from './types';
import * as Handlebars from 'handlebars';
import * as fs from 'fs';
import * as path from 'path';
import { logger } from '../utils/common';

export class ScaffoldGenerator {
  async generate(config: ScaffoldConfig, template: Template): Promise<ScaffoldResult> {
    const result: ScaffoldResult = {
      success: true,
      outputDir: config.outputDir,
      filesCreated: [],
      messages: [],
      warnings: [],
      errors: [],
    };

    try {
      logger.info(`Starting scaffold generation`, {
        template: template.name,
        outputDir: config.outputDir,
      });

      this.ensureOutputDir(config.outputDir, config.overwriteExisting, result);

      for (const file of template.files) {
        const renderedPath = this.renderString(file.path, config.parameters);
        const fullPath = path.join(config.outputDir, renderedPath);

        this.ensureDirExists(path.dirname(fullPath));

        if (fs.existsSync(fullPath) && !config.overwriteExisting) {
          result.warnings.push(`File already exists, skipping: ${renderedPath}`);
          continue;
        }

        let content: string | Buffer;
        if (file.isBinary) {
          content = Buffer.from(file.template, 'base64');
        } else {
          content = this.renderString(file.template, config.parameters);
        }

        fs.writeFileSync(fullPath, content);
        result.filesCreated.push(renderedPath);
        logger.debug(`File created`, { path: renderedPath });
      }

      result.messages.push(`Successfully generated ${result.filesCreated.length} files`);
      result.messages.push(`Project location: ${config.outputDir}`);

      if (config.installDependencies) {
        result.messages.push('To install dependencies, run: npm install');
      }

      if (config.initializeGit) {
        result.messages.push('To initialize git, run: git init && git add . && git commit -m "Initial commit"');
      }

      logger.info(`Scaffold generation completed`, {
        filesCreated: result.filesCreated.length,
        outputDir: config.outputDir,
      });

    } catch (error) {
      result.success = false;
      result.errors.push(error instanceof Error ? error.message : 'Unknown error');
      logger.error(`Scaffold generation failed`, {
        error: error instanceof Error ? error.message : 'Unknown error',
      });
    }

    return result;
  }

  generatePrompts(template: Template): InteractivePrompt[] {
    return template.parameters.map(param => {
      const prompt: InteractivePrompt = {
        name: param.name,
        message: param.description || `Enter ${param.name}:`,
        type: 'input',
        default: param.defaultValue,
      };

      switch (param.type) {
        case 'number':
          prompt.type = 'number';
          break;
        case 'boolean':
          prompt.type = 'confirm';
          break;
        case 'select':
          prompt.type = 'list';
          prompt.choices = param.options?.map(o => ({ name: o.label, value: o.value }));
          break;
        case 'multiselect':
          prompt.type = 'checkbox';
          prompt.choices = param.options?.map(o => ({ name: o.label, value: o.value }));
          break;
      }

      if (param.validation) {
        prompt.validate = (value: unknown) => {
          if (param.required && (value === undefined || value === null || value === '')) {
            return 'This field is required';
          }
          if (param.validation?.pattern && typeof value === 'string') {
            if (!new RegExp(param.validation.pattern).test(value)) {
              return `Invalid format. Pattern: ${param.validation.pattern}`;
            }
          }
          if (param.validation?.minLength && typeof value === 'string') {
            if (value.length < param.validation.minLength) {
              return `Minimum length: ${param.validation.minLength}`;
            }
          }
          if (param.validation?.maxLength && typeof value === 'string') {
            if (value.length > param.validation.maxLength) {
              return `Maximum length: ${param.validation.maxLength}`;
            }
          }
          return true;
        };
      }

      return prompt;
    });
  }

  validateParameters(template: Template, parameters: Record<string, unknown>): { valid: boolean; errors: string[] } {
    const errors: string[] = [];

    for (const param of template.parameters) {
      const value = parameters[param.name];

      if (param.required && (value === undefined || value === null || value === '')) {
        errors.push(`Parameter '${param.name}' is required`);
        continue;
      }

      if (value !== undefined && value !== null) {
        switch (param.type) {
          case 'string':
            if (typeof value !== 'string') {
              errors.push(`Parameter '${param.name}' must be a string`);
            }
            break;
          case 'number':
            if (typeof value !== 'number') {
              errors.push(`Parameter '${param.name}' must be a number`);
            }
            break;
          case 'boolean':
            if (typeof value !== 'boolean') {
              errors.push(`Parameter '${param.name}' must be a boolean`);
            }
            break;
        }
      }
    }

    return { valid: errors.length === 0, errors };
  }

  private renderString(template: string, context: Record<string, unknown>): string {
    try {
      const compiled = Handlebars.compile(template);
      return compiled(context);
    } catch (error) {
      logger.warn(`Template rendering warning`, {
        error: error instanceof Error ? error.message : 'Unknown error',
      });
      return template;
    }
  }

  private ensureOutputDir(outputDir: string, overwrite: boolean, result: ScaffoldResult): void {
    if (fs.existsSync(outputDir)) {
      const stats = fs.statSync(outputDir);
      if (!stats.isDirectory()) {
        throw new Error(`Output path exists but is not a directory: ${outputDir}`);
      }

      const files = fs.readdirSync(outputDir);
      if (files.length > 0 && !overwrite) {
        result.warnings.push('Output directory is not empty. Some files may be skipped.');
      }
    } else {
      fs.mkdirSync(outputDir, { recursive: true });
    }
  }

  private ensureDirExists(dirPath: string): void {
    if (!fs.existsSync(dirPath)) {
      fs.mkdirSync(dirPath, { recursive: true });
    }
  }
}

export const scaffoldGenerator = new ScaffoldGenerator();
