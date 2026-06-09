package com.company.dbstudio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("测试框架验证测试")
class FrameworkTest {

    @Test
    @DisplayName("JUnit 5 + AssertJ 正常工作")
    void junitAndAssertJ_ShouldWork() {
        assertThat(1 + 1).isEqualTo(2);
        assertThat("Hello DBStudio").isNotEmpty();
        assertAll(
                () -> assertThat(true).isTrue(),
                () -> assertThat("test").hasSize(4)
        );
    }

    @Test
    @DisplayName("参数化测试支持")
    void parameterizedTest_ShouldBeSupported() {
        int[] values = {1, 2, 3, 4, 5};
        for (int value : values) {
            assertThat(value).isPositive();
        }
    }
}
