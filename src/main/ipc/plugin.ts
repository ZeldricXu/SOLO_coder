import { dialog } from 'electron';
import { typedIpcMain } from './typedIpcMain';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';
import type { PluginService } from '../services/PluginService';

export function registerPluginHandlers(pluginService: PluginService) {
  typedIpcMain.handle(IPC_CHANNELS.PLUGIN.LIST, async () => {
    try {
      const plugins = await pluginService.listPlugins();
      return { success: true, data: plugins };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : '获取插件列表失败',
        data: [],
      };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.PLUGIN.INSTALL, async () => {
    try {
      const result = await dialog.showOpenDialog({
        properties: ['openDirectory'],
        title: '选择插件目录',
      });

      if (result.canceled || result.filePaths.length === 0) {
        return { success: false, data: null };
      }

      const plugin = await pluginService.installPlugin(result.filePaths[0]);
      return { success: true, data: plugin };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : '安装插件失败',
      };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.PLUGIN.UNINSTALL, async (_event, pluginId: string) => {
    try {
      const success = await pluginService.uninstallPlugin(pluginId);
      return { success, data: success };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : '卸载插件失败',
      };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.PLUGIN.ENABLE, async (_event, pluginId: string) => {
    try {
      const success = await pluginService.enablePlugin(pluginId);
      return { success, data: success };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : '启用插件失败',
      };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.PLUGIN.DISABLE, async (_event, pluginId: string) => {
    try {
      const success = await pluginService.disablePlugin(pluginId);
      return { success, data: success };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : '禁用插件失败',
      };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.PLUGIN.GET_SETTINGS, async (_event, pluginId: string) => {
    try {
      const settings = await pluginService.getPluginSettings(pluginId);
      return { success: true, data: settings };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : '获取插件设置失败',
        data: {},
      };
    }
  });

  typedIpcMain.handle(
    IPC_CHANNELS.PLUGIN.SET_SETTINGS,
    async (_event, pluginId: string, settings: Record<string, any>) => {
      try {
        await pluginService.setPluginSettings(pluginId, settings);
        return { success: true, data: undefined };
      } catch (error) {
        return {
          success: false,
          error: error instanceof Error ? error.message : '保存插件设置失败',
        };
      }
    }
  );
}
