import { ConfigData, RenderTemplateConfig } from '../types';
export interface RenderResult {
    outputPath: string;
    content: string;
    renderedAt: number;
    environment: string;
    templatePath: string;
    success: boolean;
    error?: string;
}
export interface BatchRenderConfig {
    templates: RenderTemplateConfig[];
    outputDir?: string;
}
export declare class TemplateRenderer {
    private handlebars;
    private registeredPartials;
    private registeredHelpers;
    constructor();
    private registerBuiltinHelpers;
    registerHelper(name: string, fn: (...args: unknown[]) => unknown): void;
    registerPartial(name: string, template: string): void;
    registerPartialFromFile(name: string, filePath: string): void;
    loadPartialsFromDirectory(dirPath: string): string[];
    render(config: RenderTemplateConfig): RenderResult;
    renderString(template: string, context: ConfigData, environment?: string): {
        content: string;
        success: boolean;
        error?: string;
    };
    renderBatch(config: BatchRenderConfig): RenderResult[];
    renderForEnvironments(templatePath: string, environments: {
        name: string;
        context: ConfigData;
        outputPath: string;
    }[]): RenderResult[];
    getRegisteredHelpers(): string[];
    getRegisteredPartials(): string[];
}
