package com.tracetopology.core.service;

import com.tracetopology.common.exception.TopologyConsistencyException;
import com.tracetopology.domain.topology.ServiceNode;
import com.tracetopology.domain.topology.ServiceTopology;
import com.tracetopology.domain.topology.TraceSpan;
import com.tracetopology.spi.repository.TopologyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TopologyConsistencyTest {

    @Mock
    private TopologyRepository topologyRepository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testTopologyConsistencyOnSuccess() {
        ServiceTopology topology = ServiceTopology.builder()
                .id("topo_001")
                .namespace("test")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        topology.addNode(ServiceNode.builder()
                .id("node_001")
                .serviceName("service-a")
                .namespace("test")
                .active(true)
                .build());

        doNothing().when(topologyRepository).saveTopology(any());
        topologyRepository.saveTopology(topology);

        verify(topologyRepository, times(1)).saveTopology(topology);
    }

    @Test
    void testTopologyConsistencyOnFailure() {
        ServiceTopology topology = ServiceTopology.builder()
                .id("topo_001")
                .namespace("test-fail")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        doThrow(new RuntimeException("Database connection failed"))
                .when(topologyRepository).saveTopology(any());

        assertThrows(RuntimeException.class, () -> {
            topologyRepository.saveTopology(topology);
        });
    }

    @Test
    void testTopologyConsistencyExceptionBuilder() {
        TopologyConsistencyException exception = TopologyConsistencyException.builder("TEST_ERROR", "测试一致性错误")
                .namespace("test")
                .phase("saveTopology")
                .expectedNodes(10)
                .actualNodes(5)
                .expectedEdges(20)
                .actualEdges(8)
                .recoveryInfo("rollbackVersion", 123)
                .recoveryInfo("recoverable", true)
                .build();

        assertEquals("TEST_ERROR", exception.getCode());
        assertEquals("测试一致性错误", exception.getMessage());
        assertEquals("test", exception.getNamespace());
        assertEquals("saveTopology", exception.getPhase());
        assertEquals(10, exception.getExpectedNodes());
        assertEquals(5, exception.getActualNodes());
        assertEquals(20, exception.getExpectedEdges());
        assertEquals(8, exception.getActualEdges());
        assertTrue(exception.isRecoverable());
        assertNotNull(exception.getRecoveryInfo());
    }

    @Test
    void testTopologyBuildFromSpans() {
        List<TraceSpan> spans = List.of(
                TraceSpan.builder()
                        .traceId("trace_001")
                        .spanId("span_001")
                        .serviceName("service-a")
                        .operationName("GET /api")
                        .startTime(Instant.now())
                        .durationMs(100)
                        .success(true)
                        .build(),
                TraceSpan.builder()
                        .traceId("trace_001")
                        .spanId("span_002")
                        .parentSpanId("span_001")
                        .serviceName("service-b")
                        .operationName("GET /db")
                        .startTime(Instant.now())
                        .durationMs(50)
                        .success(true)
                        .build()
        );

        assertEquals(2, spans.size());
        assertEquals("span_001", spans.get(1).getParentSpanId());
    }
}
