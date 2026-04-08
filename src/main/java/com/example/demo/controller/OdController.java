package com.example.demo.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/od")
@RequiredArgsConstructor
public class OdController {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 특정 정류장의 전체 OD 조회
     * GET /api/od/{노선명}/{ARS}?date=20231017
     * @param 노선명 버스 노선 번호
     * @param ARS   정류장 ARS 코드 5자리
     * @param date  조회할 날짜 (기본값: 20231017)
     */
    @GetMapping("/{노선명}/{ARS}")
    public Map<String, Object> getOd(
            @PathVariable String 노선명,
            @PathVariable String ARS,
            @RequestParam(defaultValue = "20231017") String date) { // 날짜 파라미터 추가

        String 승차sql =
            "SELECT a.하차_정류장명, " +
            "    SUM(CAST(a.승객수 AS INT)) AS 승객수, " +
            "    MIN(CAST(a.하차_정류장순번 AS INT)) AS 순번 " +
            "FROM Analysis_Table_Final a " +
            "WHERE a.노선명 = ? " +
            "AND a.기준일자 = ? " +                                    // 날짜 필터 추가
            "AND a.승차_정류장ARS = ? " +
            "AND a.하차_정류장ARS != '00000' " +
            "GROUP BY a.하차_정류장명 " +
            "ORDER BY 순번 ASC";

        String 하차sql =
            "SELECT a.승차_정류장명, " +
            "    SUM(CAST(a.승객수 AS INT)) AS 승객수, " +
            "    MIN(CAST(a.승차_정류장순번 AS INT)) AS 순번 " +
            "FROM Analysis_Table_Final a " +
            "WHERE a.노선명 = ? " +
            "AND a.기준일자 = ? " +                                    // 날짜 필터 추가
            "AND a.하차_정류장ARS = ? " +
            "AND a.승차_정류장ARS != '00000' " +
            "GROUP BY a.승차_정류장명 " +
            "ORDER BY 순번 ASC";

        List<Map<String, Object>> 승차전체 = jdbcTemplate.queryForList(승차sql, 노선명, date, ARS);
        List<Map<String, Object>> 하차전체 = jdbcTemplate.queryForList(하차sql, 노선명, date, ARS);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("노선명", 노선명);
        result.put("ARS", ARS);
        result.put("date", date);                                      // 응답에 날짜 포함
        result.put("승차목적지", 승차전체);
        result.put("하차출발지", 하차전체);
        return result;
    }

    /**
     * 전체 노선 OD 데이터 한 번에 조회
     * GET /api/od/all?date=20231017
     * @param date 조회할 날짜 (기본값: 20231017)
     */
    @GetMapping("/all")
    public Map<String, Object> getAllOd(
            @RequestParam(defaultValue = "20231017") String date) { // 날짜 파라미터 추가

        String 승차sql =
            "SELECT 노선명, 승차_정류장ARS, 하차_정류장명, " +
            "    SUM(CAST(승객수 AS INT)) AS 승객수, " +
            "    MIN(CAST(하차_정류장순번 AS INT)) AS 순번 " +
            "FROM Analysis_Table_Final " +
            "WHERE 기준일자 = ? " +                                    // 날짜 필터 추가
            "AND 승차_정류장ARS != '00000' " +
            "AND 하차_정류장ARS != '00000' " +
            "GROUP BY 노선명, 승차_정류장ARS, 하차_정류장명 " +
            "ORDER BY 노선명, 승차_정류장ARS, 순번 ASC";

        String 하차sql =
            "SELECT 노선명, 하차_정류장ARS, 승차_정류장명, " +
            "    SUM(CAST(승객수 AS INT)) AS 승객수, " +
            "    MIN(CAST(승차_정류장순번 AS INT)) AS 순번 " +
            "FROM Analysis_Table_Final " +
            "WHERE 기준일자 = ? " +                                    // 날짜 필터 추가
            "AND 승차_정류장ARS != '00000' " +
            "AND 하차_정류장ARS != '00000' " +
            "GROUP BY 노선명, 하차_정류장ARS, 승차_정류장명 " +
            "ORDER BY 노선명, 하차_정류장ARS, 순번 ASC";

        List<Map<String, Object>> 승차전체 = jdbcTemplate.queryForList(승차sql, date);
        List<Map<String, Object>> 하차전체 = jdbcTemplate.queryForList(하차sql, date);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date);                                      // 응답에 날짜 포함
        result.put("승차", 승차전체);
        result.put("하차", 하차전체);
        return result;
    }
}