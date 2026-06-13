package com.charging.station.service;

import com.charging.station.domain.ChargingPile;
import com.charging.station.domain.ChargingRequest;
import com.charging.station.domain.ChargingSession;
import com.charging.station.domain.TariffPolicy;
import com.charging.station.domain.Vehicle;
import com.charging.station.enums.CarState;
import com.charging.station.mapper.MaintenanceMapper;
import com.charging.station.mapper.PileMapper;
import com.charging.station.mapper.QueueMapper;
import com.charging.station.mapper.RequestMapper;
import com.charging.station.mapper.SessionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 实时状态广播：每秒向 /topic/status 推送整站快照——
 * 充电桩（含累计统计、故障原因）、充电中车辆进度/费用、全站排队队列、实时电价时段、仿真倍速、累计营收。
 * 仅做数据聚合与读取，不改变任何业务逻辑。
 */
@Service
public class StatusBroadcaster {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private PileMapper pileMapper;

    @Autowired
    private QueueMapper queueMapper;

    @Autowired
    private RequestMapper requestMapper;

    @Autowired
    private SessionMapper sessionMapper;

    @Autowired
    private MaintenanceMapper maintenanceMapper;

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private MonitoringService monitoringService;

    @Scheduled(fixedRate = 1000)
    public void broadcastStatus() {
        try {
            List<ChargingPile> piles = pileMapper.selectAllPiles();
            TariffPolicy policy = queueMapper.getCurrentTariffPolicy();
            LocalDateTime now = com.charging.station.util.SimClock.nowVirtual();

            // 车牌 -> 电池容量 / 所属用户，便于排队表展示（一次查询，避免逐车查询）
            Map<String, Double> capacityByCar = new HashMap<>();
            Map<String, String> userByCar = new HashMap<>();
            for (Vehicle v : requestMapper.selectAllVehicles()) {
                capacityByCar.put(v.getPlateNumber(), v.getBatteryCapacity());
                userByCar.put(v.getPlateNumber(), v.getUserId());
            }
            // 桩号映射，便于排队表展示「在 T3 排队」
            Map<String, String> pileNoById = piles.stream()
                    .collect(Collectors.toMap(ChargingPile::getPileId, ChargingPile::getPileNo, (a, b) -> a));

            // 全站排队/等候车辆（桩前队列 QUEUED_AT_PILE / CALLED）
            List<ChargingRequest> atPileAll = new ArrayList<>();

            List<Map<String, Object>> pilesWithVehicles = piles.stream().map(pile -> {
                Map<String, Object> pileData = new HashMap<>();
                pileData.put("pileId", pile.getPileId());
                pileData.put("pileNo", pile.getPileNo());
                pileData.put("pileType", pile.getPileType());
                pileData.put("status", pile.getStatus());
                pileData.put("ratedPower", pile.getRatedPower());
                pileData.put("queueCapacityM", pile.getQueueCapacityM());
                pileData.put("isSchedulable", pile.getIsSchedulable());
                pileData.put("faultReason", pile.getFaultReason());
                // 累计统计（来自桩自身计数器）
                pileData.put("totalChargeCount", pile.getTotalChargeCount());
                pileData.put("totalChargeDuration", pile.getTotalChargeDuration());
                pileData.put("totalChargeAmount", pile.getTotalChargeAmount());

                // 该桩排队区中等待的车辆（QUEUED_AT_PILE 或 已叫号 CALLED）
                List<ChargingRequest> atPile = requestMapper.getRequestsByPileId(pile.getPileId());
                long queueLength = atPile.stream()
                        .filter(r -> r.getRequestStatus() == CarState.QUEUED_AT_PILE
                                || r.getRequestStatus() == CarState.CALLED)
                        .count();
                pileData.put("queueLength", (int) queueLength);
                atPile.stream()
                        .filter(r -> r.getRequestStatus() == CarState.QUEUED_AT_PILE
                                || r.getRequestStatus() == CarState.CALLED)
                        .forEach(atPileAll::add);

                // 正在充电的车辆（基于活动会话，真实计算进度与费用）
                ChargingSession session = sessionMapper.getActiveSessionByPileId(pile.getPileId());
                if (session != null) {
                    double amount = session.calculateCurrentAmount(pile.getRatedPower(), now);
                    double req = session.getRequestAmount() != null ? session.getRequestAmount() : 0.0;
                    int progress = req > 0 ? (int) Math.min(100, Math.round(amount / req * 100)) : 0;
                    pileData.put("currentCarId", session.getCarId());
                    pileData.put("requestAmount", req);
                    pileData.put("chargedAmount", Math.round(amount * 100) / 100.0);
                    pileData.put("chargeProgress", progress);
                    if (policy != null) {
                        double fee = policy.calculateChargeFee(amount, session.getStartTime().toLocalTime(), pile.getRatedPower())
                                + policy.calculateServiceFee(amount);
                        pileData.put("currentCost", Math.round(fee * 100) / 100.0);
                    } else {
                        pileData.put("currentCost", 0.0);
                    }
                }

                return pileData;
            }).collect(Collectors.toList());

            // 等候区车辆（尚未进入桩排队区）
            List<ChargingRequest> waitingArea = new ArrayList<>();
            waitingArea.addAll(requestMapper.getWaitingRequests("FAST"));
            waitingArea.addAll(requestMapper.getWaitingRequests("TRICKLE"));

            // 合并：等候区 + 桩前队列，按提交时间先后（FIFO）排序，组装排队表
            List<ChargingRequest> queueAll = new ArrayList<>();
            queueAll.addAll(waitingArea);
            queueAll.addAll(atPileAll);
            queueAll.sort(Comparator.comparing(ChargingRequest::getRequestTime,
                    Comparator.nullsLast(Comparator.naturalOrder())));

            List<Map<String, Object>> waitingList = queueAll.stream().map(r -> {
                Map<String, Object> row = new HashMap<>();
                row.put("queueNum", r.getQueueNum());
                row.put("carId", r.getCarId());
                row.put("userId", userByCar.get(r.getCarId()));
                row.put("mode", r.getRequestMode());           // FAST / TRICKLE
                row.put("requestAmount", r.getRequestAmount());
                row.put("status", r.getRequestStatus());        // WAITING / QUEUED_AT_PILE / CALLED
                row.put("pileId", r.getPileId());
                row.put("pileNo", r.getPileId() != null ? pileNoById.get(r.getPileId()) : null);
                row.put("queuePosition", r.getQueuePosition());
                row.put("batteryCapacity", capacityByCar.get(r.getCarId()));
                if (r.getRequestTime() != null) {
                    // requestTime 已是仿真时间轴上的提交时刻；排队时长 = 当前虚拟时刻 - 提交虚拟时刻
                    LocalDateTime vReq = r.getRequestTime();
                    row.put("requestTime", vReq.format(HM));
                    long waitSec = java.time.Duration.between(vReq,
                            com.charging.station.util.SimClock.nowVirtual()).getSeconds();
                    row.put("waitMinutes", Math.max(0, waitSec) / 60.0);
                } else {
                    row.put("requestTime", null);
                    row.put("waitMinutes", 0.0);
                }
                return row;
            }).collect(Collectors.toList());

            Map<String, Object> status = new HashMap<>();
            status.put("piles", pilesWithVehicles);
            status.put("fastQueue", queueMapper.selectQueueByType("FAST"));
            status.put("trickleQueue", queueMapper.selectQueueByType("TRICKLE"));
            status.put("waiting", waitingList);
            status.put("waitingAreaCount", waitingArea.size());
            status.put("queuedAtPileCount", atPileAll.size());
            status.put("simSpeed", ChargingSession.TIME_ACCELERATION);
            status.put("simTime", com.charging.station.util.SimClock.nowVirtual().format(HM));
            status.put("simClockEnabled", com.charging.station.util.SimClock.isEnabled());
            status.put("revenueTotal", Math.round(maintenanceMapper.sumRevenue() * 100) / 100.0);

            // 2.8b 批量调度触发门槛：到站车辆数 / 全部车位数(充电区+等候区)
            Map<String, Object> batchGate = new HashMap<>();
            int arrived = dispatchService.countArrivedCars();
            int capacity = dispatchService.totalStationCapacity();
            batchGate.put("arrived", arrived);
            batchGate.put("capacity", capacity);
            batchGate.put("ready", arrived >= capacity);
            status.put("batchGate", batchGate);
            status.put("autoDispatch", com.charging.station.service.ChargingScheduler.isAutoDispatch());
            status.put("stationConfig", monitoringService.getStationConfig());

            // 实时电价时段
            if (policy != null) {
                Map<String, Object> tariff = new HashMap<>();
                tariff.put("peakPrice", policy.getPeakPrice());
                tariff.put("flatPrice", policy.getFlatPrice());
                tariff.put("valleyPrice", policy.getValleyPrice());
                tariff.put("serviceFee", policy.getServiceFeePerKwh());
                String period = currentPeriod(com.charging.station.util.SimClock.nowVirtual().toLocalTime());
                tariff.put("period", period);
                tariff.put("currentPrice", "PEAK".equals(period) ? policy.getPeakPrice()
                        : "FLAT".equals(period) ? policy.getFlatPrice() : policy.getValleyPrice());
                status.put("tariff", tariff);
            }

            status.put("timestamp", System.currentTimeMillis());

            messagingTemplate.convertAndSend("/topic/status", status);
        } catch (Exception e) {
            // 忽略单次推送错误，下次继续
        }
    }

    /** 当前时段：峰 [10,15)∪[18,21)；平 [7,10)∪[15,18)∪[21,23)；其余为谷。与 TariffPolicy 一致。 */
    private String currentPeriod(LocalTime t) {
        int h = t.getHour();
        if ((h >= 10 && h < 15) || (h >= 18 && h < 21)) return "PEAK";
        if ((h >= 7 && h < 10) || (h >= 15 && h < 18) || (h >= 21 && h < 23)) return "FLAT";
        return "VALLEY";
    }
}
