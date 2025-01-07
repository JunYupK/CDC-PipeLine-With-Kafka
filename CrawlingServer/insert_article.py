import os

import psycopg2
from dotenv import load_dotenv


def insert_article(article_data):
    # 데이터베이스 연결
    conn = psycopg2.connect(
        dbname=os.getenv('DB_NAME'),
        user=os.getenv('DB_USER'),
        password=os.getenv('DB_PASSWORD'),
        host=os.getenv('DB_HOST'),
        port=os.getenv('DB_PORT')
    )

    try:
        # 커서 생성
        cur = conn.cursor()

        # INSERT 쿼리 실행
        insert_query = """
        INSERT INTO articles 
        (title, content, link, stored_date, category)
        VALUES (%s, %s, %s, %s, %s)
        """

        # 데이터 삽입
        cur.execute(insert_query, (
            article_data["title"],
            article_data["content"],
            article_data["link"],
            article_data["stored_date"]
        ))

        # 변경사항 커밋
        conn.commit()

        # 새로 생성된 article의 id 반환
        article_id = cur.fetchone()[0]
        print(f"Successfully inserted article with id: {article_id}")

    except Exception as e:
        print(f"Error occurred: {e}")
        conn.rollback()

    finally:
        # 연결 종료
        cur.close()
        conn.close()


def insert_multiple_articles(articles):
    conn = psycopg2.connect(
        user=os.getenv('DB_USER'),
        password=os.getenv('DB_PASSWORD'),
        host=os.getenv('DB_HOST'),
        port=os.getenv('DB_PORT')
    )

    try:
        cur = conn.cursor()

        insert_query = """
        INSERT INTO articles 
        (title, content, link, stored_date, category)
        VALUES (%s, %s, %s, %s, %s)
        """

        # 여러 데이터를 한 번에 삽입
        article_data = [(
            article["title"],
            article["content"],
            article["link"],
            article["stored_date"],
            article.get("category", "정치")
        ) for article in articles]

        cur.executemany(insert_query, article_data)
        conn.commit()

        print(f"Successfully inserted {len(articles)} articles")

    except Exception as e:
        print(f"Error occurred: {e}")
        conn.rollback()

    finally:
        cur.close()
        conn.close()
