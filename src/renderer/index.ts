export * from './TemplateRenderer'
export { TemplateEngine } from './TemplateEngine'
export { HandlebarsEngine } from './HandlebarsEngine'
export { GoTemplateEngine } from './GoTemplateEngine'
export { Jinja2Engine } from './Jinja2Engine'

import { TemplateEngine } from './TemplateEngine'
import { HandlebarsEngine } from './HandlebarsEngine'
import { GoTemplateEngine } from './GoTemplateEngine'
import { Jinja2Engine } from './Jinja2Engine'

export function createTemplateEngine(name: string = 'handlebars'): TemplateEngine {
  switch (name) {
    case 'handlebars': return new HandlebarsEngine()
    case 'go-template': return new GoTemplateEngine()
    case 'jinja2': return new Jinja2Engine()
    default: throw new Error(`Unsupported template engine: ${name}. Supported: handlebars, go-template, jinja2`)
  }
}

export const SUPPORTED_ENGINES = ['handlebars', 'go-template', 'jinja2']
