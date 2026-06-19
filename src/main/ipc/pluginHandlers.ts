import { ipcMain, dialog, BrowserWindow } from 'electron';
import { IpcChannelName } from '../../shared/ipc/channels';
import { SettingsService } from '../db/settingsService';
import { ExportService } from '../services/exportService';

interface PluginHandlerDeps {
  settingsService: typeof SettingsService;
  exportService: typeof ExportService;
  getWindow: () => BrowserWindow | null;
}

export function registerPluginHandlers(deps: PluginHandlerDeps): void {
  const { settingsService, exportService, getWindow } = deps;

  ipcMain.handle(IpcChannelName.SETTINGS_GET, () => {
    return settingsService.get();
  });

  ipcMain.handle(IpcChannelName.SETTINGS_UPDATE, (_event, updates: any) => {
    return settingsService.update(updates);
  });

  ipcMain.handle(IpcChannelName.EXPORT_NOTE, async (_event, id: string, format: 'txt' | 'html' | 'pdf') => {
    return exportService.exportNote(id, format);
  });

  ipcMain.handle(IpcChannelName.EXPORT_DOMAIN, async (_event, noteIds: string[], format: 'markdown') => {
    return exportService.exportDomain(noteIds, format);
  });

  ipcMain.handle(IpcChannelName.EXPORT_GRAPH_PNG, async (_event, svgData: string) => {
    return exportService.exportGraphPNG(svgData);
  });

  ipcMain.handle(IpcChannelName.DIALOG_OPEN_FILE, async (_event, options?: any) => {
    const result = await dialog.showOpenDialog(getWindow()!, {
      ...options,
      properties: ['openFile'],
    });
    return result.canceled ? null : result.filePaths[0];
  });

  ipcMain.handle(IpcChannelName.DIALOG_OPEN_DIRECTORY, async (_event, options?: any) => {
    const result = await dialog.showOpenDialog(getWindow()!, {
      ...options,
      properties: ['openDirectory'],
    });
    return result.canceled ? null : result.filePaths[0];
  });

  ipcMain.handle(IpcChannelName.DIALOG_SAVE_FILE, async (_event, options?: any) => {
    const result = await dialog.showSaveDialog(getWindow()!, options);
    return result.canceled ? null : result.filePath;
  });
}
