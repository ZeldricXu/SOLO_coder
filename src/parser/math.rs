use egui::{RichText, Color32};

fn greek_letter(c: char) -> Option<char> {
    match c {
        'a' => Some('α'),
        'b' => Some('β'),
        'g' => Some('γ'),
        'G' => Some('Γ'),
        'd' => Some('δ'),
        'D' => Some('Δ'),
        'e' => Some('ε'),
        'h' => Some('η'),
        't' => Some('θ'),
        'T' => Some('Θ'),
        'k' => Some('κ'),
        'l' => Some('λ'),
        'L' => Some('Λ'),
        'm' => Some('μ'),
        'n' => Some('ν'),
        'p' => Some('π'),
        'P' => Some('Π'),
        'r' => Some('ρ'),
        's' => Some('σ'),
        'S' => Some('Σ'),
        'f' => Some('φ'),
        'F' => Some('Φ'),
        'c' => Some('χ'),
        'y' => Some('ψ'),
        'Y' => Some('Ψ'),
        'o' => Some('ω'),
        'O' => Some('Ω'),
        _ => None,
    }
}

fn superscript(c: char) -> Option<char> {
    match c {
        '0' => Some('⁰'),
        '1' => Some('¹'),
        '2' => Some('²'),
        '3' => Some('³'),
        '4' => Some('⁴'),
        '5' => Some('⁵'),
        '6' => Some('⁶'),
        '7' => Some('⁷'),
        '8' => Some('⁸'),
        '9' => Some('⁹'),
        '+' => Some('⁺'),
        '-' => Some('⁻'),
        '=' => Some('⁼'),
        '(' => Some('⁽'),
        ')' => Some('⁾'),
        'n' => Some('ⁿ'),
        'i' => Some('ⁱ'),
        _ => None,
    }
}

fn subscript(c: char) -> Option<char> {
    match c {
        '0' => Some('₀'),
        '1' => Some('₁'),
        '2' => Some('₂'),
        '3' => Some('₃'),
        '4' => Some('₄'),
        '5' => Some('₅'),
        '6' => Some('₆'),
        '7' => Some('₇'),
        '8' => Some('₈'),
        '9' => Some('₉'),
        '+' => Some('₊'),
        '-' => Some('₋'),
        '=' => Some('₌'),
        '(' => Some('₍'),
        ')' => Some('₎'),
        _ => None,
    }
}

pub fn render_math_inline(latex: &str) -> RichText {
    let mut result = String::new();
    let mut chars = latex.chars().peekable();

    while let Some(c) = chars.next() {
        match c {
            '\\' => {
                let mut cmd = String::new();
                while let Some(&ch) = chars.peek() {
                    if ch.is_alphabetic() {
                        cmd.push(ch);
                        chars.next();
                    } else {
                        break;
                    }
                }
                match cmd.as_str() {
                    "frac" => {
                        if chars.next() == Some('{') {
                            let mut num = String::new();
                            while let Some(ch) = chars.next() {
                                if ch == '}' { break; }
                                num.push(ch);
                            }
                            if chars.next() == Some('{') {
                                let mut den = String::new();
                                while let Some(ch) = chars.next() {
                                    if ch == '}' { break; }
                                    den.push(ch);
                                }
                                if num == "1" && den == "2" {
                                    result.push('½');
                                } else if num == "1" && den == "4" {
                                    result.push('¼');
                                } else if num == "3" && den == "4" {
                                    result.push('¾');
                                } else if num == "1" && den == "3" {
                                    result.push('⅓');
                                } else if num == "2" && den == "3" {
                                    result.push('⅔');
                                } else {
                                    result.push_str(&format!("({}/{})", num, den));
                                }
                            }
                        }
                    }
                    "sqrt" => {
                        result.push('√');
                    }
                    "sum" => {
                        result.push('Σ');
                    }
                    "prod" => {
                        result.push('Π');
                    }
                    "int" => {
                        result.push('∫');
                    }
                    "pi" => {
                        result.push('π');
                    }
                    "alpha" => result.push('α'),
                    "beta" => result.push('β'),
                    "gamma" => result.push('γ'),
                    "Gamma" => result.push('Γ'),
                    "delta" => result.push('δ'),
                    "Delta" => result.push('Δ'),
                    "theta" => result.push('θ'),
                    "Theta" => result.push('Θ'),
                    "lambda" => result.push('λ'),
                    "Lambda" => result.push('Λ'),
                    "mu" => result.push('μ'),
                    "sigma" => result.push('σ'),
                    "Sigma" => result.push('Σ'),
                    "phi" => result.push('φ'),
                    "Phi" => result.push('Φ'),
                    "psi" => result.push('ψ'),
                    "Psi" => result.push('Ψ'),
                    "omega" => result.push('ω'),
                    "Omega" => result.push('Ω'),
                    "infty" => result.push('∞'),
                    "leq" => result.push_str("≤"),
                    "geq" => result.push_str("≥"),
                    "neq" => result.push_str("≠"),
                    "approx" => result.push_str("≈"),
                    "cdot" => result.push('·'),
                    "times" => result.push('×'),
                    "div" => result.push('÷'),
                    "pm" => result.push('±'),
                    _ => {
                        if cmd.len() == 1 {
                            if let Some(g) = greek_letter(cmd.chars().next().unwrap()) {
                                result.push(g);
                            } else {
                                result.push_str(&cmd);
                            }
                        } else {
                            result.push_str(&cmd);
                        }
                    }
                }
            }
            '^' => {
                if let Some(&ch) = chars.peek() {
                    if ch == '{' {
                        chars.next();
                        while let Some(sch) = chars.next() {
                            if sch == '}' { break; }
                            if let Some(s) = superscript(sch) {
                                result.push(s);
                            } else {
                                result.push(sch);
                            }
                        }
                    } else {
                        chars.next();
                        if let Some(s) = superscript(ch) {
                            result.push(s);
                        } else {
                            result.push('^');
                            result.push(ch);
                        }
                    }
                }
            }
            '_' => {
                if let Some(&ch) = chars.peek() {
                    if ch == '{' {
                        chars.next();
                        while let Some(sch) = chars.next() {
                            if sch == '}' { break; }
                            if let Some(s) = subscript(sch) {
                                result.push(s);
                            } else {
                                result.push(sch);
                            }
                        }
                    } else {
                        chars.next();
                        if let Some(s) = subscript(ch) {
                            result.push(s);
                        } else {
                            result.push('_');
                            result.push(ch);
                        }
                    }
                }
            }
            _ => {
                result.push(c);
            }
        }
    }

    RichText::new(result)
        .family(egui::FontFamily::Monospace)
        .color(Color32::from_rgb(100, 100, 180))
}

pub fn render_math_block(latex: &str, ui: &mut egui::Ui) {
    let rendered = render_math_inline(latex);
    
    ui.horizontal_centered(|ui| {
        ui.add_space(20.0);
        let frame = egui::Frame::none()
            .inner_margin(egui::Margin::same(12.0))
            .rounding(egui::Rounding::same(6.0));
        frame.show(ui, |ui| {
            ui.label(rendered.size(ui.style().text_styles[&egui::TextStyle::Body].size * 1.2));
        });
        ui.add_space(20.0);
    });

    if latex.contains("matrix") || latex.contains("begin") || latex.contains("\\int_") || latex.contains("\\sum_") {
        ui.label(
            RichText::new("Note: Complex formulas may not render perfectly.")
                .italics()
                .size(10.0)
                .color(Color32::GRAY)
        );
    }
}
