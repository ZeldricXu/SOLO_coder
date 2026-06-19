import type { ProjectConfig, TemplateConfig } from '../types.js';
export declare class TemplateEngine {
    private config;
    constructor(config: ProjectConfig);
    render(): Promise<TemplateConfig>;
    private loadTemplate;
    private loadCustomTemplate;
    private cloneRemoteTemplate;
    private renderTemplateFiles;
    private renderSingleFile;
    private getBuiltinTemplateContent;
    private getFallbackTemplate;
    private getTemplateData;
    private toCamelCase;
    private toPascalCase;
    renderGlob(pattern: string, data: Record<string, unknown>): Promise<void>;
}
//# sourceMappingURL=engine.d.ts.map