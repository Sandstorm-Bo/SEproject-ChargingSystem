package com.charging.station.mapper;

import com.charging.station.domain.ChargingPile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 充电桩 Mapper
 * 根据设计文档 3.3 节定义
 */
@Mapper
public interface PileMapper {

    /**
     * 根据编号查询单个充电桩
     */
    ChargingPile getPile(@Param("pileId") String pileId);

    /**
     * 查询所有充电桩
     */
    List<ChargingPile> getAllChargingPiles();

    /**
     * 查询所有充电桩（别名）
     */
    List<ChargingPile> selectAllPiles();

    /**
     * 更新充电桩状态
     */
    void updatePileState(ChargingPile pile);

    /**
     * 插入新充电桩（初始化数据用）
     */
    void insertPile(ChargingPile pile);
}
