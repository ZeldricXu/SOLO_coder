import { Command, Flags, Args } from '@oclif/core'
import { loadContext } from './env/list'
import { TemplateRenderer, RenderResult } from '../renderer/TemplateRenderer'
import * as fs from 'fs'
import * as path from 'path'
import chalk from 'chalk'

export default class RenderCommand extends Command {
  static description = 'Render configuration templates using environment context'
  static aliases = ['render:template', 'tpl']

  static args = {
    template: Args.string({ description: 'Template path or directory' }),
    environment: Args.string({ description: 'Environment to render (all if omitted)' }),
  }

  static flags = {
    config: Flags.string({ char: 'c', description: 'Path to config file' }),
    template: Flags.string({ char: 't', description: 'Template path (alternative to arg)' }),
    output: Flags.string({ char: 'o', description: 'Output path or directory', required: false }),
    templatesDir: Flags.string({ description: 'Directory containing additional partials' }),
    data: Flags.string({ char: 'd', description: 'Additional JSON context data', multiple: true }),
    stdin: Flags.boolean({ description: 'Read template from stdin' }),
    listHelpers: Flags.boolean({ description: 'List available helpers and exit' }),
    listPartials: Flags.boolean({ description: 'List registered partials and exit' }),
    dryRun: Flags.boolean({ char: 'n', description: 'Render to stdout only' }),
    json: Flags.boolean({ description: 'Output results as JSON' }),
    verbose: Flags.boolean({ char: 'v', description: 'Verbose output' }),
  }

  async run(): Promise<void> {
    const { args, flags } = await this.parse(RenderCommand)
    const ctx = await loadContext(flags.config)

    const renderer = new TemplateRenderer()

    if (flags.templatesDir) {
      const absDir = path.resolve(flags.templatesDir)
      if (fs.existsSync(absDir)) {
        const loaded = renderer.loadPartialsFromDirectory(absDir)
        if (flags.verbose && !flags.json) {
          this.log(`Loaded ${loaded.length} partials from ${absDir}`)
        }
      }
    }

    if (flags.listHelpers) {
      const helpers = renderer.getRegisteredHelpers()
      if (flags.json) {
        this.log(JSON.stringify(helpers, null, 2))
      } else {
        this.log('Available Handlebars helpers:')
        for (const h of helpers.sort()) this.log(`  - ${h}`)
      }
      return
    }

    if (flags.listPartials) {
      const partials = renderer.getRegisteredPartials()
      if (flags.json) {
        this.log(JSON.stringify(partials, null, 2))
      } else {
        if (partials.length === 0) this.log('No partials registered.')
        else {
          this.log('Registered partials:')
          for (const p of partials.sort()) this.log(`  - ${p}`)
        }
      }
      return
    }

    if (flags.stdin) {
      const templateStr = await this.readStdin()
      const environments = args.environment
        ? [args.environment]
        : ctx.configManager.listEnvironments()

      const results: { environment: string; content: string; success: boolean; error?: string }[] = []
      for (const envName of environments) {
        const env = ctx.configManager.getEnvironment(envName)
        if (!env) continue
        const context = await env.loadAll()
        const mergedContext = this.applyDataOverrides(context, flags.data || [])
        const result = renderer.renderString(templateStr, mergedContext, envName)
        results.push({ environment: envName, ...result })
      }

      if (flags.json) {
        this.log(JSON.stringify(results, null, 2))
      } else {
        for (const r of results) {
          if (environments.length > 1) this.log(`\n=== ${r.environment} ===`)
          if (r.success) this.log(r.content)
          else this.log(chalk.red(`Error: ${r.error}`))
        }
      }
      return
    }

    const templatePath = flags.template || args.template
    if (!templatePath) {
      this.error('Template path is required (--template or positional arg)')
    }

    const absTemplate = path.resolve(templatePath)
    const environments = args.environment
      ? [args.environment]
      : ctx.configManager.listEnvironments()

    const allResults: RenderResult[] = []

    for (const envName of environments) {
      const env = ctx.configManager.getEnvironment(envName)
      if (!env) {
        this.warn(`Environment not found: ${envName}, skipping`)
        continue
      }
      const context = await env.loadAll()
      const mergedContext = this.applyDataOverrides(context, flags.data || [])

      const stat = fs.existsSync(absTemplate) ? fs.statSync(absTemplate) : null

      if (stat && stat.isDirectory()) {
        const templateFiles = fs.readdirSync(absTemplate)
          .filter((f: any) => f.endsWith('.hbs') || f.endsWith('.handlebars') || f.endsWith('.tpl'))

        for (const tplFile of templateFiles) {
          const tplFullPath = path.join(absTemplate, tplFile)
          const outputName = tplFile.replace(/\.(hbs|handlebars|tpl)$/, '')

          let outputFile: string
          if (flags.output) {
            outputFile = path.resolve(flags.output, envName, outputName)
          } else {
            outputFile = path.resolve(absTemplate, '..', 'output', envName, outputName)
          }

          if (flags.dryRun) {
            const content = fs.readFileSync(tplFullPath, 'utf-8')
            const result = renderer.renderString(content, mergedContext, envName)
            allResults.push({
              outputPath: outputFile,
              content: result.content,
              renderedAt: Date.now(),
              environment: envName,
              templatePath: tplFullPath,
              success: result.success,
              error: result.error,
            })
          } else {
            const result = renderer.render({
              templatePath: tplFullPath,
              outputPath: outputFile,
              context: mergedContext,
              environment: envName,
            })
            allResults.push(result)
          }
        }
      } else {
        let outputFile = flags.output
        if (!outputFile) {
          const baseName = path.basename(absTemplate).replace(/\.(hbs|handlebars|tpl)$/, '')
          outputFile = path.resolve(absTemplate, '..', 'output', envName, baseName)
        } else if (environments.length > 1 && flags.output && fs.existsSync(flags.output) && fs.statSync(flags.output).isDirectory()) {
          const baseName = path.basename(absTemplate).replace(/\.(hbs|handlebars|tpl)$/, '')
          outputFile = path.resolve(flags.output, envName, baseName)
        }

        if (flags.dryRun) {
          const content = fs.existsSync(absTemplate) ? fs.readFileSync(absTemplate, 'utf-8') : absTemplate
          const result = renderer.renderString(content, mergedContext, envName)
          allResults.push({
            outputPath: outputFile,
            content: result.content,
            renderedAt: Date.now(),
            environment: envName,
            templatePath: absTemplate,
            success: result.success,
            error: result.error,
          })
        } else {
          const result = renderer.render({
            templatePath: absTemplate,
            outputPath: outputFile,
            context: mergedContext,
            environment: envName,
          })
          allResults.push(result)
        }
      }
    }

    if (flags.json) {
      this.log(JSON.stringify(allResults, null, 2))
      return
    }

    const success = allResults.filter((r: any) => r.success).length
    const failed = allResults.filter((r: any) => !r.success).length
    this.log(`\nRender complete: ${chalk.green(success)} success, ${chalk.red(failed)} failed`)

    for (const r of allResults) {
      const icon = r.success ? chalk.green('✓') : chalk.red('✗')
      if (flags.dryRun || flags.verbose) {
        this.log(`\n${icon} ${r.templatePath} → ${r.outputPath} [${r.environment}]`)
        if (!r.success) this.log(`  Error: ${r.error}`)
        else if (flags.dryRun) {
          this.log('─'.repeat(50))
          this.log(r.content)
          this.log('─'.repeat(50))
        }
      } else {
        this.log(`  ${icon} ${path.basename(r.templatePath)} → ${r.outputPath} [${r.environment}]`)
        if (!r.success) this.log(`    Error: ${r.error}`)
      }
    }

    if (failed > 0) this.exit(1)
  }

  private async readStdin(): Promise<string> {
    return new Promise((resolve, reject) => {
      let data = ''
      process.stdin.setEncoding('utf8')
      process.stdin.on('data', (chunk) => (data += chunk))
      process.stdin.on('end', () => resolve(data))
      process.stdin.on('error', reject)
    })
  }

  private applyDataOverrides(context: Record<string, unknown>, dataFlags: string[]) {
    const merged: Record<string, unknown> = { ...context }

    for (const dataFlag of dataFlags) {
      try {
        if (fs.existsSync(path.resolve(dataFlag))) {
          const fileContent = fs.readFileSync(path.resolve(dataFlag), 'utf-8')
          const parsed = JSON.parse(fileContent)
          Object.assign(merged, parsed)
        } else if (dataFlag.includes('=')) {
          const eq = dataFlag.indexOf('=')
          const key = dataFlag.slice(0, eq)
          const value = dataFlag.slice(eq + 1)
          this.setByPath(merged, key, this.parseValue(value))
        } else {
          const parsed = JSON.parse(dataFlag)
          Object.assign(merged, parsed)
        }
      } catch (error) {
        this.warn(`Failed to apply data override "${dataFlag}": ${(error as Error).message}`)
      }
    }

    return merged as any
  }

  private setByPath(obj: Record<string, unknown>, pathStr: string, value: unknown): void {
    const parts = pathStr.split('.')
    let current: Record<string, unknown> = obj
    for (let i = 0; i < parts.length - 1; i++) {
      if (!current[parts[i]] || typeof current[parts[i]] !== 'object' || Array.isArray(current[parts[i]])) {
        current[parts[i]] = {}
      }
      current = current[parts[i]] as Record<string, unknown>
    }
    current[parts[parts.length - 1]] = value
  }

  private parseValue(v: string): unknown {
    if (v === 'true') return true
    if (v === 'false') return false
    if (v === 'null') return null
    if (v === '') return ''
    const num = Number(v)
    if (!isNaN(num) && v.trim() !== '') return num
    try { return JSON.parse(v) } catch { return v }
  }
}
