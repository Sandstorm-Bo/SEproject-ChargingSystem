package com.charging.station.service;

import com.charging.station.domain.ChargingPile;
import com.charging.station.domain.TariffPolicy;
import com.charging.station.mapper.PileMapper;
import com.charging.station.mapper.QueueMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 充电桩管理服务
 * 根据设计文档 2.3 节实现
 */
@Service
public class PileManagementService {

    @Autowired
    private PileMapper pileMapper;

    @Autowired
    private QueueMapper queueMapper;

    /**
     * 启动充电桩
     * 对应系统事件: powerOn(pileId)
     */
    @Transactional
    public ChargingPile powerOn(String pileId) {
        ChargingPile pile = pileMapper.getPile(pileId);
        if (pile == null) {
            throw new IllegalArgumentException("充电桩不存在");
        }

        pile.powerOn();
        pileMapper.updatePileState(pile);

        return pile;
    }

    /**
     * 设置计费参数
     * 对应系统事件: setParameters(计费规则)
     */
    @Transactional
    public TariffPolicy setParameters(TariffPolicy policy) {
        if (policy.getPeakPrice() == null || policy.getFlatPrice() == null || policy.getValleyPrice() == null) {
            throw new IllegalArgumentException("峰平谷电价不能为空");
        }
        if (policy.getServiceFeePerKwh() == null) {
            throw new IllegalArgumentException("服务费单价不能为空");
        }

        policy.setStatus("已配置");
        policy.setUpdateTime(java.time.LocalDateTime.now());

        queueMapper.saveTariffPolicy(policy);

        return policy;
    }

    /**
     * 运行充电桩
     * 对应系统事件: Start_ChargingPile(pileId)
     */
    @Transactional
    public ChargingPile startChargingPile(String pileId) {
        ChargingPile pile = pileMapper.getPile(pileId);
        if (pile == null) {
            throw new IllegalArgumentException("充电桩不存在");
        }

        // 检查是否已设置计费策略
        TariffPolicy policy = queueMapper.getCurrentTariffPolicy();
        if (policy == null || !"已配置".equals(policy.getStatus())) {
            throw new IllegalStateException("计费策略未配置");
        }

        pile.run();
        pileMapper.updatePileState(pile);

        return pile;
    }

    /**
     * 关闭充电桩
     * 对应系统事件: powerOff(pileId)
     */
    @Transactional
    public ChargingPile powerOff(String pileId) {
        ChargingPile pile = pileMapper.getPile(pileId);
        if (pile == null) {
            throw new IllegalArgumentException("充电桩不存在");
        }

        pile.powerOff();
        pileMapper.updatePileState(pile);

        return pile;
    }

    /**
     * 修改充电桩功率
     */
    @Transactional
    public ChargingPile setPileParameters(String pileId, Double powerKw) {
        ChargingPile pile = pileMapper.getPile(pileId);
        if (pile == null) {
            throw new IllegalArgumentException("充电桩不存在");
        }

        pile.setParameters(powerKw);
        pileMapper.updatePileState(pile);

        return pile;
    }
}
