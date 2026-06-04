import { describe, it, expect } from 'vitest';
import { parseMarkdownToHtml, extractHeadings, extractPlainText, getWordCount } from '@/core/markdown/parser';
import { renderMarkdown } from '@/core/markdown/renderer';

describe('Markdown Parser', () => {
  describe('标题解析', () => {
    it('应该正确解析一级到六级标题', async () => {
      const markdown = `
# 一级标题
## 二级标题
### 三级标题
#### 四级标题
##### 五级标题
###### 六级标题
      `;

      const html = await parseMarkdownToHtml(markdown);

      expect(html).toContain('<h1');
      expect(html).toContain('一级标题');
      expect(html).toContain('<h2');
      expect(html).toContain('二级标题');
      expect(html).toContain('<h3');
      expect(html).toContain('三级标题');
      expect(html).toContain('<h4');
      expect(html).toContain('四级标题');
      expect(html).toContain('<h5');
      expect(html).toContain('五级标题');
      expect(html).toContain('<h6');
      expect(html).toContain('六级标题');
    });

    it('应该提取所有标题', () => {
      const markdown = `
# 一级标题
正文内容
## 二级标题
更多内容
### 三级标题
      `;

      const headings = extractHeadings(markdown);

      expect(headings).toHaveLength(3);
      expect(headings[0]).toEqual({ level: 1, text: '一级标题', line: 1 });
      expect(headings[1]).toEqual({ level: 2, text: '二级标题', line: 3 });
      expect(headings[2]).toEqual({ level: 3, text: '三级标题', line: 5 });
    });
  });

  describe('文本格式化', () => {
    it('应该正确解析粗体文本', async () => {
      const markdown = '这是**粗体**文本';
      const html = await parseMarkdownToHtml(markdown);
      expect(html).toContain('<strong>粗体</strong>');
    });

    it('应该正确解析斜体文本', async () => {
      const markdown = '这是*斜体*文本';
      const html = await parseMarkdownToHtml(markdown);
      expect(html).toContain('<em>斜体</em>');
    });

    it('应该正确解析删除线文本', async () => {
      const markdown = '这是~~删除线~~文本';
      const html = await parseMarkdownToHtml(markdown);
      expect(html).toContain('<del>删除线</del>');
    });

    it('应该正确解析行内代码', async () => {
      const markdown = '使用 `const x = 1` 定义变量';
      const html = await parseMarkdownToHtml(markdown);
      expect(html).toContain('<code>const x = 1</code>');
    });
  });

  describe('链接和图片', () => {
    it('应该正确解析普通链接', async () => {
      const markdown = '访问[Google](https://google.com)';
      const html = await parseMarkdownToHtml(markdown);
      expect(html).toContain('<a href="https://google.com"');
      expect(html).toContain('Google</a>');
    });

    it('应该正确解析图片', async () => {
      const markdown = '![Alt text](https://example.com/image.png)';
      const html = await parseMarkdownToHtml(markdown);
      expect(html).toContain('<img');
      expect(html).toContain('src="https://example.com/image.png"');
      expect(html).toContain('alt="Alt text"');
    });
  });

  describe('Wiki链接解析', () => {
    it('应该将[[文档名]]渲染为内部链接', async () => {
      const markdown = '请参考[[技术架构]]文档';
      const html = await renderMarkdown(markdown);
      expect(html).toContain('href="app://open/技术架构"');
      expect(html).toContain('class="wikilink"');
    });

    it('应该处理带有显示文本的Wiki链接', async () => {
      const markdown = '查看[[项目文档|详细说明]]';
      const html = await renderMarkdown(markdown);
      expect(html).toContain('href="app://open/项目文档"');
      expect(html).toContain('>详细说明</a>');
    });

    it('应该处理多个Wiki链接', async () => {
      const markdown = '[[文档A]]和[[文档B]]是相关的';
      const html = await renderMarkdown(markdown);
      expect(html).toContain('href="app://open/文档A"');
      expect(html).toContain('href="app://open/文档B"');
    });
  });

  describe('列表解析', () => {
    it('应该正确解析无序列表', async () => {
      const markdown = `
- 项目一
- 项目二
- 项目三
      `;

      const html = await parseMarkdownToHtml(markdown);
      expect(html).toContain('<ul');
      expect(html).match(/<li>/g).toHaveLength(3);
    });

    it('应该正确解析有序列表', async () => {
      const markdown = `
1. 第一步
2. 第二步
3. 第三步
      `;

      const html = await parseMarkdownToHtml(markdown);
      expect(html).toContain('<ol');
      expect(html).match(/<li>/g).toHaveLength(3);
    });

    it('应该正确解析嵌套列表', async () => {
      const markdown = `
- 父项目
  - 子项目一
  - 子项目二
      `;

      const html = await parseMarkdownToHtml(markdown);
      const ulMatches = html.match(/<ul/g);
      expect(ulMatches?.length).toBeGreaterThanOrEqual(2);
    });

    it('应该正确解析任务列表', async () => {
      const markdown = `
- [x] 已完成任务
- [ ] 未完成任务
      `;

      const html = await parseMarkdownToHtml(markdown);
      expect(html).toContain('type="checkbox"');
      expect(html).toContain('checked');
    });
  });

  describe('表格解析', () => {
    it('应该正确解析标准表格', async () => {
      const markdown = `
| 列1 | 列2 | 列3 |
|-----|-----|-----|
| A   | B   | C   |
| D   | E   | F   |
      `;

      const html = await parseMarkdownToHtml(markdown);
      expect(html).toContain('<table');
      expect(html).toContain('<thead');
      expect(html).toContain('<tbody');
      expect(html).match(/<tr>/g).toHaveLength(3);
      expect(html).match(/<td>/g).toHaveLength(6);
    });

    it('应该正确解析对齐表格', async () => {
      const markdown = `
| 左对齐 | 居中 | 右对齐 |
|:-------|:----:|-------:|
| A      |  B   |      C |
      `;

      const html = await parseMarkdownToHtml(markdown);
      expect(html).toContain('style="text-align:left"');
      expect(html).toContain('style="text-align:center"');
      expect(html).toContain('style="text-align:right"');
    });
  });

  describe('代码块', () => {
    it('应该正确解析带有语言标注的代码块', async () => {
      const markdown = `
\`\`\`python
def hello():
    print("Hello World")
\`\`\`
      `;

      const html = await parseMarkdownToHtml(markdown);
      expect(html).toContain('<pre');
      expect(html).toContain('<code');
      expect(html).toContain('language-python');
      expect(html).toContain('def hello');
    });

    it('应该正确解析无语言标注的代码块', async () => {
      const markdown = `
\`\`\`
plain text code
\`\`\`
      `;

      const html = await parseMarkdownToHtml(markdown);
      expect(html).toContain('<pre');
      expect(html).toContain('<code');
    });
  });

  describe('KaTeX数学公式', () => {
    it('应该正确渲染内联公式', async () => {
      const markdown = '质能方程 $E=mc^2$ 是相对论的核心';
      const html = await renderMarkdown(markdown);
      expect(html).toContain('katex');
      expect(html).toContain('E=mc^2');
    });

    it('应该正确渲染块级公式', async () => {
      const markdown = `
$$
\\int_{-\\infty}^{\\infty} e^{-x^2} dx = \\sqrt{\\pi}
$$
      `;

      const html = await renderMarkdown(markdown);
      expect(html).toContain('katex-display');
      expect(html).toContain('\\int');
    });

    it('应该正确渲染多个内联公式', async () => {
      const markdown = '公式1: $a^2 + b^2$ 和公式2: $\\sqrt{x}$';
      const html = await renderMarkdown(markdown);
      const katexMatches = html.match(/katex/g);
      expect(katexMatches?.length).toBeGreaterThanOrEqual(2);
    });
  });

  describe('引用和分割线', () => {
    it('应该正确解析块引用', async () => {
      const markdown = `
> 这是一段引用
> 多行引用文本
      `;

      const html = await parseMarkdownToHtml(markdown);
      expect(html).toContain('<blockquote');
      expect(html).toContain('这是一段引用');
    });

    it('应该正确解析嵌套引用', async () => {
      const markdown = `
> 外层引用
> > 内层引用
      `;

      const html = await parseMarkdownToHtml(markdown);
      const blockquoteMatches = html.match(/<blockquote/g);
      expect(blockquoteMatches?.length).toBeGreaterThanOrEqual(2);
    });

    it('应该正确解析水平分割线', async () => {
      const markdown = `
内容一

---

内容二
      `;

      const html = await parseMarkdownToHtml(markdown);
      expect(html).toContain('<hr');
    });
  });
});

describe('Markdown文本提取', () => {
  it('应该正确提取纯文本', () => {
    const markdown = `
# 标题

这是**粗体**和*斜体*文本。

- 列表项1
- 列表项2
    `;

    const plainText = extractPlainText(markdown);
    expect(plainText).toContain('标题');
    expect(plainText).toContain('这是粗体和斜体文本');
    expect(plainText).toContain('列表项1');
    expect(plainText).toContain('列表项2');
    expect(plainText).not.toContain('**');
    expect(plainText).not.toContain('*');
    expect(plainText).not.toContain('#');
    expect(plainText).not.toContain('-');
  });

  it('应该正确计算单词数', () => {
    const markdown = `
# 测试文档

这是一段测试文本。Hello World!

- 列表项一
- 列表项二
    `;

    const wordCount = getWordCount(markdown);
    expect(wordCount).toBeGreaterThan(0);
  });
});

describe('Markdown渲染器缓存', () => {
  it('应该使用缓存提高性能', async () => {
    const markdown = '# 测试缓存';

    const startTime1 = Date.now();
    await renderMarkdown(markdown);
    const time1 = Date.now() - startTime1;

    const startTime2 = Date.now();
    await renderMarkdown(markdown);
    const time2 = Date.now() - startTime2;

    expect(time2).toBeLessThanOrEqual(time1);
  });
});
