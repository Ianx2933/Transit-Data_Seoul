package com.example.demo.controller;                       // Presentation Layer 패키지 선언

import lombok.RequiredArgsConstructor;                     // final 필드 생성자 자동 생성
import org.springframework.jdbc.core.JdbcTemplate;        // DB 직접 접근용 JdbcTemplate
import org.springframework.web.bind.annotation.*;          // REST API 어노테이션 모음
import java.util.*;                                        // Map, List, LinkedHashMap 등

@RestController                    // Presentation Layer 선언 + 반환값을 JSON으로 자동 변환
@RequestMapping("/api/od")         // 이 Controller의 기본 URL 경로
@RequiredArgsConstructor           // final 필드를 파라미터로 받는 생성자 자동 생성 → 의존성 주입
public class OdController {

    private final JdbcTemplate jdbcTemplate; // DB 직접 접근용 JdbcTemplate 주입
    // JPA Repository 대신 JdbcTemplate을 쓰는 이유:
    // GROUP BY + ORDER BY가 포함된 집계 SQL은
    // JPA @Query보다 JdbcTemplate으로 직접 실행하는 게 더 명확하기 때문

    /**
     * 특정 정류장의 전체 OD 조회 (노선 순번 순으로 출력)
     * GET /api/od/{노선명}/{ARS}
     * 예시: GET /api/od/143/08121
     * @param 노선명 버스 노선 번호 (예: "143")
     * @param ARS   정류장 ARS 코드 5자리 (예: "08121")
     * @return 승차목적지(여기서 타서 어디서 내리는지) + 하차출발지(어디서 타고 여기서 내리는지)
     *         각각 노선 순번 순으로 정렬
     */
    @GetMapping("/{노선명}/{ARS}")                         // GET 요청 + URL 경로변수 매핑
    public Map<String, Object> getOd(
            @PathVariable String 노선명,                   // URL 경로의 {노선명}을 파라미터로 추출
            @PathVariable String ARS) {                    // URL 경로의 {ARS}를 파라미터로 추출

        // 승차 전체: 이 정류장에서 타서 어디서 내리는지
        // → 하차 정류장 순번 순으로 정렬 (노선 흐름대로 표시)
        String 승차sql =
            "SELECT a.하차_정류장명, " +                   // 하차 정류장명 (목적지)
            "    SUM(CAST(a.승객수 AS INT)) AS 승객수, " + // 총 승객수 합산
            "    MIN(CAST(a.하차_정류장순번 AS INT)) AS 순번 " + // 순번 (GROUP BY용)
            "FROM Analysis_Table_Final a " +
            "WHERE a.노선명 = ? " +                        // 파라미터 바인딩 (노선명)
            "AND a.승차_정류장ARS = ? " +                  // 파라미터 바인딩 (승차 ARS)
            "AND a.하차_정류장ARS != '00000' " +           // 가상 정류장(차고지 등) 제외
            "GROUP BY a.하차_정류장명 " +                  // 하차 정류장별 합산
            "ORDER BY 순번 ASC";                           // 노선 순번 오름차순 정렬

        // 하차 전체: 이 정류장에서 내리는 사람들이 어디서 탔는지
        // → 승차 정류장 순번 순으로 정렬 (노선 흐름대로 표시)
        String 하차sql =
            "SELECT a.승차_정류장명, " +                   // 승차 정류장명 (출발지)
            "    SUM(CAST(a.승객수 AS INT)) AS 승객수, " + // 총 승객수 합산
            "    MIN(CAST(a.승차_정류장순번 AS INT)) AS 순번 " + // 순번 (GROUP BY용)
            "FROM Analysis_Table_Final a " +
            "WHERE a.노선명 = ? " +                        // 파라미터 바인딩 (노선명)
            "AND a.하차_정류장ARS = ? " +                  // 파라미터 바인딩 (하차 ARS)
            "AND a.승차_정류장ARS != '00000' " +           // 가상 정류장(차고지 등) 제외
            "GROUP BY a.승차_정류장명 " +                  // 승차 정류장별 합산
            "ORDER BY 순번 ASC";                           // 노선 순번 오름차순 정렬

        // SQL 실행 → 각 행을 Map<String, Object>으로 변환하여 List로 반환
        List<Map<String, Object>> 승차전체 = jdbcTemplate.queryForList(승차sql, 노선명, ARS);
        List<Map<String, Object>> 하차전체 = jdbcTemplate.queryForList(하차sql, 노선명, ARS);

        // 응답 JSON 구성 (LinkedHashMap으로 순서 보장)
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("노선명", 노선명);                      // 조회한 노선명
        result.put("ARS", ARS);                            // 조회한 ARS 코드
        result.put("승차목적지", 승차전체);                // 여기서 타서 → 어디로 내리는지 (순번순)
        result.put("하차출발지", 하차전체);                // 어디서 타고 → 여기서 내리는지 (순번순)
        return result;
    }

    /**
     * 전체 노선 OD 데이터 한 번에 조회 (노선 순번 순으로 출력)
     * GET /api/od/all
     * Python make_map.py에서 최초 1회만 호출 → 메모리에 올려놓고 팝업 생성에 활용
     * 658개 정류장마다 개별 API 호출하는 대신 한 번에 전체 데이터를 가져와 성능 최적화
     * @return 전체 노선 승차/하차 OD 데이터 (노선 순번 순)
     */
    @GetMapping("/all")                                    // GET /api/od/all
    public Map<String, Object> getAllOd() {

        // 전체 노선 승차 OD (노선 순번 순으로 정렬)
        // 노선명 + 승차ARS 기준으로 하차 정류장별 승객수 합산
        String 승차sql =
            "SELECT 노선명, 승차_정류장ARS, 하차_정류장명, " +
            "    SUM(CAST(승객수 AS INT)) AS 승객수, " +   // 총 승객수 합산
            "    MIN(CAST(하차_정류장순번 AS INT)) AS 순번 " + // 하차 순번 (정렬용)
            "FROM Analysis_Table_Final " +
            "WHERE 승차_정류장ARS != '00000' " +           // 가상 정류장 제외
            "AND 하차_정류장ARS != '00000' " +             // 가상 정류장 제외
            "GROUP BY 노선명, 승차_정류장ARS, 하차_정류장명 " +
            "ORDER BY 노선명, 승차_정류장ARS, 순번 ASC";   // 노선 → ARS → 순번 순 정렬

        // 전체 노선 하차 OD (노선 순번 순으로 정렬)
        // 노선명 + 하차ARS 기준으로 승차 정류장별 승객수 합산
        String 하차sql =
            "SELECT 노선명, 하차_정류장ARS, 승차_정류장명, " +
            "    SUM(CAST(승객수 AS INT)) AS 승객수, " +   // 총 승객수 합산
            "    MIN(CAST(승차_정류장순번 AS INT)) AS 순번 " + // 승차 순번 (정렬용)
            "FROM Analysis_Table_Final " +
            "WHERE 승차_정류장ARS != '00000' " +           // 가상 정류장 제외
            "AND 하차_정류장ARS != '00000' " +             // 가상 정류장 제외
            "GROUP BY 노선명, 하차_정류장ARS, 승차_정류장명 " +
            "ORDER BY 노선명, 하차_정류장ARS, 순번 ASC";   // 노선 → ARS → 순번 순 정렬

        // SQL 실행
        List<Map<String, Object>> 승차전체 = jdbcTemplate.queryForList(승차sql);
        List<Map<String, Object>> 하차전체 = jdbcTemplate.queryForList(하차sql);

        // 응답 JSON 구성
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("승차", 승차전체);                      // 전체 노선 승차 OD (순번순)
        result.put("하차", 하차전체);                      // 전체 노선 하차 OD (순번순)
        return result;
    }
}