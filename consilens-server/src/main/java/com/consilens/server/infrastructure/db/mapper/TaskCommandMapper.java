package com.consilens.server.infrastructure.db.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.consilens.server.infrastructure.db.entity.TaskCommandEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface TaskCommandMapper extends BaseMapper<TaskCommandEntity> {

    @Select("SELECT * FROM cs_task_command " +
            "WHERE status = 'PENDING' " +
            "AND schedule_time <= #{now} " +
            "AND shard_slot % #{totalSlot} = #{currentSlot} " +
            "AND (lock_until IS NULL OR lock_until < #{now}) " +
            "ORDER BY schedule_time LIMIT 1")
    TaskCommandEntity getStartCommand(@Param("totalSlot") int totalSlot,
                                      @Param("currentSlot") int currentSlot,
                                      @Param("now") LocalDateTime now);

    @Update("UPDATE cs_task_command " +
            "SET status = 'CLAIMED', execute_node_key = #{executeNodeKey}, lock_time = #{lockTime}, " +
            "lock_until = #{lockUntil}, updated_at = #{lockTime} " +
            "WHERE id = #{id} AND status = 'PENDING' AND (lock_until IS NULL OR lock_until < #{lockTime})")
    int claim(@Param("id") Long id,
              @Param("executeNodeKey") String executeNodeKey,
              @Param("lockTime") LocalDateTime lockTime,
              @Param("lockUntil") LocalDateTime lockUntil);
}
