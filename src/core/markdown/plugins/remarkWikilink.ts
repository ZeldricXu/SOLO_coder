import { visit } from 'unist-util-visit';
import type { Plugin } from 'unified';
import type { Root, Link, Text } from 'mdast';

export interface WikiLinkNode extends Link {
  type: 'wikiLink';
  data: {
    hName: 'a';
    hProperties: {
      href: string;
      className: string;
      'data-wikilink': string;
    };
  };
  target: string;
  alias: string;
}

declare module 'mdast' {
  interface RootContentMap {
    wikiLink: WikiLinkNode;
  }
}

export const remarkWikilink: Plugin<[], Root> = () => {
  return (tree) => {
    visit(tree, 'text', (node: Text, index, parent) => {
      if (!parent || typeof index !== 'number') return;
      
      const text = node.value;
      const wikiLinkRegex = /\[\[([^\]|]+)(?:\|([^\]]+))?\]\]/g;
      let match;
      let lastIndex = 0;
      const children: any[] = [];

      while ((match = wikiLinkRegex.exec(text)) !== null) {
        if (match.index > lastIndex) {
          children.push({
            type: 'text',
            value: text.slice(lastIndex, match.index),
          });
        }

        const target = match[1].trim();
        const alias = match[2]?.trim() || target;
        
        children.push({
          type: 'wikiLink',
          target,
          alias,
          data: {
            hName: 'a',
            hProperties: {
              href: `#/editor/${encodeURIComponent(target)}`,
              className: 'wikilink text-success-500 hover:text-success-600 underline decoration-dotted cursor-pointer',
              'data-wikilink': target,
            },
          },
          children: [{ type: 'text', value: alias }],
        } as WikiLinkNode);

        lastIndex = match.index + match[0].length;
      }

      if (lastIndex < text.length) {
        children.push({
          type: 'text',
          value: text.slice(lastIndex),
        });
      }

      if (children.length > 1) {
        parent.children.splice(index, 1, ...children);
      }
    });
  };
};
