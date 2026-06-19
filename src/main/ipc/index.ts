import { BrowserWindow } from 'electron';
import { NoteService } from '../db/noteService';
import { LinkService } from '../db/linkService';
import { SettingsService } from '../db/settingsService';
import { VaultService } from '../services/vaultService';
import { SearchService } from '../services/searchService';
import { ExportService } from '../services/exportService';
import { AttachmentService } from '../services/attachmentService';
import { LinkRepairService } from '../services/linkRepairService';
import { registerFsHandlers } from './fsHandlers';
import { registerSearchHandlers } from './searchHandlers';
import { registerPluginHandlers } from './pluginHandlers';
import { registerThemeHandlers } from './themeHandlers';

interface IpcHandlerDeps {
  getWindow: () => BrowserWindow | null;
}

export function registerAllIpcHandlers(deps: IpcHandlerDeps): void {
  registerFsHandlers({
    noteService: NoteService,
    linkService: LinkService,
    settingsService: SettingsService,
    vaultService: VaultService,
    searchService: SearchService,
    attachmentService: AttachmentService,
    linkRepairService: LinkRepairService,
  });

  registerSearchHandlers({
    searchService: SearchService,
  });

  registerPluginHandlers({
    settingsService: SettingsService,
    exportService: ExportService,
    getWindow: deps.getWindow,
  });

  registerThemeHandlers({
    settingsService: SettingsService,
    linkService: LinkService,
  });
}
