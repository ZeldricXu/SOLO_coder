/// <reference types="vite/client" />
/// <reference types="electron" />

declare module '*.css' {
  const classes: { readonly [key: string]: string };
  export default classes;
}

declare module '*.scss' {
  const classes: { readonly [key: string]: string };
  export default classes;
}

declare module '*.svg' {
  import * as React from 'react';
  export const ReactComponent: React.FC<React.SVGProps<SVGSVGElement>>;
  const src: string;
  export default src;
}

declare module '*.png' {
  const src: string;
  export default src;
}

declare module '*.jpg' {
  const src: string;
  export default src;
}

declare module '*.gif' {
  const src: string;
  export default src;
}

declare module 'flexsearch' {
  export default class Index {
    constructor(options?: any);
    add(id: string | number, text: string): void;
    add(id: string | number, item: any): void;
    search(query: string, options?: any): any[];
    search(query: string, limit?: number, options?: any): any[];
    update(id: string | number, text: string): void;
    remove(id: string | number): void;
    clear(): void;
    export(): any;
    import(data: any): void;
  }

  export class Document {
    constructor(options?: any);
    add(doc: any): void;
    update(doc: any): void;
    remove(id: string | number): void;
    search(query: string, options?: any): any[];
    search(query: string, limit?: number, options?: any): any[];
    clear(): void;
    export(): any;
    import(data: any): void;
  }

  export interface SearchOptions {
    field?: string;
    bool?: 'and' | 'or';
    expand?: boolean;
    fuzzy?: number;
    suggest?: boolean;
    limit?: number;
    offset?: number;
  }
}

declare module '@codemirror/lang-markdown' {
  export function markdown(config?: {
    codeLanguages?: any[];
    addKeymap?: boolean;
    base?: any;
  }): any;
}

declare module '@codemirror/language-data' {
  export const languages: any[];
}

interface Window {
  electron: {
    ipc: {
      invoke: <T>(channel: string, ...args: any[]) => Promise<T>;
      send: (channel: string, ...args: any[]) => void;
      on: (channel: string, listener: (...args: any[]) => void) => () => void;
      once: (channel: string, listener: (...args: any[]) => void) => void;
      removeListener: (channel: string, listener: (...args: any[]) => void) => void;
      removeAllListeners: (channel: string) => void;
    };
    platform: NodeJS.Platform;
    versions: NodeJS.ProcessVersions;
  };
}
