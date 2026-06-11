"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
exports.HandlebarsEngine = void 0;
const Handlebars = __importStar(require("handlebars"));
class HandlebarsEngine {
    name = 'handlebars';
    handlebars;
    constructor() {
        this.handlebars = Handlebars.create();
        this.registerBuiltinHelpers();
    }
    registerBuiltinHelpers() {
        this.handlebars.registerHelper('toUpperCase', (str) => String(str ?? '').toUpperCase());
        this.handlebars.registerHelper('toLowerCase', (str) => String(str ?? '').toLowerCase());
        this.handlebars.registerHelper('capitalize', (str) => {
            const s = String(str ?? '');
            return s ? s[0].toUpperCase() + s.slice(1) : '';
        });
        this.handlebars.registerHelper('trim', (str) => String(str ?? '').trim());
        this.handlebars.registerHelper('length', (obj) => {
            if (Array.isArray(obj))
                return obj.length;
            if (typeof obj === 'string')
                return obj.length;
            if (typeof obj === 'object' && obj !== null)
                return Object.keys(obj).length;
            return 0;
        });
        this.handlebars.registerHelper('join', (arr, separator = ',') => {
            return Array.isArray(arr) ? arr.join(String(separator)) : '';
        });
        this.handlebars.registerHelper('json', (obj, pretty = false) => {
            return pretty ? JSON.stringify(obj, null, 2) : JSON.stringify(obj);
        });
        this.handlebars.registerHelper('default', (value, defaultValue) => {
            return value === undefined || value === null || value === '' ? defaultValue : value;
        });
        this.handlebars.registerHelper('eq', (a, b) => a === b);
        this.handlebars.registerHelper('ne', (a, b) => a !== b);
        this.handlebars.registerHelper('gt', (a, b) => Number(a) > Number(b));
        this.handlebars.registerHelper('gte', (a, b) => Number(a) >= Number(b));
        this.handlebars.registerHelper('lt', (a, b) => Number(a) < Number(b));
        this.handlebars.registerHelper('lte', (a, b) => Number(a) <= Number(b));
        this.handlebars.registerHelper('and', (...args) => args.slice(0, -1).every(Boolean));
        this.handlebars.registerHelper('or', (...args) => args.slice(0, -1).some(Boolean));
        this.handlebars.registerHelper('not', (a) => !a);
        this.handlebars.registerHelper('date', (format = 'YYYY-MM-DD HH:mm:ss') => {
            const now = new Date();
            return String(format)
                .replace('YYYY', String(now.getFullYear()))
                .replace('MM', String(now.getMonth() + 1).padStart(2, '0'))
                .replace('DD', String(now.getDate()).padStart(2, '0'))
                .replace('HH', String(now.getHours()).padStart(2, '0'))
                .replace('mm', String(now.getMinutes()).padStart(2, '0'))
                .replace('ss', String(now.getSeconds()).padStart(2, '0'));
        });
        this.handlebars.registerHelper('sanitizeYaml', (str) => {
            if (str === undefined || str === null)
                return '';
            const s = String(str);
            if (/[:#&*!|>'"%@`[\]{}\n]/.test(s)) {
                return `"${s.replace(/"/g, '\\"')}"`;
            }
            return s;
        });
        this.handlebars.registerHelper('indent', (str, spaces = 2) => {
            if (!str)
                return '';
            const indent = ' '.repeat(Number(spaces) || 2);
            return String(str).split('\n').map((l) => indent + l).join('\n');
        });
    }
    render(template, context) {
        try {
            const compiled = this.handlebars.compile(template, {
                strict: false,
                noEscape: true,
            });
            return {
                content: compiled(context),
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
    getHandlebarsInstance() {
        return this.handlebars;
    }
}
exports.HandlebarsEngine = HandlebarsEngine;
//# sourceMappingURL=HandlebarsEngine.js.map