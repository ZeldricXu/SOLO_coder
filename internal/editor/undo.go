package editor

type UndoState struct {
	Content string
	Cursor  CursorPosition
}

type UndoStack struct {
	stack    []*UndoState
	index    int
	maxSize  int
}

func NewUndoStack(maxSize int) *UndoStack {
	return &UndoStack{
		stack:   make([]*UndoState, 0, maxSize),
		index:   -1,
		maxSize: maxSize,
	}
}

func (u *UndoStack) Push(state *UndoState) {
	if u.index < len(u.stack)-1 {
		u.stack = u.stack[:u.index+1]
	}

	u.stack = append(u.stack, state)

	if len(u.stack) > u.maxSize {
		u.stack = u.stack[1:]
	} else {
		u.index++
	}
}

func (u *UndoStack) Undo() *UndoState {
	if u.index <= 0 {
		return nil
	}

	u.index--
	return u.stack[u.index]
}

func (u *UndoStack) Redo() *UndoState {
	if u.index >= len(u.stack)-1 {
		return nil
	}

	u.index++
	return u.stack[u.index]
}

func (u *UndoStack) CanUndo() bool {
	return u.index > 0
}

func (u *UndoStack) CanRedo() bool {
	return u.index < len(u.stack)-1
}

func (u *UndoStack) Clear() {
	u.stack = u.stack[:0]
	u.index = -1
}

func (u *UndoStack) Size() int {
	return len(u.stack)
}

func (u *UndoStack) Current() *UndoState {
	if u.index < 0 || u.index >= len(u.stack) {
		return nil
	}
	return u.stack[u.index]
}
