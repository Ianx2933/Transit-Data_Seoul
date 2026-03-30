package com.example.demo.controller;                       // Presentation Layer 패키지 선언

import com.example.demo.dto.CongestionDto;                 // 응답 데이터 구조 import
import com.example.demo.service.CongestionService;         // Business Logic Layer import
import lombok.RequiredArgsConstructor;                     // final 필드 생성자 자동 생성
import org.springframework.http.ResponseEntity;            // HTTP 응답 객체 (상태코드 + 바디)
import org.springframework.web.bind.annotation.*;          // REST API 어노테이션 모음

import java.util.List;                                     // 여러 건의 데이터를 List로 반환하기 위해 import
import java.util.stream.Collectors;                        // 스트림 결과를 List로 수집하기 위해 import

@RestController                    // Presentation Layer 선언 + 반환값을 JSON으로 자동 변환
@RequestMapping("/api/congestion") // 이 Controller의 기본 URL 경로
@RequiredArgsConstructor           // final 필드를 파라미터로 받는 생성자 자동 생성 → 의존성 주입
public class CongestionController {

    private final CongestionService congestionService;     // Business Logic Layer Service 주입

    /**
     * 단일 노선 정류장 혼잡도 조회
     * GET http://localhost:8080/api/congestion/{노선명}
     * 예시: http://localhost:8080/api/congestion/143
     * @param routeName URL 경로에서 추출한 노선명
     * @return 200 OK + 정류장별 혼잡도 목록 (JSON 배열)
     */
    @GetMapping("/{routeName}")                            // GET 요청 + URL 경로변수 {routeName} 매핑
    public ResponseEntity<List<CongestionDto>> getCongestion(
            @PathVariable String routeName) {              // URL 경로의 {routeName}을 파라미터로 추출
        List<CongestionDto> result = congestionService.getCongestionByRoute(routeName); // Service에 위임
        return ResponseEntity.ok(result);                  // 200 OK + 결과 JSON 반환
    }

    /**
     * 여러 노선 정류장 혼잡도 한 번에 조회
     * GET http://localhost:8080/api/congestion?routes={노선명},{노선명},{노선명}
     * @param routes 쉼표로 구분된 노선명 목록 (예: 143,401,N13)
     * @return 200 OK + 전체 노선 정류장별 혼잡도 목록 (JSON 배열)
     */
    @GetMapping                                            // GET /api/congestion?routes=143,401,N13
    public ResponseEntity<List<CongestionDto>> getCongestionByRoutes(
            @RequestParam List<String> routes) {           // 쿼리 파라미터 routes를 List로 자동 파싱
        List<CongestionDto> result = routes.stream()       // 노선명 목록을 스트림으로 변환
            .flatMap(route ->                              // 각 노선의 결과를 하나의 스트림으로 합침
                congestionService.getCongestionByRoute(route.trim()).stream() // 노선별 혼잡도 조회 후 스트림 변환
            )
            .collect(Collectors.toList());                 // 최종 List로 수집
        return ResponseEntity.ok(result);                  // 200 OK + 결과 JSON 반환
    }
}