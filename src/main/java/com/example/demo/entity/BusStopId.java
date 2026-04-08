package com.example.demo.entity;

import java.io.Serializable; // JPA 복합키 클래스는 반드시 Serializable 구현 필요
import lombok.EqualsAndHashCode; // equals()와 hashCode() 메서드 자동 생성
import lombok.NoArgsConstructor; // 기본 생성자 자동 생성 (JPA 필수 요구사항)

// JPA에서 복합키(여러 컬럼을 PK로 사용)를 쓸 때 필요한 키 클래스
// 기준일자 추가로 3개 날짜 데이터가 공존해도 PK 충돌 없이 구분 가능
// BusStop 엔티티의 @IdClass에 지정된 클래스
@NoArgsConstructor  // JPA가 내부적으로 기본 생성자를 필요로 함
@EqualsAndHashCode  // 복합키 비교 시 equals()와 hashCode()가 정확해야 하기 때문에 자동 생성
public class BusStopId implements Serializable {

    // BusStop 엔티티의 @Id 필드와 이름이 반드시 일치해야 함
    private String 기준일자;      // BusStop.기준일자와 매핑 (시계열 데이터 구분용)
    private String 노선명;        // BusStop.노선명과 매핑
    private String 승차정류장ARS; // BusStop.승차정류장ARS와 매핑
    private String 하차정류장ARS; // BusStop.하차정류장ARS와 매핑
}