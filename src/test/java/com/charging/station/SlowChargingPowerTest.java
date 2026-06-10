package com.charging.station;

import com.charging.station.domain.ChargingPile;
import com.charging.station.mapper.PileMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试慢充桩功率是否为10kW
 */
@SpringBootTest
@org.springframework.test.context.TestPropertySource(properties = "charging.scheduler.enabled=false")
public class SlowChargingPowerTest {

    @Autowired
    private PileMapper pileMapper;

    @Test
    @DisplayName("测试慢充桩功率是否为10kW")
    public void testSlowChargingPilePower() {
        // 查询所有慢充桩
        List<ChargingPile> allPiles = pileMapper.getAllChargingPiles();
        List<ChargingPile> trickleChargers = allPiles.stream()
            .filter(pile -> "TRICKLE".equals(pile.getPileType()))
            .toList();

        // 验证有慢充桩
        assertFalse(trickleChargers.isEmpty(), "应该存在慢充桩");

        // 验证所有慢充桩功率为10kW
        for (ChargingPile pile : trickleChargers) {
            assertEquals(10.0, pile.getRatedPower(),
                String.format("慢充桩 %s 的功率应该为10kW，实际为%.1fkW",
                    pile.getPileNo(), pile.getRatedPower()));
        }

        System.out.println("✓ 所有慢充桩功率验证通过：");
        for (ChargingPile pile : trickleChargers) {
            System.out.println(String.format("  - %s: %.1fkW", pile.getPileNo(), pile.getRatedPower()));
        }
    }
}
