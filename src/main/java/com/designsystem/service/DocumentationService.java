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
import com.designsystem.mapper.ComponentDocMapper;
import com.designsystem.mapper.ComponentPropMapper;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.designsystem.config.RabbitMQConfig.*;

@Service
public class DocumentationService {

    private final ComponentPropMapper propMapper;
    private final ComponentDocMapper docMapper;
    private final RabbitTemplate rabbitTemplate;
    private final Parser markdownParser;
    private final HtmlRenderer htmlRenderer;

    public DocumentationService(ComponentPropMapper propMapper, ComponentDocMapper docMapper, RabbitTemplate rabbitTemplate) {
        this.propMapper = propMapper;
        this.docMapper = docMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.markdownParser = Parser.builder().build();
        this.htmlRenderer = HtmlRenderer.builder().build();
    }

    @Transactional(rollbackFor = Exception.class)
    public List<ComponentProp> extractPropsFromSource(Long versionId, MultipartFile file, String framework) throws IOException {
        String sourceCode = readFileContent(file);
        List<ComponentProp> props;

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

        return props;
    }

    public List<ComponentDoc> extractDocsFromSource(Long versionId, MultipartFile file) throws IOException {
        String sourceCode = readFileContent(file);
        List<ComponentDoc> docs = new ArrayList<>();

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
        } catch (Exception e) {
            ComponentDoc doc = new ComponentDoc();
            doc.setComponentVersionId(versionId);
            doc.setTitle("API 文档");
            doc.setContent(extractJsDocComments(sourceCode));
            doc.setDocType("api");
            doc.setIndexed(0);
            docs.add(doc);
        }

        for (int i = 0; i < docs.size(); i++) {
            docs.get(i).setSortOrder(i + 1);
            docMapper.insert(docs.get(i));
        }

        rabbitTemplate.convertAndSend(EXCHANGE_DESIGN_SYSTEM, ROUTING_KEY_DOC_INDEX, versionId);

        return docs;
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
