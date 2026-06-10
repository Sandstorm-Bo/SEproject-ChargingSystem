package com.charging.station.service;

import com.charging.station.domain.Bill;
import com.charging.station.domain.DetailedList;
import com.charging.station.mapper.BillMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 账单服务
 * 根据设计文档 2.2 节实现
 */
@Service
public class BillingService {

    @Autowired
    private BillMapper billMapper;

    /**
     * 查看账单
     * 对应系统事件: Request_Bill(carId, date)
     */
    public List<Bill> requestBill(String carId, LocalDateTime date) {
        if (carId == null || carId.trim().isEmpty()) {
            throw new IllegalArgumentException("车辆编号不能为空");
        }
        return billMapper.getBillsByCarIdAndDate(carId.trim().toUpperCase(), date);
    }

    /**
     * 查看详单
     * 对应系统事件: Request_DetailedList(billId)
     */
    public List<DetailedList> requestDetailedList(String billId) {
        if (billId == null || billId.trim().isEmpty()) {
            throw new IllegalArgumentException("账单编号不能为空");
        }
        return billMapper.getDetailedListByBillId(billId);
    }
}
