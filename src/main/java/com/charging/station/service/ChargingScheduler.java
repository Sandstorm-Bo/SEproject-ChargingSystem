package com.charging.station.service;

import com.charging.station.domain.ChargingPile;
import com.charging.station.domain.ChargingRequest;
import com.charging.station.domain.ChargingSession;
import com.charging.station.enums.CarState;
import com.charging.station.enums.PileStatus;
import com.charging.station.mapper.PileMapper;
import com.charging.station.mapper.RequestMapper;
import com.charging.station.mapper.SessionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 充电生命周期调度器。
 * 每秒驱动一次：等候区 -> 桩排队区(dispatch) -> 空闲桩队首自动开始充电
 * -> 按加速时间推进 -> 充满自动结束并出账单。
 * 可通过配置 charging.scheduler.enabled=false 关闭（测试环境关闭，保证事务隔离与确定性）。
 */
@Service
@ConditionalOnProperty(name = "charging.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class ChargingScheduler {

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private ChargingApplicationService chargingApplicationService;

    @Autowired
    private PileMapper pileMapper;

    @Autowired
    private RequestMapper requestMapper;

    @Autowired
    private SessionMapper sessionMapper;

    @Scheduled(fixedRate = 1000)
    public void tick() {
        // 1) 等候区 -> 桩排队区
        safely(dispatchService::dispatchWhenEmptySlot);
        // 2) 空闲桩队首 -> 开始充电
        safely(this::startReadyCharging);
        // 3) 充满 -> 结束并出账单
        safely(this::finishCompletedCharging);
    }

    /** 对每个“可调度且空闲（无车在充）”的桩，自动启动其排队区队首车辆充电 */
    private void startReadyCharging() {
        for (ChargingPile pile : pileMapper.getAllChargingPiles()) {
            if (pile.getStatus() != PileStatus.IDLE || !Boolean.TRUE.equals(pile.getIsSchedulable())) {
                continue;
            }
            ChargingRequest head = firstAtPileHead(pile.getPileId());
            if (head == null) {
                continue;
            }
            if (head.getRequestStatus() == CarState.CALLED) {
                // 上一周期已叫号，本周期正式开始充电
                safely(() -> chargingApplicationService.startCharging(head.getCarId(), pile.getPileId()));
            } else if (head.getRequestStatus() == CarState.QUEUED_AT_PILE) {
                // 刚到达队首：先叫号置 CALLED，下一调度周期再开始充电
                safely(() -> chargingApplicationService.callVehicle(head.getCarId()));
            }
        }
    }

    /** 对每个进行中的会话，按加速时间判断是否充满，充满则结束并出账单 */
    private void finishCompletedCharging() {
        for (ChargingPile pile : pileMapper.getAllChargingPiles()) {
            ChargingSession session = sessionMapper.getActiveSessionByPileId(pile.getPileId());
            if (session == null) {
                continue;
            }
            double charged = session.calculateCurrentAmount(pile.getRatedPower(), LocalDateTime.now());
            if (charged >= session.getRequestAmount() - 1e-6) {
                safely(() -> chargingApplicationService.endCharging(session.getCarId(), pile.getPileId()));
            }
        }
    }

    /** 取某桩排队区队首（QUEUED_AT_PILE 或 CALLED，按 queue_position 升序）的请求 */
    private ChargingRequest firstAtPileHead(String pileId) {
        for (ChargingRequest r : requestMapper.getRequestsByPileId(pileId)) {
            if (r.getRequestStatus() == CarState.QUEUED_AT_PILE || r.getRequestStatus() == CarState.CALLED) {
                return r;
            }
        }
        return null;
    }

    private void safely(Runnable action) {
        try {
            action.run();
        } catch (Exception ignored) {
            // 某一步失败不应中断整个调度循环，下次 tick 继续
        }
    }
}
