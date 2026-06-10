package com.charging.station.service;

import com.charging.station.domain.ChargingPile;
import com.charging.station.domain.ChargingQueue;
import com.charging.station.domain.ChargingRequest;
import com.charging.station.domain.ChargingSession;
import com.charging.station.domain.TariffPolicy;
import com.charging.station.enums.CarState;
import com.charging.station.mapper.PileMapper;
import com.charging.station.mapper.QueueMapper;
import com.charging.station.mapper.RequestMapper;
import com.charging.station.mapper.SessionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 实时状态广播：每秒向 /topic/status 推送所有充电桩、排队区、充电中车辆及进度/费用。
 */
@Service
public class StatusBroadcaster {

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

    @Scheduled(fixedRate = 1000)
    public void broadcastStatus() {
        try {
            List<ChargingPile> piles = pileMapper.selectAllPiles();
            TariffPolicy policy = queueMapper.getCurrentTariffPolicy();
            LocalDateTime now = LocalDateTime.now();

            List<Map<String, Object>> pilesWithVehicles = piles.stream().map(pile -> {
                Map<String, Object> pileData = new HashMap<>();
                pileData.put("pileId", pile.getPileId());
                pileData.put("pileNo", pile.getPileNo());
                pileData.put("pileType", pile.getPileType());
                pileData.put("status", pile.getStatus());
                pileData.put("ratedPower", pile.getRatedPower());

                // 该桩排队区中等待的车辆数（QUEUED_AT_PILE 或 已叫号 CALLED）
                List<ChargingRequest> atPile = requestMapper.getRequestsByPileId(pile.getPileId());
                long queueLength = atPile.stream()
                        .filter(r -> r.getRequestStatus() == CarState.QUEUED_AT_PILE
                                || r.getRequestStatus() == CarState.CALLED)
                        .count();
                pileData.put("queueLength", (int) queueLength);

                // 正在充电的车辆（基于活动会话，真实计算进度与费用）
                ChargingSession session = sessionMapper.getActiveSessionByPileId(pile.getPileId());
                if (session != null) {
                    double amount = session.calculateCurrentAmount(pile.getRatedPower(), now);
                    double req = session.getRequestAmount() != null ? session.getRequestAmount() : 0.0;
                    int progress = req > 0 ? (int) Math.min(100, Math.round(amount / req * 100)) : 0;
                    pileData.put("currentCarId", session.getCarId());
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

            List<ChargingQueue> fastQueue = queueMapper.selectQueueByType("FAST");
            List<ChargingQueue> trickleQueue = queueMapper.selectQueueByType("TRICKLE");

            Map<String, Object> status = new HashMap<>();
            status.put("piles", pilesWithVehicles);
            status.put("fastQueue", fastQueue);
            status.put("trickleQueue", trickleQueue);
            status.put("timestamp", System.currentTimeMillis());

            messagingTemplate.convertAndSend("/topic/status", status);
        } catch (Exception e) {
            // 忽略单次推送错误，下次继续
        }
    }
}
