package com.charging.station.domain;

import com.charging.station.enums.CarState;
import com.charging.station.enums.RequestMode;
import java.time.LocalDateTime;

/**
 * 充电请求领域对象
 * 根据设计文档 3.1 节定义
 */
public class ChargingRequest {

    // 基本属性
    private String requestId;           // 请求编号
    private String carId;               // 车辆编号
    private RequestMode requestMode;    // 充电模式（快充/慢充）
    private Double requestAmount;       // 请求充电量
    private String queueNum;            // 排队号（F1, F2... 或 T1, T2...）
    private LocalDateTime requestTime;  // 提交时间
    private CarState requestStatus;     // 请求状态

    // 关联属性
    private String pileId;              // 分配的充电桩编号（进入充电桩队列后）
    private Integer queuePosition;      // 在充电桩队列中的位置
    private Integer carsBeforeCount;    // 前方等待车辆数（查询时计算，不持久化）

    // Constructors
    public ChargingRequest() {}

    // Getters and Setters
    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getCarId() {
        return carId;
    }

    public void setCarId(String carId) {
        this.carId = carId;
    }

    public RequestMode getRequestMode() {
        return requestMode;
    }

    public void setRequestMode(RequestMode requestMode) {
        this.requestMode = requestMode;
    }

    public Double getRequestAmount() {
        return requestAmount;
    }

    public void setRequestAmount(Double requestAmount) {
        this.requestAmount = requestAmount;
    }

    public String getQueueNum() {
        return queueNum;
    }

    public void setQueueNum(String queueNum) {
        this.queueNum = queueNum;
    }

    public LocalDateTime getRequestTime() {
        return requestTime;
    }

    public void setRequestTime(LocalDateTime requestTime) {
        this.requestTime = requestTime;
    }

    public CarState getRequestStatus() {
        return requestStatus;
    }

    public void setRequestStatus(CarState requestStatus) {
        this.requestStatus = requestStatus;
    }

    public String getPileId() {
        return pileId;
    }

    public void setPileId(String pileId) {
        this.pileId = pileId;
    }

    public Integer getQueuePosition() {
        return queuePosition;
    }

    public void setQueuePosition(Integer queuePosition) {
        this.queuePosition = queuePosition;
    }

    public Integer getCarsBeforeCount() {
        return carsBeforeCount;
    }

    public void setCarsBeforeCount(Integer carsBeforeCount) {
        this.carsBeforeCount = carsBeforeCount;
    }

    // 业务方法

    /**
     * 修改充电量
     * 前置条件：车辆在等候区
     */
    public void modifyAmount(Double amount) {
        if (this.requestStatus != CarState.WAITING) {
            throw new IllegalStateException("只有在等候区的车辆才能修改充电量");
        }
        if (amount == null || amount <= 0) {
            throw new IllegalArgumentException("充电量必须大于 0");
        }
        this.requestAmount = amount;
    }

    /**
     * 修改充电模式
     * 前置条件：车辆在等候区
     */
    public void modifyMode(RequestMode mode, String newQueueNum) {
        if (this.requestStatus != CarState.WAITING) {
            throw new IllegalStateException("只有在等候区的车辆才能修改充电模式");
        }
        if (mode == this.requestMode) {
            throw new IllegalArgumentException("新模式与当前模式相同");
        }
        this.requestMode = mode;
        this.queueNum = newQueueNum;
        // 规范：修改模式 = 重新生成排队号并排到新模式队列的最后一位。
        // 调度按 requestTime 升序取车，必须刷新提交时间，否则改模式的车会按原时间插队
        this.requestTime = LocalDateTime.now();
    }

    /**
     * 开始充电
     */
    public void startCharging(String pileId) {
        this.requestStatus = CarState.CHARGING;
        this.pileId = pileId;
    }

    /**
     * 标记为已完成
     */
    public void markFinished() {
        this.requestStatus = CarState.FINISHED;
    }

    /**
     * 标记为已取消
     */
    public void markCancelled() {
        this.requestStatus = CarState.CANCELLED;
    }

    /**
     * 标记为已叫号（到达充电桩队首、准备开始充电的中间态）
     */
    public void markCalled() {
        this.requestStatus = CarState.CALLED;
    }

    /**
     * 移动到等候区（故障后重排）
     */
    public void moveToWaiting() {
        this.requestStatus = CarState.WAITING;
        this.pileId = null;
        this.queuePosition = null;
    }

    /**
     * 故障中断
     */
    public void interruptByFault(Double remainingAmount) {
        this.requestStatus = CarState.INTERRUPTED;
        this.requestAmount = remainingAmount; // 更新为剩余未充电量
    }

    /**
     * 分配到充电桩队列
     */
    public void assignToPile(String pileId, Integer position) {
        this.requestStatus = CarState.QUEUED_AT_PILE;
        this.pileId = pileId;
        this.queuePosition = position;
    }
}
