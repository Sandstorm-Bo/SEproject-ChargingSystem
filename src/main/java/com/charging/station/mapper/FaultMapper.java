package com.charging.station.mapper;

import com.charging.station.domain.FaultRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 故障记录 Mapper
 */
@Mapper
public interface FaultMapper {

    /** 新增故障记录 */
    void insertFaultRecord(FaultRecord record);

    /** 将该桩当前未恢复(OPEN)的故障记录标记为已恢复 */
    void markRecovered(@Param("pileId") String pileId, @Param("recoveredAt") LocalDateTime recoveredAt);

    /** 查询所有故障记录 */
    List<FaultRecord> getAllFaultRecords();
}
