import { ConfigData } from '../types';
import { TemplateEngine } from './TemplateEngine';
export declare class GoTemplateEngine implements TemplateEngine {
    readonly name = "go-template";
    private handlebarsEngine;
    constructor();
    render(template: string, context: ConfigData): {
        content: string;
        success: boolean;
        error?: string;
    };
    private translateGoToHandlebars;
    private tokenize;
}
