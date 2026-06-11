"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.Jinja2Engine = void 0;
class Jinja2Engine {
    name = 'jinja2';
    env;
    constructor() {
        try {
            const nunjucks = require('nunjucks');
            const loader = new nunjucks.PrecompiledLoader({});
            this.env = new nunjucks.Environment(loader, {
                autoescape: false,
                throwOnUndefined: false,
            });
        }
        catch {
            throw new Error('nunjucks package is required for the jinja2 engine. Install it with: npm install nunjucks');
        }
    }
    render(template, context) {
        try {
            const content = this.env.renderString(template, context);
            return {
                content,
                success: true,
            };
        }
        catch (error) {
            return {
                content: '',
                success: false,
                error: error.message,
            };
        }
    }
}
exports.Jinja2Engine = Jinja2Engine;
//# sourceMappingURL=Jinja2Engine.js.map