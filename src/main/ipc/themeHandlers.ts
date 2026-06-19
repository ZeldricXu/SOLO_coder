import { ipcMain } from 'electron';
import { IpcChannelName } from '../../shared/ipc/channels';
import { SettingsService } from '../db/settingsService';
import { LinkService } from '../db/linkService';

interface ThemeHandlerDeps {
  settingsService: typeof SettingsService;
  linkService: typeof LinkService;
}

export function registerThemeHandlers(deps: ThemeHandlerDeps): void {
  const { settingsService, linkService } = deps;

  ipcMain.handle(IpcChannelName.THEME_GET, () => {
    return settingsService.get().theme;
  });

  ipcMain.handle(IpcChannelName.THEME_SET, (_event, theme: string) => {
    settingsService.update({ theme });
    return theme;
  });

  ipcMain.handle(IpcChannelName.GRAPH_GET_DATA, () => {
    return linkService.getGraphData();
  });

  ipcMain.handle(IpcChannelName.GRAPH_GET_FOCUS_DATA, (_event, options: any) => {
    return linkService.getFocusGraphData(options);
  });
}
