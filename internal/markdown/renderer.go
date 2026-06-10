package markdown

import (
	"bytes"
	"fmt"
	"regexp"
	"strings"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/pkg/utils"
	"github.com/yuin/goldmark"
	"github.com/yuin/goldmark/ast"
	"github.com/yuin/goldmark/extension"
	"github.com/yuin/goldmark/parser"
	"github.com/yuin/goldmark/renderer"
	"github.com/yuin/goldmark/renderer/html"
	"github.com/yuin/goldmark/text"
	"github.com/yuin/goldmark/util"
)

type WikiRenderer struct {
	cfg    *config.Config
	md     goldmark.Markdown
}

func NewWikiRenderer(cfg *config.Config) *WikiRenderer {
	md := goldmark.New(
		goldmark.WithExtensions(
			extension.GFM,
			extension.Table,
			extension.Strikethrough,
			extension.Linkify,
			extension.TaskList,
			&WikiLinkExtension{},
			&KaTeXExtension{},
		),
		goldmark.WithParserOptions(
			parser.WithAutoHeadingID(),
		),
		goldmark.WithRendererOptions(
			html.WithHardWraps(),
			html.WithXHTML(),
		),
	)

	return &WikiRenderer{
		cfg: cfg,
		md:  md,
	}
}

var sourcePathKey = parser.NewContextKey()

func (r *WikiRenderer) Render(content string, sourcePath string) (string, error) {
	var buf bytes.Buffer
	ctx := parser.NewContext()
	ctx.Set(sourcePathKey, sourcePath)

	if err := r.md.Convert([]byte(content), &buf, parser.WithContext(ctx)); err != nil {
		return "", err
	}

	return buf.String(), nil
}

type wikiLinkNode struct {
	ast.BaseInline
	Target  string
	Display string
	Anchor  string
	Alias   string
}

var kindWikiLink = ast.NewNodeKind("WikiLink")

func (n *wikiLinkNode) Kind() ast.NodeKind {
	return kindWikiLink
}

func (n *wikiLinkNode) Dump(source []byte, level int) {
	ast.DumpHelper(n, source, level, map[string]string{
		"Target":  n.Target,
		"Display": n.Display,
		"Anchor":  n.Anchor,
		"Alias":   n.Alias,
	}, nil)
}

type WikiLinkExtension struct{}

func (e *WikiLinkExtension) Extend(m goldmark.Markdown) {
	m.Parser().AddOptions(
		parser.WithInlineParsers(
			util.Prioritized(&wikiLinkParser{}, 100),
		),
	)
	m.Renderer().AddOptions(
		renderer.WithNodeRenderers(
			util.Prioritized(&wikiLinkRenderer{}, 100),
		),
	)
}

type wikiLinkParser struct{}

func (p *wikiLinkParser) Trigger() []byte {
	return []byte{'['}
}

func (p *wikiLinkParser) Parse(parent ast.Node, block text.Reader, pc parser.Context) ast.Node {
	line, segment := block.PeekLine()
	if len(line) < 4 || line[0] != '[' || line[1] != '[' {
		return nil
	}

	closeIdx := -1
	for i := 2; i < len(line)-1; i++ {
		if line[i] == ']' && line[i+1] == ']' {
			closeIdx = i
			break
		}
	}

	if closeIdx == -1 {
		return nil
	}

	inner := string(line[2:closeIdx])
	block.Advance(closeIdx + 2)
	_ = segment

	link := parseWikiLink(inner, 0)
	node := &wikiLinkNode{
		Target:  link.Target,
		Display: link.Display,
		Anchor:  link.Anchor,
		Alias:   link.Alias,
	}

	return node
}

type wikiLinkRenderer struct{}

func (r *wikiLinkRenderer) RegisterFuncs(reg renderer.NodeRendererFuncRegisterer) {
	reg.Register(kindWikiLink, r.renderWikiLink)
}

func (r *wikiLinkRenderer) renderWikiLink(w util.BufWriter, source []byte, node ast.Node, entering bool) (ast.WalkStatus, error) {
	if !entering {
		return ast.WalkContinue, nil
	}

	n := node.(*wikiLinkNode)
	display := n.Display
	target := n.Target

	if n.Anchor != "" {
		target = target + "#" + n.Anchor
	}

	slug := utils.Slugify(target)
	href := fmt.Sprintf(`/note/%s`, slug)

	_, _ = w.WriteString(fmt.Sprintf(`<a href="%s" class="wiki-link" data-target="%s">%s</a>`,
		href, target, display))

	return ast.WalkContinue, nil
}

type katexBlock struct {
	ast.BaseBlock
	Content string
}

var kindKaTeXBlock = ast.NewNodeKind("KaTeXBlock")

func (n *katexBlock) Kind() ast.NodeKind {
	return kindKaTeXBlock
}

func (n *katexBlock) Dump(source []byte, level int) {
	ast.DumpHelper(n, source, level, map[string]string{
		"Content": n.Content,
	}, nil)
}

type katexInline struct {
	ast.BaseInline
	Content string
}

var kindKaTeXInline = ast.NewNodeKind("KaTeXInline")

func (n *katexInline) Kind() ast.NodeKind {
	return kindKaTeXInline
}

func (n *katexInline) Dump(source []byte, level int) {
	ast.DumpHelper(n, source, level, map[string]string{
		"Content": n.Content,
	}, nil)
}

type KaTeXExtension struct{}

func (e *KaTeXExtension) Extend(m goldmark.Markdown) {
	m.Parser().AddOptions(
		parser.WithBlockParsers(
			util.Prioritized(&katexBlockParser{}, 101),
		),
		parser.WithInlineParsers(
			util.Prioritized(&katexInlineParser{}, 99),
		),
	)
	m.Renderer().AddOptions(
		renderer.WithNodeRenderers(
			util.Prioritized(&katexRenderer{}, 100),
		),
	)
}

type katexBlockParser struct{}

func (p *katexBlockParser) Open(parent ast.Node, reader text.Reader, pc parser.Context) (ast.Node, parser.State) {
	line, segment := reader.PeekLine()
	line = bytes.TrimSpace(line)
	if !bytes.HasPrefix(line, []byte("$$")) {
		return nil, parser.NoChildren
	}

	_ = segment
	node := &katexBlock{}
	return node, parser.NoChildren
}

func (p *katexBlockParser) Continue(node ast.Node, reader text.Reader, pc parser.Context) parser.State {
	line, _ := reader.PeekLine()
	trimmed := bytes.TrimSpace(line)
	if bytes.HasSuffix(trimmed, []byte("$$")) && len(trimmed) >= 2 {
		n := node.(*katexBlock)
		content := n.Content
		if len(trimmed) > 2 {
			content += string(trimmed[:len(trimmed)-2])
		}
		n.Content = content
		return parser.Close
	}

	n := node.(*katexBlock)
	if n.Content != "" {
		n.Content += "\n"
	}
	n.Content += string(bytes.TrimSpace(line))
	return parser.Continue | parser.NoChildren
}

func (p *katexBlockParser) Close(node ast.Node, reader text.Reader, pc parser.Context) {}

func (p *katexBlockParser) CanInterruptParagraph() bool {
	return false
}

func (p *katexBlockParser) CanAcceptIndentedLine() bool {
	return false
}

type katexInlineParser struct{}

func (p *katexInlineParser) Trigger() []byte {
	return []byte{'$'}
}

func (p *katexInlineParser) Parse(parent ast.Node, block text.Reader, pc parser.Context) ast.Node {
	line, _ := block.PeekLine()
	if len(line) < 2 || line[0] != '$' || line[1] == '$' {
		return nil
	}

	closeIdx := -1
	for i := 1; i < len(line); i++ {
		if line[i] == '$' && line[i-1] != '\\' {
			closeIdx = i
			break
		}
	}

	if closeIdx == -1 || closeIdx == 1 {
		return nil
	}

	content := string(line[1:closeIdx])
	block.Advance(closeIdx + 1)

	return &katexInline{
		Content: content,
	}
}

type katexRenderer struct{}

func (r *katexRenderer) RegisterFuncs(reg renderer.NodeRendererFuncRegisterer) {
	reg.Register(kindKaTeXBlock, r.renderKaTeXBlock)
	reg.Register(kindKaTeXInline, r.renderKaTeXInline)
}

func (r *katexRenderer) renderKaTeXBlock(w util.BufWriter, source []byte, node ast.Node, entering bool) (ast.WalkStatus, error) {
	if !entering {
		return ast.WalkContinue, nil
	}

	n := node.(*katexBlock)
	_, _ = w.WriteString(fmt.Sprintf(`<div class="katex-display">%s</div>`, escapeHTML(n.Content)))
	return ast.WalkContinue, nil
}

func (r *katexRenderer) renderKaTeXInline(w util.BufWriter, source []byte, node ast.Node, entering bool) (ast.WalkStatus, error) {
	if !entering {
		return ast.WalkContinue, nil
	}

	n := node.(*katexInline)
	_, _ = w.WriteString(fmt.Sprintf(`<span class="katex-inline">%s</span>`, escapeHTML(n.Content)))
	return ast.WalkContinue, nil
}

func escapeHTML(s string) string {
	s = strings.ReplaceAll(s, "&", "&amp;")
	s = strings.ReplaceAll(s, "<", "&lt;")
	s = strings.ReplaceAll(s, ">", "&gt;")
	s = strings.ReplaceAll(s, `"`, "&quot;")
	return s
}

func RenderMarkdown(content string, sourcePath string, cfg *config.Config) (string, error) {
	renderer := NewWikiRenderer(cfg)
	return renderer.Render(content, sourcePath)
}

var codeBlockRegex = regexp.MustCompile("```([\\w+-]*)\\n([\\s\\S]*?)```")

func highlightCodeBlocks(html string) string {
	return codeBlockRegex.ReplaceAllStringFunc(html, func(match string) string {
		matches := codeBlockRegex.FindStringSubmatch(match)
		if len(matches) < 3 {
			return match
		}
		lang := matches[1]
		code := matches[2]
		return fmt.Sprintf(`<pre><code class="language-%s">%s</code></pre>`, lang, escapeHTML(code))
	})
}
