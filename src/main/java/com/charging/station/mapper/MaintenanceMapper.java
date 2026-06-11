package com.charging.station.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 运维/统计辅助 Mapper（纯增量，供监控大屏与运维控制台使用）。
 * - sumRevenue：累计营收（账单总额之和），用于大屏 KPI。
 * - reset*：一键重置充电业务数据（等价 reset_db.sh，供控制台「重置」按钮使用）。
 */
@Mapper
public interface MaintenanceMapper {

    /** 累计营收：所有账单总费用之和（元） */
    @Select("SELECT COALESCE(SUM(total_fee), 0) FROM bill")
    double sumRevenue();

    // ---- 一键重置（按外键依赖顺序：子表先删） ----

    @Delete("DELETE FROM detailed_list")
    void deleteAllDetails();

    @Delete("DELETE FROM bill")
    void deleteAllBills();

    @Delete("DELETE FROM charging_session")
    void deleteAllSessions();

    @Delete("DELETE FROM charging_request")
    void deleteAllRequests();

    @Update("UPDATE waiting_queue SET queue_length = 0")
    void resetWaitingQueues();

    @Update("UPDATE charging_queue SET current_length = 0")
    void resetChargingQueues();

    /** 复位所有充电桩：恢复空闲可调度、清故障原因与累计统计 */
    @Update("UPDATE charging_pile SET status = 'IDLE', is_schedulable = TRUE, fault_reason = NULL, "
            + "total_charge_count = 0, total_charge_duration = 0, total_charge_amount = 0, last_run_time = NOW()")
    void resetPiles();

    @Update("UPDATE vehicle SET car_state = 'IDLE', current_battery_level = 0")
    void resetVehicles();
}
