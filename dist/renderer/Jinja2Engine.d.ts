import { ConfigData } from '../types';
import { TemplateEngine } from './TemplateEngine';
export declare class Jinja2Engine implements TemplateEngine {
    readonly name = "jinja2";
    private env;
    constructor();
    render(template: string, context: ConfigData): {
        content: string;
        success: boolean;
        error?: string;
    };
}
