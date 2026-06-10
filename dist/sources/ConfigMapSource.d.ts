import { BaseConfigSource } from './ConfigSource';
import { ConfigData, ConfigValue } from '../types';
interface ConfigMapSourceOptions {
    namespace?: string;
    name: string;
    kubeconfig?: string;
    context?: string;
    dataKey?: string;
}
export declare class ConfigMapSource extends BaseConfigSource {
    readonly type = "configmap";
    readonly priority: number;
    readonly name: string;
    private options;
    private k8sApi;
    private data;
    private loaded;
    constructor(name: string, priority: number, options: ConfigMapSourceOptions);
    private initClient;
    private parseConfigMapData;
    private parseEnvContent;
    private parseValue;
    private serializeValue;
    private flattenData;
    load(): Promise<ConfigData>;
    get(key: string): Promise<ConfigValue | undefined>;
    set(key: string, value: ConfigValue): Promise<void>;
    delete(key: string): Promise<void>;
    listKeys(): Promise<string[]>;
}
export {};
