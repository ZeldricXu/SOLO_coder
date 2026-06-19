import { ipcMain } from 'electron';
import path from 'path';
import fs from 'fs';
import { IpcChannelName } from '../../shared/ipc/channels';
import { NoteService } from '../db/noteService';
import { LinkService } from '../db/linkService';
import { SettingsService } from '../db/settingsService';
import { VaultService } from '../services/vaultService';
import { SearchService } from '../services/searchService';
import { AttachmentService } from '../services/attachmentService';
import { LinkRepairService } from '../services/linkRepairService';

interface FsHandlerDeps {
  noteService: typeof NoteService;
  linkService: typeof LinkService;
  settingsService: typeof SettingsService;
  vaultService: typeof VaultService;
  searchService: typeof SearchService;
  attachmentService: typeof AttachmentService;
  linkRepairService: typeof LinkRepairService;
}

export function registerFsHandlers(deps: FsHandlerDeps): void {
  const {
    noteService,
    linkService,
    settingsService,
    vaultService,
    searchService,
    attachmentService,
    linkRepairService,
  } = deps;

  ipcMain.handle(IpcChannelName.NOTES_GET_ALL, () => {
    return noteService.getAll();
  });

  ipcMain.handle(IpcChannelName.NOTES_GET_BY_ID, (_event, id: string) => {
    return noteService.getById(id);
  });

  ipcMain.handle(IpcChannelName.NOTES_GET_BY_PATH, (_event, pathStr: string) => {
    return noteService.getByPath(pathStr);
  });

  ipcMain.handle(IpcChannelName.NOTES_CREATE, (_event, note: any) => {
    return noteService.create(note);
  });

  ipcMain.handle(IpcChannelName.NOTES_UPDATE, (_event, id: string, updates: any) => {
    return noteService.update(id, updates);
  });

  ipcMain.handle(IpcChannelName.NOTES_DELETE, (_event, id: string) => {
    return noteService.delete(id);
  });

  ipcMain.handle(IpcChannelName.NOTES_SAVE_CONTENT, (_event, id: string, content: string) => {
    const note = noteService.getById(id);
    if (!note) return false;

    const result = noteService.saveContent(id, content);
    if (result) {
      const updated = noteService.getById(id)!;
      vaultService.extractAndSaveLinks(updated);
      searchService.updateNote(updated);

      const vaultPath = vaultService.getVaultPath();
      if (vaultPath) {
        const fullPath = path.join(vaultPath, note.path);
        let fileContent = '';
        if (note.frontmatter && Object.keys(note.frontmatter).length > 0) {
          fileContent += '---\n';
          for (const [key, value] of Object.entries(note.frontmatter)) {
            fileContent += `${key}: ${value}\n`;
          }
          fileContent += '---\n\n';
        }
        fileContent += content;
        fs.writeFileSync(fullPath, fileContent, 'utf-8');
      }
    }
    return result;
  });

  ipcMain.handle(IpcChannelName.NOTES_FIND_SIMILAR, (_event, title: string, threshold?: number) => {
    return linkRepairService.findSimilarNotes(title, threshold);
  });

  ipcMain.handle(IpcChannelName.NOTES_UPDATE_LINK_TARGET, (_event, sourceNoteId: string, oldTarget: string, newTargetId: string) => {
    return linkRepairService.updateLinkTarget(sourceNoteId, oldTarget, newTargetId);
  });

  ipcMain.handle(IpcChannelName.NOTES_SCAN_BROKEN_LINKS, (_event, noteId?: string) => {
    return linkRepairService.scanBrokenLinks(noteId);
  });

  ipcMain.handle(IpcChannelName.LINKS_GET_ALL, () => {
    return linkService.getAll();
  });

  ipcMain.handle(IpcChannelName.LINKS_GET_BACKLINKS, (_event, noteId: string) => {
    return linkService.getBacklinks(noteId);
  });

  ipcMain.handle(IpcChannelName.LINKS_GET_FORWARD_LINKS, (_event, noteId: string) => {
    return linkService.getForwardLinks(noteId);
  });

  ipcMain.handle(IpcChannelName.LINKS_MIGRATE_BACKLINKS, (_event, oldNoteId: string, newNoteId: string) => {
    return linkRepairService.migrateBacklinks(oldNoteId, newNoteId);
  });

  ipcMain.handle(IpcChannelName.VAULT_SET_PATH, async (_event, vaultPath: string) => {
    const success = vaultService.setVaultPath(vaultPath);
    if (success) {
      settingsService.setVaultPath(vaultPath);
      attachmentService.init(vaultPath);
      setTimeout(() => {
        searchService.rebuildIndex(noteService.getAll());
      }, 500);
      
      const permResult = vaultService.checkPermissions(vaultPath);
      if (!permResult.accessible && permResult.isProtectedPath) {
        setTimeout(() => {
          vaultService.showPermissionDialog();
        }, 500);
      }
    }
    return success;
  });

  ipcMain.handle(IpcChannelName.VAULT_GET_PATH, () => {
    return vaultService.getVaultPath() || settingsService.getVaultPath();
  });

  ipcMain.handle(IpcChannelName.VAULT_RESCAN, async () => {
    vaultService.rescan();
    searchService.rebuildIndex(noteService.getAll());
  });

  ipcMain.handle(IpcChannelName.VAULT_CHECK_PERMISSIONS, (_event, targetPath: string) => {
    return vaultService.checkPermissions(targetPath);
  });

  ipcMain.handle(IpcChannelName.VAULT_REQUEST_PERMISSIONS, async (_event, targetPath: string) => {
    return vaultService.requestPermissions(targetPath);
  });

  ipcMain.handle(IpcChannelName.VAULT_GET_WATCHER_STATUS, () => {
    return vaultService.getWatcherStatus();
  });

  ipcMain.handle(IpcChannelName.ATTACHMENTS_LIST, () => {
    return attachmentService.list();
  });

  ipcMain.handle(IpcChannelName.ATTACHMENTS_UPLOAD, async (_event, fileData: any, targetDir?: string) => {
    if (typeof fileData === 'string') {
      return attachmentService.upload(fileData, targetDir);
    } else if (fileData && fileData.name && fileData.data) {
      const buffer = Buffer.from(fileData.data);
      return attachmentService.uploadFromData(fileData.name, buffer, targetDir);
    }
    throw new Error('Invalid upload data');
  });

  ipcMain.handle(IpcChannelName.ATTACHMENTS_DELETE, (_event, attachmentId: string) => {
    return attachmentService.delete(attachmentId);
  });

  ipcMain.handle(IpcChannelName.ATTACHMENTS_RENAME, (_event, attachmentId: string, newName: string) => {
    return attachmentService.rename(attachmentId, newName);
  });

  ipcMain.handle(IpcChannelName.ATTACHMENTS_GET_THUMBNAIL, (_event, attachmentId: string) => {
    return attachmentService.getThumbnail(attachmentId);
  });

  ipcMain.handle(IpcChannelName.ATTACHMENTS_GET_ASSETS_PATH, () => {
    return attachmentService.getAssetsPath();
  });
}
