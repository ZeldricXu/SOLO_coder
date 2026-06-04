import { typedIpcMain } from './typedIpcMain';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';
import type { IPCResponse, GitConfig, GitStatus, GitCommit, DiffHunk } from '@shared/types';
import type { GitService } from '../services/GitService';

export function registerGitIPCHandlers(gitService: GitService): void {
  typedIpcMain.handle(IPC_CHANNELS.GIT.INIT, async (): Promise<IPCResponse<void>> => {
    try {
      await gitService.init();
      return { success: true, data: undefined };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'GIT_INIT_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.GIT.STATUS, async (): Promise<IPCResponse<GitStatus[]>> => {
    try {
      const status = await gitService.status();
      return { success: true, data: status };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'GIT_STATUS_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.GIT.COMMIT, async (_event, message: string): Promise<IPCResponse<string>> => {
    try {
      const sha = await gitService.commit(message);
      return { success: true, data: sha };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'GIT_COMMIT_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.GIT.PUSH, async (): Promise<IPCResponse<void>> => {
    try {
      await gitService.push();
      return { success: true, data: undefined };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'GIT_PUSH_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.GIT.PULL, async (): Promise<IPCResponse<void>> => {
    try {
      await gitService.pull();
      return { success: true, data: undefined };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'GIT_PULL_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.GIT.LOG, async (_event, maxCount?: number): Promise<IPCResponse<GitCommit[]>> => {
    try {
      const logs = await gitService.log(maxCount);
      return { success: true, data: logs };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'GIT_LOG_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.GIT.DIFF, async (_event, filepath: string): Promise<IPCResponse<DiffHunk[]>> => {
    try {
      const diff = await gitService.diff(filepath);
      return { success: true, data: diff };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'GIT_DIFF_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.GIT.CLONE, async (_event, url: string, branch?: string): Promise<IPCResponse<void>> => {
    try {
      await gitService.clone(url, branch);
      return { success: true, data: undefined };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'GIT_CLONE_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.GIT.CONFIG_GET, (): IPCResponse<GitConfig> => {
    try {
      const config = gitService.getConfig();
      return { success: true, data: config };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'GIT_CONFIG_GET_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.GIT.CONFIG_SET, (_event, config: Partial<GitConfig>): IPCResponse<void> => {
    try {
      gitService.setConfig(config);
      return { success: true, data: undefined };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'GIT_CONFIG_SET_ERROR' };
    }
  });

  typedIpcMain.handle(IPC_CHANNELS.GIT.REMOTE_SET, (_event, url: string): IPCResponse<void> => {
    try {
      gitService.setConfig({ remoteUrl: url });
      return { success: true, data: undefined };
    } catch (e) {
      return { success: false, error: (e as Error).message, code: 'GIT_REMOTE_SET_ERROR' };
    }
  });
}
