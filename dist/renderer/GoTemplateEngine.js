"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.GoTemplateEngine = void 0;
const HandlebarsEngine_1 = require("./HandlebarsEngine");
class GoTemplateEngine {
    name = 'go-template';
    handlebarsEngine;
    constructor() {
        this.handlebarsEngine = new HandlebarsEngine_1.HandlebarsEngine();
    }
    render(template, context) {
        try {
            const translated = this.translateGoToHandlebars(template);
            return this.handlebarsEngine.render(translated, context);
        }
        catch (error) {
            return {
                content: '',
                success: false,
                error: error.message,
            };
        }
    }
    translateGoToHandlebars(template) {
        let result = template;
        result = result.replace(/\{\{len\s+\.(\w+)\}\}/g, '{{length $1}}');
        result = result.replace(/\{\{\.([\w.]+)\s*\|\s*default\s+"([^"]*)"\}\}/g, '{{default $1 "$2"}}');
        const stack = [];
        const tokens = this.tokenize(result);
        const parts = [];
        for (const token of tokens) {
            if (token.type === 'range') {
                stack.push('each');
                parts.push(`{{#each ${token.field}}}`);
            }
            else if (token.type === 'with') {
                stack.push('with');
                parts.push(`{{#with ${token.field}}}`);
            }
            else if (token.type === 'if') {
                stack.push('if');
                parts.push(`{{#if ${token.field}}}`);
            }
            else if (token.type === 'end') {
                const closer = stack.length > 0 ? stack.pop() : 'if';
                parts.push(`{{/${closer}}}`);
            }
            else if (token.type === 'dot') {
                parts.push('{{this}}');
            }
            else if (token.type === 'field') {
                parts.push(`{{${token.field}}}`);
            }
            else {
                parts.push(token.raw);
            }
        }
        return parts.join('');
    }
    tokenize(template) {
        const tokens = [];
        const regex = /\{\{([\s\S]*?)\}\}/g;
        let lastIndex = 0;
        let match;
        while ((match = regex.exec(template)) !== null) {
            if (match.index > lastIndex) {
                tokens.push({ type: 'text', raw: template.slice(lastIndex, match.index) });
            }
            const inner = match[1].trim();
            const rangeMatch = inner.match(/^range\s+\.(\w+)$/);
            const withMatch = inner.match(/^with\s+\.(\w+)$/);
            const ifMatch = inner.match(/^if\s+\.(\w+)$/);
            const dotMatch = inner === '.';
            const fieldMatch = inner.match(/^\.([\w.]+)$/);
            if (rangeMatch) {
                tokens.push({ type: 'range', field: rangeMatch[1], raw: match[0] });
            }
            else if (withMatch) {
                tokens.push({ type: 'with', field: withMatch[1], raw: match[0] });
            }
            else if (ifMatch) {
                tokens.push({ type: 'if', field: ifMatch[1], raw: match[0] });
            }
            else if (inner === 'end') {
                tokens.push({ type: 'end', raw: match[0] });
            }
            else if (dotMatch) {
                tokens.push({ type: 'dot', raw: match[0] });
            }
            else if (fieldMatch) {
                tokens.push({ type: 'field', field: fieldMatch[1], raw: match[0] });
            }
            else {
                tokens.push({ type: 'passthrough', raw: match[0] });
            }
            lastIndex = regex.lastIndex;
        }
        if (lastIndex < template.length) {
            tokens.push({ type: 'text', raw: template.slice(lastIndex) });
        }
        return tokens;
    }
}
exports.GoTemplateEngine = GoTemplateEngine;
//# sourceMappingURL=GoTemplateEngine.js.map