import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'
import { TemplateRenderer } from '../../renderer/TemplateRenderer'
import { ConfigData } from '../../types'
import { createTempDir, removeTempDir, writeTemplate, readOutputFile } from '../factories/TestHelper'
import {
  createDevConfig,
  createProdConfig,
  createNginxTemplate,
  createDockerComposeTemplate,
  createAppPropertiesTemplate,
} from '../factories/TestDataFactory'

describe('TemplateRenderer', () => {
  let renderer: TemplateRenderer
  let tempDir: string

  beforeEach(() => {
    renderer = new TemplateRenderer()
    tempDir = createTempDir('template-test-')
  })

  afterEach(() => {
    removeTempDir(tempDir)
  })

  describe('normal path - string rendering', () => {
    it('should render simple template with context', () => {
      const result = renderer.renderString('Hello {{name}}!', { name: 'World' })
      expect(result.success).toBe(true)
      expect(result.content).toBe('Hello World!')
    })

    it('should render nested object values', () => {
      const result = renderer.renderString('{{app.name}}:{{app.port}}', {
        app: { name: 'my-service', port: 3000 },
      })
      expect(result.success).toBe(true)
      expect(result.content).toBe('my-service:3000')
    })

    it('should render boolean values', () => {
      const result = renderer.renderString('debug={{app.debug}}', {
        app: { debug: true },
      })
      expect(result.success).toBe(true)
      expect(result.content).toBe('debug=true')
    })

    it('should render numeric values', () => {
      const result = renderer.renderString('port={{app.port}}', {
        app: { port: 8080 },
      })
      expect(result.success).toBe(true)
      expect(result.content).toBe('port=8080')
    })

    it('should render with conditional blocks (if)', () => {
      const template = '{{#if app.debug}}debug-mode{{/if}}'
      const debugResult = renderer.renderString(template, { app: { debug: true } })
      const prodResult = renderer.renderString(template, { app: { debug: false } })

      expect(debugResult.content).toBe('debug-mode')
      expect(prodResult.content).toBe('')
    })

    it('should render with each loops', () => {
      const template = '{{#each items}}{{this}} {{/each}}'
      const result = renderer.renderString(template, { items: ['a', 'b', 'c'] })

      expect(result.success).toBe(true)
      expect(result.content).toBe('a b c ')
    })
  })

  describe('normal path - file rendering', () => {
    it('should render nginx template with prod config', () => {
      const templatePath = writeTemplate(tempDir, 'nginx.conf.hbs', createNginxTemplate())
      const outputPath = path.join(tempDir, 'nginx.conf')
      const prodConfig = createProdConfig()

      const result = renderer.render({
        templatePath,
        outputPath,
        context: prodConfig,
        environment: 'prod',
      })

      expect(result.success).toBe(true)
      expect(result.outputPath).toBe(outputPath)
      expect(fs.existsSync(outputPath)).toBe(true)

      const content = readOutputFile(outputPath)
      expect(content).toContain('upstream my-service')
      expect(content).toContain('server 127.0.0.1:8080')
      expect(content).toContain('proxy_pass')
      expect(content).toContain('server_name my-service.example.com')
    })

    it('should render docker-compose template', () => {
      const templatePath = writeTemplate(tempDir, 'docker-compose.hbs', createDockerComposeTemplate())
      const outputPath = path.join(tempDir, 'docker-compose.override.yml')
      const devConfig = createDevConfig()

      const result = renderer.render({
        templatePath,
        outputPath,
        context: devConfig,
        environment: 'dev',
      })

      expect(result.success).toBe(true)
      const content = readOutputFile(outputPath)
      expect(content).toContain('my-service')
      expect(content).toContain('3000')
      expect(content).toContain('localhost')
    })

    it('should render application.properties template', () => {
      const templatePath = writeTemplate(tempDir, 'app.properties.hbs', createAppPropertiesTemplate())
      const outputPath = path.join(tempDir, 'application.properties')
      const stagingConfig = {
        ...createDevConfig(),
        logLevel: 'info',
      }

      const result = renderer.render({
        templatePath,
        outputPath,
        context: stagingConfig,
        environment: 'staging',
      })

      expect(result.success).toBe(true)
      const content = readOutputFile(outputPath)
      expect(content).toContain('app.name=my-service')
      expect(content).toContain('db.host=localhost')
    })

    it('should include _meta in rendered output', () => {
      const templatePath = writeTemplate(tempDir, 'meta.hbs', 'env={{_meta.environment}}')
      const outputPath = path.join(tempDir, 'meta.txt')

      renderer.render({
        templatePath,
        outputPath,
        context: {},
        environment: 'prod',
      })

      const content = readOutputFile(outputPath)
      expect(content).toContain('env=prod')
    })

    it('should create output directories if they do not exist', () => {
      const templatePath = writeTemplate(tempDir, 'test.hbs', 'hello')
      const outputPath = path.join(tempDir, 'sub', 'dir', 'output.txt')

      const result = renderer.render({
        templatePath,
        outputPath,
        context: {},
        environment: 'test',
      })

      expect(result.success).toBe(true)
      expect(fs.existsSync(outputPath)).toBe(true)
    })
  })

  describe('normal path - batch rendering', () => {
    it('should render batch for multiple environments', () => {
      const templatePath = writeTemplate(tempDir, 'app.hbs', 'name={{app.name}} port={{app.port}}')

      const results = renderer.renderForEnvironments(
        templatePath,
        [
          { name: 'dev', context: createDevConfig(), outputPath: path.join(tempDir, 'dev.conf') },
          { name: 'prod', context: createProdConfig(), outputPath: path.join(tempDir, 'prod.conf') },
        ]
      )

      expect(results).toHaveLength(2)
      expect(results[0].success).toBe(true)
      expect(results[1].success).toBe(true)

      const devContent = readOutputFile(path.join(tempDir, 'dev.conf'))
      const prodContent = readOutputFile(path.join(tempDir, 'prod.conf'))

      expect(devContent).toContain('port=3000')
      expect(prodContent).toContain('port=8080')
    })
  })

  describe('normal path - custom helpers', () => {
    it('should use toUpperCase helper', () => {
      const result = renderer.renderString('{{toUpperCase name}}', { name: 'hello' })
      expect(result.content).toBe('HELLO')
    })

    it('should use toLowerCase helper', () => {
      const result = renderer.renderString('{{toLowerCase name}}', { name: 'HELLO' })
      expect(result.content).toBe('hello')
    })

    it('should use capitalize helper', () => {
      const result = renderer.renderString('{{capitalize name}}', { name: 'hello' })
      expect(result.content).toBe('Hello')
    })

    it('should use default helper for missing values', () => {
      const result = renderer.renderString('{{default missing "fallback"}}', {})
      expect(result.content).toBe('fallback')
    })

    it('should use default helper for existing values', () => {
      const result = renderer.renderString('{{default name "fallback"}}', { name: 'actual' })
      expect(result.content).toBe('actual')
    })

    it('should use eq helper', () => {
      const template = '{{#if (eq env "prod")}}production{{else}}non-prod{{/if}}'
      const prodResult = renderer.renderString(template, { env: 'prod' })
      const devResult = renderer.renderString(template, { env: 'dev' })

      expect(prodResult.content).toBe('production')
      expect(devResult.content).toBe('non-prod')
    })

    it('should use json helper', () => {
      const result = renderer.renderString('{{json data}}', { data: { key: 'value' } })
      const parsed = JSON.parse(result.content)
      expect(parsed).toEqual({ key: 'value' })
    })

    it('should use json helper with pretty print', () => {
      const result = renderer.renderString('{{json data true}}', { data: { key: 'value' } })
      expect(result.content).toContain('\n')
    })

    it('should use join helper', () => {
      const result = renderer.renderString('{{join items ","}}', { items: ['a', 'b', 'c'] })
      expect(result.content).toBe('a,b,c')
    })

    it('should use indent helper', () => {
      const result = renderer.renderString('{{indent content 4}}', { content: 'line1\nline2' })
      expect(result.content).toBe('    line1\n    line2')
    })

    it('should use sanitizeYaml helper', () => {
      const result = renderer.renderString('{{sanitizeYaml value}}', { value: 'simple' })
      expect(result.content).toBe('simple')
    })

    it('should use sanitizeYaml helper for special chars', () => {
      const result = renderer.renderString('{{sanitizeYaml value}}', { value: 'has:colon' })
      expect(result.content).toContain('"')
    })

    it('should use gt helper', () => {
      const result = renderer.renderString('{{#if (gt port 1000)}}high{{else}}low{{/if}}', { port: 8080 })
      expect(result.content).toBe('high')
    })

    it('should register custom helper', () => {
      renderer.registerHelper('double', (n: unknown) => Number(n) * 2)
      const result = renderer.renderString('{{double value}}', { value: 5 })
      expect(result.content).toBe('10')
    })

    it('should list registered helpers', () => {
      const helpers = renderer.getRegisteredHelpers()
      expect(helpers).toContain('toUpperCase')
      expect(helpers).toContain('default')
      expect(helpers).toContain('eq')
    })
  })

  describe('normal path - partials', () => {
    it('should register and use partials', () => {
      renderer.registerPartial('header', '# Header: {{title}}')
      const result = renderer.renderString('{{> header}}', { title: 'My App' })
      expect(result.content).toBe('# Header: My App')
    })

    it('should register partial from file', () => {
      writeTemplate(tempDir, 'footer.hbs', 'Footer: {{year}}')
      renderer.registerPartialFromFile('footer', path.join(tempDir, 'footer.hbs'))

      const result = renderer.renderString('{{> footer}}', { year: 2024 })
      expect(result.content).toBe('Footer: 2024')
    })

    it('should load partials from directory', () => {
      const partialsDir = path.join(tempDir, 'partials')
      fs.mkdirSync(partialsDir, { recursive: true })
      writeTemplate(partialsDir, 'nav.hbs', '<nav>{{title}}</nav>')
      writeTemplate(partialsDir, 'sidebar.hbs', '<sidebar>{{title}}</sidebar>')

      const loaded = renderer.loadPartialsFromDirectory(partialsDir)
      expect(loaded).toContain('nav')
      expect(loaded).toContain('sidebar')

      const result = renderer.renderString('{{> nav}}', { title: 'Home' })
      expect(result.content).toBe('<nav>Home</nav>')
    })

    it('should list registered partials', () => {
      renderer.registerPartial('test', 'content')
      expect(renderer.getRegisteredPartials()).toContain('test')
    })
  })

  describe('exception path - missing variables', () => {
    it('should render empty string for undefined variable by default', () => {
      const result = renderer.renderString('Hello {{nonexistent}}!', {})
      expect(result.success).toBe(true)
      expect(result.content).toBe('Hello !')
    })

    it('should use default helper as fallback for undefined variable', () => {
      const result = renderer.renderString('host={{default db.host "localhost"}}', {})
      expect(result.success).toBe(true)
      expect(result.content).toBe('host=localhost')
    })

    it('should handle template with undefined nested path gracefully', () => {
      const result = renderer.renderString('{{db.replica.host}}', { db: {} })
      expect(result.success).toBe(true)
      expect(result.content).toBe('')
    })
  })

  describe('exception path - template errors', () => {
    it('should return error for missing template file', () => {
      const result = renderer.render({
        templatePath: path.join(tempDir, 'nonexistent.hbs'),
        outputPath: path.join(tempDir, 'output.txt'),
        context: {},
        environment: 'test',
      })

      expect(result.success).toBe(false)
      expect(result.error).toContain('Template not found')
    })

    it('should return error result for invalid template syntax', () => {
      const templatePath = writeTemplate(tempDir, 'bad.hbs', '{{#if unclosed')
      const result = renderer.render({
        templatePath,
        outputPath: path.join(tempDir, 'output.txt'),
        context: {},
        environment: 'test',
      })

      expect(result.success).toBe(false)
      expect(result.error).toBeDefined()
    })
  })

  describe('edge cases', () => {
    it('should handle empty context', () => {
      const result = renderer.renderString('static content', {})
      expect(result.success).toBe(true)
      expect(result.content).toBe('static content')
    })

    it('should handle template with only static content', () => {
      const templatePath = writeTemplate(tempDir, 'static.hbs', 'just text')
      const outputPath = path.join(tempDir, 'static.txt')

      const result = renderer.render({
        templatePath,
        outputPath,
        context: {},
        environment: 'test',
      })

      expect(result.success).toBe(true)
      expect(readOutputFile(outputPath)).toBe('just text')
    })

    it('should handle very long values in template', () => {
      const longValue = 'x'.repeat(10240)
      const result = renderer.renderString('{{value}}', { value: longValue })
      expect(result.success).toBe(true)
      expect(result.content).toBe(longValue)
    })

    it('should include renderedAt timestamp in result', () => {
      const templatePath = writeTemplate(tempDir, 'ts.hbs', 'ok')
      const result = renderer.render({
        templatePath,
        outputPath: path.join(tempDir, 'ts.txt'),
        context: {},
        environment: 'test',
      })

      expect(result.renderedAt).toBeGreaterThan(0)
    })
  })
})
