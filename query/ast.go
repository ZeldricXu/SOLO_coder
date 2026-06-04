package query

type JoinType int

const (
	JoinInner JoinType = iota
	JoinLeft
)

type JoinClause struct {
	JoinType JoinType
	Table    string
	LeftCol  string
	RightCol string
}

type WhereExpr interface {
	exprNode()
}

type SelectStmt struct {
	Columns  []string
	From     string
	Joins    []*JoinClause
	Where    WhereExpr
	GroupBy  []string
	AggCol   string
	AggFunc  string
	OrderBy  string
	OrderAsc bool
	Limit    int
}

type BinaryExpr struct {
	Left  WhereExpr
	Right WhereExpr
	Op    string
}

type CompareExpr struct {
	Col   string
	Op    string
	Value interface{}
}

type InExpr struct {
	Col     string
	Values  []interface{}
	Negated bool
}

type BetweenExpr struct {
	Col  string
	Low  interface{}
	High interface{}
}

type IsNullExpr struct {
	Col     string
	Negated bool
}

type LikeExpr struct {
	Col     string
	Pattern string
}

type BoolExpr struct {
	Value bool
}

func (BinaryExpr) exprNode()  {}
func (CompareExpr) exprNode() {}
func (InExpr) exprNode()      {}
func (BetweenExpr) exprNode() {}
func (IsNullExpr) exprNode()  {}
func (LikeExpr) exprNode()    {}
func (BoolExpr) exprNode()    {}
