import type { ProjectConfig, CiConfig } from '../types.js';
export declare class CiGenerator {
    private config;
    private ciConfig;
    private targetDir;
    constructor(config: ProjectConfig, ciConfig?: Partial<CiConfig>);
    generate(): Promise<void>;
    private buildPipeline;
    private buildLintStage;
    private buildTestStage;
    private buildBuildStage;
    private buildServices;
    private buildEnv;
    private getDatabaseSetupSteps;
    private getTestEnv;
    private needsDatabase;
    private getInstallCommand;
    private getScriptCommand;
}
//# sourceMappingURL=ci-generator.d.ts.map