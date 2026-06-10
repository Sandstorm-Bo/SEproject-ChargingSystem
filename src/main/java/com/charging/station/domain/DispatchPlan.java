package com.charging.station.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 调度方案对象（2.8 扩展调度的返回值 dispatchPlan / batchPlan）。
 * 记录一批车辆按最短总完成时长分配到各桩的结果。
 */
public class DispatchPlan {

    private String mode;              // FAST / TRICKLE / MIXED
    private double totalDuration;     // 各车完成时间之和（小时），即被最小化的目标
    private List<Assignment> assignments = new ArrayList<>();

    public DispatchPlan() {}

    /** 单条分配：某车 -> 某桩排队区某位置 */
    public static class Assignment {
        private String carId;
        private String queueNum;
        private String pileId;
        private int position;
        private double durationHours;     // 该车自身充电时长
        private double completionHours;   // 该车完成时间（含桩内前方等待）

        public Assignment() {}

        public Assignment(String carId, String queueNum, String pileId, int position,
                          double durationHours, double completionHours) {
            this.carId = carId;
            this.queueNum = queueNum;
            this.pileId = pileId;
            this.position = position;
            this.durationHours = durationHours;
            this.completionHours = completionHours;
        }

        public String getCarId() { return carId; }
        public void setCarId(String carId) { this.carId = carId; }
        public String getQueueNum() { return queueNum; }
        public void setQueueNum(String queueNum) { this.queueNum = queueNum; }
        public String getPileId() { return pileId; }
        public void setPileId(String pileId) { this.pileId = pileId; }
        public int getPosition() { return position; }
        public void setPosition(int position) { this.position = position; }
        public double getDurationHours() { return durationHours; }
        public void setDurationHours(double durationHours) { this.durationHours = durationHours; }
        public double getCompletionHours() { return completionHours; }
        public void setCompletionHours(double completionHours) { this.completionHours = completionHours; }
    }

    public void addAssignment(String carId, String queueNum, String pileId, int position,
                              double durationHours, double completionHours) {
        assignments.add(new Assignment(carId, queueNum, pileId, position, durationHours, completionHours));
    }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public double getTotalDuration() { return totalDuration; }
    public void setTotalDuration(double totalDuration) { this.totalDuration = totalDuration; }
    public List<Assignment> getAssignments() { return assignments; }
    public void setAssignments(List<Assignment> assignments) { this.assignments = assignments; }
}
