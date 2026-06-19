import type { PackageManagerType } from '../types.js';
export interface CodeQualityOptions {
    fix?: boolean | undefined;
    staged?: boolean | undefined;
}
export interface LintResult {
    success: boolean;
    errorCount: number;
    warningCount: number;
    output: string;
}
export declare class CodeQualityChecker {
    private cwd;
    private pm;
    constructor(cwd: string, packageManager: PackageManagerType);
    lint(options?: CodeQualityOptions): Promise<LintResult>;
    format(options?: CodeQualityOptions): Promise<LintResult>;
    runAll(options?: CodeQualityOptions): Promise<boolean>;
    private lintStaged;
    private getStagedFiles;
    installPreCommitHook(): Promise<boolean>;
    private getPreCommitHookContent;
    private addHuskyConfig;
    uninstallPreCommitHook(): Promise<boolean>;
    isPreCommitHookInstalled(): boolean;
}
declare module '../package-manager.js' {
    interface PackageManager {
        runScriptWithOutput(script: string, args?: string[]): Promise<LintResult>;
        installDevDependency(packageName: string, version?: string): Promise<void>;
    }
}
//# sourceMappingURL=code-quality.d.ts.map