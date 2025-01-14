import os
import psycopg2
import backoff
from config import Config

def get_db_connection():
    return psycopg2.connect(
        dbname=Config.DB_NAME,
        user=Config.DB_USER,
        password=Config.DB_PASSWORD,
        host=Config.DB_HOST,
        port=Config.DB_PORT
    )


def insert_multiple_articles(articles):
    conn = get_db_connection()
    try:
        cur = conn.cursor()

        insert_query = """
        INSERT INTO articles 
        (title, content, link, stored_date, category)
        VALUES (%s, %s, %s, %s, %s)
        """

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
        raise

    finally:
        cur.close()
        conn.close()


@backoff.on_exception(
    backoff.expo,
    Exception,
    max_tries=3,
    max_time=30
)
def save_to_db_with_retry(articles):
    return insert_multiple_articles(articles)