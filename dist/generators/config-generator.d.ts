import type { ProjectConfig } from '../types.js';
export declare class ConfigGenerator {
    private config;
    private targetDir;
    constructor(config: ProjectConfig);
    generateAll(): Promise<void>;
    generateTsConfig(): Promise<void>;
    private getBaseTsConfig;
    private getFrameworkTsConfig;
    private generateTsConfigNode;
    generateESLintConfig(): Promise<void>;
    private getESLintConfig;
    generatePrettierConfig(): Promise<void>;
    generateEditorConfig(): Promise<void>;
    generateGitIgnore(): Promise<void>;
    private getFrameworkGitIgnore;
    generatePackageJson(): Promise<void>;
}
//# sourceMappingURL=config-generator.d.ts.map