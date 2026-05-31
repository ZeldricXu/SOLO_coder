package com.taskplatform.document;

import java.io.IOException;
import java.io.InputStream;

public interface DocumentParser {

    boolean supports(String fileType);

    String parse(InputStream inputStream, String fileType) throws IOException;

    default int getOrder() {
        return 0;
    }
}
