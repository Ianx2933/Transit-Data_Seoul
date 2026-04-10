import requests
import psycopg2
from datetime import datetime, timedelta
import time

# ============================================================
# 설정
# ============================================================
DAILY_API_KEY  = "4f514c576773746134316f7643526a"
HOURLY_API_KEY = "47764c6d7273746134356745597371"

DB_CONN = {
    "host": "localhost",
    "port": 5432,
    "dbname": "Seoul_Transit",
    "user": "postgres",
    "password": "330218"
}

START_DATE = datetime(2023, 1, 1)
END_DATE   = datetime.today()

# ============================================================
# 일별 승하차 과거 데이터 적재 (페이징 처리)
# ============================================================
def load_daily_historical():
    conn = psycopg2.connect(**DB_CONN)
    cursor = conn.cursor()

    current = START_DATE
    while current <= END_DATE:
        target_date = current.strftime("%Y%m%d")

        try:
            # 1페이지 먼저 호출해서 총 건수 확인
            url = f"http://openapi.seoul.go.kr:8088/4f514c576773746134316f7643526a/json/CardBusStatisticsServiceNew/1/1000/{target_date}"
            response = requests.get(url, timeout=30)
            data = response.json()

            if "CardBusStatisticsServiceNew" not in data:
                print(f"[경고] 데이터 없음: {target_date}")
                current += timedelta(days=1)
                continue

            total = data["CardBusStatisticsServiceNew"]["list_total_count"]
            rows  = data["CardBusStatisticsServiceNew"]["row"]
            print(f"[정보] {target_date} 총 {total}건")

            # 페이징: 1000건씩 나눠서 수집
            page_size = 1000
            start_idx = 1
            all_rows = list(rows)

            while start_idx + page_size <= total:
                start_idx += page_size
                end_idx = min(start_idx + page_size - 1, total)
                page_url = f"http://openapi.seoul.go.kr:8088/4f514c576773746134316f7643526a/json/CardBusStatisticsServiceNew/{start_idx}/{end_idx}/{target_date}"
                page_response = requests.get(page_url, timeout=30)
                page_data = page_response.json()

                if "CardBusStatisticsServiceNew" in page_data:
                    all_rows.extend(page_data["CardBusStatisticsServiceNew"]["row"])

                time.sleep(0.3)

            # DB 적재
            for row in all_rows:
                cursor.execute("""
                    INSERT INTO daily_od_data (
                        기준일자, 노선번호, 노선명,
                        표준버스정류장ID, 버스정류장ARS번호, 역명,
                        승차총승객수, 하차총승객수
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT DO NOTHING
                """, (
                    row.get("USE_YMD"),
                    row.get("RTE_NO"),
                    row.get("RTE_NM"),
                    row.get("STOPS_ID"),
                    row.get("STOPS_ARS_NO"),
                    row.get("SBWY_STNS_NM"),
                    int(row.get("GTON_TNOPE", 0)),
                    int(row.get("GTOFF_TNOPE", 0))
                ))

            conn.commit()
            print(f"[정보] 일별 적재 완료: {target_date} ({len(all_rows)}건)")

        except Exception as e:
            print(f"[오류] {target_date} 실패: {e}")
            conn.rollback()

        current += timedelta(days=1)
        time.sleep(0.5)

    cursor.close()
    conn.close()
    print("[정보] 일별 과거 데이터 적재 완료!")


# ============================================================
# 시간대별 승하차 과거 데이터 적재 (페이징 처리)
# ============================================================
def load_hourly_historical():
    conn = psycopg2.connect(**DB_CONN)
    cursor = conn.cursor()

    current = START_DATE.replace(day=1)
    while current <= END_DATE:
        target_ym = current.strftime("%Y%m")

        try:
            # 1페이지 먼저 호출해서 총 건수 확인
            url = f"http://openapi.seoul.go.kr:8088/47764c6d7273746134356745597371/json/CardBusTimeNew/1/1000/{target_ym}"
            response = requests.get(url, timeout=30)
            data = response.json()

            if "CardBusTimeNew" not in data:
                print(f"[경고] 데이터 없음: {target_ym}")
            else:
                total = data["CardBusTimeNew"]["list_total_count"]
                rows  = data["CardBusTimeNew"]["row"]
                print(f"[정보] {target_ym} 총 {total}건")

                # 페이징: 1000건씩 나눠서 수집
                page_size = 1000
                start_idx = 1
                all_rows = list(rows)

                while start_idx + page_size <= total:
                    start_idx += page_size
                    end_idx = min(start_idx + page_size - 1, total)
                    page_url = f"http://openapi.seoul.go.kr:8088/47764c6d7273746134356745597371/json/CardBusTimeNew/{start_idx}/{end_idx}/{target_ym}"
                    page_response = requests.get(page_url, timeout=30)
                    page_data = page_response.json()

                    if "CardBusTimeNew" in page_data:
                        all_rows.extend(page_data["CardBusTimeNew"]["row"])

                    time.sleep(0.3)

                # DB 적재 (wide → long 변환)
                for row in all_rows:
                    for hour in range(24):
                        on_key  = f"HR_{hour}_GET_ON_TNOPE"
                        off_key = f"HR_{hour}_GET_OFF_TNOPE"

                        # 1시 하차는 필드명 오타
                        if hour == 1:
                            off_key = "HR_1_GET_OFF_NOPE"

                        cursor.execute("""
                            INSERT INTO hourly_od_data (
                                기준년월, 노선번호, 노선명,
                                표준버스정류장ID, 버스정류장ARS번호, 역명,
                                시간대, 승차인원, 하차인원
                            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                            ON CONFLICT DO NOTHING
                        """, (
                            row.get("USE_YM"),
                            row.get("RTE_NO"),
                            row.get("RTE_NM"),
                            row.get("STOPS_ID"),
                            row.get("STOPS_ARS_NO"),
                            row.get("SBWY_STNS_NM"),
                            hour,
                            int(row.get(on_key, 0) or 0),
                            int(row.get(off_key, 0) or 0)
                        ))

                conn.commit()
                print(f"[정보] 시간대별 적재 완료: {target_ym} ({len(all_rows)}건)")

        except Exception as e:
            print(f"[오류] {target_ym} 실패: {e}")
            conn.rollback()

        # 다음 달로 이동
        if current.month == 12:
            current = current.replace(year=current.year + 1, month=1)
        else:
            current = current.replace(month=current.month + 1)

        time.sleep(1)

    cursor.close()
    conn.close()
    print("[정보] 시간대별 과거 데이터 적재 완료!")


# ============================================================
# 실행
# ============================================================
if __name__ == "__main__":
    print("=" * 50)
    print("일별 승하차 과거 데이터 적재 시작")
    print("=" * 50)
    load_daily_historical()

    print("=" * 50)
    print("시간대별 승하차 과거 데이터 적재 시작")
    print("=" * 50)
    load_hourly_historical()