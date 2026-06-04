import { ipcMain } from 'electron';
import type { IPCChannelKey, IPCHandler, TypedIpcMain } from '@shared/ipc-api';

export const typedIpcMain: TypedIpcMain = {
  handle<K extends IPCChannelKey>(
    channel: K,
    handler: IPCHandler<K>
  ): void {
    ipcMain.handle(channel, handler as any);
  },

  removeHandler<K extends IPCChannelKey>(channel: K): void {
    ipcMain.removeHandler(channel);
  },
};

export function registerTypedHandler<K extends IPCChannelKey>(
  channel: K,
  handler: IPCHandler<K>
): void {
  typedIpcMain.handle(channel, handler);
}
