import { Command, Flags } from '@oclif/core'
import * as path from 'path'
import {
  loadConfig,
  generateSampleConfig,
  generateSampleSchema,
  validateCliConfig,
} from '../utils/AppConfigLoader'

export default class InitCommand extends Command {
  static description = 'Initialize a new ConfigFlow project in the current directory'

  static examples = [
    '<%= config.bin %> <%= command.id %>',
    '<%= config.bin %> <%= command.id %> --force',
  ]

  static flags = {
    force: Flags.boolean({ char: 'f', description: 'Overwrite existing config' }),
    output: Flags.string({ char: 'o', description: 'Output directory' }),
  }

  async run(): Promise<void> {
    const { flags } = await this.parse(InitCommand)

    const outputDir = path.resolve(flags.output || process.cwd())

    const existingConfigPath = path.join(outputDir, 'config-flow.yaml')

    const fs = await import('fs')
    if (!flags.force) {
      if (fs.existsSync(existingConfigPath)) {
        this.error(`Config already exists at ${existingConfigPath}. Use --force to overwrite.`)
      }
    }

    if (!fs.existsSync(outputDir)) {
      fs.mkdirSync(outputDir, { recursive: true })
    }

    const configPath = generateSampleConfig(outputDir)
    const schemaPath = generateSampleSchema(outputDir)

    const configDir = path.join(outputDir, '.config-flow')
    if (!fs.existsSync(configDir)) {
      fs.mkdirSync(configDir, { recursive: true })
    }

    const templatesDir = path.join(outputDir, 'templates')
    if (!fs.existsSync(templatesDir)) {
      fs.mkdirSync(templatesDir, { recursive: true })

      fs.writeFileSync(
        path.join(templatesDir, 'nginx.conf.hbs'),
        `server {
    listen {{ app.port }};
    server_name {{ app.host | default "localhost" }};

    {{#if app.debug}}
    # Debug mode enabled
    {{/if}}

    {{#each app.upstreams as |upstream|}}
    upstream {{upstream.name}} {
        {{#each upstream.servers as |server|}}
        server {{server.host}}:{{server.port}};
        {{/each}}
    }
    {{/each}}

    location / {
        proxy_pass http://{{app.upstreamName | default "app"}};
    }
}
`
      )

      fs.writeFileSync(
        path.join(templatesDir, 'application.properties.hbs'),
        `# Generated for environment: {{_meta.environment}} at {{_meta.renderedAt}}
server.port={{app.port}}
spring.application.name={{app.name}}
spring.datasource.url=jdbc:postgresql://{{database.host}}:{{database.port}}/{{database.name}}
spring.datasource.username={{database.username}}
spring.datasource.password={{database.password}}
{{#if rateLimit}}
# Rate limiting
rate-limit.max-requests={{rateLimit.maxRequests}}
rate-limit.window-ms={{rateLimit.windowMs}}
{{/if}}
`
      )

      fs.writeFileSync(
        path.join(templatesDir, 'docker-compose.override.yml.hbs'),
        `version: '3.8'
services:
  app:
    environment:
      NODE_ENV: {{app.environment}}
      DEBUG: {{app.debug}}
    ports:
      - "{{app.port}}:{{app.port}}"
    {{#if rateLimit}}
    deploy:
      resources:
        limits:
          cpus: '1.0'
          memory: 512M
    {{/if}}
`
      )
    }

    const config = loadConfig(configPath)
    const errors = validateCliConfig(config)
    if (errors.length > 0) {
      this.warn('Generated config has validation issues:')
      for (const err of errors) {
        this.warn(`  - ${err}`)
      }
    }

    this.log('✅ ConfigFlow project initialized!')
    this.log('')
    this.log(`📁 Config file:    ${configPath}`)
    this.log(`📋 Schema file:    ${schemaPath}`)
    this.log(`📦 Storage dir:    ${path.join(outputDir, '.config-flow')}`)
    this.log(`📝 Templates dir:  ${templatesDir}`)
    this.log('')
    this.log('Next steps:')
    this.log('  1. Edit config-flow.yaml with your environment configurations')
    this.log('  2. Update config-schema.json to define your validation rules')
    this.log('  3. Add templates to templates/ directory or use existing ones')
    this.log('  4. Run `config-flow env list` to see configured environments')
  }
}
