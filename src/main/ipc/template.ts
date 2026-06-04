import { typedIpcMain } from './typedIpcMain';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';
import type { TemplateService } from '../services/TemplateService';
import type { DatabaseService } from '../services/DatabaseService';
import type { Document } from '@shared/types';

export function registerTemplateHandlers(
  templateService: TemplateService,
  dbService: DatabaseService
) {
  typedIpcMain.handle(IPC_CHANNELS.TEMPLATE.LIST, async () => {
    try {
      const templates = await templateService.getAllTemplates();
      return { success: true, data: templates };
    } catch (error) {
      return { success: false, error: error instanceof Error ? error.message : '获取模板列表失败' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.TEMPLATE.GET, async (_event, templateId: string) => {
    try {
      const template = templateService.getTemplate(templateId);
      if (!template) {
        return { success: false, error: '模板不存在' };
      }
      return { success: true, data: template };
    } catch (error) {
      return { success: false, error: error instanceof Error ? error.message : '获取模板失败' };
    }
  });

  typedIpcMain.handle(
    IPC_CHANNELS.TEMPLATE.GET_VARIABLES,
    async (_event, templateId: string) => {
      try {
        const variables = templateService.getTemplateVariables(templateId);
        return { success: true, data: variables };
      } catch (error) {
        return { success: false, error: error instanceof Error ? error.message : '获取模板变量失败' };
      }
    }
  );

  typedIpcMain.handle(
    IPC_CHANNELS.TEMPLATE.RENDER,
    async (_event, templateId: string, variables?: Record<string, string>) => {
      try {
        const rendered = templateService.renderTemplateWithInput(templateId, variables || {});
        return { success: true, data: rendered };
      } catch (error) {
        return { success: false, error: error instanceof Error ? error.message : '渲染模板失败' };
      }
    }
  );

  typedIpcMain.handle(
    IPC_CHANNELS.TEMPLATE.CREATE,
    async (_event, templateId: string, variables?: Record<string, string>, customTitle?: string) => {
      try {
        const docData = await templateService.createDocumentFromTemplate(
          templateId,
          variables || {},
          customTitle
        );

        const existing = await dbService.getDocumentByTitle(docData.title);
        if (existing) {
          return { success: false, error: '文档标题已存在' };
        }

        const doc = await dbService.createDocument({
          title: docData.title,
          content: docData.content,
          tags: docData.tags,
          filePath: docData.filePath,
          filename: docData.filename,
        });

        return { success: true, data: doc };
      } catch (error) {
        return { success: false, error: error instanceof Error ? error.message : '从模板创建文档失败' };
      }
    }
  );

  typedIpcMain.handle(
    IPC_CHANNELS.TEMPLATE.SAVE,
    async (_event, template: any) => {
      try {
        const saved = await templateService.saveTemplate(template);
        return { success: true, data: saved };
      } catch (error) {
        return { success: false, error: error instanceof Error ? error.message : '保存模板失败' };
      }
    }
  );

  typedIpcMain.handle(IPC_CHANNELS.TEMPLATE.DELETE, async (_event, templateId: string) => {
    try {
      const success = await templateService.deleteTemplate(templateId);
      return { success, data: success };
    } catch (error) {
      return { success: false, error: error instanceof Error ? error.message : '删除模板失败' };
    }
  });
}
