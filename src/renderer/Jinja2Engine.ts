import { ConfigData } from '../types'
import { TemplateEngine } from './TemplateEngine'

export class Jinja2Engine implements TemplateEngine {
  readonly name = 'jinja2'
  private env: any

  constructor() {
    try {
      const nunjucks = require('nunjucks')
      const loader = new nunjucks.PrecompiledLoader({})
      this.env = new nunjucks.Environment(loader, {
        autoescape: false,
        throwOnUndefined: false,
      })
    } catch {
      throw new Error('nunjucks package is required for the jinja2 engine. Install it with: npm install nunjucks')
    }
  }

  render(template: string, context: ConfigData): { content: string; success: boolean; error?: string } {
    try {
      const content = this.env.renderString(template, context)
      return {
        content,
        success: true,
      }
    } catch (error) {
      return {
        content: '',
        success: false,
        error: (error as Error).message,
      }
    }
  }
}
