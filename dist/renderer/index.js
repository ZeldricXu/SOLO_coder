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
var __exportStar = (this && this.__exportStar) || function(m, exports) {
    for (var p in m) if (p !== "default" && !Object.prototype.hasOwnProperty.call(exports, p)) __createBinding(exports, m, p);
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.SUPPORTED_ENGINES = exports.Jinja2Engine = exports.GoTemplateEngine = exports.HandlebarsEngine = void 0;
exports.createTemplateEngine = createTemplateEngine;
__exportStar(require("./TemplateRenderer"), exports);
var HandlebarsEngine_1 = require("./HandlebarsEngine");
Object.defineProperty(exports, "HandlebarsEngine", { enumerable: true, get: function () { return HandlebarsEngine_1.HandlebarsEngine; } });
var GoTemplateEngine_1 = require("./GoTemplateEngine");
Object.defineProperty(exports, "GoTemplateEngine", { enumerable: true, get: function () { return GoTemplateEngine_1.GoTemplateEngine; } });
var Jinja2Engine_1 = require("./Jinja2Engine");
Object.defineProperty(exports, "Jinja2Engine", { enumerable: true, get: function () { return Jinja2Engine_1.Jinja2Engine; } });
const HandlebarsEngine_2 = require("./HandlebarsEngine");
const GoTemplateEngine_2 = require("./GoTemplateEngine");
const Jinja2Engine_2 = require("./Jinja2Engine");
function createTemplateEngine(name = 'handlebars') {
    switch (name) {
        case 'handlebars': return new HandlebarsEngine_2.HandlebarsEngine();
        case 'go-template': return new GoTemplateEngine_2.GoTemplateEngine();
        case 'jinja2': return new Jinja2Engine_2.Jinja2Engine();
        default: throw new Error(`Unsupported template engine: ${name}. Supported: handlebars, go-template, jinja2`);
    }
}
exports.SUPPORTED_ENGINES = ['handlebars', 'go-template', 'jinja2'];
//# sourceMappingURL=index.js.map