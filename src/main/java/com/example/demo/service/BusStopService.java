package com.example.demo.service;

import com.example.demo.entity.BusStop;
import com.example.demo.repository.BusStopRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.json.JSONArray;
import java.util.List;

@Service                 // Business Logic Layer 컴포넌트 선언
@RequiredArgsConstructor // final 필드를 파라미터로 받는 생성자 자동 생성 → 의존성 주입
public class BusStopService {

    private final BusStopRepository busStopRepository; // DB 접근 Repository 주입
    private final RestTemplate restTemplate;            // 외부 HTTP API 호출 클라이언트 주입

    /**
     * JdbcTemplate을 Service에서 직접 주입받는 이유:
     * deduplicateAndSum()과 fixAlightingSequenceByArs(), fixSameStopODByArs()는 임시 테이블 또는 JOIN UPDATE가 필요함
     * 즉, 하나의 DB 커넥션에서 실행해야 함.
     * JPA Repository는 메서드마다 커넥션을 새로 가져올 수 있어서 임시 테이블이 세션 간 사라지는 문제가 발생함.
     * JdbcTemplate은 하나의 커넥션에서 순서 실행을 보장하므로 Service에서 직접 주입.
     */
    private final JdbcTemplate jdbcTemplate; // 하나의 커넥션에서 SQL 순서 실행 보장

    private static final String API_KEY = "9603e709-9d71-46e0-b539-d2e36073286f"; // 공공 API 인증키
    private static final String API_URL = "https://t-data.seoul.go.kr/apig/apiman-gateway/tapi/BisTbisMsSttn/1.0"; // 공공 API URL

    // ============================================================
    // 1. 조회
    // ============================================================

    /**
     * 특정 날짜의 승차 표준코드가 NULL인 정류장 목록 조회
     * @param 기준일자 조회할 날짜 (예: 20231017)
     * @return 승차 표준코드가 NULL인 BusStop 목록
     */
    public List<BusStop> getNullStandardCode(String 기준일자) {
        return busStopRepository.findBy기준일자AndAnd승차정류장표준코드IsNull(기준일자); // Repository에 위임
    }

    /**
     * 특정 날짜의 하차 표준코드가 NULL인 정류장 목록 조회
     * @param 기준일자 조회할 날짜 (예: 20231017)
     * @return 하차 표준코드가 NULL인 BusStop 목록
     */
    public List<BusStop> getNullAlightingStandardCode(String 기준일자) {
        return busStopRepository.findBy기준일자And하차정류장표준코드IsNull(기준일자); // Repository에 위임
    }

    /**
     * ARS 코드(5자리)로 공공 API를 호출해서 정류장 표준코드(9자리) 조회
     * 날짜와 무관하게 ARS 코드만으로 조회하므로 기준일자 파라미터 없음
     * 429 Rate limit 또는 기타 API 오류 시 null 반환하고 계속 진행
     * @param arsCode 조회할 정류장 ARS 코드 (5자리)
     * @return 정류장 표준코드 (9자리), 결과 없거나 오류 시 null 반환
     */
    public String getStandardCodeByArs(String arsCode) {
        try {
            String url = API_URL + "?apikey=" + API_KEY + "&stId=" + arsCode; // API 호출 URL 조합

            HttpHeaders headers = new HttpHeaders();               // HTTP 헤더 객체 생성
            headers.set("User-Agent", "Mozilla/5.0");              // RestTemplate 기본 요청은 API 서버가 차단하므로 브라우저처럼 인식하게끔 함.
            HttpEntity<String> entity = new HttpEntity<>(headers); // 헤더를 포함한 HTTP 요청 객체 생성

            ResponseEntity<String> response = restTemplate.exchange( // 공공 API 호출
                url, HttpMethod.GET, entity, String.class            // GET 방식으로 호출 후 String으로 응답 수신
            );

            JSONArray jsonArray = new JSONArray(response.getBody()); // API 응답이 JSON 배열 형태이므로 JSONArray로 파싱

            if (jsonArray.length() > 0) {                                // 조회 결과가 있으면
                return jsonArray.getJSONObject(0).getString("sttnId");   // 첫 번째 결과의 표준코드 반환
            }

            return null; // 조회 결과 없으면 null 반환

        } catch (Exception e) {
            // 429 Rate limit 또는 기타 API 오류 시 null 반환하고 계속 진행
            System.out.println("[경고] API 호출 실패 - ARS: " + arsCode + " → " + e.getMessage());
            return null;
        }
    }

    /**
     * 특정 날짜의 양방향 출발 노선 탐지
     * 순번 0인 가상 정류장이 존재하면서 최대순번이 100 이상인 노선 탐지
     * 탐지된 노선은 경고 로그로 출력하고 수동 처리 권장
     * @param 기준일자 조회할 날짜 (예: 20231017)
     * @return 양방향 출발 의심 노선명 목록
     */
    public List<String> detectBidirectionalRoutes(String 기준일자) {
        List<String> suspiciousRoutes = busStopRepository.detectBidirectionalRoutes(기준일자); // 양방향 의심 노선 조회
        if (!suspiciousRoutes.isEmpty()) {                                                     // 탐지된 노선이 있으면
            System.out.println("[경고] 양방향 노선 의심 케이스 발견: " + suspiciousRoutes);               // 경고 로그 출력
            System.out.println("[경고] 해당 노선은 수동으로 회차 지점 순번을 확인 후 처리 필요");          // 수동 처리 안내
        }
        return suspiciousRoutes; // 탐지된 노선명 목록 반환
    }

    // ============================================================
    // 2. 공통 중복 행 합산 처리 메서드
    // ============================================================

    /**
     * 보정 작업 후 중복이 발생할 때마다 호출하는 공통 메서드
     * 특정 날짜 데이터만 대상으로 중복 합산 처리
     *
     * JdbcTemplate을 사용하여 하나의 커넥션에서 아래 순서 보장:
     *   1) 중복 그룹 수 확인 → 0건이면 스킵
     *   2) 임시 테이블에 합산 결과 먼저 저장 (PostgreSQL: TEMP TABLE)
     *   3) 원본 중복 행 DELETE
     *   4) 임시 테이블에서 합산 행 INSERT
     *   5) 임시 테이블 DROP
     *
     * 주의: 2번 단계(임시 테이블 저장) 이전에 DELETE를 실행하면 데이터 유실 발생
     *       반드시 이 순서를 지켜야 함
     *
     * @param 기준일자 처리할 날짜 (예: 20231017)
     * @return 합산 처리된 중복 그룹 수 (0이면 중복 없음)
     */
    public int deduplicateAndSum(String 기준일자) {

        // 1. 중복 그룹 수 확인 → 0건이면 스킵
        Integer duplicateCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM (" +
            "    SELECT 노선명, 전환_노선id, " +
            "        승차_정류장순번, 승차_정류장ars, 승차_정류장명, " +
            "        하차_정류장순번, 하차_정류장ars, 하차_정류장명 " +
            "    FROM analysis_table_final " +
            "    WHERE 기준일자 = ? " +
            "    GROUP BY 노선명, 전환_노선id, " +
            "        승차_정류장순번, 승차_정류장ars, 승차_정류장명, " +
            "        하차_정류장순번, 하차_정류장ars, 하차_정류장명 " +
            "    HAVING COUNT(*) > 1" +
            ") t",
            Integer.class,
            기준일자
        );

        if (duplicateCount == null || duplicateCount == 0) {
            System.out.println("[정보] 중복 없음 → deduplicateAndSum 스킵 [" + 기준일자 + "]");
            return 0;
        }

        System.out.println("[정보] 중복 그룹: " + duplicateCount + "건 → 합산 처리 시작 [" + 기준일자 + "]");

        // 2. 임시 테이블에 합산 결과 먼저 저장 (PostgreSQL: TEMP TABLE)
        jdbcTemplate.execute("DROP TABLE IF EXISTS 중복합산임시"); // 혹시 남아있을 수 있는 임시 테이블 정리
        jdbcTemplate.execute(
            "CREATE TEMP TABLE 중복합산임시 AS " +
            "SELECT MIN(기준일자) AS 기준일자, 노선명, 전환_노선id, " +
            "    승차_정류장순번, 승차_정류장ars, 승차_정류장명, " +
            "    하차_정류장순번, 하차_정류장ars, 하차_정류장표준코드, 하차_정류장명, " +
            "    SUM(승객수) AS 승객수 " +               // 승객수 INTEGER 타입이므로 CAST 불필요
            "FROM analysis_table_final " +
            "WHERE 기준일자 = '" + 기준일자 + "' " +
            "GROUP BY 노선명, 전환_노선id, " +
            "    승차_정류장순번, 승차_정류장ars, 승차_정류장명, " +
            "    하차_정류장순번, 하차_정류장ars, 하차_정류장표준코드, 하차_정류장명 " +
            "HAVING COUNT(*) > 1"
        );

        // 3. 원본 중복 행 DELETE
        jdbcTemplate.update(
            "DELETE FROM analysis_table_final a " +
            "USING 중복합산임시 b " +                   // 임시 테이블과 조인
            "WHERE a.기준일자 = b.기준일자 " +
            "AND a.노선명 = b.노선명 " +
            "AND a.전환_노선id = b.전환_노선id " +
            "AND a.승차_정류장순번 = b.승차_정류장순번 " +
            "AND a.승차_정류장ars = b.승차_정류장ars " +
            "AND a.하차_정류장순번 = b.하차_정류장순번 " +
            "AND a.하차_정류장ars = b.하차_정류장ars"
        );

        // 4. 임시 테이블에서 합산 행 INSERT
        jdbcTemplate.update(
            "INSERT INTO analysis_table_final " +
            "(기준일자, 노선명, 전환_노선id, " +
            "승차_정류장순번, 승차_정류장ars, 승차_정류장명, " +
            "하차_정류장순번, 하차_정류장ars, 하차_정류장표준코드, 하차_정류장명, 승객수) " +
            "SELECT 기준일자, 노선명, 전환_노선id, " +
            "    승차_정류장순번, 승차_정류장ars, 승차_정류장명, " +
            "    하차_정류장순번, 하차_정류장ars, 하차_정류장표준코드, 하차_정류장명, 승객수 " + // INTEGER 타입이므로 CAST 불필요
            "FROM 중복합산임시"
        );

        // 5. 임시 테이블 정리
        jdbcTemplate.execute("DROP TABLE IF EXISTS 중복합산임시");

        System.out.println("[정보] 중복 합산 완료: " + duplicateCount + "건 [" + 기준일자 + "]");
        return duplicateCount;
    }

    // ============================================================
    // 3. 보정 - 그룹 1: 가상 정류장 처리
    // ============================================================

    /**
     * 그룹 1 전체 실행: 가상 정류장 관련 보정 + 중복 합산
     *
     * 처리 순서:
     *   1) fixVirtualStopArs()        : 가상 정류장 ARS 00000 통합
     *   2) fixBoardingSequence()      : 기점 가상 정류장 승차순번 0 부여
     *   3) fixAlightingSequence()     : 종점 가상 정류장 하차순번 보정
     *   4) fixEmptyArrivalStopName()  : 종점 가상 정류장 빈 정류장명 보정
     *   5) fixVirtualStopBoarding()   : 가상 정류장 승차 처리
     *   6) fixMidVirtualStopBoarding(): 중간 경유 가상 정류장 승차 처리
     *   7) fixVirtualStopSameOD()     : 가상 정류장 승차=하차 동일 처리
     *   8) deduplicateAndSum()        : 중복 합산
     *
     * @param 기준일자 처리할 날짜 (예: 20231017)
     * @return 각 단계 보정 건수 합산
     */
    @Transactional
    public int fixVirtualStop(String 기준일자) {
        int total = 0;

        total += fixVirtualStopArs(기준일자);
        total += fixBoardingSequence(기준일자);
        total += fixAlightingSequence(기준일자);
        total += fixEmptyArrivalStopName(기준일자);
        total += fixVirtualStopBoarding(기준일자);
        total += fixMidVirtualStopBoarding(기준일자);
        total += fixVirtualStopSameOD(기준일자);

        deduplicateAndSum(기준일자);

        System.out.println("[정보] 그룹 1 (가상 정류장 처리) 완료 [" + 기준일자 + "]: " + total + "건");
        return total;
    }

    // ============================================================
    // 4. 그룹 2: 순번/ARS 보정
    // ============================================================

    /**
     * 그룹 2 전체 실행: 순번/ARS 보정 + 중복 합산
     *
     * 처리 순서:
     *   1) fixSequenceByArs()         : 동일 ARS 순번 불일치 보정
     *   2) fixAlightingSequenceByArs(): 하차순번 2 이하 차이 케이스 최소순번 통일
     *   3) deduplicateAndSum()        : 중복 합산
     *
     * @param 기준일자 처리할 날짜 (예: 20231017)
     * @return 각 단계 보정 건수 합산
     */
    @Transactional
    public int fixSequence(String 기준일자) {
        int total = 0;

        total += fixSequenceByArs(기준일자);
        total += fixAlightingSequenceByArs(기준일자);

        deduplicateAndSum(기준일자);

        System.out.println("[정보] 그룹 2 (순번/ARS 보정) 완료 [" + 기준일자 + "]: " + total + "건");
        return total;
    }

    // ============================================================
    // 5. 그룹 3: 승차=하차 동일 보정
    // ============================================================

    /**
     * 그룹 3 전체 실행: 승차=하차 동일 보정 + 중복 합산
     *
     * 처리 순서:
     *   1) fixSameStopODBySequence(): 순번 기준 승차=하차 동일 보정
     *   2) fixSameStopODByArs()    : ARS 기준 승차=하차 동일 보정
     *   3) deduplicateAndSum()     : 중복 합산
     *
     * @param 기준일자 처리할 날짜 (예: 20231017)
     * @return 각 단계 보정 건수 합산
     */
    @Transactional
    public int fixSameStopOD(String 기준일자) {
        int total = 0;

        total += fixSameStopODBySequence(기준일자);
        total += fixSameStopODByArs(기준일자);

        deduplicateAndSum(기준일자);

        System.out.println("[정보] 그룹 3 (승차=하차 동일 보정) 완료 [" + 기준일자 + "]: " + total + "건");
        return total;
    }

    // ============================================================
    // 6. 개별 보정 메서드
    // ============================================================

    /**
     * 특정 날짜의 NULL인 승차 표준코드를 보정
     * 1단계: 2025 데이터 조인으로 대량 채우기
     * 2단계: 잔여 NULL은 같은 테이블 내 다른 날짜 데이터 조인으로 채우기
     * @param 기준일자 처리할 날짜 (예: 20231017)
     * @return 보정된 건수
     */
    public int fixNullStandardCodes(String 기준일자) {

        // 1단계: 2025 데이터와 조인으로 대량 채우기
        int fixedByJoin = jdbcTemplate.update(
            "UPDATE analysis_table_final a " +
            "SET 승차_정류장표준코드 = b.승차_정류장표준코드 " +
            "FROM analysis_table_final b " +
            "WHERE a.승차_정류장ars = b.승차_정류장ars " +
            "AND a.승차_정류장표준코드 IS NULL " +
            "AND a.기준일자 = ? " +
            "AND b.기준일자 LIKE '2025%' " +
            "AND b.승차_정류장표준코드 IS NOT NULL",
            기준일자
        );
        System.out.println("[정보] 2025 데이터와 조인으로 승차 표준코드 보정: " + fixedByJoin + "건 [" + 기준일자 + "]");

        // 2단계: 잔여 NULL은 다른 날짜 데이터 조인으로 채우기 (2023/2024 데이터 활용)
        int fixedByOtherDate = jdbcTemplate.update(
            "UPDATE analysis_table_final a " +
            "SET 승차_정류장표준코드 = b.승차_정류장표준코드 " +
            "FROM analysis_table_final b " +
            "WHERE a.승차_정류장ars = b.승차_정류장ars " +
            "AND a.승차_정류장표준코드 IS NULL " +
            "AND a.기준일자 = ? " +
            "AND b.기준일자 != ? " +
            "AND b.승차_정류장표준코드 IS NOT NULL",
            기준일자, 기준일자
        );
        System.out.println("[정보] 다른 날짜 조인으로 승차 표준코드 보정: " + fixedByOtherDate + "건 [" + 기준일자 + "]");

        // 잔여 NULL 로그
        List<BusStop> remaining =
            busStopRepository.findBy기준일자AndAnd승차정류장표준코드IsNull(기준일자);
        if (!remaining.isEmpty()) {
            System.out.println("[정보] 승차 표준코드 잔여 NULL: " + remaining.size() +
                "건 → 가상 정류장(00000) 또는 미운행 노선 [" + 기준일자 + "]");
        }

        return fixedByJoin + fixedByOtherDate;
    }

    /**
     * 특정 날짜의 NULL인 하차 표준코드를 보정
     * 1단계: 2025 데이터 조인으로 대량 채우기
     * 2단계: 잔여 NULL은 같은 테이블 내 다른 날짜 데이터 조인으로 채우기
     * @param 기준일자 처리할 날짜 (예: 20231017)
     * @return 보정된 건수
     */
    public int fixNullAlightingStandardCodes(String 기준일자) {

        // 1단계: 2025 데이터와 조인으로 대량 채우기
        int fixedByJoin = jdbcTemplate.update(
            "UPDATE analysis_table_final a " +
            "SET 하차_정류장표준코드 = b.하차_정류장표준코드 " +
            "FROM analysis_table_final b " +
            "WHERE a.하차_정류장ars = b.하차_정류장ars " +
            "AND a.하차_정류장표준코드 IS NULL " +
            "AND a.기준일자 = ? " +
            "AND b.기준일자 LIKE '2025%' " +
            "AND b.하차_정류장표준코드 IS NOT NULL",
            기준일자
        );
        System.out.println("[정보] 2025 데이터와 조인으로 하차 표준코드 보정: " + fixedByJoin + "건 [" + 기준일자 + "]");

        // 2단계: 잔여 NULL은 다른 날짜 데이터 조인으로 채우기 (2023/2024 데이터 활용)
        int fixedByOtherDate = jdbcTemplate.update(
            "UPDATE analysis_table_final a " +
            "SET 하차_정류장표준코드 = b.하차_정류장표준코드 " +
            "FROM analysis_table_final b " +
            "WHERE a.하차_정류장ars = b.하차_정류장ars " +
            "AND a.하차_정류장표준코드 IS NULL " +
            "AND a.기준일자 = ? " +
            "AND b.기준일자 != ? " +
            "AND b.하차_정류장표준코드 IS NOT NULL",
            기준일자, 기준일자
        );
        System.out.println("[정보] 다른 날짜 조인으로 하차 표준코드 보정: " + fixedByOtherDate + "건 [" + 기준일자 + "]");

        // 잔여 NULL 로그
        List<BusStop> remaining =
            busStopRepository.findBy기준일자And하차정류장표준코드IsNull(기준일자);
        if (!remaining.isEmpty()) {
            System.out.println("[정보] 하차 표준코드 잔여 NULL: " + remaining.size() +
                "건 → 가상 정류장(00000) 또는 미운행 노선 [" + 기준일자 + "]");
        }

        return fixedByJoin + fixedByOtherDate;
    }

    /**
     * 특정 날짜의 종점 가상 정류장 빈 정류장명 자동 보정
     * @param 기준일자 처리할 날짜 (예: 20231017)
     * @return 보정된 건수
     */
    public int fixEmptyArrivalStopName(String 기준일자) {
        int fixedCount = busStopRepository.fixEmptyArrivalStopName(기준일자);
        int remaining  = busStopRepository.countEmptyArrivalStopName(기준일자);
        if (remaining > 0) {
            System.out.println("[경고] 보정 후 잔여 NULL: " + remaining + "건 → 수동 확인 필요 [" + 기준일자 + "]");
        }
        return fixedCount;
    }

    /**
     * 특정 날짜의 가상 정류장 ARS 00000 통합
     * @param 기준일자 처리할 날짜 (예: 20231017)
     * @return 승차/하차 총 보정 건수
     */
    public int fixVirtualStopArs(String 기준일자) {
        int boardingFixed      = busStopRepository.fixVirtualStopBoardingArs(기준일자);
        int alightingFixed     = busStopRepository.fixVirtualStopAlightingArs(기준일자);
        int remainingBoarding  = busStopRepository.countUnfixedVirtualBoardingArs(기준일자);
        int remainingAlighting = busStopRepository.countUnfixedVirtualAlightingArs(기준일자);
        if (remainingBoarding > 0) {
            System.out.println("[경고] 승차 가상 정류장 ARS 잔여: " + remainingBoarding + "건 → 수동 확인 필요 [" + 기준일자 + "]");
        }
        if (remainingAlighting > 0) {
            System.out.println("[경고] 하차 가상 정류장 ARS 잔여: " + remainingAlighting + "건 → 수동 확인 필요 [" + 기준일자 + "]");
        }
        return boardingFixed + alightingFixed;
    }

    /**
     * 특정 날짜의 기점 가상 정류장 승차순번 0 부여
     * @param 기준일자 처리할 날짜 (예: 20231017)
     * @return 보정된 건수
     */
    public int fixBoardingSequence(String 기준일자) {
        List<String> suspiciousRoutes = detectBidirectionalRoutes(기준일자);
        if (!suspiciousRoutes.isEmpty()) {
            System.out.println("[경고] 양방향 출발 노선 수동 처리 후 재실행 권장: " + suspiciousRoutes);
        }
        int fixedCount = busStopRepository.fixBoardingSequence(기준일자);
        int remaining  = busStopRepository.countNullBoardingSequence(기준일자);
        if (remaining > 0) {
            System.out.println("[경고] 승차순번 NULL 잔여: " + remaining + "건 → 수동 확인 필요 [" + 기준일자 + "]");
        }
        return fixedCount;
    }

    /**
     * 특정 날짜의 종점 가상 정류장 하차순번 보정 전체 실행
     * 케이스 A → C1 → C2 → B 순서로 처리
     * @param 기준일자 처리할 날짜 (예: 20231017)
     * @return 케이스별 보정 건수 합산
     */
    public int fixAlightingSequence(String 기준일자) {
        List<String> suspiciousRoutes = detectBidirectionalRoutes(기준일자);
        if (!suspiciousRoutes.isEmpty()) {
            System.out.println("[경고] 양방향 출발 노선 수동 처리 후 재실행 권장: " + suspiciousRoutes);
        }
        int caseAFixed     = busStopRepository.fixAlightingSequenceCaseA(기준일자);
        System.out.println("[정보] 케이스 a 완료: " + caseAFixed + "건 [" + 기준일자 + "]");
        int caseC1Fixed    = busStopRepository.fixAlightingSequenceCaseC1(기준일자);
        System.out.println("[정보] 케이스 c1 완료: " + caseC1Fixed + "건 [" + 기준일자 + "]");
        int caseC2Inserted = busStopRepository.insertAnomalyDataCaseC2(기준일자);
        int caseC2Deleted  = busStopRepository.deleteAnomalyDataCaseC2(기준일자);
        System.out.println("[정보] 케이스 c2 분리 완료: " + caseC2Inserted + "건 (삭제: " + caseC2Deleted + "건) [" + 기준일자 + "]");
        int caseBFixed     = busStopRepository.fixAlightingSequenceCaseB(기준일자);
        System.out.println("[정보] 케이스 b 완료: " + caseBFixed + "건 [" + 기준일자 + "]");
        int remaining      = busStopRepository.countNullAlightingSequence(기준일자);
        if (remaining > 0) {
            System.out.println("[경고] 하차순번 NULL 잔여: " + remaining + "건 → 수동 확인 필요 [" + 기준일자 + "]");
        }
        return caseAFixed + caseC1Fixed + caseBFixed;
    }

    /**
     * 특정 날짜의 가상 정류장 승차 처리
     * @param 기준일자 처리할 날짜 (예: 20231017)
     * @return 보정된 건수
     */
    public int fixVirtualStopBoarding(String 기준일자) {
        int fixedCount = busStopRepository.fixVirtualStopBoarding(기준일자);
        System.out.println("[정보] 가상 정류장 승차 처리 완료: " + fixedCount + "건 [" + 기준일자 + "]");
        return fixedCount;
    }

    /**
     * 특정 날짜의 중간 경유 가상 정류장 승차 처리
     * @param 기준일자 처리할 날짜 (예: 20231017)
     * @return 보정된 건수
     */
    public int fixMidVirtualStopBoarding(String 기준일자) {
        int fixedCount = busStopRepository.fixMidVirtualStopBoarding(기준일자);
        System.out.println("[정보] 중간 경유 가상 정류장 승차 처리 완료: " + fixedCount + "건 [" + 기준일자 + "]");
        return fixedCount;
    }

    /**
     * 특정 날짜의 가상 정류장 승차=하차 동일 처리
     * @param 기준일자 처리할 날짜 (예: 20231017)
     * @return 보정된 건수
     */
    public int fixVirtualStopSameOD(String 기준일자) {
        int fixedCount = busStopRepository.fixVirtualStopSameOD(기준일자);
        System.out.println("[정보] 가상 정류장 승차=하차 동일 처리 완료: " + fixedCount + "건 [" + 기준일자 + "]");
        return fixedCount;
    }

    /**
     * 특정 날짜의 동일 ARS 순번 불일치 보정
     * @param 기준일자 처리할 날짜 (예: 20231017)
     * @return 보정된 건수
     */
    public int fixSequenceByArs(String 기준일자) {
        int fixedCount = busStopRepository.fixSequenceByArs(기준일자);
        System.out.println("[정보] 동일 ARS 순번 불일치 보정 완료: " + fixedCount + "건 [" + 기준일자 + "]");
        return fixedCount;
    }

    /**
     * 특정 날짜의 하차순번 보정
     * 동일 노선+승차ARS+하차ARS인데 하차순번이 2 이하 차이나는 케이스를 최소 하차순번으로 통일
     * JOIN UPDATE가 필요하므로 JdbcTemplate으로 직접 처리
     * 승차순번, 하차순번 모두 INTEGER 타입이므로 CAST 불필요
     * @param 기준일자 처리할 날짜 (예: 20231017)
     * @return 보정된 건수
     */
    public int fixAlightingSequenceByArs(String 기준일자) {
        int fixedCount = jdbcTemplate.update(
            "UPDATE analysis_table_final a " +
            "SET 하차_정류장순번 = b.최소하차순번 " +
            "FROM (" +
            "    SELECT 노선명, 승차_정류장순번, 승차_정류장ars, 하차_정류장ars, " +
            "        MIN(하차_정류장순번) AS 최소하차순번 " +       // INTEGER 타입이므로 CAST 불필요
            "    FROM analysis_table_final " +
            "    WHERE 승차_정류장ars != '00000' " +
            "    AND 하차_정류장ars != '00000' " +
            "    AND 기준일자 = ? " +
            "    GROUP BY 노선명, 승차_정류장순번, 승차_정류장ars, 하차_정류장ars " +
            "    HAVING COUNT(DISTINCT 하차_정류장순번) > 1 " +
            "      AND MAX(하차_정류장순번) - MIN(하차_정류장순번) <= 2 " + // INTEGER 타입이므로 CAST 불필요
            ") b " +
            "WHERE a.노선명 = b.노선명 " +
            "AND a.승차_정류장순번 = b.승차_정류장순번 " +
            "AND a.승차_정류장ars = b.승차_정류장ars " +
            "AND a.하차_정류장ars = b.하차_정류장ars " +
            "AND a.하차_정류장순번 != b.최소하차순번 " +             // INTEGER 타입이므로 CAST 불필요
            "AND a.기준일자 = ?",
            기준일자, 기준일자
        );
        System.out.println("[정보] 하차순번 보정 완료: " + fixedCount + "건 [" + 기준일자 + "]");
        return fixedCount;
    }

    /**
     * 특정 날짜의 순번 기준 승차=하차 동일 보정
     * 승차순번 = 하차순번이면서 승차ARS = 하차ARS인 경우 하차순번 +1 처리
     * @param 기준일자 처리할 날짜 (예: 20231017)
     * @return 보정된 건수
     */
    public int fixSameStopODBySequence(String 기준일자) {
        int fixedCount = busStopRepository.fixSameStopODBySequence(기준일자);
        System.out.println("[정보] 순번 기준 승차=하차 동일 보정 완료: " + fixedCount + "건 [" + 기준일자 + "]");
        return fixedCount;
    }

    /**
     * 특정 날짜의 ARS 기준 승차=하차 동일 보정
     * 승차ARS = 하차ARS인 경우 승차순번 +1의 다음 정류장 정보로 하차 보정
     * JOIN UPDATE가 필요하므로 JdbcTemplate으로 직접 처리
     * 승차순번, 하차순번 모두 INTEGER 타입이므로 CAST 불필요
     * @param 기준일자 처리할 날짜 (예: 20231017)
     * @return 보정된 건수
     */
    public int fixSameStopODByArs(String 기준일자) {
        int fixedCount = jdbcTemplate.update(
            "UPDATE analysis_table_final a " +
            "SET 하차_정류장순번 = a.승차_정류장순번 + 1, " +       // INTEGER 연산 결과 직접 설정
            "    하차_정류장ars = b.승차_정류장ars, " +
            "    하차_정류장표준코드 = b.승차_정류장표준코드, " +
            "    하차_정류장명 = b.승차_정류장명 " +
            "FROM analysis_table_final b " +
            "WHERE a.노선명 = b.노선명 " +
            "AND a.기준일자 = b.기준일자 " +
            "AND b.승차_정류장순번 = a.승차_정류장순번 + 1 " +       // INTEGER 타입이므로 CAST 불필요
            "AND a.승차_정류장ars = a.하차_정류장ars " +
            "AND a.기준일자 = ?",
            기준일자
        );
        System.out.println("[정보] ARS 기준 승차=하차 동일 보정 완료: " + fixedCount + "건 [" + 기준일자 + "]");
        return fixedCount;
    }
}