package com.flowplatform.test;

import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;

@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
public abstract class BaseUnitTest {

    protected static final String TEST_USER = "test_user";
    protected static final Long TEST_USER_ID = 1L;
    protected static final String TEST_FORM_KEY = "form_test_001";
    protected static final String TEST_PROCESS_KEY = "process_test_001";
}
