import { useEffect } from 'react';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';

export function useIPCListener(
  channel: typeof IPC_CHANNELS[keyof typeof IPC_CHANNELS],
  handler: (...args: any[]) => void
) {
  useEffect(() => {
    const removeListener = window.electron.ipc.on(channel, handler);
    return () => removeListener();
  }, [channel, handler]);
}

export function useIPCListenerOnce(
  channel: typeof IPC_CHANNELS[keyof typeof IPC_CHANNELS],
  handler: (...args: any[]) => void
) {
  useEffect(() => {
    const removeListener = window.electron.ipc.once(channel, handler);
    return () => removeListener();
  }, [channel, handler]);
}
