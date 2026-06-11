package com.exam.sandbox;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PythonCodeSecurityScanner {

    private static final Set<String> DANGEROUS_MODULES = Set.of(
            "os", "subprocess", "pty", "popen2", "commands", "multiprocessing",
            "shutil", "ctypes", "sys"
    );

    private static final Set<String> DANGEROUS_FUNCTIONS = Set.of(
            "eval", "exec", "compile", "open", "getattr", "setattr",
            "delattr"
    );

    private static final Set<String> DANGEROUS_OS_METHODS = Set.of(
            "system", "popen", "remove", "unlink", "rmdir", "removedirs",
            "rename", "replace", "chmod", "chown", "kill", "fork"
    );

    private static final Set<String> DANGEROUS_SUBPROCESS_METHODS = Set.of(
            "call", "run", "Popen", "check_call", "check_output"
    );

    private static final Set<String> DANGEROUS_PATHS = Set.of(
            "/etc/passwd", "/etc/shadow", "/etc/hosts", "/root", "/boot",
            "/proc", "/sys", "/var/run/docker.sock", "/dev/random"
    );

    private static final List<Pattern> PATTERNS = Arrays.asList(
            Pattern.compile("(?i)__import__\\s*\\(\\s*['\"]os['\"]"),
            Pattern.compile("(?i)__import__\\s*\\(\\s*['\"]subprocess['\"]"),
            Pattern.compile("(?i)os\\s*\\.\\s*system\\s*\\("),
            Pattern.compile("(?i)os\\s*\\.\\s*popen\\s*\\("),
            Pattern.compile("(?i)subprocess\\s*\\."),
            Pattern.compile("(?i)\\beval\\s*\\("),
            Pattern.compile("(?i)\\bexec\\s*\\("),
            Pattern.compile("(?i)rm\\s+-rf\\s+/"),
            Pattern.compile("(?i)open\\s*\\(\\s*['\"]/etc/"),
            Pattern.compile("(?i)\\bfork\\s*\\(\\s*\\)"),
            Pattern.compile("(?i)shutil\\s*\\.")
    );

    public ScanResult scan(String code) {
        ScanResult result = new ScanResult();
        String safeCode = (code == null) ? "" : code;
        result.setCode(safeCode);

        for (Pattern pattern : PATTERNS) {
            Matcher matcher = pattern.matcher(safeCode);
            while (matcher.find()) {
                int line = lineNumberOf(safeCode, matcher.start());
                result.addViolation(new Violation(matcher.group(), describePattern(pattern), line));
            }
        }

        Set<String> foundModules = extractModuleImports(safeCode);
        Set<String> dangerous = new HashSet<>();
        for (String mod : foundModules) {
            String base = mod.split("\\.")[0];
            if (DANGEROUS_MODULES.contains(base)) {
                dangerous.add(base);
            }
        }
        if (!dangerous.isEmpty()) {
            String first = dangerous.iterator().next();
            result.addViolation(new Violation(
                    String.join(",", dangerous),
                    "检测到危险模块导入：" + dangerous,
                    firstLineOfModule(safeCode, first)));
        }

        extractDangerousCalls(safeCode, result);
        extractDangerousFileAccess(safeCode, result);

        if (result.hasViolations()) {
            log.warn("Python代码安全扫描发现 {} 项违规: {}",
                    result.getViolations().size(),
                    result.getViolations().stream()
                            .map(Violation::getDescription)
                            .collect(Collectors.joining("; ")));
        }
        return result;
    }

    private void extractDangerousCalls(String code, ScanResult result) {
        for (String func : DANGEROUS_FUNCTIONS) {
            Pattern p = Pattern.compile("(?<!\\w)" + Pattern.quote(func) + "\\s*\\(");
            Matcher m = p.matcher(code);
            while (m.find()) {
                int line = lineNumberOf(code, m.start());
                if ("open".equals(func) && !isDangerousFileArg(code, m.end())) {
                    continue;
                }
                result.addViolation(new Violation(m.group(), "禁止调用危险函数: " + func, line));
            }
        }
        for (String method : DANGEROUS_OS_METHODS) {
            Pattern p = Pattern.compile("\\bos\\s*\\.\\s*" + method + "\\s*\\(");
            Matcher m = p.matcher(code);
            while (m.find()) {
                int line = lineNumberOf(code, m.start());
                result.addViolation(new Violation(m.group(), "禁止调用 os." + method, line));
            }
        }
        for (String method : DANGEROUS_SUBPROCESS_METHODS) {
            Pattern p = Pattern.compile("\\bsubprocess\\s*\\.\\s*" + method + "\\s*\\(");
            Matcher m = p.matcher(code);
            while (m.find()) {
                int line = lineNumberOf(code, m.start());
                result.addViolation(new Violation(m.group(), "禁止调用 subprocess." + method, line));
            }
        }
    }

    private boolean isDangerousFileArg(String code, int pos) {
        int depth = 1;
        int i = pos;
        while (i < code.length() && depth > 0 && i < pos + 200) {
            char c = code.charAt(i);
            if (c == '(') depth++;
            if (c == ')') depth--;
            i++;
        }
        String args = code.substring(pos, Math.min(i, code.length()));
        for (String dp : DANGEROUS_PATHS) {
            if (args.contains(dp)) return true;
        }
        return false;
    }

    private void extractDangerousFileAccess(String code, ScanResult result) {
        for (String dp : DANGEROUS_PATHS) {
            Pattern p = Pattern.compile(Pattern.quote(dp));
            Matcher m = p.matcher(code);
            while (m.find()) {
                int line = lineNumberOf(code, m.start());
                result.addViolation(new Violation(m.group(), "禁止访问敏感路径: " + dp, line));
            }
        }
    }

    private Set<String> extractModuleImports(String code) {
        Set<String> modules = new HashSet<>();
        Pattern importP = Pattern.compile("(?m)^\\s*import\\s+([\\w\\.]+)");
        Matcher m = importP.matcher(code);
        while (m.find()) modules.add(m.group(1));
        Pattern fromP = Pattern.compile("(?m)^\\s*from\\s+([\\w\\.]+)\\s+import");
        m = fromP.matcher(code);
        while (m.find()) modules.add(m.group(1));
        return modules;
    }

    private int lineNumberOf(String code, int position) {
        int end = Math.min(position, code.length());
        return (int) code.substring(0, end).chars().filter(c -> c == '\n').count() + 1;
    }

    private String describePattern(Pattern p) {
        String s = p.toString();
        if (s.contains("os") && s.contains("system")) return "禁止调用 os.system（执行系统命令）";
        if (s.contains("subprocess")) return "禁止使用 subprocess 模块";
        if (s.contains("eval")) return "禁止调用 eval（任意代码执行）";
        if (s.contains("exec")) return "禁止调用 exec（代码执行）";
        if (s.contains("rm")) return "禁止使用递归删除命令";
        if (s.contains("/etc/")) return "禁止访问系统配置文件";
        if (s.contains("__import__")) return "禁止动态导入模块";
        return "疑似危险代码模式";
    }

    private int firstLineOfModule(String code, String module) {
        Pattern p = Pattern.compile("(?m)^\\s*(import|from)\\s+" + Pattern.quote(module));
        Matcher m = p.matcher(code);
        if (m.find()) return lineNumberOf(code, m.start());
        return 1;
    }

    @Data
    public static class ScanResult {
        private String code;
        private List<Violation> violations = new ArrayList<>();

        public boolean hasViolations() {
            return !violations.isEmpty();
        }

        public void addViolation(Violation v) {
            violations.add(v);
        }
    }

    @Data
    public static class Violation {
        private String matchedCode;
        private String description;
        private int line;

        public Violation(String matchedCode, String description, int line) {
            this.matchedCode = matchedCode;
            this.description = description;
            this.line = line;
        }
    }
}
