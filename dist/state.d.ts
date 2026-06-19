import type { UserPreferences, TemplateInfo, FrameworkType, PackageManagerType, CiProviderType } from './types.js';
export declare class GlobalState {
    private preferences;
    private initialized;
    constructor();
    init(): Promise<void>;
    private loadPreferences;
    private savePreferences;
    getPreferences(): UserPreferences;
    setFramework(framework: FrameworkType): Promise<void>;
    setPackageManager(pm: PackageManagerType): Promise<void>;
    setCiProvider(provider: CiProviderType): Promise<void>;
    setAuthor(author: string): Promise<void>;
    setUseDocker(useDocker: boolean): Promise<void>;
    setUsePreCommitHook(use: boolean): Promise<void>;
    setTemplateVersion(version: string): Promise<void>;
    updateLastCheckTime(): Promise<void>;
    checkForUpdates(force?: boolean): Promise<TemplateInfo | null>;
    updateTemplates(): Promise<boolean>;
    getTemplateCacheDir(): string;
    getConfigDir(): string;
    clearCache(): Promise<void>;
}
export declare const globalState: GlobalState;
//# sourceMappingURL=state.d.ts.map