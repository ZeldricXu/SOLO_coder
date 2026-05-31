package com.cdcsync.metadata.service.impl;

import com.cdcsync.common.service.AbstractBaseService;
import com.cdcsync.metadata.domain.DataSource;
import com.cdcsync.metadata.mapper.DataSourceMapper;
import com.cdcsync.metadata.service.DataSourceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DataSourceServiceImpl extends AbstractBaseService<DataSource, String, DataSourceMapper> implements DataSourceService {

    public DataSourceServiceImpl(DataSourceMapper mapper) {
        super(mapper);
    }

    @Override
    protected void setId(DataSource entity, String id) {
        entity.setId(id);
    }

    @Override
    protected String getId(DataSource entity) {
        return entity.getId();
    }
}
