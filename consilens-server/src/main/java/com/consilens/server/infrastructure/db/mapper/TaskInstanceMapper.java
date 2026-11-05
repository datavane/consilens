package com.consilens.server.infrastructure.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consilens.server.infrastructure.db.entity.TaskInstanceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TaskInstanceMapper extends BaseMapper<TaskInstanceEntity> {

    @Select("SELECT * FROM cs_task_instance WHERE serial_no = #{serialNo} FOR UPDATE")
    TaskInstanceEntity lockBySerialNo(@Param("serialNo") String serialNo);
}
