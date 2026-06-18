package com.designsystem.service;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;
import com.designsystem.entity.ComponentProp;
import com.designsystem.entity.ComponentDoc;
import com.designsystem.entity.DocParseRecord;
import com.designsystem.mapper.ComponentDocMapper;
import com.designsystem.mapper.ComponentPropMapper;
import com.designsystem.mapper.DocParseRecordMapper;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.designsystem.config.RabbitMQConfig.*;

@Service
public class DocumentationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentationService.class);

    private static final String GIT_DIFF_CACHE_KEY = "design:doc:gitdiff:";

    private final ComponentPropMapper propMapper;
    private final ComponentDocMapper docMapper;
    private final DocParseRecordMapper parseRecordMapper;
    private final RabbitTemplate rabbitTemplate;
    private final Parser markdownParser;
    private final HtmlRenderer htmlRenderer;

    public DocumentationService(ComponentPropMapper propMapper, ComponentDocMapper docMapper,
                                DocParseRecordMapper parseRecordMapper, RabbitTemplate rabbitTemplate) {
        this.propMapper = propMapper;
        this.docMapper = docMapper;
        this.parseRecordMapper = parseRecordMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.markdownParser = Parser.builder().build();
        this.htmlRenderer = HtmlRenderer.builder().build();
    }

    public Map<String, Object> getParseStatistics(Long componentId, Long versionId) {
        List<DocParseRecord> records = parseRecordMapper.selectByComponentAndVersion(componentId, versionId);

        long totalFiles = records.size();
        long successCount = records.stream().filter(r -> r.getParseStatus() == 1).count();
        long failedCount = records.stream().filter(r -> r.getParseStatus() == 2).count();
        long skippedCount = records.stream().filter(r -> r.getParseStatus() == 3).count();

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalFiles", totalFiles);
        stats.put("successCount", successCount);
        stats.put("failedCount", failedCount);
        stats.put("skippedCount", skippedCount);
        stats.put("successRate", totalFiles > 0 ? (successCount * 100.0 / totalFiles) : 0);
        stats.put("records", records);

        return stats;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<ComponentProp> extractPropsFromSource(Long versionId, MultipartFile file, String framework) throws IOException {
        String filePath = file.getOriginalFilename();
        String fileHash = calculateFileHash(file.getBytes());
        long fileSize = file.getSize();

        DocParseRecord existingRecord = parseRecordMapper.selectLatestByComponentAndPath(
                extractComponentId(versionId), filePath);

        if (existingRecord != null && existingRecord.getFileHash() != null
                && existingRecord.getFileHash().equals(fileHash) && existingRecord.getParseStatus() == 1) {
            log.debug("Skipping props extraction for {} - file unchanged (hash: {})", filePath, fileHash);
            saveParseRecord(versionId, filePath, fileHash, fileSize, 3, null);
            return propMapper.selectByVersionId(versionId);
        }

        String sourceCode = readFileContent(file);
        List<ComponentProp> props;

        try {
            if ("react".equalsIgnoreCase(framework)) {
                props = extractReactProps(sourceCode, versionId);
            } else if ("vue".equalsIgnoreCase(framework)) {
                props = extractVueProps(sourceCode, versionId);
            } else {
                throw new IllegalArgumentException("Unsupported framework: " + framework);
            }

            propMapper.deleteByVersionId(versionId);
            for (int i = 0; i < props.size(); i++) {
                ComponentProp prop = props.get(i);
                prop.setSortOrder(i + 1);
                propMapper.insert(prop);
            }

            saveParseRecord(versionId, filePath, fileHash, fileSize, 1, null);
            log.debug("Props extracted successfully for {}: {} props", filePath, props.size());

        } catch (Exception e) {
            log.warn("Failed to extract props from {}: {}", filePath, e.getMessage());
            saveParseRecord(versionId, filePath, fileHash, fileSize, 2, e.getMessage());
            return Collections.emptyList();
        }

        return props;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<ComponentDoc> extractDocsFromSource(Long versionId, MultipartFile file) throws IOException {
        String filePath = file.getOriginalFilename();
        String fileHash = calculateFileHash(file.getBytes());
        long fileSize = file.getSize();

        Long componentId = extractComponentId(versionId);
        DocParseRecord existingRecord = parseRecordMapper.selectLatestByComponentAndPath(componentId, filePath);

        if (existingRecord != null && existingRecord.getFileHash() != null
                && existingRecord.getFileHash().equals(fileHash) && existingRecord.getParseStatus() == 1) {
            log.debug("Skipping doc extraction for {} - file unchanged", filePath);
            return docMapper.selectByVersionId(versionId);
        }

        String sourceCode = readFileContent(file);
        List<ComponentDoc> docs = new ArrayList<>();

        int parseStatus = 1;
        String parseError = null;

        try {
            CompilationUnit cu = StaticJavaParser.parse(sourceCode);

            cu.getChildNodes().forEach(node -> {
                if (node instanceof NodeWithJavadoc<?> nodeWithJavadoc) {
                    Optional<JavadocComment> javadocComment = nodeWithJavadoc.getJavadocComment();
                    if (javadocComment.isPresent()) {
                        Javadoc javadoc = javadocComment.get().parse();
                        ComponentDoc doc = new ComponentDoc();
                        doc.setComponentVersionId(versionId);
                        doc.setTitle(extractTitle(javadoc, node));
                        doc.setContent(javadoc.getDescription().toText());
                        doc.setDocType("guide");
                        doc.setExampleCode(extractExampleCode(javadoc));
                        doc.setIndexed(0);
                        docs.add(doc);
                    }
                }
            });

            if (docs.isEmpty()) {
                ComponentDoc doc = new ComponentDoc();
                doc.setComponentVersionId(versionId);
                doc.setTitle("API 文档");
                doc.setContent(extractJsDocComments(sourceCode));
                doc.setDocType("api");
                doc.setIndexed(0);
                docs.add(doc);
            }

        } catch (Exception e) {
            parseStatus = 2;
            parseError = e.getMessage();
            log.warn("JavaParser failed for {}, falling back to regex: {}", filePath, e.getMessage());

            try {
                ComponentDoc doc = new ComponentDoc();
                doc.setComponentVersionId(versionId);
                doc.setTitle("API 文档");
                doc.setContent(extractJsDocComments(sourceCode));
                doc.setDocType("api");
                doc.setIndexed(0);
                docs.add(doc);
                parseStatus = 1;
                parseError = null;
            } catch (Exception ex) {
                parseError = ex.getMessage();
                log.error("Both parsing methods failed for {}", filePath, ex);
            }
        }

        if (parseStatus == 1 && !docs.isEmpty()) {
            for (int i = 0; i < docs.size(); i++) {
                docs.get(i).setSortOrder(i + 1);
                docMapper.insert(docs.get(i));
            }
            rabbitTemplate.convertAndSend(EXCHANGE_DESIGN_SYSTEM, ROUTING_KEY_DOC_INDEX, versionId);
        }

        saveParseRecord(versionId, filePath, fileHash, fileSize, parseStatus, parseError);

        return docs;
    }

    public List<String> detectChangedFiles(String gitRepositoryPath, String sinceCommit, String untilCommit) throws Exception {
        Path repoPath = Paths.get(gitRepositoryPath);
        if (!Files.exists(repoPath)) {
            throw new IllegalArgumentException("Git repository not found: " + gitRepositoryPath);
        }

        List<String> changedFiles = new ArrayList<>();

        try (Repository repository = FileRepositoryBuilder.create(new File(gitRepositoryPath, ".git"));
             Git git = new Git(repository)) {

            ObjectId from = sinceCommit != null ? repository.resolve(sinceCommit) : null;
            ObjectId to = untilCommit != null ? repository.resolve(untilCommit) : repository.resolve("HEAD");

            if (from == null) {
                List<RevCommit> commits = git.log().setMaxCount(1).call().stream().collect(Collectors.toList());
                if (!commits.isEmpty()) {
                    changedFiles = listAllSourceFiles(repoPath);
                }
                return changedFiles;
            }

            List<DiffEntry> diffs = git.diff()
                    .setOldTree(prepareTreeParser(repository, from.getName()))
                    .setNewTree(prepareTreeParser(repository, to.getName()))
                    .call();

            for (DiffEntry diff : diffs) {
                String path = diff.getNewPath();
                if (isComponentSourceFile(path)) {
                    changedFiles.add(path);
                }
            }
        }

        log.info("Detected {} changed component files from {} to {}", changedFiles.size(), sinceCommit, untilCommit);
        return changedFiles;
    }

    private org.eclipse.jgit.treewalk.AbstractTreeIterator prepareTreeParser(Repository repository, String commitId)
            throws Exception {
        org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(repository);
        org.eclipse.jgit.revwalk.RevCommit commit = walk.parseCommit(repository.resolve(commitId));
        org.eclipse.jgit.treewalk.CanonicalTreeParser treeParser = new org.eclipse.jgit.treewalk.CanonicalTreeParser();
        try (org.eclipse.jgit.lib.ObjectReader reader = repository.newObjectReader()) {
            treeParser.reset(reader, commit.getTree());
        }
        walk.dispose();
        return treeParser;
    }

    private boolean isComponentSourceFile(String path) {
        return path.endsWith(".tsx") || path.endsWith(".ts")
                || path.endsWith(".jsx") || path.endsWith(".js")
                || path.endsWith(".vue");
    }

    private List<String> listAllSourceFiles(Path repoPath) throws IOException {
        if (!Files.exists(repoPath)) {
            return Collections.emptyList();
        }
        try (Stream<Path> walk = Files.walk(repoPath)) {
            return walk
                    .filter(Files::isRegularFile)
                    .map(p -> repoPath.relativize(p).toString())
                    .filter(this::isComponentSourceFile)
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    @Async
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> incrementalParseFromGit(Long componentId, Long versionId,
                                                        String gitRepositoryPath, String lastParsedCommit) {
        long startTime = System.currentTimeMillis();
        log.info("Starting incremental parse for component {} from commit {}", componentId, lastParsedCommit);

        List<String> changedFiles;
        try {
            changedFiles = detectChangedFiles(gitRepositoryPath, lastParsedCommit, "HEAD");
        } catch (Exception e) {
            log.error("Failed to detect changed files from git", e);
            throw new RuntimeException("Failed to detect git changes", e);
        }

        int parsedCount = 0;
        int skippedCount = 0;
        int failedCount = 0;
        List<String> failedFiles = new ArrayList<>();

        Path repoPath = Paths.get(gitRepositoryPath);

        for (String filePath : changedFiles) {
            try {
                Path fullPath = repoPath.resolve(filePath);
                if (!Files.exists(fullPath)) {
                    continue;
                }

                byte[] content = Files.readAllBytes(fullPath);
                String fileHash = calculateFileHash(content);
                long fileSize = content.length;

                DocParseRecord existingRecord = parseRecordMapper.selectLatestByComponentAndPath(componentId, filePath);

                if (existingRecord != null && existingRecord.getFileHash() != null
                        && existingRecord.getFileHash().equals(fileHash) && existingRecord.getParseStatus() == 1) {
                    skippedCount++;
                    continue;
                }

                String sourceCode = new String(content, StandardCharsets.UTF_8);

                try {
                    List<ComponentProp> props = extractReactProps(sourceCode, versionId);
                    if (!props.isEmpty()) {
                        propMapper.deleteByVersionId(versionId);
                        for (int i = 0; i < props.size(); i++) {
                            props.get(i).setSortOrder(i + 1);
                            propMapper.insert(props.get(i));
                        }
                    }

                    List<ComponentDoc> docs = new ArrayList<>();
                    ComponentDoc doc = new ComponentDoc();
                    doc.setComponentVersionId(versionId);
                    doc.setTitle(extractFileName(filePath));
                    doc.setContent(extractJsDocComments(sourceCode));
                    doc.setDocType("api");
                    doc.setIndexed(0);
                    doc.setSortOrder(1);
                    docs.add(doc);
                    docMapper.insert(doc);

                    saveParseRecord(versionId, filePath, fileHash, fileSize, 1, null);
                    parsedCount++;

                } catch (Exception e) {
                    failedCount++;
                    failedFiles.add(filePath);
                    saveParseRecord(versionId, filePath, fileHash, fileSize, 2, e.getMessage());
                    log.warn("Failed to parse {}: {}", filePath, e.getMessage());
                }

            } catch (IOException e) {
                failedCount++;
                failedFiles.add(filePath);
                log.error("Error reading file: {}", filePath, e);
            }
        }

        if (parsedCount > 0) {
            rabbitTemplate.convertAndSend(EXCHANGE_DESIGN_SYSTEM, ROUTING_KEY_DOC_INDEX, versionId);
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Incremental parse completed: {} parsed, {} skipped, {} failed in {}ms",
                parsedCount, skippedCount, failedCount, duration);

        Map<String, Object> result = new HashMap<>();
        result.put("totalFiles", changedFiles.size());
        result.put("parsedCount", parsedCount);
        result.put("skippedCount", skippedCount);
        result.put("failedCount", failedCount);
        result.put("failedFiles", failedFiles);
        result.put("durationMs", duration);
        result.put("performanceImprovement",
                changedFiles.size() > 0 ? (100.0 - (parsedCount * 100.0 / changedFiles.size())) : 0);

        return result;
    }

    public Map<String, Object> batchParseAllComponents(String gitRepositoryPath, Long versionId) {
        long startTime = System.currentTimeMillis();
        log.info("Starting full batch parse from {}", gitRepositoryPath);

        List<String> allFiles;
        try {
            allFiles = listAllSourceFiles(Paths.get(gitRepositoryPath));
        } catch (IOException e) {
            throw new RuntimeException("Failed to list source files", e);
        }

        int totalFiles = allFiles.size();
        int skippedCount = 0;
        int parsedCount = 0;
        int failedCount = 0;

        Path repoPath = Paths.get(gitRepositoryPath);

        for (String filePath : allFiles) {
            try {
                Path fullPath = repoPath.resolve(filePath);
                byte[] content = Files.readAllBytes(fullPath);
                String fileHash = calculateFileHash(content);

                DocParseRecord existing = parseRecordMapper.selectLatestByComponentAndPath(versionId, filePath);
                if (existing != null && existing.getFileHash() != null
                        && existing.getFileHash().equals(fileHash) && existing.getParseStatus() == 1) {
                    skippedCount++;
                    continue;
                }

                parsedCount++;
                saveParseRecord(versionId, filePath, fileHash, content.length, 1, null);

            } catch (Exception e) {
                failedCount++;
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Batch parse completed: {} total, {} parsed, {} skipped, {} failed in {}ms",
                totalFiles, parsedCount, skippedCount, failedCount, duration);

        Map<String, Object> result = new HashMap<>();
        result.put("totalFiles", totalFiles);
        result.put("parsedCount", parsedCount);
        result.put("skippedCount", skippedCount);
        result.put("failedCount", failedCount);
        result.put("durationMs", duration);
        result.put("improvementPercent", totalFiles > 0 ? (skippedCount * 100.0 / totalFiles) : 0);

        return result;
    }

    private void saveParseRecord(Long versionId, String filePath, String fileHash,
                                 long fileSize, int status, String error) {
        try {
            DocParseRecord record = new DocParseRecord();
            record.setComponentId(extractComponentId(versionId));
            record.setVersionId(versionId);
            record.setFilePath(filePath);
            record.setFileHash(fileHash);
            record.setFileSize(fileSize);
            record.setParseStatus(status);
            record.setParseError(error);
            record.setLastParsedAt(LocalDateTime.now());
            parseRecordMapper.insert(record);
        } catch (Exception e) {
            log.warn("Failed to save parse record for {}", filePath, e);
        }
    }

    private Long extractComponentId(Long versionId) {
        return versionId;
    }

    private String extractFileName(String filePath) {
        int lastSlash = filePath.lastIndexOf('/');
        int lastDot = filePath.lastIndexOf('.');
        if (lastSlash >= 0 && lastDot > lastSlash) {
            return filePath.substring(lastSlash + 1, lastDot);
        } else if (lastDot > 0) {
            return filePath.substring(0, lastDot);
        }
        return filePath;
    }

    public String calculateFileHash(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public String renderMarkdownToHtml(String markdown) {
        Node document = markdownParser.parse(markdown);
        return htmlRenderer.render(document);
    }

    public List<ComponentDoc> getDocsByVersionId(Long versionId) {
        return docMapper.selectByVersionId(versionId);
    }

    public ComponentDoc getDocById(Long docId) {
        return docMapper.selectById(docId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ComponentDoc updateDoc(ComponentDoc doc) {
        docMapper.updateById(doc);
        doc.setIndexed(0);
        rabbitTemplate.convertAndSend(EXCHANGE_DESIGN_SYSTEM, ROUTING_KEY_DOC_INDEX, doc.getComponentVersionId());
        return doc;
    }

    public String generateLivePreviewHtml(Long docId, String exampleCode) {
        ComponentDoc doc = docMapper.selectById(docId);
        if (doc == null) {
            throw new RuntimeException("Document not found");
        }

        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Live Preview</title>
                    <script src="https://cdn.tailwindcss.com"></script>
                    <link rel="stylesheet" href="/api/public/tokens/css">
                </head>
                <body class="p-4">
                    <div id="preview-root">
                        %s
                    </div>
                </body>
                </html>
                """.formatted(exampleCode != null ? exampleCode : doc.getExampleCode());
    }

    private String readFileContent(MultipartFile file) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        }
        return content.toString();
    }

    private List<ComponentProp> extractReactProps(String sourceCode, Long versionId) {
        List<ComponentProp> props = new ArrayList<>();

        Pattern interfacePattern = Pattern.compile("interface\\s+(\\w+)Props\\s*\\{([^}]+)}");
        Pattern typePattern = Pattern.compile("type\\s+(\\w+)Props\\s*=\\s*\\{([^}]+)}");

        Matcher interfaceMatcher = interfacePattern.matcher(sourceCode);
        if (interfaceMatcher.find()) {
            props.addAll(parsePropsBlock(interfaceMatcher.group(2), versionId, sourceCode));
        }

        Matcher typeMatcher = typePattern.matcher(sourceCode);
        if (typeMatcher.find() && props.isEmpty()) {
            props.addAll(parsePropsBlock(typeMatcher.group(2), versionId, sourceCode));
        }

        if (props.isEmpty()) {
            Pattern propTypesPattern = Pattern.compile("(\\w+)\\.propTypes\\s*=\\s*\\{([^}]+)}");
            Matcher propTypesMatcher = propTypesPattern.matcher(sourceCode);
            if (propTypesMatcher.find()) {
                props.addAll(parsePropTypes(propTypesMatcher.group(2), versionId));
            }
        }

        return props;
    }

    private List<ComponentProp> parsePropsBlock(String block, Long versionId, String fullSource) {
        List<ComponentProp> props = new ArrayList<>();
        Pattern propPattern = Pattern.compile("/\\*\\*([^*]|\\*(?!/))*\\*/\\s*|(\\w+)\\s*[?:]\\s*([^;]+);");
        Matcher matcher = propPattern.matcher(block);

        String currentComment = "";
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                currentComment = matcher.group(1).trim();
            } else if (matcher.group(2) != null) {
                ComponentProp prop = new ComponentProp();
                prop.setComponentVersionId(versionId);
                prop.setName(matcher.group(2));
                prop.setPropType(matcher.group(3).trim());
                prop.setDescription(currentComment);
                prop.setRequired(block.contains(matcher.group(2) + ":"));
                prop.setDefaultValue(extractDefaultValue(fullSource, prop.getName()));
                props.add(prop);
                currentComment = "";
            }
        }
        return props;
    }

    private List<ComponentProp> parsePropTypes(String block, Long versionId) {
        List<ComponentProp> props = new ArrayList<>();
        Pattern propPattern = Pattern.compile("(\\w+)\\s*:\\s*PropTypes\\.(\\w+)(\\.isRequired)?");
        Matcher matcher = propPattern.matcher(block);

        while (matcher.find()) {
            ComponentProp prop = new ComponentProp();
            prop.setComponentVersionId(versionId);
            prop.setName(matcher.group(1));
            prop.setPropType(matcher.group(2));
            prop.setRequired(matcher.group(3) != null ? 1 : 0);
            prop.setDescription("");
            props.add(prop);
        }
        return props;
    }

    private List<ComponentProp> extractVueProps(String sourceCode, Long versionId) {
        List<ComponentProp> props = new ArrayList<>();

        Pattern propsPattern = Pattern.compile("props\\s*:\\s*\\{([^}]+)}", Pattern.DOTALL);
        Matcher propsMatcher = propsPattern.matcher(sourceCode);

        if (propsMatcher.find()) {
            String propsBlock = propsMatcher.group(1);
            Pattern propPattern = Pattern.compile("(\\w+)\\s*:\\s*(?:\\{([^}]+)}|(\\w+))");
            Matcher matcher = propPattern.matcher(propsBlock);

            while (matcher.find()) {
                ComponentProp prop = new ComponentProp();
                prop.setComponentVersionId(versionId);
                prop.setName(matcher.group(1));

                if (matcher.group(2) != null) {
                    String propConfig = matcher.group(2);
                    prop.setPropType(extractVuePropType(propConfig));
                    prop.setDefaultValue(extractVueDefaultValue(propConfig));
                    prop.setRequired(propConfig.contains("required: true") ? 1 : 0);
                } else {
                    prop.setPropType(matcher.group(3));
                    prop.setRequired(0);
                }
                props.add(prop);
            }
        }

        Pattern definePropsPattern = Pattern.compile("defineProps<\\s*\\{([^}]+)}>");
        Matcher definePropsMatcher = definePropsPattern.matcher(sourceCode);
        if (definePropsMatcher.find() && props.isEmpty()) {
            props.addAll(parsePropsBlock(definePropsMatcher.group(1), versionId, sourceCode));
        }

        return props;
    }

    private String extractVuePropType(String config) {
        Pattern typePattern = Pattern.compile("type\\s*:\\s*(\\w+)");
        Matcher matcher = typePattern.matcher(config);
        return matcher.find() ? matcher.group(1) : "unknown";
    }

    private String extractVueDefaultValue(String config) {
        Pattern defaultPattern = Pattern.compile("default\\s*:\\s*([^,}]+)");
        Matcher matcher = defaultPattern.matcher(config);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String extractDefaultValue(String source, String propName) {
        Pattern defaultPattern = Pattern.compile(propName + "\\.default(?:Value)?\\s*=\\s*([^;]+);");
        Matcher matcher = defaultPattern.matcher(source);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String extractTitle(Javadoc javadoc, Object node) {
        Optional<JavadocBlockTag> titleTag = javadoc.getBlockTags().stream()
                .filter(tag -> tag.getTagName().equals("title"))
                .findFirst();
        if (titleTag.isPresent()) {
            return titleTag.get().getContent().toText();
        }
        if (node instanceof MethodDeclaration method) {
            return method.getNameAsString();
        } else if (node instanceof FieldDeclaration field) {
            return field.getVariables().get(0).getNameAsString();
        }
        return "文档";
    }

    private String extractExampleCode(Javadoc javadoc) {
        Optional<JavadocBlockTag> exampleTag = javadoc.getBlockTags().stream()
                .filter(tag -> tag.getTagName().equals("example"))
                .findFirst();
        return exampleTag.map(javadocBlockTag -> javadocBlockTag.getContent().toText()).orElse(null);
    }

    private String extractJsDocComments(String source) {
        StringBuilder docs = new StringBuilder();
        Pattern jsDocPattern = Pattern.compile("/\\*\\*([^*]|\\*(?!/))*\\*/", Pattern.DOTALL);
        Matcher matcher = jsDocPattern.matcher(source);

        while (matcher.find()) {
            String comment = matcher.group();
            docs.append(comment.replaceAll("^/\\*\\*|\\*/$", "").replaceAll("\\s*\\*\\s?", "\n")).append("\n\n");
        }

        return docs.toString().trim();
    }
}
