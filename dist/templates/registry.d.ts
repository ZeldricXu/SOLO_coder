import type { NpmTemplateInfo, TemplateCacheEntry } from '../types.js';
export declare class TemplateRegistry {
    private cacheDir;
    private cacheManifestPath;
    private manifest;
    constructor();
    init(): Promise<void>;
    private loadManifest;
    private saveManifest;
    searchTemplates(keyword?: string): Promise<NpmTemplateInfo[]>;
    installTemplate(packageName: string, version?: string): Promise<TemplateCacheEntry>;
    getCachedTemplate(packageName: string): TemplateCacheEntry | null;
    getTemplatePath(packageName: string, version?: string): Promise<string | null>;
    clearCache(): Promise<void>;
    listCachedTemplates(): Promise<TemplateCacheEntry[]>;
    getCacheDir(): string;
}
export declare const templateRegistry: TemplateRegistry;
//# sourceMappingURL=registry.d.ts.map