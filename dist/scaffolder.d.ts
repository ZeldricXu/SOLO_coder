import type { ProjectConfig } from './types.js';
export declare class Scaffolder {
    private config;
    private templateConfig;
    constructor(config: ProjectConfig);
    run(): Promise<boolean>;
    private prepareTargetDir;
    private updatePackageJson;
    private sortObjectKeys;
    private runInitialBuild;
    private saveUserPreferences;
    private printSuccessMessage;
    private getPmCommand;
}
//# sourceMappingURL=scaffolder.d.ts.map