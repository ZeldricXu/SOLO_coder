import { Command, Flags, Args } from '@oclif/core'
import { loadContext } from './env/list'
import { SchemaValidator, SchemaConfig } from '../schemas/SchemaValidator'
import { formatValidationReport } from '../utils/formatters'
import { HistoryStorage } from '../storage/HistoryStorage'
import chalk from 'chalk'
import * as fs from 'fs'

export default class ValidateCommand extends Command {
  static description = 'Validate environment configuration against schema'
  static aliases = ['validate:check', 'check']

  static args = {
    environment: Args.string({ description: 'Environment to validate (all if omitted)' }),
  }

  static flags = {
    config: Flags.string({ char: 'c', description: 'Path to config file' }),
    schema: Flags.string({ char: 's', description: 'Path to schema JSON file' }),
    json: Flags.boolean({ description: 'Output as JSON' }),
    noHistory: Flags.boolean({ description: 'Do not record validation history' }),
    strict: Flags.boolean({ description: 'Exit with error code on validation failure' }),
  }

  async run(): Promise<void> {
    const { args, flags } = await this.parse(ValidateCommand)
    const ctx = await loadContext(flags.config)

    const schemaPath = flags.schema || ctx.config.schemaPath
    if (!fs.existsSync(schemaPath)) {
      this.error(`Schema file not found: ${schemaPath}. Run 'config-flow init' to generate a sample.`)
    }

    const rawSchema = JSON.parse(fs.readFileSync(schemaPath, 'utf-8')) as SchemaConfig
    const validator = new SchemaValidator(rawSchema)

    const environments = args.environment
      ? [args.environment]
      : ctx.configManager.listEnvironments()

    const allReports = []
    let anyInvalid = false

    const storage = flags.noHistory ? null : new HistoryStorage(ctx.config.storagePath)

    for (const envName of environments) {
      const env = ctx.configManager.getEnvironment(envName)
      if (!env) {
        this.warn(`Environment not found: ${envName}, skipping`)
        continue
      }

      try {
        const data = await env.loadAll()
        const report = validator.validate(data, envName)

        if (storage) {
          await storage.recordValidation(report)
        }

        allReports.push(report)

        if (!flags.json) {
          this.log(formatValidationReport(report))
          this.log('')
        }

        if (!report.valid) anyInvalid = true
      } catch (error) {
        this.error(`Failed to validate ${envName}: ${(error as Error).message}`)
      }
    }

    if (storage) storage.close()

    if (flags.json) {
      this.log(JSON.stringify(allReports, null, 2))
    }

    const validCount = allReports.filter((r) => r.valid).length
    const totalCount = allReports.length

    if (!flags.json) {
      this.log(`Validation Summary: ${chalk.green(validCount)} valid / ${totalCount} total`)
    }

    if (flags.strict && anyInvalid) {
      this.exit(1)
    }
  }
}
