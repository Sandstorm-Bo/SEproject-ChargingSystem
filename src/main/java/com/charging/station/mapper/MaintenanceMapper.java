package com.charging.station.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 运维/统计辅助 Mapper（纯增量，供监控大屏与运维控制台使用）。
 * - sumRevenue：累计营收（账单总额之和），用于大屏 KPI。
 * - selectReport：按时间维度 × 充电桩聚合详单，生成报表行。
 * - reset*：一键重置充电业务数据（等价 reset_db.sh，供控制台「重置」按钮使用）。
 */
@Mapper
public interface MaintenanceMapper {

    /** 累计营收：所有账单总费用之和（元） */
    @Select("SELECT COALESCE(SUM(total_fee), 0) FROM bill")
    double sumRevenue();

    /**
     * 报表聚合：按 fmt 指定的时间粒度（日 %Y-%m-%d / 周 %x-W%v / 月 %Y-%m）
     * 与充电桩分组，统计累计充电次数、时长、电量与各项费用。
     * 详单 start_time 已是仿真时间轴时刻，报表时间维度与验收场景一致。
     */
    @Select("SELECT DATE_FORMAT(d.start_time, #{fmt}) AS time, "
            + "p.pile_no AS pileNo, d.pile_id AS pileId, "
            + "COUNT(*) AS chargeCount, "
            + "ROUND(SUM(d.charge_duration), 1) AS totalDuration, "
            + "ROUND(SUM(d.charge_amount), 2) AS totalAmount, "
            + "ROUND(SUM(d.charge_fee), 2) AS totalChargeFee, "
            + "ROUND(SUM(d.service_fee), 2) AS totalServiceFee, "
            + "ROUND(SUM(d.subtotal_fee), 2) AS totalFee "
            + "FROM detailed_list d JOIN charging_pile p ON d.pile_id = p.pile_id "
            + "GROUP BY time, pileNo, pileId "
            + "ORDER BY time DESC, pileNo")
    List<Map<String, Object>> selectReport(@Param("fmt") String fmt);

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

    // ---- 验收参数重配：重建充电桩拓扑（按外键依赖顺序，业务数据须先清空） ----

    @Delete("DELETE FROM charging_queue")
    void deleteAllChargingQueues();

    @Delete("DELETE FROM charging_pile")
    void deleteAllPiles();

    /** 设置全部等候区（快/慢）的最大容量并清零当前长度（WaitingAreaSize 可调） */
    @Update("UPDATE waiting_queue SET max_capacity = #{cap}, queue_length = 0")
    void setWaitingAreaCapacity(@Param("cap") int cap);
}
