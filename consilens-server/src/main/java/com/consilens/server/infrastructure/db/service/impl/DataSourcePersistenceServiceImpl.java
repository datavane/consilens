package com.consilens.server.infrastructure.db.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.consilens.server.infrastructure.db.entity.DataSourceEntity;
import com.consilens.server.infrastructure.db.mapper.DataSourceMapper;
import com.consilens.server.infrastructure.db.service.DataSourcePersistenceService;
import org.springframework.stereotype.Service;

@Service
public class DataSourcePersistenceServiceImpl extends ServiceImpl<DataSourceMapper, DataSourceEntity>
        implements DataSourcePersistenceService {
}
