import * as Handlebars from 'handlebars'
import { ConfigData } from '../types'
import { TemplateEngine } from './TemplateEngine'

export class HandlebarsEngine implements TemplateEngine {
  readonly name = 'handlebars'
  private handlebars: typeof Handlebars

  constructor() {
    this.handlebars = Handlebars.create()
    this.registerBuiltinHelpers()
  }

  private registerBuiltinHelpers(): void {
    this.handlebars.registerHelper('toUpperCase', (str: unknown) => String(str ?? '').toUpperCase())
    this.handlebars.registerHelper('toLowerCase', (str: unknown) => String(str ?? '').toLowerCase())
    this.handlebars.registerHelper('capitalize', (str: unknown) => {
      const s = String(str ?? '')
      return s ? s[0].toUpperCase() + s.slice(1) : ''
    })
    this.handlebars.registerHelper('trim', (str: unknown) => String(str ?? '').trim())
    this.handlebars.registerHelper('length', (obj: unknown) => {
      if (Array.isArray(obj)) return obj.length
      if (typeof obj === 'string') return obj.length
      if (typeof obj === 'object' && obj !== null) return Object.keys(obj).length
      return 0
    })
    this.handlebars.registerHelper('join', (arr: unknown, separator: unknown = ',') => {
      return Array.isArray(arr) ? arr.join(String(separator)) : ''
    })
    this.handlebars.registerHelper('json', (obj: unknown, pretty: unknown = false) => {
      return pretty ? JSON.stringify(obj, null, 2) : JSON.stringify(obj)
    })
    this.handlebars.registerHelper('default', (value: unknown, defaultValue: unknown) => {
      return value === undefined || value === null || value === '' ? defaultValue : value
    })
    this.handlebars.registerHelper('eq', (a: unknown, b: unknown) => a === b)
    this.handlebars.registerHelper('ne', (a: unknown, b: unknown) => a !== b)
    this.handlebars.registerHelper('gt', (a: unknown, b: unknown) => Number(a) > Number(b))
    this.handlebars.registerHelper('gte', (a: unknown, b: unknown) => Number(a) >= Number(b))
    this.handlebars.registerHelper('lt', (a: unknown, b: unknown) => Number(a) < Number(b))
    this.handlebars.registerHelper('lte', (a: unknown, b: unknown) => Number(a) <= Number(b))
    this.handlebars.registerHelper('and', (...args: unknown[]) => args.slice(0, -1).every(Boolean))
    this.handlebars.registerHelper('or', (...args: unknown[]) => args.slice(0, -1).some(Boolean))
    this.handlebars.registerHelper('not', (a: unknown) => !a)
    this.handlebars.registerHelper('date', (format: unknown = 'YYYY-MM-DD HH:mm:ss') => {
      const now = new Date()
      return String(format)
        .replace('YYYY', String(now.getFullYear()))
        .replace('MM', String(now.getMonth() + 1).padStart(2, '0'))
        .replace('DD', String(now.getDate()).padStart(2, '0'))
        .replace('HH', String(now.getHours()).padStart(2, '0'))
        .replace('mm', String(now.getMinutes()).padStart(2, '0'))
        .replace('ss', String(now.getSeconds()).padStart(2, '0'))
    })
    this.handlebars.registerHelper('sanitizeYaml', (str: unknown) => {
      if (str === undefined || str === null) return ''
      const s = String(str)
      if (/[:#&*!|>'"%@`[\]{}\n]/.test(s)) {
        return `"${s.replace(/"/g, '\\"')}"`
      }
      return s
    })
    this.handlebars.registerHelper('indent', (str: unknown, spaces: unknown = 2) => {
      if (!str) return ''
      const indent = ' '.repeat(Number(spaces) || 2)
      return String(str).split('\n').map((l) => indent + l).join('\n')
    })
  }

  render(template: string, context: ConfigData): { content: string; success: boolean; error?: string } {
    try {
      const compiled = this.handlebars.compile(template, {
        strict: false,
        noEscape: true,
      })
      return {
        content: compiled(context),
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

  getHandlebarsInstance(): typeof Handlebars {
    return this.handlebars
  }
}
