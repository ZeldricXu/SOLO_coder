import type { PackageManagerType, ProjectConfig } from './types.js';
export declare function detectPackageManagers(): PackageManagerType[];
export declare function getFastestPackageManager(): PackageManagerType;
export declare class PackageManager {
    private pm;
    cwd: string;
    constructor(pm: PackageManagerType, cwd: string);
    installDependencies(): Promise<void>;
    runScript(script: string, args?: string[]): Promise<void>;
    getType(): PackageManagerType;
}
export declare function initGitRepo(config: ProjectConfig): Promise<void>;
export declare function isGitAvailable(): boolean;
//# sourceMappingURL=package-manager.d.ts.map