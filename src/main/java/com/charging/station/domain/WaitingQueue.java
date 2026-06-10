package com.charging.station.domain;

import com.charging.station.enums.RequestMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 等候区队列领域对象
 * 根据设计文档 3.1 节定义
 */
public class WaitingQueue {

    private String queueId;                         // 队列编号
    private RequestMode queueType;                  // 快充/慢充
    private Integer queueLength;                    // 当前队列长度
    private Integer maxCapacity;                    // 最大容量
    private String queueStatus;                     // 队列状态
    private List<ChargingRequest> requests;         // 充电请求集合

    public WaitingQueue() {
        this.requests = new ArrayList<>();
    }

    // Getters and Setters
    public String getQueueId() {
        return queueId;
    }

    public void setQueueId(String queueId) {
        this.queueId = queueId;
    }

    public RequestMode getQueueType() {
        return queueType;
    }

    public void setQueueType(RequestMode queueType) {
        this.queueType = queueType;
    }

    public Integer getQueueLength() {
        return queueLength;
    }

    public void setQueueLength(Integer queueLength) {
        this.queueLength = queueLength;
    }

    public Integer getMaxCapacity() {
        return maxCapacity;
    }

    public void setMaxCapacity(Integer maxCapacity) {
        this.maxCapacity = maxCapacity;
    }

    public String getQueueStatus() {
        return queueStatus;
    }

    public void setQueueStatus(String queueStatus) {
        this.queueStatus = queueStatus;
    }

    public List<ChargingRequest> getRequests() {
        return requests;
    }

    public void setRequests(List<ChargingRequest> requests) {
        this.requests = requests;
    }

    /**
     * 判断队列是否已满
     */
    public boolean isFull() {
        return this.queueLength >= this.maxCapacity;
    }

    /**
     * 判断队列是否有空位
     */
    public boolean hasRoom() {
        return this.queueLength < this.maxCapacity;
    }

    /**
     * 添加充电请求到队尾
     */
    public void enqueue(ChargingRequest request) {
        if (isFull()) {
            throw new IllegalStateException("等候区已满");
        }
        this.requests.add(request);
        this.queueLength++;
    }

    /**
     * 移除指定车辆的请求
     */
    public void removeRequest(String carId) {
        boolean removed = this.requests.removeIf(r -> r.getCarId().equals(carId));
        if (removed) {
            this.queueLength--;
        }
    }

    /**
     * 获取队首请求（按模式）
     */
    public ChargingRequest peek(RequestMode mode) {
        return this.requests.stream()
            .filter(r -> r.getRequestMode() == mode)
            .findFirst()
            .orElse(null);
    }

    /**
     * 移除队首请求（按模式）
     */
    public ChargingRequest poll(RequestMode mode) {
        ChargingRequest request = peek(mode);
        if (request != null) {
            removeRequest(request.getCarId());
        }
        return request;
    }

    /**
     * 计算指定排队号前方的车辆数
     */
    public int getCarsBeforePosition(String queueNum) {
        int position = 0;
        for (ChargingRequest request : this.requests) {
            if (request.getQueueNum().equals(queueNum)) {
                return position;
            }
            position++;
        }
        return -1; // 未找到
    }

    /**
     * 将请求列表插入队首（故障恢复时）
     */
    public void enqueueFront(List<ChargingRequest> requestsToAdd) {
        this.requests.addAll(0, requestsToAdd);
        this.queueLength += requestsToAdd.size();
    }
}
