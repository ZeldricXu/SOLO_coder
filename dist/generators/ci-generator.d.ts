import type { ProjectConfig, CiConfig } from '../types.js';
export declare class CiGenerator {
    private config;
    private ciConfig;
    private targetDir;
    constructor(config: ProjectConfig, ciConfig?: Partial<CiConfig>);
    generate(): Promise<void>;
    private generateGitHubActions;
    private getGitHubWorkflow;
    private getServices;
    private getDatabaseSetupSteps;
    private getTestEnv;
    private generateGitLabCI;
    private getGitLabCIConfig;
    private getGitLabServices;
    private needsDatabase;
    private getInstallCommand;
    private getScriptCommand;
}
//# sourceMappingURL=ci-generator.d.ts.map