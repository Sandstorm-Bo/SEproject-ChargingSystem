package com.charging.station.mapper;

import com.charging.station.domain.DispatchRecord;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 调度记录 Mapper
 */
@Mapper
public interface DispatchRecordMapper {

    /** 新增调度记录 */
    void insertDispatchRecord(DispatchRecord record);

    /** 查询所有调度记录 */
    List<DispatchRecord> getAllDispatchRecords();
}
