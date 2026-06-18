package com.designsystem.service;

import com.designsystem.entity.ComponentProp;
import com.designsystem.mapper.ComponentDocMapper;
import com.designsystem.mapper.ComponentPropMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("组件Props解析测试")
@ExtendWith(MockitoExtension.class)
class DocumentationServicePropsTest {

    @Mock
    private ComponentPropMapper propMapper;

    @Mock
    private ComponentDocMapper docMapper;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private DocumentationService documentationService;

    @BeforeEach
    void setUp() {
        documentationService = new DocumentationService(propMapper, docMapper, rabbitTemplate);
    }

    private MultipartFile createMockFile(String content) {
        return new MockMultipartFile(
                "source",
                "Component.tsx",
                "text/typescript",
                content.getBytes(StandardCharsets.UTF_8)
        );
    }

    @Nested
    @DisplayName("React Props解析测试")
    class ReactPropsParsingTests {

        @Test
        @DisplayName("解析TypeScript interface声明的Props")
        void shouldParseInterfaceProps() throws IOException {
            String source = """
                    interface ButtonProps {
                      /** 按钮类型 */
                      type?: 'primary' | 'secondary' | 'danger';
                      /** 按钮尺寸 */
                      size: 'small' | 'medium' | 'large';
                      /** 是否禁用 */
                      disabled?: boolean;
                      /** 点击事件 */
                      onClick?: () => void;
                    }
                    
                    const Button: React.FC<ButtonProps> = (props) => {
                      return <button {...props} />;
                    };
                    
                    Button.defaultProps = {
                      type: 'secondary',
                      disabled: false
                    };
                    """;

            MultipartFile file = createMockFile(source);
            List<ComponentProp> props = documentationService.extractPropsFromSource(1L, file, "react");

            assertNotNull(props);
            assertEquals(4, props.size());

            ComponentProp typeProp = props.stream().filter(p -> p.getName().equals("type")).findFirst().orElse(null);
            assertNotNull(typeProp);
            assertEquals("'primary' | 'secondary' | 'danger'", typeProp.getPropType());
            assertEquals("'secondary'", typeProp.getDefaultValue());
            assertEquals("按钮类型", typeProp.getDescription().trim());
            assertEquals(0, typeProp.getRequired());

            ComponentProp sizeProp = props.stream().filter(p -> p.getName().equals("size")).findFirst().orElse(null);
            assertNotNull(sizeProp);
            assertEquals("'small' | 'medium' | 'large'", sizeProp.getPropType());
            assertEquals(1, sizeProp.getRequired());
        }

        @Test
        @DisplayName("解析TypeScript type alias声明的Props")
        void shouldParseTypeAliasProps() throws IOException {
            String source = """
                    type InputProps = {
                      /** 占位符 */
                      placeholder?: string;
                      /** 值 */
                      value?: string;
                      /** 变化回调 */
                      onChange?: (value: string) => void;
                    };
                    
                    const Input = (props: InputProps) => {
                      return <input {...props} />;
                    };
                    """;

            MultipartFile file = createMockFile(source);
            List<ComponentProp> props = documentationService.extractPropsFromSource(1L, file, "react");

            assertNotNull(props);
            assertEquals(3, props.size());

            ComponentProp placeholderProp = props.stream().filter(p -> p.getName().equals("placeholder")).findFirst().orElse(null);
            assertNotNull(placeholderProp);
            assertEquals("string", placeholderProp.getPropType());
            assertEquals("占位符", placeholderProp.getDescription().trim());
        }

        @Test
        @DisplayName("解析带extends继承的interface Props")
        void shouldParseExtendedInterfaceProps() throws IOException {
            String source = """
                    interface BaseButtonProps {
                      /** 是否禁用 */
                      disabled?: boolean;
                      /** 点击事件 */
                      onClick?: () => void;
                    }
                    
                    interface ButtonProps extends BaseButtonProps {
                      /** 按钮类型 */
                      type?: 'primary' | 'secondary';
                      /** 按钮尺寸 */
                      size?: 'small' | 'large';
                    }
                    
                    const Button: React.FC<ButtonProps> = (props) => <button {...props} />;
                    """;

            MultipartFile file = createMockFile(source);
            List<ComponentProp> props = documentationService.extractPropsFromSource(1L, file, "react");

            assertNotNull(props);
            assertTrue(props.size() >= 2);
        }

        @Test
        @DisplayName("解析使用Pick/Omit工具类型的Props")
        void shouldParseUtilityTypeProps() throws IOException {
            String source = """
                    interface BaseProps {
                      disabled?: boolean;
                      size?: string;
                      color?: string;
                      variant?: string;
                    }
                    
                    type ButtonProps = Pick<BaseProps, 'disabled' | 'size'> & {
                      /** 按钮文本 */
                      children?: React.ReactNode;
                    };
                    
                    type LinkProps = Omit<BaseProps, 'disabled'> & {
                      /** 链接地址 */
                      href: string;
                    };
                    """;

            MultipartFile file = createMockFile(source);
            List<ComponentProp> props = documentationService.extractPropsFromSource(1L, file, "react");

            assertNotNull(props);
            assertTrue(props.size() > 0);
        }

        @Test
        @DisplayName("解析defaultProps静态属性")
        void shouldParseDefaultProps() throws IOException {
            String source = """
                    interface AlertProps {
                      /** 警告类型 */
                      type?: 'success' | 'warning' | 'error' | 'info';
                      /** 是否可关闭 */
                      closable?: boolean;
                      /** 显示时长 */
                      duration?: number;
                    }
                    
                    class Alert extends React.Component<AlertProps> {
                      static defaultProps = {
                        type: 'info',
                        closable: true,
                        duration: 3000
                      };
                      
                      render() {
                        return <div />;
                      }
                    }
                    """;

            MultipartFile file = createMockFile(source);
            List<ComponentProp> props = documentationService.extractPropsFromSource(1L, file, "react");

            assertNotNull(props);
            assertEquals(3, props.size());

            ComponentProp typeProp = props.stream().filter(p -> p.getName().equals("type")).findFirst().orElse(null);
            assertNotNull(typeProp);
            assertEquals("'info'", typeProp.getDefaultValue());

            ComponentProp durationProp = props.stream().filter(p -> p.getName().equals("duration")).findFirst().orElse(null);
            assertNotNull(durationProp);
            assertEquals("3000", durationProp.getDefaultValue());
        }

        @Test
        @DisplayName("解析PropTypes声明（旧版React）")
        void shouldParsePropTypes() throws IOException {
            String source = """
                    import PropTypes from 'prop-types';
                    
                    const Card = ({ title, content, actions, onClick }) => {
                      return <div />;
                    };
                    
                    Card.propTypes = {
                      title: PropTypes.string.isRequired,
                      content: PropTypes.string,
                      actions: PropTypes.arrayOf(PropTypes.node),
                      onClick: PropTypes.func
                    };
                    
                    Card.defaultProps = {
                      content: '',
                      actions: []
                    };
                    """;

            MultipartFile file = createMockFile(source);
            List<ComponentProp> props = documentationService.extractPropsFromSource(1L, file, "react");

            assertNotNull(props);
            assertEquals(4, props.size());

            ComponentProp titleProp = props.stream().filter(p -> p.getName().equals("title")).findFirst().orElse(null);
            assertNotNull(titleProp);
            assertEquals("string", titleProp.getPropType());
            assertEquals(1, titleProp.getRequired());
        }
    }

    @Nested
    @DisplayName("Vue Props解析测试")
    class VuePropsParsingTests {

        @Test
        @DisplayName("解析Vue Options API的Props")
        void shouldParseVueOptionsApiProps() throws IOException {
            String source = """
                    <template>
                      <div>{{ message }}</div>
                    </template>
                    
                    <script>
                    export default {
                      name: 'HelloWorld',
                      props: {
                        /** 消息内容 */
                        message: {
                          type: String,
                          required: true,
                          default: 'Hello'
                        },
                        /** 字体大小 */
                        fontSize: {
                          type: Number,
                          default: 14
                        },
                        /** 是否加粗 */
                        bold: {
                          type: Boolean,
                          default: false
                        },
                        /** 颜色 */
                        color: String
                      }
                    };
                    </script>
                    """;

            MultipartFile file = createMockFile(source);
            List<ComponentProp> props = documentationService.extractPropsFromSource(1L, file, "vue");

            assertNotNull(props);
            assertEquals(4, props.size());

            ComponentProp messageProp = props.stream().filter(p -> p.getName().equals("message")).findFirst().orElse(null);
            assertNotNull(messageProp);
            assertEquals("String", messageProp.getPropType());
            assertEquals("'Hello'", messageProp.getDefaultValue());
            assertEquals(1, messageProp.getRequired());

            ComponentProp boldProp = props.stream().filter(p -> p.getName().equals("bold")).findFirst().orElse(null);
            assertNotNull(boldProp);
            assertEquals("Boolean", boldProp.getPropType());
            assertEquals("false", boldProp.getDefaultValue());
        }

        @Test
        @DisplayName("解析Vue Composition API的defineProps")
        void shouldParseVueDefineProps() throws IOException {
            String source = """
                    <template>
                      <button :class="classes">
                        <slot />
                      </button>
                    </template>
                    
                    <script setup lang="ts">
                    interface ButtonProps {
                      /** 按钮类型 */
                      type?: 'primary' | 'default' | 'text';
                      /** 按钮尺寸 */
                      size?: 'small' | 'default' | 'large';
                      /** 是否禁用 */
                      disabled?: boolean;
                      /** 加载状态 */
                      loading?: boolean;
                    }
                    
                    const props = withDefaults(defineProps<ButtonProps>(), {
                      type: 'default',
                      size: 'default',
                      disabled: false,
                      loading: false
                    });
                    
                    const classes = computed(() => [
                      'btn',
                      `btn-${props.type}`,
                      `btn-${props.size}`
                    ]);
                    </script>
                    """;

            MultipartFile file = createMockFile(source);
            List<ComponentProp> props = documentationService.extractPropsFromSource(1L, file, "vue");

            assertNotNull(props);
            assertTrue(props.size() > 0);
        }

        @Test
        @DisplayName("解析Vue简写Props（仅类型声明）")
        void shouldParseVueShorthandProps() throws IOException {
            String source = """
                    <template>
                      <input :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" />
                    </template>
                    
                    <script>
                    export default {
                      name: 'Input',
                      props: ['modelValue', 'placeholder', 'disabled']
                    };
                    </script>
                    """;

            MultipartFile file = createMockFile(source);
            List<ComponentProp> props = documentationService.extractPropsFromSource(1L, file, "vue");

            assertNotNull(props);
            assertTrue(props.size() > 0);
        }
    }

    @Nested
    @DisplayName("异常容错测试")
    class ErrorHandlingTests {

        @Test
        @DisplayName("遇到语法错误的源码应跳过该文件，标记解析失败不阻塞其他组件")
        void shouldHandleMalformedSourceCodeGracefully() throws IOException {
            String malformedSource = """
                    interface BrokenProps {
                      /** 不完整的声明
                      name: string;
                      // 缺少闭合括号
                    
                    const BrokenComponent = (props) => {
                      return <div />;
                    };
                    """;

            MultipartFile file = createMockFile(malformedSource);

            assertDoesNotThrow(() -> {
                List<ComponentProp> props = documentationService.extractPropsFromSource(1L, file, "react");
                assertNotNull(props);
            });
        }

        @Test
        @DisplayName("空文件应返回空列表不抛出异常")
        void shouldHandleEmptyFile() throws IOException {
            MultipartFile file = createMockFile("");

            List<ComponentProp> props = documentationService.extractPropsFromSource(1L, file, "react");

            assertNotNull(props);
            assertTrue(props.isEmpty());
        }

        @Test
        @DisplayName("不支持的框架应抛出异常")
        void shouldRejectUnsupportedFramework() {
            MultipartFile file = createMockFile("test");

            assertThrows(IllegalArgumentException.class, () -> {
                documentationService.extractPropsFromSource(1L, file, "angular");
            });
        }

        @Test
        @DisplayName("无法识别的Props格式应返回空列表")
        void shouldHandleUnrecognizedPropsFormat() throws IOException {
            String source = """
                    // 完全没有Props声明的组件
                    const SimpleComponent = () => {
                      return <div>Hello</div>;
                    };
                    """;

            MultipartFile file = createMockFile(source);
            List<ComponentProp> props = documentationService.extractPropsFromSource(1L, file, "react");

            assertNotNull(props);
            assertTrue(props.isEmpty());
        }
    }

    @Nested
    @DisplayName("JSDoc注释提取测试")
    class JSDocExtractionTests {

        @Test
        @DisplayName("提取JSDoc注释中的@example示例代码")
        void shouldExtractExampleCodeFromJSDoc() throws IOException {
            String source = """
                    /**
                     * 按钮组件
                     *
                     * 这是一个通用的按钮组件，支持多种样式和状态。
                     *
                     * @title 按钮 Button
                     * @example
                     * <Button type="primary" onClick={() => console.log('clicked')}>
                     *   点击我
                     * </Button>
                     *
                     * @example
                     * <Button type="danger" disabled>
                     *   禁用按钮
                     * </Button>
                     */
                    interface ButtonProps {
                      /** 按钮类型 */
                      type?: string;
                      /** 是否禁用 */
                      disabled?: boolean;
                    }
                    
                    const Button = (props: ButtonProps) => <button {...props} />;
                    """;

            MultipartFile file = createMockFile(source);

            assertDoesNotThrow(() -> {
                var docs = documentationService.extractDocsFromSource(1L, file);
                assertNotNull(docs);
            });
        }

        @Test
        @DisplayName("无JSDoc注释的组件也应能生成基本文档")
        void shouldGenerateBasicDocsWithoutJSDoc() throws IOException {
            String source = """
                    interface SimpleProps {
                      name: string;
                    }
                    
                    const Simple = (props: SimpleProps) => <div>{props.name}</div>;
                    """;

            MultipartFile file = createMockFile(source);

            assertDoesNotThrow(() -> {
                var docs = documentationService.extractDocsFromSource(1L, file);
                assertNotNull(docs);
                assertFalse(docs.isEmpty());
            });
        }
    }
}
