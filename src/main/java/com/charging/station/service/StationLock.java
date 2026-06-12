package com.charging.station.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 全站调度互斥锁：串行化所有「改变调度状态」的入口——
 * HTTP 控制器（申请/取消/开始/结束充电、故障注入/恢复、批量调度、重置）、
 * 秒级调度循环（ChargingScheduler.tick）、定时故障注入（FaultSchedulerService）。
 *
 * 为什么锁必须加在控制器/调度器层而不是 @Transactional 服务方法内部：
 * 事务在服务方法返回后才提交，若在方法内部加锁，锁释放发生在提交之前，
 * 下一个持锁者可能读到未提交前的旧状态（check-then-act 竞态）。
 * 在事务边界之外持锁可保证持锁期间看到的一定是上一个操作已提交的最新状态。
 *
 * 使用公平锁，防止秒级调度循环长期插队导致用户请求饿死。
 */
@Component
public class StationLock {

    private final ReentrantLock lock = new ReentrantLock(true);

    public <T> T call(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public void run(Runnable action) {
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }
}
