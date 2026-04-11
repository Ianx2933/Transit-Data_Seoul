import pandas as pd
import psycopg2
from dotenv import load_dotenv
import os

load_dotenv()

DB_CONN = {
    "host": "localhost",
    "port": 5432,
    "dbname": "Seoul_Transit",
    "user": "postgres",
    "password": "330218"
}

CSV_PATH = r"C:\Users\miyum\OneDrive\바탕 화면\서울특별시 양천구_공휴일 목록_20251127.csv"

def load_holiday_data():
    print("[정보] 공휴일 CSV 파일 읽는 중...")
    df = pd.read_csv(CSV_PATH, encoding='cp949')

    # 컬럼명 공백 제거
    df.columns = df.columns.str.strip()

    # 날짜 변환
    df['날짜'] = pd.to_datetime(df['날짜']).dt.date

    print(f"[정보] 총 {len(df)}건 적재 시작")

    conn = psycopg2.connect(**DB_CONN)
    cursor = conn.cursor()

    # 테이블 생성
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS holiday_config (
            날짜 DATE PRIMARY KEY,
            휴일명 VARCHAR(50)
        )
    """)

    # 기존 데이터 초기화
    cursor.execute("TRUNCATE TABLE holiday_config")

    for _, row in df.iterrows():
        cursor.execute("""
            INSERT INTO holiday_config (날짜, 휴일명)
            VALUES (%s, %s)
            ON CONFLICT (날짜) DO NOTHING
        """, (
            row['날짜'],
            row['휴일명'].strip()
        ))

    conn.commit()
    cursor.close()
    conn.close()
    print(f"[정보] 공휴일 적재 완료: {len(df)}건")


if __name__ == "__main__":
    load_holiday_data()