package com.charging.station.controller;

import com.charging.station.domain.Vehicle;
import com.charging.station.dto.Result;
import com.charging.station.mapper.RequestMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/vehicles")
@CrossOrigin(origins = "*")
public class VehicleController {

    @Autowired
    private RequestMapper requestMapper;

    @GetMapping
    public Result<List<Vehicle>> getAllVehicles() {
        try {
            List<Vehicle> vehicles = requestMapper.selectAllVehicles();
            return Result.success(vehicles);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
}
