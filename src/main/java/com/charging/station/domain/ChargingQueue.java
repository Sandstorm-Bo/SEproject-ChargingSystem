package com.charging.station.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 充电桩前队列领域对象
 * 根据设计文档 3.1 节定义
 */
public class ChargingQueue {

    private String queueId;                     // 队列编号
    private String pileId;                      // 所属充电桩编号
    private Integer queueLenM;                  // 队列最大长度 M
    private Integer currentLength;              // 当前队列长度
    private List<ChargingRequest> requests;     // 充电请求集合

    public ChargingQueue() {
        this.requests = new ArrayList<>();
    }

    // Getters and Setters
    public String getQueueId() {
        return queueId;
    }

    public void setQueueId(String queueId) {
        this.queueId = queueId;
    }

    public String getPileId() {
        return pileId;
    }

    public void setPileId(String pileId) {
        this.pileId = pileId;
    }

    public Integer getQueueLenM() {
        return queueLenM;
    }

    public void setQueueLenM(Integer queueLenM) {
        this.queueLenM = queueLenM;
    }

    public Integer getCurrentLength() {
        return currentLength;
    }

    public void setCurrentLength(Integer currentLength) {
        this.currentLength = currentLength;
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
        return this.currentLength >= this.queueLenM;
    }

    /**
     * 判断队列是否有空位
     */
    public boolean hasRoom() {
        return this.currentLength < this.queueLenM;
    }

    /**
     * 添加充电请求到队尾
     */
    public void enqueue(ChargingRequest request) {
        if (isFull()) {
            throw new IllegalStateException("充电桩队列已满");
        }
        this.requests.add(request);
        this.currentLength++;
    }

    /**
     * 获取队首请求
     */
    public ChargingRequest peek() {
        return this.requests.isEmpty() ? null : this.requests.get(0);
    }

    /**
     * 移除队首请求
     */
    public ChargingRequest poll() {
        if (this.requests.isEmpty()) {
            return null;
        }
        ChargingRequest request = this.requests.remove(0);
        this.currentLength--;
        return request;
    }

    /**
     * 移除指定车辆的请求
     */
    public void removeByCarId(String carId) {
        boolean removed = this.requests.removeIf(r -> r.getCarId().equals(carId));
        if (removed) {
            this.currentLength--;
        }
    }

    /**
     * 判断指定车辆是否在队首
     */
    public boolean isHead(String carId) {
        ChargingRequest head = peek();
        return head != null && head.getCarId().equals(carId);
    }

    /**
     * 释放队首空位
     */
    public void releaseHead() {
        poll();
    }

    /**
     * 清空队列（故障时）
     */
    public List<ChargingRequest> drain() {
        List<ChargingRequest> drained = new ArrayList<>(this.requests);
        this.requests.clear();
        this.currentLength = 0;
        return drained;
    }

    /**
     * 获取队列中指定车辆的位置
     */
    public Integer getPositionOf(String carId) {
        for (int i = 0; i < this.requests.size(); i++) {
            if (this.requests.get(i).getCarId().equals(carId)) {
                return i + 1; // 位置从1开始
            }
        }
        return null;
    }
}
