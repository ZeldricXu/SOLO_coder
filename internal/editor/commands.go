package editor

import (
	"fmt"
	"strings"
)

type CommandType string

const (
	CmdBold          CommandType = "bold"
	CmdItalic        CommandType = "italic"
	CmdUnderline     CommandType = "underline"
	CmdStrike        CommandType = "strike"
	CmdCode          CommandType = "code"
	CmdCodeBlock     CommandType = "code_block"
	CmdHeading1      CommandType = "heading_1"
	CmdHeading2      CommandType = "heading_2"
	CmdHeading3      CommandType = "heading_3"
	CmdHeading4      CommandType = "heading_4"
	CmdHeading5      CommandType = "heading_5"
	CmdHeading6      CommandType = "heading_6"
	CmdQuote         CommandType = "quote"
	CmdUnorderedList CommandType = "unordered_list"
	CmdOrderedList   CommandType = "ordered_list"
	CmdLink          CommandType = "link"
	CmdImage         CommandType = "image"
	CmdTable         CommandType = "table"
	CmdHorizontalRule CommandType = "horizontal_rule"
	CmdInlineMath    CommandType = "inline_math"
	CmdBlockMath     CommandType = "block_math"
	CmdWikiLink      CommandType = "wiki_link"
	CmdTag           CommandType = "tag"
)

type CommandResult struct {
	Content    string
	Selection  Selection
	Successful bool
}

func (e *Editor) ExecuteCommand(cmd CommandType, args ...string) CommandResult {
	e.mu.Lock()
	defer e.mu.Unlock()

	e.pushUndoState()

	result := CommandResult{Successful: false}

	switch cmd {
	case CmdBold:
		result = e.cmdToggleInlineFormat("**", "**")
	case CmdItalic:
		result = e.cmdToggleInlineFormat("*", "*")
	case CmdStrike:
		result = e.cmdToggleInlineFormat("~~", "~~")
	case CmdCode:
		result = e.cmdToggleInlineFormat("`", "`")
	case CmdInlineMath:
		result = e.cmdToggleInlineFormat("$", "$")
	case CmdHeading1:
		result = e.cmdToggleHeading(1)
	case CmdHeading2:
		result = e.cmdToggleHeading(2)
	case CmdHeading3:
		result = e.cmdToggleHeading(3)
	case CmdHeading4:
		result = e.cmdToggleHeading(4)
	case CmdHeading5:
		result = e.cmdToggleHeading(5)
	case CmdHeading6:
		result = e.cmdToggleHeading(6)
	case CmdQuote:
		result = e.cmdToggleLinePrefix("> ")
	case CmdUnorderedList:
		result = e.cmdToggleList("- ")
	case CmdOrderedList:
		result = e.cmdToggleOrderedList()
	case CmdCodeBlock:
		result = e.cmdToggleCodeBlock()
	case CmdBlockMath:
		result = e.cmdToggleBlockMath()
	case CmdLink:
		url := ""
		if len(args) > 0 {
			url = args[0]
		}
		result = e.cmdInsertLink(url)
	case CmdWikiLink:
		target := ""
		if len(args) > 0 {
			target = args[0]
		}
		result = e.cmdInsertWikiLink(target)
	case CmdImage:
		url := ""
		if len(args) > 0 {
			url = args[0]
		}
		result = e.cmdInsertImage(url)
	case CmdTable:
		rows := 3
		cols := 3
		if len(args) >= 1 {
			fmt.Sscanf(args[0], "%d", &rows)
		}
		if len(args) >= 2 {
			fmt.Sscanf(args[1], "%d", &cols)
		}
		result = e.cmdInsertTable(rows, cols)
	case CmdHorizontalRule:
		result = e.cmdInsertHorizontalRule()
	case CmdTag:
		tag := ""
		if len(args) > 0 {
			tag = args[0]
		}
		result = e.cmdInsertTag(tag)
	default:
		result = CommandResult{Successful: false}
	}

	if result.Successful {
		e.state.Content = result.Content
		e.state.IsDirty = true
		e.state.WordCount = countWords(e.state.Content)
		e.state.Cursor = result.Selection.End
		e.renderContent()
		e.scheduleAutoSave()
		e.changeCount++
	}

	return result
}

func (e *Editor) cmdToggleInlineFormat(prefix, suffix string) CommandResult {
	content := []rune(e.state.Content)
	selection := e.getCurrentSelection()

	startOffset := selection.Start.Offset
	endOffset := selection.End.Offset

	if startOffset > endOffset {
		startOffset, endOffset = endOffset, startOffset
	}

	selectedText := string(content[startOffset:endOffset])
	prefixRunes := []rune(prefix)
	suffixRunes := []rune(suffix)
	prefixLen := len(prefixRunes)
	suffixLen := len(suffixRunes)

	if startOffset >= prefixLen && endOffset+suffixLen <= len(content) {
		before := string(content[startOffset-prefixLen : startOffset])
		after := string(content[endOffset : endOffset+suffixLen])
		if before == prefix && after == suffix {
			newContent := make([]rune, 0, len(content)-prefixLen-suffixLen)
			newContent = append(newContent, content[:startOffset-prefixLen]...)
			newContent = append(newContent, content[startOffset:endOffset]...)
			newContent = append(newContent, content[endOffset+suffixLen:]...)

			newStart := startOffset - prefixLen
			newEnd := endOffset - prefixLen

			return CommandResult{
				Content: string(newContent),
				Selection: Selection{
					Start: CursorPosition{Offset: newStart},
					End:   CursorPosition{Offset: newEnd},
				},
				Successful: true,
			}
		}
	}

	newContent := make([]rune, 0, len(content)+prefixLen+suffixLen)
	newContent = append(newContent, content[:startOffset]...)
	newContent = append(newContent, prefixRunes...)
	newContent = append(newContent, content[startOffset:endOffset]...)
	newContent = append(newContent, suffixRunes...)
	newContent = append(newContent, content[endOffset:]...)

	newStart := startOffset + prefixLen
	newEnd := endOffset + prefixLen

	if startOffset == endOffset && selectedText == "" {
		placeholder := "text"
		placeholderRunes := []rune(placeholder)
		insertPos := startOffset + prefixLen

		newContent2 := make([]rune, 0, len(newContent)+len(placeholderRunes))
		newContent2 = append(newContent2, newContent[:insertPos]...)
		newContent2 = append(newContent2, placeholderRunes...)
		newContent2 = append(newContent2, newContent[insertPos:]...)

		return CommandResult{
			Content: string(newContent2),
			Selection: Selection{
				Start: CursorPosition{Offset: insertPos},
				End:   CursorPosition{Offset: insertPos + len(placeholderRunes)},
			},
			Successful: true,
		}
	}

	return CommandResult{
		Content: string(newContent),
		Selection: Selection{
			Start: CursorPosition{Offset: newStart},
			End:   CursorPosition{Offset: newEnd},
		},
		Successful: true,
	}
}

func (e *Editor) cmdToggleHeading(level int) CommandResult {
	content := e.state.Content
	lines := splitLines(content)
	cursorLine := e.state.Cursor.Line

	if cursorLine < 0 || cursorLine >= len(lines) {
		return CommandResult{Successful: false}
	}

	prefix := strings.Repeat("#", level) + " "
	currentLine := lines[cursorLine]

	trimmed := strings.TrimLeft(currentLine, "#")
	if trimmed != currentLine && len(trimmed) > 0 && trimmed[0] == ' ' {
		lines[cursorLine] = trimmed[1:]
	} else {
		lines[cursorLine] = prefix + currentLine
	}

	newContent := strings.Join(lines, "\n")

	return CommandResult{
		Content: newContent,
		Selection: Selection{
			Start: e.state.Cursor,
			End:   e.state.Cursor,
		},
		Successful: true,
	}
}

func (e *Editor) cmdToggleLinePrefix(prefix string) CommandResult {
	content := e.state.Content
	lines := splitLines(content)
	selection := e.getCurrentSelection()

	startLine := selection.Start.Line
	endLine := selection.End.Line

	if startLine > endLine {
		startLine, endLine = endLine, startLine
	}

	if startLine < 0 {
		startLine = 0
	}
	if endLine >= len(lines) {
		endLine = len(lines) - 1
	}

	allHavePrefix := true
	for i := startLine; i <= endLine; i++ {
		if !strings.HasPrefix(lines[i], prefix) {
			allHavePrefix = false
			break
		}
	}

	for i := startLine; i <= endLine; i++ {
		if allHavePrefix {
			lines[i] = strings.TrimPrefix(lines[i], prefix)
		} else {
			lines[i] = prefix + lines[i]
		}
	}

	newContent := strings.Join(lines, "\n")

	return CommandResult{
		Content: newContent,
		Selection: Selection{
			Start: selection.Start,
			End:   selection.End,
		},
		Successful: true,
	}
}

func (e *Editor) cmdToggleList(prefix string) CommandResult {
	return e.cmdToggleLinePrefix(prefix)
}

func (e *Editor) cmdToggleOrderedList() CommandResult {
	content := e.state.Content
	lines := splitLines(content)
	selection := e.getCurrentSelection()

	startLine := selection.Start.Line
	endLine := selection.End.Line

	if startLine > endLine {
		startLine, endLine = endLine, startLine
	}

	if startLine < 0 {
		startLine = 0
	}
	if endLine >= len(lines) {
		endLine = len(lines) - 1
	}

	allOrdered := true
	for i := startLine; i <= endLine; i++ {
		if !matchOrderedList(lines[i]) {
			allOrdered = false
			break
		}
	}

	if allOrdered {
		for i := startLine; i <= endLine; i++ {
			lines[i] = skipOrderedList(lines[i])
		}
	} else {
		counter := 1
		for i := startLine; i <= endLine; i++ {
			lines[i] = fmt.Sprintf("%d. %s", counter, lines[i])
			counter++
		}
	}

	newContent := strings.Join(lines, "\n")

	return CommandResult{
		Content: newContent,
		Selection: Selection{
			Start: selection.Start,
			End:   selection.End,
		},
		Successful: true,
	}
}

func (e *Editor) cmdToggleCodeBlock() CommandResult {
	content := []rune(e.state.Content)
	selection := e.getCurrentSelection()

	startOffset := selection.Start.Offset
	endOffset := selection.End.Offset

	if startOffset > endOffset {
		startOffset, endOffset = endOffset, startOffset
	}

	selectedText := string(content[startOffset:endOffset])
	lang := ""

	if strings.Contains(selectedText, "\n") {
		codeBlock := "```" + lang + "\n" + selectedText + "\n```"
		codeBlockRunes := []rune(codeBlock)

		newContent := make([]rune, 0, len(content)-(endOffset-startOffset)+len(codeBlockRunes))
		newContent = append(newContent, content[:startOffset]...)
		newContent = append(newContent, codeBlockRunes...)
		newContent = append(newContent, content[endOffset:]...)

		newStart := startOffset + 4
		newEnd := startOffset + 4 + len([]rune(selectedText))

		return CommandResult{
			Content: string(newContent),
			Selection: Selection{
				Start: CursorPosition{Offset: newStart},
				End:   CursorPosition{Offset: newEnd},
			},
			Successful: true,
		}
	}

	codeBlock := "```\n\n```"
	codeBlockRunes := []rune(codeBlock)

	newContent := make([]rune, 0, len(content)+len(codeBlockRunes))
	newContent = append(newContent, content[:startOffset]...)
	newContent = append(newContent, codeBlockRunes...)
	newContent = append(newContent, content[endOffset:]...)

	cursorPos := startOffset + 4

	return CommandResult{
		Content: string(newContent),
		Selection: Selection{
			Start: CursorPosition{Offset: cursorPos},
			End:   CursorPosition{Offset: cursorPos},
		},
		Successful: true,
	}
}

func (e *Editor) cmdToggleBlockMath() CommandResult {
	content := []rune(e.state.Content)
	selection := e.getCurrentSelection()

	startOffset := selection.Start.Offset
	endOffset := selection.End.Offset

	if startOffset > endOffset {
		startOffset, endOffset = endOffset, startOffset
	}

	selectedText := string(content[startOffset:endOffset])
	mathBlock := "$$\n" + selectedText + "\n$$"
	mathBlockRunes := []rune(mathBlock)

	newContent := make([]rune, 0, len(content)-(endOffset-startOffset)+len(mathBlockRunes))
	newContent = append(newContent, content[:startOffset]...)
	newContent = append(newContent, mathBlockRunes...)
	newContent = append(newContent, content[endOffset:]...)

	newStart := startOffset + 3
	newEnd := startOffset + 3 + len([]rune(selectedText))

	return CommandResult{
		Content: string(newContent),
		Selection: Selection{
			Start: CursorPosition{Offset: newStart},
			End:   CursorPosition{Offset: newEnd},
		},
		Successful: true,
	}
}

func (e *Editor) cmdInsertLink(url string) CommandResult {
	content := []rune(e.state.Content)
	selection := e.getCurrentSelection()

	startOffset := selection.Start.Offset
	endOffset := selection.End.Offset

	if startOffset > endOffset {
		startOffset, endOffset = endOffset, startOffset
	}

	selectedText := string(content[startOffset:endOffset])
	if selectedText == "" {
		selectedText = "link text"
	}
	if url == "" {
		url = "https://"
	}

	linkText := fmt.Sprintf("[%s](%s)", selectedText, url)
	linkRunes := []rune(linkText)

	newContent := make([]rune, 0, len(content)-(endOffset-startOffset)+len(linkRunes))
	newContent = append(newContent, content[:startOffset]...)
	newContent = append(newContent, linkRunes...)
	newContent = append(newContent, content[endOffset:]...)

	urlStart := startOffset + len([]rune(selectedText)) + 2
	urlEnd := urlStart + len([]rune(url))

	return CommandResult{
		Content: string(newContent),
		Selection: Selection{
			Start: CursorPosition{Offset: urlStart},
			End:   CursorPosition{Offset: urlEnd},
		},
		Successful: true,
	}
}

func (e *Editor) cmdInsertWikiLink(target string) CommandResult {
	content := []rune(e.state.Content)
	cursorOffset := e.state.Cursor.Offset

	if target == "" {
		target = "note"
	}

	wikiLink := "[[" + target + "]]"
	wikiLinkRunes := []rune(wikiLink)

	newContent := make([]rune, 0, len(content)+len(wikiLinkRunes))
	newContent = append(newContent, content[:cursorOffset]...)
	newContent = append(newContent, wikiLinkRunes...)
	newContent = append(newContent, content[cursorOffset:]...)

	newOffset := cursorOffset + 2
	endOffset := cursorOffset + 2 + len([]rune(target))

	return CommandResult{
		Content: string(newContent),
		Selection: Selection{
			Start: CursorPosition{Offset: newOffset},
			End:   CursorPosition{Offset: endOffset},
		},
		Successful: true,
	}
}

func (e *Editor) cmdInsertImage(url string) CommandResult {
	content := []rune(e.state.Content)
	cursorOffset := e.state.Cursor.Offset

	if url == "" {
		url = "https://example.com/image.png"
	}

	imageText := fmt.Sprintf("![alt text](%s)", url)
	imageRunes := []rune(imageText)

	newContent := make([]rune, 0, len(content)+len(imageRunes))
	newContent = append(newContent, content[:cursorOffset]...)
	newContent = append(newContent, imageRunes...)
	newContent = append(newContent, content[cursorOffset:]...)

	return CommandResult{
		Content: string(newContent),
		Selection: Selection{
			Start: CursorPosition{Offset: cursorOffset + 2},
			End:   CursorPosition{Offset: cursorOffset + 10},
		},
		Successful: true,
	}
}

func (e *Editor) cmdInsertTable(rows, cols int) CommandResult {
	content := []rune(e.state.Content)
	cursorOffset := e.state.Cursor.Offset

	if rows < 1 {
		rows = 1
	}
	if cols < 1 {
		cols = 1
	}

	var tableBuilder strings.Builder
	tableBuilder.WriteString("\n")

	for i := 0; i < cols; i++ {
		if i > 0 {
			tableBuilder.WriteString(" | ")
		}
		tableBuilder.WriteString("Header ")
		tableBuilder.WriteString(fmt.Sprintf("%d", i+1))
	}
	tableBuilder.WriteString("\n")

	for i := 0; i < cols; i++ {
		if i > 0 {
			tableBuilder.WriteString(" | ")
		}
		tableBuilder.WriteString("---")
	}
	tableBuilder.WriteString("\n")

	for r := 0; r < rows; r++ {
		for c := 0; c < cols; c++ {
			if c > 0 {
				tableBuilder.WriteString(" | ")
			}
			tableBuilder.WriteString("Cell ")
			tableBuilder.WriteString(fmt.Sprintf("%d-%d", r+1, c+1))
		}
		tableBuilder.WriteString("\n")
	}

	tableText := tableBuilder.String()
	tableRunes := []rune(tableText)

	newContent := make([]rune, 0, len(content)+len(tableRunes))
	newContent = append(newContent, content[:cursorOffset]...)
	newContent = append(newContent, tableRunes...)
	newContent = append(newContent, content[cursorOffset:]...)

	return CommandResult{
		Content: string(newContent),
		Selection: Selection{
			Start: CursorPosition{Offset: cursorOffset + 1},
			End:   CursorPosition{Offset: cursorOffset + 1},
		},
		Successful: true,
	}
}

func (e *Editor) cmdInsertHorizontalRule() CommandResult {
	content := []rune(e.state.Content)
	cursorOffset := e.state.Cursor.Offset

	hrText := "\n---\n"
	hrRunes := []rune(hrText)

	newContent := make([]rune, 0, len(content)+len(hrRunes))
	newContent = append(newContent, content[:cursorOffset]...)
	newContent = append(newContent, hrRunes...)
	newContent = append(newContent, content[cursorOffset:]...)

	newOffset := cursorOffset + len(hrRunes)

	return CommandResult{
		Content: string(newContent),
		Selection: Selection{
			Start: CursorPosition{Offset: newOffset},
			End:   CursorPosition{Offset: newOffset},
		},
		Successful: true,
	}
}

func (e *Editor) cmdInsertTag(tag string) CommandResult {
	content := []rune(e.state.Content)
	cursorOffset := e.state.Cursor.Offset

	if tag == "" {
		tag = "tag"
	}

	tagText := "#" + tag
	tagRunes := []rune(tagText)

	newContent := make([]rune, 0, len(content)+len(tagRunes))
	newContent = append(newContent, content[:cursorOffset]...)
	newContent = append(newContent, tagRunes...)
	newContent = append(newContent, content[cursorOffset:]...)

	newOffset := cursorOffset + 1
	endOffset := cursorOffset + 1 + len([]rune(tag))

	return CommandResult{
		Content: string(newContent),
		Selection: Selection{
			Start: CursorPosition{Offset: newOffset},
			End:   CursorPosition{Offset: endOffset},
		},
		Successful: true,
	}
}

func (e *Editor) getCurrentSelection() Selection {
	return Selection{
		Start: e.state.Cursor,
		End:   e.state.Cursor,
		Text:  "",
	}
}

func countWords(s string) int {
	count := 0
	inWord := false

	for _, r := range s {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '_' {
			if !inWord {
				count++
				inWord = true
			}
		} else if isCJKChar(r) {
			count++
			inWord = false
		} else {
			inWord = false
		}
	}

	return count
}

func isCJKChar(r rune) bool {
	return r >= 0x4e00 && r <= 0x9fff
}
