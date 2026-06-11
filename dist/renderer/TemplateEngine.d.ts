import { ConfigData } from '../types';
export interface TemplateEngine {
    readonly name: string;
    render(template: string, context: ConfigData): {
        content: string;
        success: boolean;
        error?: string;
    };
}
