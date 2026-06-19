export type IpcChannel = import('./channels').IpcChannels;
export type IpcRequest<C extends keyof IpcChannel> = IpcChannel[C]['request'];
export type IpcResponse<C extends keyof IpcChannel> = IpcChannel[C]['response'];
