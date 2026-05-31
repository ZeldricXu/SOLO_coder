package com.cdcsync.vectorindex;

import com.cdcsync.common.exception.BusinessException;
import com.cdcsync.test.builder.TestDataFactory;
import com.cdcsync.test.builder.VectorIndexBuilder;
import com.cdcsync.vectorindex.domain.VectorIndex;
import com.cdcsync.vectorindex.mapper.VectorIndexMapper;
import com.cdcsync.vectorindex.service.impl.VectorIndexServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VectorIndexServiceImpl 单元测试")
class VectorIndexServiceImplTest {

    @Mock
    private VectorIndexMapper mapper;

    @InjectMocks
    private VectorIndexServiceImpl service;

    private VectorIndex sampleIndex;

    @BeforeEach
    void setUp() {
        sampleIndex = VectorIndexBuilder.aVectorIndex()
                .withDefaults()
                .withId("test-index-001")
                .withDimension(16)
                .build();
    }

    @Nested
    @DisplayName("索引构建测试")
    class BuildIndexTests {

        @Test
        @DisplayName("构建索引 - 应成功并更新状态为READY")
        void buildIndex_Success_ShouldSetStatusToReady() {
            when(mapper.selectById(sampleIndex.getId())).thenReturn(sampleIndex);
            when(mapper.updateById(any(VectorIndex.class))).thenReturn(1);

            List<float[]> vectors = TestDataFactory.createRandomVectors(10, 16);
            service.buildIndex(sampleIndex.getId(), vectors);

            verify(mapper, times(1)).updateById(any(VectorIndex.class));
            assertThat(sampleIndex.getStatus()).isEqualTo("READY");
            assertThat(sampleIndex.getVectorCount()).isEqualTo(10L);
        }

        @Test
        @DisplayName("构建索引 - 索引不存在应抛出异常")
        void buildIndex_NotFound_ShouldThrowException() {
            when(mapper.selectById("non-existent")).thenReturn(null);

            List<float[]> vectors = TestDataFactory.createRandomVectors(5, 16);

            assertThatThrownBy(() -> service.buildIndex("non-existent", vectors))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Vector index not found");
        }

        @Test
        @DisplayName("构建索引 - 空向量列表应抛出异常")
        void buildIndex_EmptyVectors_ShouldThrowException() {
            when(mapper.selectById(sampleIndex.getId())).thenReturn(sampleIndex);

            assertThatThrownBy(() -> service.buildIndex(sampleIndex.getId(), List.of()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Vectors cannot be empty");
        }
    }

    @Nested
    @DisplayName("向量搜索测试")
    class SearchTests {

        @Test
        @DisplayName("搜索向量 - 索引未就绪应抛出异常")
        void search_IndexNotReady_ShouldThrowException() {
            VectorIndex notReadyIndex = VectorIndexBuilder.aVectorIndex()
                    .withDefaults()
                    .withId("not-ready")
                    .withStatus("CREATING")
                    .withDimension(16)
                    .build();

            when(mapper.selectById("not-ready")).thenReturn(notReadyIndex);

            float[] query = TestDataFactory.createRandomVector(16);

            assertThatThrownBy(() -> service.search("not-ready", query, 10))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Index is not ready");
        }
    }

    @Nested
    @DisplayName("向量添加测试")
    class AddVectorsTests {

        @Test
        @DisplayName("添加向量 - 索引不存在应抛出异常")
        void addVectors_NotFound_ShouldThrowException() {
            when(mapper.selectById("non-existent")).thenReturn(null);

            List<float[]> vectors = TestDataFactory.createRandomVectors(5, 16);

            assertThatThrownBy(() -> service.addVectors("non-existent", vectors))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Vector index not found");
        }
    }
}
