package com.example.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PredictionDto {

    // 일별 총 승객수 예측값
    private double dailyPrediction;

    // 시간대별 승차/하차 예측값 목록
    private List<HourlyPrediction> hourlyPrediction;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HourlyPrediction {
        private int hour;        // 시간대 (0~23)
        private double boarding;  // 승차 예측
        private double alighting; // 하차 예측
    }
}