import { contextBridge, ipcRenderer } from 'electron';
import type { IPCChannel } from '@shared/constants/ipcChannels';

contextBridge.exposeInMainWorld('electron', {
  ipc: {
    invoke: <T>(channel: IPCChannel, ...args: any[]): Promise<T> => {
      return ipcRenderer.invoke(channel, ...args);
    },
    send: (channel: IPCChannel, ...args: any[]): void => {
      ipcRenderer.send(channel, ...args);
    },
    on: (channel: IPCChannel, listener: (...args: any[]) => void) => {
      ipcRenderer.on(channel, listener);
      return () => ipcRenderer.removeListener(channel, listener);
    },
    once: (channel: IPCChannel, listener: (...args: any[]) => void) => {
      ipcRenderer.once(channel, listener);
    },
    removeListener: (channel: IPCChannel, listener: (...args: any[]) => void) => {
      ipcRenderer.removeListener(channel, listener);
    },
    removeAllListeners: (channel: IPCChannel) => {
      ipcRenderer.removeAllListeners(channel);
    },
  },
  platform: process.platform,
  versions: process.versions,
});

declare global {
  interface Window {
    electron: {
      ipc: {
        invoke: <T>(channel: IPCChannel, ...args: any[]) => Promise<T>;
        send: (channel: IPCChannel, ...args: any[]) => void;
        on: (channel: IPCChannel, listener: (...args: any[]) => void) => () => void;
        once: (channel: IPCChannel, listener: (...args: any[]) => void) => void;
        removeListener: (channel: IPCChannel, listener: (...args: any[]) => void) => void;
        removeAllListeners: (channel: IPCChannel) => void;
      };
      platform: NodeJS.Platform;
      versions: NodeJS.ProcessVersions;
    };
  }
}
