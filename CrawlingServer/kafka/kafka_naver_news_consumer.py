from kafka import KafkaConsumer
import json
import psycopg2
from datetime import datetime
from psycopg2.extras import execute_batch


class NaverNewsKafkaConsumer:
    def __init__(self):
        # Kafka Consumer 설정
        self.consumer = KafkaConsumer(
            'raw_news',
            bootstrap_servers=['localhost:9092'],
            group_id='news_to_postgres_group',
            auto_offset_reset='earliest',
            enable_auto_commit=True,
            value_deserializer=lambda x: json.loads(x.decode('utf-8'))
        )

        # PostgreSQL 연결
        self.conn = psycopg2.connect(
            dbname="your_db_name",
            user="your_user",
            password="your_password",
            host="localhost"
        )
        self.cur = self.conn.cursor()

    def process_messages(self):
        try:
            # 메시지 배치 처리를 위한 리스트
            messages_batch = []
            batch_size = 100

            for message in self.consumer:
                article = message.value

                # 배치에 메시지 추가
                messages_batch.append((
                    article['title'],
                    article['link'],
                    datetime.now()
                ))

                # 배치 크기에 도달하면 DB에 저장
                if len(messages_batch) >= batch_size:
                    self._save_to_postgres(messages_batch)
                    messages_batch = []

        except Exception as e:
            print(f"Error processing message: {str(e)}")
            self.conn.rollback()

        finally:
            self._cleanup()

    def _save_to_postgres(self, messages):
        try:
            # 배치 실행
            execute_batch(self.cur, """
                INSERT INTO news_articles (title, link, crawled_at)
                VALUES (%s, %s, %s)
                ON CONFLICT (link) DO NOTHING
            """, messages)

            self.conn.commit()
            print(f"Saved {len(messages)} articles to PostgreSQL")

        except Exception as e:
            print(f"Error saving to PostgreSQL: {str(e)}")
            self.conn.rollback()
            raise

    def _cleanup(self):
        try:
            self.cur.close()
            self.conn.close()
            self.consumer.close()
        except:
            pass


def main():
    consumer = NewsKafkaConsumer()
    try:
        consumer.process_messages()
    except KeyboardInterrupt:
        print("Shutting down...")
    finally:
        consumer._cleanup()


if __name__ == "__main__":
    main()