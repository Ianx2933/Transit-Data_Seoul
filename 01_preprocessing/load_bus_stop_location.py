import pandas as pd
import psycopg2

DB_CONN = {
    "host": "localhost",
    "port": 5432,
    "dbname": "Seoul_Transit",
    "user": "postgres",
    "password": "330218"
}

CSV_PATH = r"C:\data\노드정보.csv"

def load_bus_stop_location():
    print("[정보] CSV 파일 읽는 중...")
    df = pd.read_csv(CSV_PATH, encoding='utf-8-sig')

    # 컬럼명 공백 + BOM 제거
    df.columns = df.columns.str.strip().str.replace('\ufeff', '', regex=False)

    # 전체 데이터 공백 + BOM 제거
    df = df.apply(lambda x: x.map(lambda v: v.strip().replace('\ufeff', '') if isinstance(v, str) else v))

    # 좌표 숫자 변환
    df['좌표X'] = pd.to_numeric(df['좌표X'], errors='coerce')
    df['좌표Y'] = pd.to_numeric(df['좌표Y'], errors='coerce')

    # 좌표 없는 행 제거
    df = df.dropna(subset=['좌표X', '좌표Y'])

    print(f"[정보] 총 {len(df)}건 적재 시작")

    conn = psycopg2.connect(**DB_CONN)
    cursor = conn.cursor()

    cursor.execute("TRUNCATE TABLE bus_stop_location")

    for _, row in df.iterrows():
        cursor.execute("""
            INSERT INTO bus_stop_location (
                노드id, 정류장번호, 정류장명,
                경도, 위도, 표준코드여부
            ) VALUES (%s, %s, %s, %s, %s, %s)
        """, (
            str(row.get("노드ID", "")),
            str(row.get("정류장번호", "")),
            str(row.get("노드명", "")),
            float(row.get("좌표X")),
            float(row.get("좌표Y")),
            int(row.get("표준코드여부(1:표준/0:비표준)", 0))
        ))

    conn.commit()
    cursor.close()
    conn.close()
    print(f"[정보] bus_stop_location 적재 완료: {len(df)}건")


if __name__ == "__main__":
    load_bus_stop_location()