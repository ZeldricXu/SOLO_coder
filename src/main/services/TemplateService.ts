import type { Document } from '@shared/types';
import { TEMPLATES } from '@shared/constants/templates';
import { generateDocId, formatDate } from '@shared/utils/markdown';
import { joinPaths } from '@shared/utils/path';
import * as fs from 'fs/promises';
import * as path from 'path';

export interface TemplateVariable {
  name: string;
  description: string;
  type: 'string' | 'date' | 'textarea';
  required?: boolean;
  defaultValue?: string;
}

export interface Template {
  id: string;
  name: string;
  description: string;
  icon: string;
  content: string;
  tags: string[];
  variables?: TemplateVariable[];
}

export interface RenderedTemplate {
  content: string;
  requiredInputs: TemplateVariable[];
}

export class TemplateService {
  private repoPath: string;

  constructor(repoPath: string) {
    this.repoPath = repoPath;
  }

  listTemplates(): Template[] {
    return TEMPLATES.map(t => ({
      id: t.id,
      name: t.name,
      description: t.description,
      icon: t.icon,
      content: t.content,
      tags: t.tags,
    }));
  }

  getTemplate(templateId: string): Template | null {
    const template = TEMPLATES.find(t => t.id === templateId);
    if (!template) return null;
    return {
      id: template.id,
      name: template.name,
      description: template.description,
      icon: template.icon,
      content: template.content,
      tags: template.tags,
    };
  }

  getTemplateVariables(templateId: string): TemplateVariable[] {
    const template = this.getTemplate(templateId);
    if (!template) return [];

    const content = template.content;
    const variables: TemplateVariable[] = [];
    const foundVars = new Set<string>();

    const varRegex = /\{\{\s*([a-zA-Z0-9_]+)\s*\}\}/g;
    let match;
    while ((match = varRegex.exec(content)) !== null) {
      const varName = match[1];
      if (foundVars.has(varName)) continue;
      foundVars.add(varName);

      if (['date', 'datetime', 'year', 'month', 'day', 'time', 'weekday', 'yesterday', 'yesterday_date', 'tomorrow', 'tomorrow_date'].includes(varName)) {
        continue;
      }

      let variable: TemplateVariable;
      switch (varName) {
        case 'title':
          variable = {
            name: 'title',
            description: '文档标题',
            type: 'string',
            required: true,
          };
          break;
        default:
          variable = {
            name: varName,
            description: varName,
            type: 'string',
            required: false,
          };
      }

      variables.push(variable);
    }

    if (template.variables) {
      template.variables.forEach(v => {
        if (!foundVars.has(v.name)) {
          variables.push(v);
        }
      });
    }

    return variables;
  }

  renderTemplate(templateId: string, variables: Record<string, string> = {}): string {
    const template = this.getTemplate(templateId);
    if (!template) return '';

    const now = new Date();
    const yesterday = new Date(now);
    yesterday.setDate(yesterday.getDate() - 1);
    const tomorrow = new Date(now);
    tomorrow.setDate(tomorrow.getDate() + 1);

    const yesterdayDate = formatDate(yesterday, 'YYYY-MM-DD');
    const tomorrowDate = formatDate(tomorrow, 'YYYY-MM-DD');

    const defaultVars: Record<string, string> = {
      date: formatDate(now, 'YYYY-MM-DD'),
      datetime: formatDate(now, 'YYYY-MM-DD HH:mm:ss'),
      year: formatDate(now, 'YYYY'),
      month: formatDate(now, 'MM'),
      day: formatDate(now, 'DD'),
      time: formatDate(now, 'HH:mm:ss'),
      weekday: ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][now.getDay()],
      yesterday: `[[${yesterdayDate} 每日笔记]]`,
      yesterday_date: yesterdayDate,
      tomorrow: `[[${tomorrowDate} 每日笔记]]`,
      tomorrow_date: tomorrowDate,
    };

    const allVars = { ...defaultVars, ...variables };
    let content = template.content;

    Object.entries(allVars).forEach(([key, value]) => {
      const regex = new RegExp(`\\{\\{\\s*${key}\\s*\\}\\}`, 'g');
      content = content.replace(regex, value);
    });

    return content;
  }

  renderTemplateWithInput(templateId: string, variables: Record<string, string> = {}): RenderedTemplate {
    const requiredInputs = this.getTemplateVariables(templateId);
    const content = this.renderTemplate(templateId, variables);

    return {
      content,
      requiredInputs,
    };
  }

  async createDocumentFromTemplate(
    templateId: string,
    variables: Record<string, string> = {},
    customTitle?: string
  ): Promise<Omit<Document, 'id' | 'createdAt' | 'updatedAt'> & { content: string; filename: string }> {
    const template = this.getTemplate(templateId);
    if (!template) {
      throw new Error(`Template not found: ${templateId}`);
    }

    const content = this.renderTemplate(templateId, variables);
    const now = new Date();
    const dateStr = formatDate(now, 'YYYY-MM-DD');
    
    let title = customTitle || template.name;
    if (templateId === 'daily') {
      title = customTitle || `${dateStr} 每日笔记`;
    } else if (templateId === 'meeting') {
      title = customTitle || `${dateStr} 会议记录`;
    }

    const id = generateDocId(title);
    const filename = `${title}.md`;

    return {
      title,
      content,
      tags: template.tags,
      filename,
      filePath: joinPaths(this.repoPath, filename),
      wordCount: content.length,
      hash: '',
      backlinks: [],
      outline: [],
    };
  }

  async saveTemplate(template: Omit<Template, 'id'> & { id?: string }): Promise<Template> {
    const templatesDir = joinPaths(this.repoPath, '.templates');
    try {
      await fs.access(templatesDir);
    } catch {
      await fs.mkdir(templatesDir, { recursive: true });
    }

    const id = template.id || generateDocId(template.name);
    const templatePath = joinPaths(templatesDir, `${id}.json`);
    
    const templateData: Template = {
      id,
      name: template.name,
      description: template.description,
      icon: template.icon,
      content: template.content,
      tags: template.tags,
    };

    await fs.writeFile(templatePath, JSON.stringify(templateData, null, 2), 'utf-8');
    return templateData;
  }

  async deleteTemplate(templateId: string): Promise<boolean> {
    const templatePath = joinPaths(this.repoPath, '.templates', `${templateId}.json`);
    try {
      await fs.unlink(templatePath);
      return true;
    } catch {
      return false;
    }
  }

  async loadCustomTemplates(): Promise<Template[]> {
    const templatesDir = joinPaths(this.repoPath, '.templates');
    try {
      await fs.access(templatesDir);
    } catch {
      return [];
    }

    const files = await fs.readdir(templatesDir);
    const templates: Template[] = [];

    for (const file of files) {
      if (file.endsWith('.json')) {
        try {
          const content = await fs.readFile(joinPaths(templatesDir, file), 'utf-8');
          const template = JSON.parse(content) as Template;
          templates.push(template);
        } catch (error) {
          console.error(`Failed to load template ${file}:`, error);
        }
      }
    }

    return templates;
  }

  async getAllTemplates(): Promise<Template[]> {
    const builtin = this.listTemplates();
    const custom = await this.loadCustomTemplates();
    return [...builtin, ...custom];
  }
}
