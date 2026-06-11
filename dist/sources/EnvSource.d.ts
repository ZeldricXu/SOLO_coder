import { BaseConfigSource } from './ConfigSource';
import { ConfigData, ConfigValue } from '../types';
interface EnvSourceOptions {
    filePath?: string;
    useProcessEnv?: boolean;
    prefix?: string;
    lowerCaseKeys?: boolean;
}
export declare class EnvSource extends BaseConfigSource {
    readonly type = "env";
    readonly priority: number;
    readonly name: string;
    private options;
    private data;
    private loaded;
    constructor(name: string, priority: number, options?: EnvSourceOptions);
    private parseValue;
    private normalizeKey;
    private flattenData;
    load(): Promise<ConfigData>;
    get(key: string): Promise<ConfigValue | undefined>;
    set(key: string, value: ConfigValue): Promise<void>;
    delete(key: string): Promise<void>;
    listKeys(): Promise<string[]>;
}
export {};
