package com.example.demo.controller;

import com.example.demo.dto.CongestionDto;
import com.example.demo.service.CongestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/congestion")
@RequiredArgsConstructor
public class CongestionController {

    private final CongestionService congestionService;

    /**
     * 단일 노선 정류장 혼잡도 조회
     * GET http://localhost:8080/api/congestion/{노선명}?date=20231017
     * @param routeName URL 경로에서 추출한 노선명
     * @param date      조회할 날짜 (기본값: 20231017)
     * @return 200 OK + 정류장별 혼잡도 목록 (JSON 배열)
     */
    @GetMapping("/{routeName}")
    public ResponseEntity<List<CongestionDto>> getCongestion(
            @PathVariable String routeName,
            @RequestParam(defaultValue = "20231017") String date) {
        List<CongestionDto> result = congestionService.getCongestionByRoute(routeName, date);
        return ResponseEntity.ok(result);
    }

    /**
     * 여러 노선 정류장 혼잡도 한 번에 조회
     * GET http://localhost:8080/api/congestion?routes={노선명},{노선명}&date=20231017
     * @param routes 쉼표로 구분된 노선명 목록 (예: 143,401,N13)
     * @param date   조회할 날짜 (기본값: 20231017)
     * @return 200 OK + 전체 노선 정류장별 혼잡도 목록 (JSON 배열)
     */
    @GetMapping
    public ResponseEntity<List<CongestionDto>> getCongestionByRoutes(
            @RequestParam List<String> routes,
            @RequestParam(defaultValue = "20231017") String date) {
        List<CongestionDto> result = routes.stream()
            .flatMap(route ->
                congestionService.getCongestionByRoute(route.trim(), date).stream()
            )
            .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * 특정 노선의 정류장 목록 조회 (자동완성용)
     * GET http://localhost:8080/api/congestion/stops?route=영등포10&date=20231017
     * @param route 노선명
     * @param date  조회할 날짜 (기본값: 20231017)
     * @return 200 OK + 정류장명 목록 (순번 순)
     */
    @GetMapping("/stops")
    public ResponseEntity<List<String>> getStops(
            @RequestParam String route,
            @RequestParam(defaultValue = "20231017") String date) {
        List<String> stops = congestionService.getStopsByRoute(route, date);
        return ResponseEntity.ok(stops);
    }

    /**
     * 출발-도착 구간 혼잡도 조회
     * GET http://localhost:8080/api/congestion/section?route=영등포10&from=영등포역&to=당산역&date=20231017
     * @param route 노선명
     * @param from  출발 정류장명
     * @param to    도착 정류장명
     * @param date  조회할 날짜 (기본값: 20231017)
     * @return 200 OK + 구간 내 정류장별 혼잡도 목록
     */
    @GetMapping("/section")
    public ResponseEntity<List<CongestionDto>> getSection(
            @RequestParam String route,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "20231017") String date) {
        List<CongestionDto> result = congestionService.getSectionCongestion(route, from, to, date);
        return ResponseEntity.ok(result);
    }
}
