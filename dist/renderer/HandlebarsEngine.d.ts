import * as Handlebars from 'handlebars';
import { ConfigData } from '../types';
import { TemplateEngine } from './TemplateEngine';
export declare class HandlebarsEngine implements TemplateEngine {
    readonly name = "handlebars";
    private handlebars;
    constructor();
    private registerBuiltinHelpers;
    render(template: string, context: ConfigData): {
        content: string;
        success: boolean;
        error?: string;
    };
    getHandlebarsInstance(): typeof Handlebars;
}
