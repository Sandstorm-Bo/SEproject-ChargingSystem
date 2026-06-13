package com.charging.station.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 调度触发器：把“业务状态已变更”的信号转交给事件驱动编排器 {@link ChargingScheduler}。
 *
 * <p>两点关键设计：
 * <ol>
 *   <li><b>事务提交后才触发</b>：在 @Transactional 服务方法内调用 {@link #afterCommitReconcile()} 时，
 *       通过事务同步把对账推迟到【提交之后】执行，避免对账读到未提交状态、也避免为可能回滚的会话排定时器。
 *       与 {@link StationLock}“锁加在事务边界之外”的理由一致。</li>
 *   <li><b>惰性、可缺省</b>：用 {@link ObjectProvider} 惰性获取编排器。当 {@code charging.scheduler.enabled=false}
 *       （单测）时编排器整个不创建，这里自动空操作，从而保持单测“纯手工驱动、零自动调度”的确定性。</li>
 * </ol>
 */
@Component
public class SchedulerTrigger {

    @Autowired
    private ObjectProvider<ChargingScheduler> scheduler;

    /** 在当前事务【提交后】触发一次对账（等候区→桩排队→队首即时开充→为新会话排定“充满”定时器）。无事务则立即触发。 */
    public void afterCommitReconcile() {
        ChargingScheduler s = scheduler.getIfAvailable();
        if (s == null) {
            return; // 编排器未启用（测试态）：空操作
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    s.onSchedulingEvent();
                }
            });
        } else {
            s.onSchedulingEvent();
        }
    }

    /** 倍速 / 桩功率变化：在充会话的“充满真实时刻”随之改变，立即取消并按新值重排所有在充定时器。 */
    public void onRateChange() {
        ChargingScheduler s = scheduler.getIfAvailable();
        if (s != null) {
            s.onSpeedChange();
        }
    }
}
