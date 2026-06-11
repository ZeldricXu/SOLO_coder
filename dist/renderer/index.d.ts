export * from './TemplateRenderer';
export { TemplateEngine } from './TemplateEngine';
export { HandlebarsEngine } from './HandlebarsEngine';
export { GoTemplateEngine } from './GoTemplateEngine';
export { Jinja2Engine } from './Jinja2Engine';
import { TemplateEngine } from './TemplateEngine';
export declare function createTemplateEngine(name?: string): TemplateEngine;
export declare const SUPPORTED_ENGINES: string[];
