package com.example.demo.service;

import com.example.demo.dto.PredictionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PredictionService {

    private final RestTemplate restTemplate;
    private final JdbcTemplate jdbcTemplate;

    // Flask 마이크로서비스 주소 (Docker 컨테이너 내부 통신)
    private static final String FLASK_URL = "http://seoulprediction:5000";

    // ============================================================
    // 요일타입 코드 계산
    // holiday_config 테이블 기반으로 공휴일/임시공휴일 포함 판단
    // 0: 평일, 1: 토요일, 2: 일요일, 3: 공휴일
    // ============================================================
    private int getDayTypeCode(LocalDate date) {
        // holiday_config 테이블에서 공휴일 여부 조회
        // 공휴일 목록 CSV 기반 (임시공휴일 포함)
        // LocalDate 직접 전달 → DATE 타입 일치
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM holiday_config WHERE 날짜 = ?",
            Integer.class, date
        );
        if (count != null && count > 0) return 3; // 공휴일 (임시공휴일 포함)

        int dayOfWeek = date.getDayOfWeek().getValue(); //
        if (dayOfWeek == 6) return 1; // 토요일
        if (dayOfWeek == 7) return 2; // 일요일
        return 0;                     // 평일
    }

    // ============================================================
    // 전주/전월 승객수 조회
    // daily_od_data에서 lag feature 계산
    // XGBoost 모델의 핵심 피처 (전주승객수, 전월승객수)
    // ============================================================
    private double getPrevPassengers(String routeNo, String arsNo, LocalDate baseDate, int daysAgo) {
        LocalDate targetDate = baseDate.minusDays(daysAgo);
        String sql = """
            SELECT COALESCE(SUM(승차총승객수), 0)
            FROM daily_od_data
            WHERE 노선번호 = ?
            AND 버스정류장ars번호 = ?
            AND 기준일자 = ?
        """;
        // LocalDate 직접 전달 → DATE 타입 일치 (toString() 제거)
        Double result = jdbcTemplate.queryForObject(sql, Double.class,
            routeNo, arsNo, targetDate);
        return result != null ? result : 0.0;
    }

    // ============================================================
    // 시간대별 예측 요청
    // Flask /predict 엔드포인트 호출
    // 일별 XGBoost 예측 × 시간대 비율 = 시간대별 승차/하차 예측
    // ============================================================
    public PredictionDto predictHourly(String routeNo, String arsNo, String date) {

        LocalDate targetDate = LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE);

        // lag feature 계산 (7일 전, 30일 전 동일 정류장 승객수)
        double prevWeek  = getPrevPassengers(routeNo, arsNo, targetDate, 7);
        double prevMonth = getPrevPassengers(routeNo, arsNo, targetDate, 30);

        // Flask 요청 바디 구성
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("routeNo",   routeNo);
        requestBody.put("arsNo",     arsNo);
        requestBody.put("dayType",   getDayTypeCode(targetDate));
        requestBody.put("month",     targetDate.getMonthValue());
        requestBody.put("prevWeek",  prevWeek);
        requestBody.put("prevMonth", prevMonth);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        // Flask 호출 → 예측 결과 반환
        ResponseEntity<PredictionDto> response = restTemplate.exchange(
            FLASK_URL + "/predict",
            HttpMethod.POST,
            entity,
            PredictionDto.class
        );

        return response.getBody();
    }

    // ============================================================
    // 가중치 업데이트
    // STCIS 데이터 확인 후 요일타입별 시간대 가중치 수정
    // Flask /weight/update → DB time_weight_config 테이블 업데이트
    // ============================================================
    public boolean updateWeight(String dayType, int hour, double weight) {

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("dayType", dayType);
        requestBody.put("hour",    hour);
        requestBody.put("weight",  weight);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map> response = restTemplate.exchange(
            FLASK_URL + "/weight/update",
            HttpMethod.POST,
            entity,
            Map.class
        );

        Map body = response.getBody();
        return body != null && Boolean.TRUE.equals(body.get("success"));
    }
}