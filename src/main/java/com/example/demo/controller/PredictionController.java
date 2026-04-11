package com.example.demo.controller;

import com.example.demo.dto.PredictionDto;
import com.example.demo.service.PredictionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/prediction")
@RequiredArgsConstructor
@Tag(name = "Prediction", description = "버스 혼잡도 예측 API")
public class PredictionController {

    private final PredictionService predictionService;

    // ============================================================
    // 시간대별 승차/하차 예측
    // XGBoost 모델 기반 일별 예측 × 시간대 비율
    // Flask 마이크로서비스 호출
    // ============================================================
    @GetMapping("/hourly")
    @Operation(summary = "시간대별 예측", description = "노선번호 + ARS번호 + 날짜 기반 시간대별 승차/하차 예측")
    public ResponseEntity<PredictionDto> predictHourly(
            @RequestParam String routeNo,  // 노선번호 (예: 9401)
            @RequestParam String arsNo,    // 정류장 ARS번호 (예: 01267)
            @RequestParam String date      // 날짜 (예: 20260411)
    ) {
        PredictionDto result = predictionService.predictHourly(routeNo, arsNo, date);
        return ResponseEntity.ok(result);
    }

    // ============================================================
    // 시간대 가중치 업데이트
    // STCIS 데이터 확인 후 요일타입별 시간대 가중치 수정
    // DB time_weight_config 테이블 업데이트
    // ============================================================
    @PutMapping("/weight")
    @Operation(summary = "가중치 업데이트", description = "요일타입별 시간대 가중치 수정 (STCIS 데이터 기반)")
    public ResponseEntity<String> updateWeight(
            @RequestParam String dayType,  // 요일타입 (평일/토요일/일요일/공휴일)
            @RequestParam int hour,        // 시간대 (0~23)
            @RequestParam double weight    // 가중치 값
    ) {
        boolean success = predictionService.updateWeight(dayType, hour, weight);
        if (success) {
            return ResponseEntity.ok("가중치 업데이트 완료");
        } else {
            return ResponseEntity.internalServerError().body("가중치 업데이트 실패");
        }
    }
}