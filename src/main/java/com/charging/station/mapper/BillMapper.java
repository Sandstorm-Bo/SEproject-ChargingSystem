package com.charging.station.mapper;

import com.charging.station.domain.Bill;
import com.charging.station.domain.DetailedList;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 账单 Mapper
 * 根据设计文档 3.2 节定义
 */
@Mapper
public interface BillMapper {

    /**
     * 根据车辆编号和日期查询账单
     */
    List<Bill> getBillsByCarIdAndDate(@Param("carId") String carId, @Param("date") LocalDateTime date);

    /**
     * 根据账单编号查询详单
     */
    List<DetailedList> getDetailedListByBillId(@Param("billId") String billId);

    /**
     * 按用户查询其名下所有车辆的详单（联查账单/车辆，含车牌）
     */
    List<DetailedList> getDetailsByUserId(@Param("userId") String userId);

    /**
     * 按车辆查询详单（联查账单，含车牌）
     */
    List<DetailedList> getDetailsByCarId(@Param("carId") String carId);

    /**
     * 插入账单
     */
    void insertBill(Bill bill);

    /**
     * 插入详单
     */
    void insertDetailedList(DetailedList detail);
}
