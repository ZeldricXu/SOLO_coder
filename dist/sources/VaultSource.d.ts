import { BaseConfigSource } from './ConfigSource';
import { ConfigData, ConfigValue } from '../types';
interface VaultSourceOptions {
    endpoint?: string;
    token?: string;
    path: string;
    namespace?: string;
    roleId?: string;
    secretId?: string;
}
export declare class VaultSource extends BaseConfigSource {
    readonly type = "vault";
    readonly priority: number;
    readonly name: string;
    private options;
    private client;
    private data;
    private loaded;
    constructor(name: string, priority: number, options: VaultSourceOptions);
    private initClient;
    private flattenData;
    load(): Promise<ConfigData>;
    get(key: string): Promise<ConfigValue | undefined>;
    set(key: string, value: ConfigValue): Promise<void>;
    delete(key: string): Promise<void>;
    listKeys(): Promise<string[]>;
}
export {};
