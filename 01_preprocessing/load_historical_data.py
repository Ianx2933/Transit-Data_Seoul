import requests
import psycopg2
from datetime import datetime, timedelta
import time

# ============================================================
# 설정
# ============================================================
DAILY_API_KEY = "4f514c576773746134316f7643526a"
HOURLY_API_KEY = "47764c6d7273746134356745597371"

DB_CONN = {
    "host": "localhost",  # 로컬에서 직접 실행하므로 localhost
    "port": 5432,
    "dbname": "Seoul_Transit",
    "user": "postgres",
    "password": "330218"
}

# 수집 기간 설정
START_DATE = datetime(2023, 1, 1)
END_DATE = datetime.today()

# ============================================================
# 일별 승하차 과거 데이터 적재
# ============================================================
def load_daily_historical():
    conn = psycopg2.connect(**DB_CONN)
    cursor = conn.cursor()

    current = START_DATE
    while current <= END_DATE:
        target_date = current.strftime("%Y%m%d")

        try:
            url = f"http://openapi.seoul.go.kr:8088/{DAILY_API_KEY}/json/CardBusStatisticsServiceNew/1/1000/{target_date}"
            response = requests.get(url, timeout=30)
            data = response.json()

            if "CardBusStatisticsServiceNew" not in data:
                print(f"[경고] 데이터 없음: {target_date}")
                current += timedelta(days=1)
                continue

            rows = data["CardBusStatisticsServiceNew"]["row"]

            for row in rows:
                cursor.execute("""
                    INSERT INTO daily_od_data (
                        기준일자, 노선번호, 노선명,
                        표준버스정류장ID, 버스정류장ARS번호, 역명,
                        승차총승객수, 하차총승객수
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT DO NOTHING
                """, (
                    row.get("USE_DT"),
                    row.get("LINE_NUM"),
                    row.get("LINE_NM"),
                    row.get("STND_BSST_ID"),
                    row.get("BSST_ARS_NO"),
                    row.get("STATION_NM"),
                    int(row.get("RIDE_PASGR_NUM", 0)),
                    int(row.get("ALIGHT_PASGR_NUM", 0))
                ))

            conn.commit()
            print(f"[정보] 일별 적재 완료: {target_date} ({len(rows)}건)")

        except Exception as e:
            print(f"[오류] {target_date} 실패: {e}")
            conn.rollback()

        current += timedelta(days=1)
        time.sleep(0.5)  # API Rate limit 방지

    cursor.close()
    conn.close()
    print("[정보] 일별 과거 데이터 적재 완료!")


# ============================================================
# 시간대별 승하차 과거 데이터 적재
# ============================================================
def load_hourly_historical():
    conn = psycopg2.connect(**DB_CONN)
    cursor = conn.cursor()

    # 월별로 수집
    current = START_DATE.replace(day=1)
    while current <= END_DATE:
        target_ym = current.strftime("%Y%m")

        try:
            url = f"http://openapi.seoul.go.kr:8088/{HOURLY_API_KEY}/json/CardBusTimeStatisticsServiceNew/1/1000/{target_ym}"
            response = requests.get(url, timeout=30)
            data = response.json()

            if "CardBusTimeStatisticsServiceNew" not in data:
                print(f"[경고] 데이터 없음: {target_ym}")
                # 다음 달로 이동
                if current.month == 12:
                    current = current.replace(year=current.year + 1, month=1)
                else:
                    current = current.replace(month=current.month + 1)
                continue

            rows = data["CardBusTimeStatisticsServiceNew"]["row"]

            for row in rows:
                cursor.execute("""
                    INSERT INTO hourly_od_data (
                        기준년월, 노선번호, 노선명,
                        버스정류장ARS번호, 역명,
                        시간대, 승차인원, 하차인원
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT DO NOTHING
                """, (
                    row.get("USE_MON"),
                    row.get("LINE_NUM"),
                    row.get("LINE_NM"),
                    row.get("BSST_ARS_NO"),
                    row.get("STATION_NM"),
                    int(row.get("HHMM", 0)),
                    int(row.get("RIDE_PASGR_NUM", 0)),
                    int(row.get("ALIGHT_PASGR_NUM", 0))
                ))

            conn.commit()
            print(f"[정보] 시간대별 적재 완료: {target_ym} ({len(rows)}건)")

        except Exception as e:
            print(f"[오류] {target_ym} 실패: {e}")
            conn.rollback()

        # 다음 달로 이동
        if current.month == 12:
            current = current.replace(year=current.year + 1, month=1)
        else:
            current = current.replace(month=current.month + 1)

        time.sleep(1)  # API Rate limit 방지

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