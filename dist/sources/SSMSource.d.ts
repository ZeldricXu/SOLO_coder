import { BaseConfigSource } from './ConfigSource';
import { ConfigData, ConfigValue } from '../types';
interface SSMSourceOptions {
    region?: string;
    pathPrefix: string;
    withDecryption?: boolean;
    recursive?: boolean;
    accessKeyId?: string;
    secretAccessKey?: string;
}
export declare class SSMSource extends BaseConfigSource {
    readonly type = "ssm";
    readonly priority: number;
    readonly name: string;
    private options;
    private client;
    private data;
    private loaded;
    constructor(name: string, priority: number, options: SSMSourceOptions);
    private initClient;
    private stripPrefix;
    private normalizeKey;
    private denormalizeKey;
    private convertValue;
    private valueToString;
    load(): Promise<ConfigData>;
    get(key: string): Promise<ConfigValue | undefined>;
    set(key: string, value: ConfigValue): Promise<void>;
    delete(key: string): Promise<void>;
    listKeys(): Promise<string[]>;
}
export {};
