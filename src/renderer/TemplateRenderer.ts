import * as fs from 'fs'
import * as path from 'path'
import * as Handlebars from 'handlebars'
import { ConfigData, RenderTemplateConfig } from '../types'

export interface RenderResult {
  outputPath: string
  content: string
  renderedAt: number
  environment: string
  templatePath: string
  success: boolean
  error?: string
}

export interface BatchRenderConfig {
  templates: RenderTemplateConfig[]
  outputDir?: string
}

export class TemplateRenderer {
  private handlebars: typeof Handlebars
  private registeredPartials: Map<string, string> = new Map()
  private registeredHelpers: Map<string, (...args: unknown[]) => unknown> = new Map()

  constructor() {
    this.handlebars = Handlebars.create()
    this.registerBuiltinHelpers()
  }

  private registerBuiltinHelpers(): void {
    this.registerHelper('toUpperCase', (str: unknown) => String(str ?? '').toUpperCase())
    this.registerHelper('toLowerCase', (str: unknown) => String(str ?? '').toLowerCase())
    this.registerHelper('capitalize', (str: unknown) => {
      const s = String(str ?? '')
      return s ? s[0].toUpperCase() + s.slice(1) : ''
    })
    this.registerHelper('trim', (str: unknown) => String(str ?? '').trim())
    this.registerHelper('length', (obj: unknown) => {
      if (Array.isArray(obj)) return obj.length
      if (typeof obj === 'string') return obj.length
      if (typeof obj === 'object' && obj !== null) return Object.keys(obj).length
      return 0
    })
    this.registerHelper('join', (arr: unknown, separator: unknown = ',') => {
      return Array.isArray(arr) ? arr.join(String(separator)) : ''
    })
    this.registerHelper('json', (obj: unknown, pretty: unknown = false) => {
      return pretty ? JSON.stringify(obj, null, 2) : JSON.stringify(obj)
    })
    this.registerHelper('default', (value: unknown, defaultValue: unknown) => {
      return value === undefined || value === null || value === '' ? defaultValue : value
    })
    this.registerHelper('eq', (a: unknown, b: unknown) => a === b)
    this.registerHelper('ne', (a: unknown, b: unknown) => a !== b)
    this.registerHelper('gt', (a: unknown, b: unknown) => Number(a) > Number(b))
    this.registerHelper('gte', (a: unknown, b: unknown) => Number(a) >= Number(b))
    this.registerHelper('lt', (a: unknown, b: unknown) => Number(a) < Number(b))
    this.registerHelper('lte', (a: unknown, b: unknown) => Number(a) <= Number(b))
    this.registerHelper('and', (...args: unknown[]) => args.slice(0, -1).every(Boolean))
    this.registerHelper('or', (...args: unknown[]) => args.slice(0, -1).some(Boolean))
    this.registerHelper('not', (a: unknown) => !a)
    this.registerHelper('date', (format: unknown = 'YYYY-MM-DD HH:mm:ss') => {
      const now = new Date()
      return String(format)
        .replace('YYYY', String(now.getFullYear()))
        .replace('MM', String(now.getMonth() + 1).padStart(2, '0'))
        .replace('DD', String(now.getDate()).padStart(2, '0'))
        .replace('HH', String(now.getHours()).padStart(2, '0'))
        .replace('mm', String(now.getMinutes()).padStart(2, '0'))
        .replace('ss', String(now.getSeconds()).padStart(2, '0'))
    })
    this.registerHelper('sanitizeYaml', (str: unknown) => {
      if (str === undefined || str === null) return ''
      const s = String(str)
      if (/[:#&*!|>'"%@`[\]{}\n]/.test(s)) {
        return `"${s.replace(/"/g, '\\"')}"`
      }
      return s
    })
    this.registerHelper('indent', (str: unknown, spaces: unknown = 2) => {
      if (!str) return ''
      const indent = ' '.repeat(Number(spaces) || 2)
      return String(str).split('\n').map((l) => indent + l).join('\n')
    })
  }

  registerHelper(name: string, fn: (...args: unknown[]) => unknown): void {
    this.handlebars.registerHelper(name, fn)
    this.registeredHelpers.set(name, fn)
  }

  registerPartial(name: string, template: string): void {
    this.handlebars.registerPartial(name, template)
    this.registeredPartials.set(name, template)
  }

  registerPartialFromFile(name: string, filePath: string): void {
    const content = fs.readFileSync(path.resolve(filePath), 'utf-8')
    this.registerPartial(name, content)
  }

  loadPartialsFromDirectory(dirPath: string): string[] {
    const registered: string[] = []
    const absPath = path.resolve(dirPath)

    if (!fs.existsSync(absPath) || !fs.statSync(absPath).isDirectory()) {
      return registered
    }

    const files = fs.readdirSync(absPath)
    for (const file of files) {
      const filePath = path.join(absPath, file)
      const stat = fs.statSync(filePath)

      if (stat.isDirectory()) {
        const sub = this.loadPartialsFromDirectory(filePath)
        registered.push(...sub)
      } else if (stat.isFile()) {
        const name = path.basename(file, path.extname(file))
        try {
          this.registerPartialFromFile(name, filePath)
          registered.push(name)
        } catch (error) {
          console.warn(`Failed to register partial ${file}:`, error)
        }
      }
    }

    return registered
  }

  render(config: RenderTemplateConfig): RenderResult {
    const start = Date.now()
    const templatePath = path.resolve(config.templatePath)

    try {
      if (!fs.existsSync(templatePath)) {
        throw new Error(`Template not found: ${templatePath}`)
      }

      const templateContent = fs.readFileSync(templatePath, 'utf-8')
      const compiled = this.handlebars.compile(templateContent, {
        strict: false,
        noEscape: true,
      })

      const fullContext: ConfigData = {
        ...config.context,
        _meta: {
          environment: config.environment,
          renderedAt: new Date().toISOString(),
          template: path.basename(templatePath),
        },
      }

      const content = compiled(fullContext)

      const outputPath = path.resolve(config.outputPath)
      const outputDir = path.dirname(outputPath)
      if (!fs.existsSync(outputDir)) {
        fs.mkdirSync(outputDir, { recursive: true })
      }
      fs.writeFileSync(outputPath, content)

      return {
        outputPath,
        content,
        renderedAt: start,
        environment: config.environment,
        templatePath,
        success: true,
      }
    } catch (error) {
      return {
        outputPath: config.outputPath,
        content: '',
        renderedAt: start,
        environment: config.environment,
        templatePath,
        success: false,
        error: (error as Error).message,
      }
    }
  }

  renderString(template: string, context: ConfigData, environment = 'default'): { content: string; success: boolean; error?: string } {
    try {
      const compiled = this.handlebars.compile(template, {
        strict: false,
        noEscape: true,
      })

      const fullContext: ConfigData = {
        ...context,
        _meta: {
          environment,
          renderedAt: new Date().toISOString(),
        },
      }

      return {
        content: compiled(fullContext),
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

  renderBatch(config: BatchRenderConfig): RenderResult[] {
    const results: RenderResult[] = []

    for (const template of config.templates) {
      const renderConfig: RenderTemplateConfig = {
        ...template,
        outputPath: config.outputDir
          ? path.join(config.outputDir, template.environment, path.basename(template.outputPath))
          : template.outputPath,
      }
      results.push(this.render(renderConfig))
    }

    return results
  }

  renderForEnvironments(
    templatePath: string,
    environments: { name: string; context: ConfigData; outputPath: string }[]
  ): RenderResult[] {
    return environments.map((env) =>
      this.render({
        templatePath,
        outputPath: env.outputPath,
        context: env.context,
        environment: env.name,
      })
    )
  }

  getRegisteredHelpers(): string[] {
    return Array.from(this.registeredHelpers.keys())
  }

  getRegisteredPartials(): string[] {
    return Array.from(this.registeredPartials.keys())
  }
}
