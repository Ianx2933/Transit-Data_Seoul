package com.example.demo.controller;

import com.example.demo.entity.BusStop;
import com.example.demo.service.BusStopService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/busstop")  // 이 Controller의 기본 URL 경로 지정
@RequiredArgsConstructor         // final 필드를 파라미터로 받는 생성자 자동 생성 → 의존성 주입
public class BusStopController {

    private final BusStopService busStopService; // Business Logic Layer Service 주입

    // ============================================================
    // 1. 조회
    // ============================================================

    /**
     * 특정 날짜의 승차 표준코드가 NULL인 정류장 목록 조회
     * GET http://localhost:8080/api/busstop/null-standard-code?date=20231017
     */
    @GetMapping("/null-standard-code")
    public ResponseEntity<List<BusStop>> getNullStandardCode(
            @RequestParam String date) {             // 기준일자 쿼리 파라미터
        return ResponseEntity.ok(busStopService.getNullStandardCode(date));
    }

    /**
     * 특정 날짜의 하차 표준코드가 NULL인 정류장 목록 조회
     * GET http://localhost:8080/api/busstop/null-alighting-code?date=20231017
     */
    @GetMapping("/null-alighting-code")
    public ResponseEntity<List<BusStop>> getNullAlightingStandardCode(
            @RequestParam String date) {             // 기준일자 쿼리 파라미터
        return ResponseEntity.ok(busStopService.getNullAlightingStandardCode(date));
    }

    /**
     * ARS 코드로 공공 API를 호출해서 표준코드 단건 조회
     * GET http://localhost:8080/api/busstop/standard-code/{arsCode}
     */
    @GetMapping("/standard-code/{arsCode}")
    public ResponseEntity<String> getStandardCode(@PathVariable String arsCode) {
        String standardCode = busStopService.getStandardCodeByArs(arsCode);
        if (standardCode == null) {
            return ResponseEntity.notFound().build(); // 404 Not Found
        }
        return ResponseEntity.ok(standardCode);
    }

    /**
     * 특정 날짜의 양방향 출발 노선 탐지
     * GET http://localhost:8080/api/busstop/detect-bidirectional?date=20231017
     */
    @GetMapping("/detect-bidirectional")
    public ResponseEntity<List<String>> detectBidirectionalRoutes(
            @RequestParam String date) {             // 기준일자 쿼리 파라미터
        return ResponseEntity.ok(busStopService.detectBidirectionalRoutes(date));
    }

    // ============================================================
    // 2. 그룹 실행
    // ============================================================

    /**
     * 그룹 1 전체 실행: 가상 정류장 관련 보정 + 중복 합산
     * GET http://localhost:8080/api/busstop/fix-virtual-stop?date=20231017
     *
     * 처리 순서: 6번 → 1번 → 3번 → 4번 → 5번 → 9번 → 11번 → 중복합산
     */
    @GetMapping("/fix-virtual-stop")
    public ResponseEntity<String> fixVirtualStop(
            @RequestParam String date) {             // 기준일자 쿼리 파라미터
        int total = busStopService.fixVirtualStop(date);
        return ResponseEntity.ok("그룹 1 (가상 정류장 처리) 완료 [" + date + "]: " + total + "건");
    }

    /**
     * 그룹 2 전체 실행: 순번/ARS 보정 + 중복 합산
     * GET http://localhost:8080/api/busstop/fix-sequence?date=20231017
     *
     * 처리 순서: 12번 → 신규(하차순번 보정) → 중복합산
     */
    @GetMapping("/fix-sequence")
    public ResponseEntity<String> fixSequence(
            @RequestParam String date) {             // 기준일자 쿼리 파라미터
        int total = busStopService.fixSequence(date);
        return ResponseEntity.ok("그룹 2 (순번/ARS 보정) 완료 [" + date + "]: " + total + "건");
    }

    /**
     * 그룹 3 전체 실행: 승차=하차 동일 보정 + 중복 합산
     * GET http://localhost:8080/api/busstop/fix-same-stop-od?date=20231017
     *
     * 처리 순서: 10번 → 신규(ARS 기준 보정) → 중복합산
     */
    @GetMapping("/fix-same-stop-od")
    public ResponseEntity<String> fixSameStopOD(
            @RequestParam String date) {             // 기준일자 쿼리 파라미터
        int total = busStopService.fixSameStopOD(date);
        return ResponseEntity.ok("그룹 3 (승차=하차 동일 보정) 완료 [" + date + "]: " + total + "건");
    }

    /**
     * 중복 합산 단독 실행 (수동 호출용)
     * GET http://localhost:8080/api/busstop/deduplicate?date=20231017
     */
    @GetMapping("/deduplicate")
    public ResponseEntity<String> deduplicateAndSum(
            @RequestParam String date) {             // 기준일자 쿼리 파라미터
        int count = busStopService.deduplicateAndSum(date);
        return ResponseEntity.ok("중복 합산 완료 [" + date + "]: " + count + "건");
    }

    // ============================================================
    // 3. 개별 실행
    // ============================================================

    /**
     * NULL인 승차 표준코드를 공공 API로 자동 보정 (7번)
     * GET http://localhost:8080/api/busstop/fix-null?date=20231017
     */
    @GetMapping("/fix-null")
    public ResponseEntity<String> fixNullStandardCodes(
            @RequestParam String date) {             // 기준일자 쿼리 파라미터
        int fixedCount = busStopService.fixNullStandardCodes(date);
        return ResponseEntity.ok("승차 표준코드 보정 완료 [" + date + "]: " + fixedCount + "건");
    }

    /**
     * NULL인 하차 표준코드를 공공 API로 자동 보정 (7번)
     * GET http://localhost:8080/api/busstop/fix-null-alighting?date=20231017
     */
    @GetMapping("/fix-null-alighting")
    public ResponseEntity<String> fixNullAlightingStandardCodes(
            @RequestParam String date) {             // 기준일자 쿼리 파라미터
        int fixedCount = busStopService.fixNullAlightingStandardCodes(date);
        return ResponseEntity.ok("하차 표준코드 보정 완료 [" + date + "]: " + fixedCount + "건");
    }

    /**
     * 종점 가상 정류장 빈 정류장명 자동 보정 (4번)
     * GET http://localhost:8080/api/busstop/fix-empty-arrival-name?date=20231017
     */
    @GetMapping("/fix-empty-arrival-name")
    public ResponseEntity<String> fixEmptyArrivalStopName(
            @RequestParam String date) {             // 기준일자 쿼리 파라미터
        int fixedCount = busStopService.fixEmptyArrivalStopName(date);
        return ResponseEntity.ok("종점 가상 정류장 정류장명 보정 완료 [" + date + "]: " + fixedCount + "건");
    }

    /**
     * 가상 정류장 ARS 00000 통합 (6번)
     * GET http://localhost:8080/api/busstop/fix-virtual-stop-ars?date=20231017
     */
    @GetMapping("/fix-virtual-stop-ars")
    public ResponseEntity<String> fixVirtualStopArs(
            @RequestParam String date) {             // 기준일자 쿼리 파라미터
        int fixedCount = busStopService.fixVirtualStopArs(date);
        return ResponseEntity.ok("가상 정류장 ARS 통합 완료 [" + date + "]: " + fixedCount + "건");
    }

    /**
     * 기점 가상 정류장 승차순번 0 부여 (1번)
     * GET http://localhost:8080/api/busstop/fix-boarding-sequence?date=20231017
     */
    @GetMapping("/fix-boarding-sequence")
    public ResponseEntity<String> fixBoardingSequence(
            @RequestParam String date) {             // 기준일자 쿼리 파라미터
        int fixedCount = busStopService.fixBoardingSequence(date);
        return ResponseEntity.ok("기점 가상 정류장 승차순번 보정 완료 [" + date + "]: " + fixedCount + "건");
    }

    /**
     * 종점 가상 정류장 하차순번 보정 전체 실행 (3번)
     * GET http://localhost:8080/api/busstop/fix-alighting-sequence?date=20231017
     */
    @GetMapping("/fix-alighting-sequence")
    public ResponseEntity<String> fixAlightingSequence(
            @RequestParam String date) {             // 기준일자 쿼리 파라미터
        int fixedCount = busStopService.fixAlightingSequence(date);
        return ResponseEntity.ok("종점 가상 정류장 하차순번 보정 완료 [" + date + "]: " + fixedCount + "건");
    }

    /**
     * 가상 정류장 승차 처리 (5번)
     * GET http://localhost:8080/api/busstop/fix-virtual-stop-boarding?date=20231017
     */
    @GetMapping("/fix-virtual-stop-boarding")
    public ResponseEntity<String> fixVirtualStopBoarding(
            @RequestParam String date) {             // 기준일자 쿼리 파라미터
        int fixedCount = busStopService.fixVirtualStopBoarding(date);
        return ResponseEntity.ok("가상 정류장 승차 처리 완료 [" + date + "]: " + fixedCount + "건");
    }

    /**
     * 중간 경유 가상 정류장 승차 처리 (9번)
     * GET http://localhost:8080/api/busstop/fix-mid-virtual-stop-boarding?date=20231017
     */
    @GetMapping("/fix-mid-virtual-stop-boarding")
    public ResponseEntity<String> fixMidVirtualStopBoarding(
            @RequestParam String date) {             // 기준일자 쿼리 파라미터
        int fixedCount = busStopService.fixMidVirtualStopBoarding(date);
        return ResponseEntity.ok("중간 경유 가상 정류장 승차 처리 완료 [" + date + "]: " + fixedCount + "건");
    }

    /**
     * 가상 정류장 승차=하차 동일 처리 (11번)
     * GET http://localhost:8080/api/busstop/fix-virtual-stop-same-od?date=20231017
     */
    @GetMapping("/fix-virtual-stop-same-od")
    public ResponseEntity<String> fixVirtualStopSameOD(
            @RequestParam String date) {             // 기준일자 쿼리 파라미터
        int fixedCount = busStopService.fixVirtualStopSameOD(date);
        return ResponseEntity.ok("가상 정류장 승차=하차 동일 처리 완료 [" + date + "]: " + fixedCount + "건");
    }

    /**
     * 동일 ARS 순번 불일치 보정 (12번)
     * GET http://localhost:8080/api/busstop/fix-sequence-by-ars?date=20231017
     */
    @GetMapping("/fix-sequence-by-ars")
    public ResponseEntity<String> fixSequenceByArs(
            @RequestParam String date) {             // 기준일자 쿼리 파라미터
        int fixedCount = busStopService.fixSequenceByArs(date);
        return ResponseEntity.ok("동일 ARS 순번 불일치 보정 완료 [" + date + "]: " + fixedCount + "건");
    }

    /**
     * 하차순번 보정 (신규)
     * GET http://localhost:8080/api/busstop/fix-alighting-sequence-by-ars?date=20231017
     */
    @GetMapping("/fix-alighting-sequence-by-ars")
    public ResponseEntity<String> fixAlightingSequenceByArs(
            @RequestParam String date) {             // 기준일자 쿼리 파라미터
        int fixedCount = busStopService.fixAlightingSequenceByArs(date);
        return ResponseEntity.ok("하차순번 보정 완료 [" + date + "]: " + fixedCount + "건");
    }

    /**
     * 순번 기준 승차=하차 동일 보정 (10번)
     * GET http://localhost:8080/api/busstop/fix-same-stop-od-by-sequence?date=20231017
     */
    @GetMapping("/fix-same-stop-od-by-sequence")
    public ResponseEntity<String> fixSameStopODBySequence(
            @RequestParam String date) {             // 기준일자 쿼리 파라미터
        int fixedCount = busStopService.fixSameStopODBySequence(date);
        return ResponseEntity.ok("순번 기준 승차=하차 동일 보정 완료 [" + date + "]: " + fixedCount + "건");
    }

    /**
     * ARS 기준 승차=하차 동일 보정 (신규)
     * GET http://localhost:8080/api/busstop/fix-same-stop-od-by-ars?date=20231017
     */
    @GetMapping("/fix-same-stop-od-by-ars")
    public ResponseEntity<String> fixSameStopODByArs(
            @RequestParam String date) {             // 기준일자 쿼리 파라미터
        int fixedCount = busStopService.fixSameStopODByArs(date);
        return ResponseEntity.ok("ARS 기준 승차=하차 동일 보정 완료 [" + date + "]: " + fixedCount + "건");
    }
}