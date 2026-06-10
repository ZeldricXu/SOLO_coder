import { AppConfig, EnvironmentConfig, NotificationConfig } from '../types';
export interface CliConfig {
    projectRoot: string;
    storagePath: string;
    gitRepoPath: string;
    schemaPath: string;
    environments: EnvironmentConfig[];
    notifications?: NotificationConfig[];
    defaultOperator?: string;
}
export declare function findConfigFile(customPath?: string): string | null;
export declare function loadConfig(customPath?: string): CliConfig;
export declare function saveConfig(config: CliConfig, outputPath?: string): string;
export declare function generateSampleConfig(outputDir: string): string;
export declare function generateSampleSchema(outputDir: string): string;
export declare function validateCliConfig(config: CliConfig): string[];
export declare function configToAppConfig(config: CliConfig): AppConfig;
