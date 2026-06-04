import { typedIpcMain } from './typedIpcMain';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';
import type { IPCResponse } from '@shared/types';
import type { FileService } from '../services/FileService';

export function registerFileIPCHandlers(fileService: FileService): void {
  typedIpcMain.handle(IPC_CHANNELS.FILE.READ, async (_event, filePath: string): Promise<IPCResponse<string>> => {
    try {
      const content = await fileService.readFile(filePath);
      return { success: true, data: content };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'FILE_READ_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.FILE.WRITE, async (_event, filePath: string, content: string): Promise<IPCResponse<string>> => {
    try {
      const path = await fileService.writeFile(filePath, content);
      return { success: true, data: path };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'FILE_WRITE_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.FILE.DELETE, async (_event, filePath: string): Promise<IPCResponse<void>> => {
    try {
      await fileService.deleteFile(filePath);
      return { success: true, data: undefined };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'FILE_DELETE_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.FILE.RENAME, async (_event, oldPath: string, newPath: string): Promise<IPCResponse<string>> => {
    try {
      const path = await fileService.renameFile(oldPath, newPath);
      return { success: true, data: path };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'FILE_RENAME_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.FILE.LIST, async (_event, dirPath?: string): Promise<IPCResponse<string[]>> => {
    try {
      const files = await fileService.listFiles(dirPath);
      return { success: true, data: files };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'FILE_LIST_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.FILE.EXISTS, async (_event, filePath: string): Promise<IPCResponse<boolean>> => {
    try {
      const exists = await fileService.exists(filePath);
      return { success: true, data: exists };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'FILE_EXISTS_ERROR' };
    }
  });
}
