import { describe, it, expect } from 'vitest';
import { parseMarkdownToHtml, extractPlainText, getWordCount } from '@/core/markdown/parser';
import { renderMarkdown } from '@/core/markdown/renderer';

describe('Markdown边缘情况测试', () => {
  describe('空文档处理', () => {
    it('空字符串应该返回空HTML', async () => {
      const html = await parseMarkdownToHtml('');
      expect(html.trim()).toBe('');
    });

    it('仅包含空白字符的文档应该正常处理', async () => {
      const html = await parseMarkdownToHtml('   \n\n   \t  \n');
      expect(html.trim()).toBe('');
    });

    it('null或undefined应该不崩溃', async () => {
      await expect(parseMarkdownToHtml('')).resolves.not.toThrow();
    });
  });

  describe('纯图片链接文档', () => {
    it('仅包含图片的文档应该正常渲染', async () => {
      const markdown = '![Image](https://example.com/image.png)';
      const html = await parseMarkdownToHtml(markdown);
      expect(html).toContain('<img');
      expect(html).toContain('src="https://example.com/image.png"');
    });

    it('多个图片的文档应该正常处理', async () => {
      const markdown = `
![Image1](https://example.com/1.png)
![Image2](https://example.com/2.png)
![Image3](https://example.com/3.png)
      `;
      const html = await parseMarkdownToHtml(markdown);
      const imgMatches = html.match(/<img/g);
      expect(imgMatches).toHaveLength(3);
    });
  });

  describe('超长文本处理', () => {
    it('超长单行（10万字符）不应该导致崩溃', async () => {
      const longLine = 'a'.repeat(100000);
      await expect(parseMarkdownToHtml(longLine)).resolves.not.toThrow();
    });

    it('超长文档（1000行）应该正常处理', async () => {
      const longDoc = Array.from({ length: 1000 }, (_, i) => `# 标题 ${i}\n内容行 ${i}\n\n`).join('');
      const startTime = Date.now();
      await parseMarkdownToHtml(longDoc);
      const duration = Date.now() - startTime;
      expect(duration).toBeLessThan(5000);
    });

    it('超长代码块应该正常处理', async () => {
      const longCode = Array.from({ length: 1000 }, (_, i) => `console.log(${i});`).join('\n');
      const markdown = `\n\`\`\`javascript\n${longCode}\n\`\`\`\n`;
      await expect(parseMarkdownToHtml(markdown)).resolves.not.toThrow();
    });
  });

  describe('深度嵌套结构', () => {
    it('深度嵌套引用不应该导致崩溃', async () => {
      let markdown = '> 第一层';
      for (let i = 2; i <= 20; i++) {
        markdown = `> ${markdown}`;
      }
      await expect(parseMarkdownToHtml(markdown)).resolves.not.toThrow();
    });

    it('深度嵌套列表不应该导致崩溃', async () => {
      let markdown = '- 第1层';
      for (let i = 2; i <= 15; i++) {
        const indent = '  '.repeat(i - 1);
        markdown += `\n${indent}- 第${i}层`;
      }
      await expect(parseMarkdownToHtml(markdown)).resolves.not.toThrow();
    });
  });

  describe('损坏的Markdown语法', () => {
    it('未闭合的粗体标记应该正常处理', async () => {
      const markdown = '这是**未闭合的粗体';
      await expect(parseMarkdownToHtml(markdown)).resolves.not.toThrow();
    });

    it('未闭合的链接应该正常处理', async () => {
      const markdown = '这是[未闭合的链接(https://example.com)';
      await expect(parseMarkdownToHtml(markdown)).resolves.not.toThrow();
    });

    it('错误的表格语法应该正常处理', async () => {
      const markdown = `
| 列1 | 列2 |
|-----|
| A
      `;
      await expect(parseMarkdownToHtml(markdown)).resolves.not.toThrow();
    });
  });

  describe('特殊字符处理', () => {
    it('HTML特殊字符应该被正确转义', async () => {
      const markdown = '这是 <script>alert("xss")</script> 文本';
      const html = await parseMarkdownToHtml(markdown);
      expect(html).not.toContain('<script>');
      expect(html).toContain('&lt;script&gt;');
    });

    it('Unicode字符应该正常处理', async () => {
      const markdown = '# 中文标题 📚 🌟 🌍';
      const html = await parseMarkdownToHtml(markdown);
      expect(html).toContain('中文标题');
      expect(html).toContain('📚');
      expect(html).toContain('🌟');
      expect(html).toContain('🌍');
    });

    it('Emoji应该正常渲染', async () => {
      const markdown = '✅ 已完成 ❌ 未完成 ⏳ 进行中';
      const html = await parseMarkdownToHtml(markdown);
      expect(html).toContain('✅');
      expect(html).toContain('❌');
      expect(html).toContain('⏳');
    });
  });

  describe('公式渲染异常', () => {
    it('无效的LaTeX语法不应该导致崩溃', async () => {
      const markdown = '$\\invalid{command}$';
      await expect(renderMarkdown(markdown)).resolves.not.toThrow();
    });

    it('未闭合的公式标记应该正常处理', async () => {
      const markdown = '这是 $E=mc^2 未闭合的公式';
      await expect(renderMarkdown(markdown)).resolves.not.toThrow();
    });
  });

  describe('纯文本提取边缘情况', () => {
    it('空文档提取纯文本应该返回空字符串', () => {
      expect(extractPlainText('')).toBe('');
    });

    it('仅包含Markdown语法的文档提取纯文本', () => {
      const markdown = '**~~*`code`*~~**';
      const plainText = extractPlainText(markdown);
      expect(plainText.trim()).toBe('code');
    });
  });

  describe('单词计数边缘情况', () => {
    it('空文档单词数为0', () => {
      expect(getWordCount('')).toBe(0);
    });

    it('仅包含标点符号的文档单词数', () => {
      expect(getWordCount('!!!,,,，，。。')).toBe(0);
    });

    it('纯中文文档单词计数', () => {
      const chineseText = '这是一段中文文本，包含多个汉字。';
      const count = getWordCount(chineseText);
      expect(count).toBeGreaterThan(0);
    });
  });

  describe('Wiki链接异常情况', () => {
    it('未闭合的Wiki链接应该正常处理', async () => {
      const markdown = '这是[[未闭合的链接';
      await expect(renderMarkdown(markdown)).resolves.not.toThrow();
    });

    it('空Wiki链接应该正常处理', async () => {
      const markdown = '这是[[]]空链接';
      await expect(renderMarkdown(markdown)).resolves.not.toThrow();
    });

    it('Wiki链接中包含特殊字符', async () => {
      const markdown = '参考[[文档/路径/名称|显示文本]]';
      const html = await renderMarkdown(markdown);
      expect(html).toContain('href="app://open/文档/路径/名称"');
      expect(html).toContain('>显示文本</a>');
    });
  });
});
