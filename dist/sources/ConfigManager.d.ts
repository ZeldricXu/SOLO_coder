import { ConfigSource } from './ConfigSource';
import { ConfigData, ConfigValue, EnvironmentConfig } from '../types';
export declare class Environment {
    readonly name: string;
    readonly sources: ConfigSource[];
    readonly labels?: Record<string, string>;
    constructor(name: string, sources: ConfigSource[], labels?: Record<string, string>);
    loadAll(): Promise<ConfigData>;
    get(key: string): Promise<ConfigValue | undefined>;
    set(key: string, value: ConfigValue, sourceType?: string): Promise<void>;
    delete(key: string, sourceType?: string): Promise<void>;
    listKeys(): Promise<string[]>;
    private deepMerge;
    getSourceByType(type: string): ConfigSource | undefined;
    getHighestPrioritySource(): ConfigSource;
}
export declare class ConfigManager {
    private environments;
    addEnvironment(env: EnvironmentConfig): Environment;
    private createSource;
    getEnvironment(name: string): Environment | undefined;
    listEnvironments(): string[];
    loadAll(): Promise<Map<string, ConfigData>>;
}
