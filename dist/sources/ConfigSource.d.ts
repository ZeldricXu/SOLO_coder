import { ConfigData, ConfigValue } from '../types';
export interface ConfigSource {
    readonly type: string;
    readonly priority: number;
    readonly name: string;
    load(): Promise<ConfigData>;
    get(key: string): Promise<ConfigValue | undefined>;
    set(key: string, value: ConfigValue): Promise<void>;
    delete(key: string): Promise<void>;
    listKeys(): Promise<string[]>;
    exists(key: string): Promise<boolean>;
}
export declare abstract class BaseConfigSource implements ConfigSource {
    abstract readonly type: string;
    abstract readonly priority: number;
    abstract readonly name: string;
    abstract load(): Promise<ConfigData>;
    abstract get(key: string): Promise<ConfigValue | undefined>;
    abstract set(key: string, value: ConfigValue): Promise<void>;
    abstract delete(key: string): Promise<void>;
    abstract listKeys(): Promise<string[]>;
    exists(key: string): Promise<boolean>;
    protected getNestedValue(data: ConfigData, path: string): ConfigValue | undefined;
    protected setNestedValue(data: ConfigData, path: string, value: ConfigValue): void;
}
