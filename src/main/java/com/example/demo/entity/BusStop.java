package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "analysis_table_final") // DB 테이블명 매핑 (PostgreSQL 소문자)
@Getter
@NoArgsConstructor
@IdClass(BusStopId.class) // 복합키 클래스 지정 (기준일자 + 노선명 + 승차ARS + 하차ARS)
public class BusStop {

    @Id
    @Column(name = "기준일자") // 시계열 데이터 구분용으로 복합키에 추가
    private String 기준일자;

    @Id
    @Column(name = "노선명")
    private String 노선명;

    @Column(name = "전환_노선id") // BIGINT = LONG 타입
    private Long 전환노선ID;

    @Column(name = "승차_정류장순번") // INTEGER 타입
    private Integer 승차정류장순번;

    @Id
    @Column(name = "승차_정류장ars")
    private String 승차정류장ARS;

    @Column(name = "승차_정류장표준코드")
    private String 승차정류장표준코드;

    @Column(name = "승차_정류장명")
    private String 승차정류장명;

    @Column(name = "하차_정류장순번") // INTEGER 타입
    private Integer 하차정류장순번;

    @Id
    @Column(name = "하차_정류장ars")
    private String 하차정류장ARS;

    @Column(name = "하차_정류장표준코드")
    private String 하차정류장표준코드;

    @Column(name = "하차_정류장명")
    private String 하차정류장명;

    @Column(name = "승객수") // INTEGER 타입
    private Integer 승객수;
}