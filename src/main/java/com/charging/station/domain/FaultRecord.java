package com.charging.station.domain;

import java.time.LocalDateTime;

/**
 * 故障记录领域对象（对应设计文档故障调度中的故障审计记录）
 */
public class FaultRecord {

    private String faultId;            // 故障记录编号
    private String pileId;             // 故障充电桩编号
    private String strategy;           // 调度策略：PRIORITY / TIME_ORDER
    private String faultReason;        // 故障原因
    private LocalDateTime occurredAt;  // 故障发生时间
    private LocalDateTime recoveredAt; // 恢复时间
    private String status;             // OPEN / RECOVERED

    public FaultRecord() {}

    public String getFaultId() { return faultId; }
    public void setFaultId(String faultId) { this.faultId = faultId; }

    public String getPileId() { return pileId; }
    public void setPileId(String pileId) { this.pileId = pileId; }

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public String getFaultReason() { return faultReason; }
    public void setFaultReason(String faultReason) { this.faultReason = faultReason; }

    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }

    public LocalDateTime getRecoveredAt() { return recoveredAt; }
    public void setRecoveredAt(LocalDateTime recoveredAt) { this.recoveredAt = recoveredAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
