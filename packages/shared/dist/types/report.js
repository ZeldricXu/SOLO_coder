export const REPORT_TEMPLATE = `
\\documentclass{article}
\\usepackage{amsmath}
\\usepackage{graphicx}
\\usepackage{booktabs}
\\usepackage{siunitx}
\\title{<<title>>}
\\author{<<author>>}
\\date{<<date>>}
\\begin{document}
\\maketitle
\\begin{abstract}
<<abstract>>
\\end{abstract}
<<sections>>
<<figures>>
<<tables>>
\\section{Conclusion}
<<conclusion>>
\\end{document}
`;
//# sourceMappingURL=report.js.map