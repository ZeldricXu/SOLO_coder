package com.apishield.classification.scanner;

import com.apishield.classification.domain.DataClassification;
import com.apishield.classification.domain.ScanJob;
import java.util.List;

public interface DataScanner {
    String getScannerType();
    List<DataClassification> scan(ScanJob job);
    boolean supports(String dataSource);
}
