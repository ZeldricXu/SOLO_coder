import { describe, it, expect } from 'vitest'
import { HandlebarsEngine } from '../../renderer/HandlebarsEngine'
import { GoTemplateEngine } from '../../renderer/GoTemplateEngine'
import { Jinja2Engine } from '../../renderer/Jinja2Engine'
import { createTemplateEngine, SUPPORTED_ENGINES } from '../../renderer'
import { ConfigData } from '../../types'

describe('Pluggable TemplateEngine', () => {
  const sampleContext: ConfigData = {
    app: { name: 'my-service', port: 3000, debug: true },
    db: { host: 'db.local', port: 5432 },
    logLevel: 'debug',
  }

  describe('HandlebarsEngine', () => {
    it('should have correct name', () => {
      const engine = new HandlebarsEngine()
      expect(engine.name).toBe('handlebars')
    })

    it('should render basic template', () => {
      const engine = new HandlebarsEngine()
      const result = engine.render('Hello {{app.name}}!', sampleContext)
      expect(result.success).toBe(true)
      expect(result.content).toBe('Hello my-service!')
    })

    it('should render with built-in helpers', () => {
      const engine = new HandlebarsEngine()
      const result = engine.render('{{toUpperCase app.name}}', sampleContext)
      expect(result.success).toBe(true)
      expect(result.content).toBe('MY-SERVICE')
    })

    it('should render with conditional', () => {
      const engine = new HandlebarsEngine()
      const result = engine.render('{{#if app.debug}}debug-mode{{/if}}', sampleContext)
      expect(result.success).toBe(true)
      expect(result.content).toBe('debug-mode')
    })

    it('should return error for invalid template', () => {
      const engine = new HandlebarsEngine()
      const result = engine.render('{{#if unclosed', sampleContext)
      expect(result.success).toBe(false)
      expect(result.error).toBeDefined()
    })

    it('should handle missing variables gracefully', () => {
      const engine = new HandlebarsEngine()
      const result = engine.render('{{nonexistent}}', sampleContext)
      expect(result.success).toBe(true)
    })
  })

  describe('GoTemplateEngine', () => {
    it('should have correct name', () => {
      const engine = new GoTemplateEngine()
      expect(engine.name).toBe('go-template')
    })

    it('should render dot-access fields', () => {
      const engine = new GoTemplateEngine()
      const result = engine.render('App: {{.app.name}}', sampleContext)
      expect(result.success).toBe(true)
      expect(result.content).toBe('App: my-service')
    })

    it('should render nested dot-access', () => {
      const engine = new GoTemplateEngine()
      const result = engine.render('DB: {{.db.host}}', sampleContext)
      expect(result.success).toBe(true)
      expect(result.content).toBe('DB: db.local')
    })

    it('should handle if blocks', () => {
      const engine = new GoTemplateEngine()
      const result = engine.render('{{if .app.debug}}debug-on{{end}}', sampleContext)
      expect(result.success).toBe(true)
      expect(result.content).toBe('debug-on')
    })

    it('should handle range blocks', () => {
      const engine = new GoTemplateEngine()
      const ctx: ConfigData = { items: ['a', 'b'] }
      const result = engine.render('{{range .items}}item{{end}}', ctx)
      expect(result.success).toBe(true)
      expect(result.content).toContain('item')
    })

    it('should handle len function', () => {
      const engine = new GoTemplateEngine()
      const ctx: ConfigData = { items: [1, 2, 3] }
      const result = engine.render('{{len .items}}', ctx)
      expect(result.success).toBe(true)
      expect(result.content).toBe('3')
    })

    it('should handle dot (this) reference', () => {
      const engine = new GoTemplateEngine()
      const ctx: ConfigData = { name: 'test' }
      const result = engine.render('name={{.name}}', ctx)
      expect(result.success).toBe(true)
      expect(result.content).toBe('name=test')
    })

    it('should handle default pipe', () => {
      const engine = new GoTemplateEngine()
      const result = engine.render('{{.app.name | default "fallback"}}', sampleContext)
      expect(result.success).toBe(true)
      expect(result.content).toBe('my-service')
    })

    it('should handle with blocks', () => {
      const engine = new GoTemplateEngine()
      const result = engine.render('{{with .app}}app-name{{end}}', sampleContext)
      expect(result.success).toBe(true)
    })
  })

  describe('Jinja2Engine', () => {
    it('should have correct name', () => {
      const engine = new Jinja2Engine()
      expect(engine.name).toBe('jinja2')
    })

    it('should render basic variable', () => {
      const engine = new Jinja2Engine()
      const result = engine.render('Hello {{ app.name }}!', sampleContext)
      expect(result.success).toBe(true)
      expect(result.content).toBe('Hello my-service!')
    })

    it('should render nested variable', () => {
      const engine = new Jinja2Engine()
      const result = engine.render('DB: {{ db.host }}', sampleContext)
      expect(result.success).toBe(true)
      expect(result.content).toBe('DB: db.local')
    })

    it('should render conditional blocks', () => {
      const engine = new Jinja2Engine()
      const result = engine.render('{% if app.debug %}debug-on{% endif %}', sampleContext)
      expect(result.success).toBe(true)
      expect(result.content).toBe('debug-on')
    })

    it('should render for loops', () => {
      const engine = new Jinja2Engine()
      const ctx: ConfigData = { items: ['a', 'b', 'c'] }
      const result = engine.render('{% for item in items %}{{ item }}{% endfor %}', ctx)
      expect(result.success).toBe(true)
      expect(result.content).toBe('abc')
    })

    it('should handle undefined variables gracefully', () => {
      const engine = new Jinja2Engine()
      const result = engine.render('{{ nonexistent }}', sampleContext)
      expect(result.success).toBe(true)
    })

    it('should handle template syntax errors', () => {
      const engine = new Jinja2Engine()
      const result = engine.render('{% if unclosed', sampleContext)
      expect(result.success).toBe(false)
      expect(result.error).toBeDefined()
    })
  })

  describe('createTemplateEngine factory', () => {
    it('should create HandlebarsEngine by default', () => {
      const engine = createTemplateEngine()
      expect(engine.name).toBe('handlebars')
    })

    it('should create HandlebarsEngine when specified', () => {
      const engine = createTemplateEngine('handlebars')
      expect(engine.name).toBe('handlebars')
    })

    it('should create GoTemplateEngine', () => {
      const engine = createTemplateEngine('go-template')
      expect(engine.name).toBe('go-template')
    })

    it('should create Jinja2Engine', () => {
      const engine = createTemplateEngine('jinja2')
      expect(engine.name).toBe('jinja2')
    })

    it('should throw for unsupported engine', () => {
      expect(() => createTemplateEngine('unknown')).toThrow('Unsupported template engine')
    })

    it('should list all supported engines', () => {
      expect(SUPPORTED_ENGINES).toContain('handlebars')
      expect(SUPPORTED_ENGINES).toContain('go-template')
      expect(SUPPORTED_ENGINES).toContain('jinja2')
      expect(SUPPORTED_ENGINES.length).toBe(3)
    })
  })

  describe('cross-engine consistency', () => {
    it('should produce same output for simple variable access across engines', () => {
      const hbs = createTemplateEngine('handlebars')
      const go = createTemplateEngine('go-template')
      const j2 = createTemplateEngine('jinja2')

      const hbsResult = hbs.render('{{app.name}}', sampleContext)
      const goResult = go.render('{{.app.name}}', sampleContext)
      const j2Result = j2.render('{{ app.name }}', sampleContext)

      expect(hbsResult.content).toBe('my-service')
      expect(goResult.content).toBe('my-service')
      expect(j2Result.content).toBe('my-service')
    })
  })
})
