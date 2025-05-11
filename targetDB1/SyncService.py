#!/usr/bin/env python3
# SyncService.py
import os
import json
import time
import logging
from typing import Dict, Any, List, Optional
import threading
import mysql.connector
from mysql.connector import pooling
from confluent_kafka import Consumer, KafkaError, KafkaException
from dotenv import load_dotenv

# 로그 설정
logging.basicConfig(
    level=logging.DEBUG,  # INFO에서 DEBUG로 변경
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger("sync-service")

# 환경 변수 로드
load_dotenv()

# 설정 상수
KAFKA_BOOTSTRAP_SERVERS = os.getenv('KAFKA_BOOTSTRAP_SERVERS', 'kafka.internal:9092')
KAFKA_GROUP_ID = os.getenv('KAFKA_GROUP_ID', 'mysql-sync-group-new2')
MYSQL_HOST = os.getenv('MYSQL_HOST', 'localhost')
MYSQL_PORT = int(os.getenv('MYSQL_PORT', '3307'))
MYSQL_USER = os.getenv('MYSQL_USER', 'kjy')
MYSQL_PASSWORD = os.getenv('MYSQL_PASSWORD', 'home')
MYSQL_DATABASE = os.getenv('MYSQL_DATABASE', 'target_db')
BATCH_SIZE = int(os.getenv('SYNC_BATCH_SIZE', '100'))
POLL_TIMEOUT = float(os.getenv('POLL_TIMEOUT', '1.0'))
RETRY_INTERVAL = int(os.getenv('RETRY_INTERVAL', '5'))
MAX_RETRIES = int(os.getenv('MAX_RETRIES', '3'))

# Kafka 토픽 설정
KAFKA_TOPICS = [
    'postgres.public.article_changes'
]

class MySQLConnectionPool:
    """MySQL 연결 풀 관리 클래스"""

    def __init__(self):
        """연결 풀 초기화"""
        self.pool = None
        self.init_pool()

    def init_pool(self) -> None:
        """연결 풀 생성"""
        try:
            self.pool = pooling.MySQLConnectionPool(
                pool_name="mysql_pool",
                pool_size=5,
                host=MYSQL_HOST,
                port=MYSQL_PORT,
                user=MYSQL_USER,
                password=MYSQL_PASSWORD,
                database=MYSQL_DATABASE
            )
            logger.info(f"MySQL 연결 풀 생성 완료 (host={MYSQL_HOST}, db={MYSQL_DATABASE})")
        except mysql.connector.Error as err:
            logger.error(f"MySQL 연결 풀 생성 실패: {err}")
            raise

    def get_connection(self):
        """연결 풀에서 연결 객체 가져오기"""
        if self.pool is None:
            self.init_pool()

        try:
            return self.pool.get_connection()
        except mysql.connector.Error as err:
            logger.error(f"연결 풀에서 연결 객체 획득 실패: {err}")
            raise


class DebeziumEventHandler:
    """Debezium 이벤트 처리 클래스"""

    def __init__(self, db_pool: MySQLConnectionPool):
        """초기화"""
        self.db_pool = db_pool

        # 테이블별 INSERT 쿼리 미리 준비
        self.insert_queries = {
            'articles': """
                INSERT INTO articles 
                (id, title, content, link, category_id, category, source, author, 
                published_at, stored_date, views_count, sentiment_score, 
                article_text_length, extracted_entities, version, is_deleted)
                VALUES 
                (%(id)s, %(title)s, %(content)s, %(link)s, %(category_id)s, %(category)s, 
                %(source)s, %(author)s, %(published_at)s, %(stored_date)s, %(views_count)s, 
                %(sentiment_score)s, %(article_text_length)s, %(extracted_entities)s, 
                %(version)s, %(is_deleted)s)
                ON DUPLICATE KEY UPDATE
                title = VALUES(title),
                content = VALUES(content),
                link = VALUES(link),
                category_id = VALUES(category_id),
                category = VALUES(category),
                source = VALUES(source),
                author = VALUES(author),
                published_at = VALUES(published_at),
                views_count = VALUES(views_count),
                sentiment_score = VALUES(sentiment_score),
                article_text_length = VALUES(article_text_length),
                extracted_entities = VALUES(extracted_entities),
                version = VALUES(version),
                is_deleted = VALUES(is_deleted)
            """,

            'media': """
                INSERT INTO media
                (id, article_id, stored_date, type, url, caption)
                VALUES
                (%(id)s, %(article_id)s, %(stored_date)s, %(type)s, %(url)s, %(caption)s)
                ON DUPLICATE KEY UPDATE
                article_id = VALUES(article_id),
                stored_date = VALUES(stored_date),
                type = VALUES(type),
                url = VALUES(url),
                caption = VALUES(caption)
            """,
            'article_changes': """
                INSERT INTO article_changes 
                (id, article_id, operation, changed_at, old_data, new_data)
                VALUES 
                (%(id)s, %(article_id)s, %(operation)s, %(changed_at)s, 
                 %(old_data)s, %(new_data)s)
                ON DUPLICATE KEY UPDATE
                article_id = VALUES(article_id),
                operation = VALUES(operation),
                changed_at = VALUES(changed_at),
                old_data = VALUES(old_data),
                new_data = VALUES(new_data)
            """
        }

        # 테이블별 삭제 쿼리
        self.delete_queries = {
            'articles': "UPDATE articles SET is_deleted = TRUE WHERE id = %s",
            'media': "DELETE FROM media WHERE id = %s"
        }

    def process_event(self, event: Dict[str, Any]) -> bool:
        """
        Debezium 이벤트를 처리하는 메소드
        """
        if not event:
            return False

        # 이벤트에서 테이블과 작업 유형 추출
        try:
            table_name = self._extract_table_name(event)

            # 테이블명이 비어있으면 처리 중단
            if not table_name:
                logger.warning(f"추출된 테이블명이 비어있습니다: {event}")
                return False

            # payload 체크 (이벤트 자체가 페이로드인 경우와 payload 키가 있는 경우 모두 처리)
            payload = event
            if 'payload' in event:
                payload = event.get('payload', {})

            if not payload:
                logger.warning(f"이벤트 페이로드가 비어 있습니다: {event}")
                return False

            # 작업 유형(op) 추출
            operation = payload.get('op')
            if not operation:
                logger.warning(f"작업 유형이 없는 이벤트: {payload}")
                return False

            # 디버깅을 위한 로깅 추가
            logger.debug(f"처리 중인 이벤트: 테이블={table_name}, 작업={operation}")

            # 작업 유형에 따라 처리
            if operation in ('c', 'r', 'u'):  # 생성, 읽기, 업데이트
                data = payload.get('after', {})
                return self._handle_insert_or_update(table_name, data)
            elif operation == 'd':  # 삭제
                data = payload.get('before', {})
                record_id = data.get('id')
                return self._handle_delete(table_name, record_id)
            else:
                logger.warning(f"지원되지 않는 작업 유형: {operation}")
                return False

        except Exception as e:
            logger.error(f"이벤트 처리 중 오류 발생: {str(e)}", exc_info=True)  # 전체 스택 추적 추가
            return False

    def _extract_table_name(self, event: Dict[str, Any]) -> str:
        """이벤트에서 테이블명 추출"""
        # 이벤트 자체가 페이로드인 경우 (직접 source에 접근)
        source = event.get('source', {})

        # 만약 source가 없으면 이벤트가 payload 아래에 있는 원래 구조인지 확인
        if not source and 'payload' in event:
            source = event.get('payload', {}).get('source', {})

        table = source.get('table', '')

        logger.debug(f"이벤트에서 추출한 테이블: '{table}', 소스: {source}")

        # 테이블명이 있는지 확인하고 지원하는 테이블인지 검사
        if table == 'articles':
            return 'articles'
        elif table == 'media':
            return 'media'
        elif table == 'article_changes':
            return 'article_changes'
        else:
            logger.warning(f"지원되지 않는 테이블: '{table}'")
            return ''

    def _handle_insert_or_update(self, table_name: str, data: Dict[str, Any]) -> bool:
        """INSERT 또는 UPDATE 작업 처리"""
        if not data or table_name not in self.insert_queries:
            return False

        # JSON 필드가 있는 경우 딕셔너리를 JSON 문자열로 변환
        processed_data = self._prepare_data_for_mysql(data)

        # MySQL 연결 및 쿼리 실행
        conn = None
        try:
            conn = self.db_pool.get_connection()
            cursor = conn.cursor()

            query = self.insert_queries[table_name]
            cursor.execute(query, processed_data)
            conn.commit()

            logger.debug(f"{table_name} 테이블에 레코드 삽입/업데이트 성공 (ID: {data.get('id')})")
            return True

        except mysql.connector.Error as err:
            if conn:
                conn.rollback()
            logger.error(f"MySQL 작업 실패 ({table_name}): {err}")
            return False

        finally:
            if cursor:
                cursor.close()
            if conn:
                conn.close()

    def _handle_delete(self, table_name: str, record_id: str) -> bool:
        """DELETE 작업 처리"""
        if not record_id or table_name not in self.delete_queries:
            return False

        # MySQL 연결 및 쿼리 실행
        conn = None
        try:
            conn = self.db_pool.get_connection()
            cursor = conn.cursor()

            query = self.delete_queries[table_name]
            cursor.execute(query, (record_id,))
            conn.commit()

            logger.debug(f"{table_name} 테이블에서 레코드 삭제 성공 (ID: {record_id})")
            return True

        except mysql.connector.Error as err:
            if conn:
                conn.rollback()
            logger.error(f"MySQL 삭제 작업 실패 ({table_name}): {err}")
            return False

        finally:
            if cursor:
                cursor.close()
            if conn:
                conn.close()

    def _prepare_data_for_mysql(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """MySQL용 데이터 준비 (JSON 필드 등 처리)"""
        processed = data.copy()

        # datetime 문자열 변환 (ISO 8601 -> MySQL 형식)
        if 'changed_at' in processed and processed['changed_at']:
            try:
                # Python의 datetime.fromisoformat는 'Z'를 처리하지 못하므로 문자열 치환
                changed_at_str = processed['changed_at']
                if changed_at_str.endswith('Z'):
                    changed_at_str = changed_at_str[:-1] + '+00:00'

                # Python 3.7 이상: fromisoformat 사용
                from datetime import datetime
                dt = datetime.fromisoformat(changed_at_str)

                # MySQL이 인식할 수 있는 형식으로 변환
                processed['changed_at'] = dt.strftime('%Y-%m-%d %H:%M:%S.%f')
            except Exception as e:
                logger.warning(f"날짜 변환 실패: {processed['changed_at']} - {str(e)}")

        # article_changes 테이블 처리
        if 'operation' in processed:  # article_changes 테이블의 특징적인 필드를 체크
            # JSON 필드 처리
            if 'old_data' in processed and processed['old_data'] is not None:
                if not isinstance(processed['old_data'], str):
                    processed['old_data_json'] = json.dumps(processed['old_data'])
                else:
                    processed['old_data_json'] = processed['old_data']
            else:
                processed['old_data_json'] = None

            if 'new_data' in processed and processed['new_data'] is not None:
                if not isinstance(processed['new_data'], str):
                    processed['new_data_json'] = json.dumps(processed['new_data'])
                else:
                    processed['new_data_json'] = processed['new_data']
            else:
                processed['new_data_json'] = None

        # 기존 로직 유지
        if 'extracted_entities' in processed and processed['extracted_entities']:
            if isinstance(processed['extracted_entities'], dict):
                processed['extracted_entities'] = json.dumps(processed['extracted_entities'])

        # NULL이 될 수 있는 필드에 None이 아닌 빈 문자열이 있는 경우 None으로 변환
        for key in ['category_id', 'published_at', 'sentiment_score', 'article_text_length']:
            if key in processed and processed[key] == '':
                processed[key] = None

        return processed



class KafkaConsumerWorker:
    """Kafka 컨슈머 워커 클래스"""

    def __init__(self, event_handler: DebeziumEventHandler, topics: List[str]):
        """초기화"""
        self.event_handler = event_handler
        self.topics = topics
        self.running = False
        self.consumer = None
        self.worker_thread = None

        # Kafka 컨슈머 설정
        self.kafka_config = {
            'bootstrap.servers': KAFKA_BOOTSTRAP_SERVERS,
            'group.id': KAFKA_GROUP_ID,
            'auto.offset.reset': 'earliest',
            'enable.auto.commit': False,
            'max.poll.interval.ms': 300000,  # 5분
            'session.timeout.ms': 30000,     # 30초
        }

    def start(self) -> None:
        """워커 시작"""
        if self.running:
            logger.warning("이미 실행 중인 워커 스레드가 있습니다")
            return

        self.running = True
        self.worker_thread = threading.Thread(target=self._consume_loop)
        self.worker_thread.daemon = True
        self.worker_thread.start()
        logger.info(f"Kafka 컨슈머 워커 시작 (topics={self.topics})")

    def stop(self) -> None:
        """워커 중지"""
        self.running = False
        if self.worker_thread:
            logger.info("Kafka 컨슈머 워커 종료 중...")
            self.worker_thread.join(timeout=30)
            logger.info("Kafka 컨슈머 워커 종료 완료")

    def _consume_loop(self) -> None:
        """메시지 소비 루프"""
        # Kafka 컨슈머 생성
        try:
            self.consumer = Consumer(self.kafka_config)
            self.consumer.subscribe(self.topics)

            batch = []
            last_commit_time = time.time()

            # 메인 소비 루프
            while self.running:
                try:
                    # 메시지 폴링
                    msg = self.consumer.poll(timeout=POLL_TIMEOUT)

                    if msg is None:
                        # 배치가 있으면 처리
                        if batch:
                            self._process_batch(batch)
                            batch = []
                            self.consumer.commit()
                            last_commit_time = time.time()
                        continue

                    if msg.error():
                        if msg.error().code() == KafkaError._PARTITION_EOF:
                            logger.debug(f"파티션 끝에 도달: {msg.topic()}-{msg.partition()}")
                        elif msg.error().code() == KafkaError._TRANSPORT:
                            logger.warning("전송 오류 발생. 재연결 시도 중...")
                            time.sleep(1)
                        else:
                            logger.error(f"Kafka 오류: {msg.error()}")
                        continue

                    # 메시지 처리
                    try:
                        value = msg.value()
                        if value:
                            event = json.loads(value.decode('utf-8'))
                            batch.append(event)
                    except json.JSONDecodeError as e:
                        logger.error(f"JSON 파싱 오류: {e}")
                        continue

                    # 배치 크기 확인 또는 일정 시간마다 커밋
                    current_time = time.time()
                    if len(batch) >= BATCH_SIZE or (current_time - last_commit_time > 5):
                        self._process_batch(batch)
                        batch = []
                        self.consumer.commit()
                        last_commit_time = current_time

                except KafkaException as e:
                    logger.error(f"Kafka 예외 발생: {e}")
                    time.sleep(RETRY_INTERVAL)
                except Exception as e:
                    logger.error(f"예상치 못한 오류 발생: {e}")
                    time.sleep(RETRY_INTERVAL)

            # 루프 종료 시 마지막 배치 처리
            if batch:
                self._process_batch(batch)

        except Exception as e:
            logger.error(f"컨슈머 실행 중 치명적 오류: {e}")
        finally:
            # 자원 정리
            if self.consumer:
                try:
                    self.consumer.close()
                except Exception as e:
                    logger.error(f"컨슈머 종료 중 오류: {e}")

    def _process_batch(self, batch: List[Dict[str, Any]]) -> None:
        """배치 처리"""
        if not batch:
            return

        success_count = 0
        error_count = 0

        for event in batch:
            if self.event_handler.process_event(event):
                success_count += 1
            else:
                error_count += 1

        logger.info(f"배치 처리 완료: {success_count} 성공, {error_count} 실패 (총 {len(batch)})")


class SyncService:
    """
    MySQL 동기화 서비스 메인 클래스

    Kafka에서 PostgreSQL 변경 이벤트를 구독하고 MySQL에 동기화
    """

    def __init__(self):
        """서비스 초기화"""
        self.db_pool = MySQLConnectionPool()
        self.event_handler = DebeziumEventHandler(self.db_pool)
        self.consumer_worker = KafkaConsumerWorker(self.event_handler, KAFKA_TOPICS)

        self.running = False

    def start(self) -> None:
        """서비스 시작"""
        self.running = True
        logger.info("MySQL 동기화 서비스 시작 중...")

        # Kafka 컨슈머 워커 시작
        self.consumer_worker.start()

        logger.info("MySQL 동기화 서비스 시작 완료")

    def stop(self) -> None:
        """서비스 중지"""
        self.running = False
        logger.info("MySQL 동기화 서비스 중지 중...")

        # Kafka 컨슈머 워커 중지
        self.consumer_worker.stop()

        logger.info("MySQL 동기화 서비스 중지 완료")

    def run(self) -> None:
        """서비스 실행"""
        try:
            self.start()

            # 종료 신호 대기
            while self.running:
                try:
                    time.sleep(1)
                except KeyboardInterrupt:
                    logger.info("종료 신호 수신")
                    self.running = False

        finally:
            self.stop()


if __name__ == "__main__":
    try:
        # MySQL 연결 확인
        logger.info("MySQL 연결 테스트 중...")
        conn = mysql.connector.connect(
            host=MYSQL_HOST,
            port=MYSQL_PORT,
            user=MYSQL_USER,
            password=MYSQL_PASSWORD,
            database=MYSQL_DATABASE
        )
        conn.close()
        logger.info("MySQL 연결 테스트 성공")

        # 서비스 시작
        service = SyncService()
        service.run()

    except mysql.connector.Error as err:
        logger.error(f"MySQL 연결 실패: {err}")
        exit(1)
    except Exception as e:
        logger.error(f"서비스 실행 중 오류 발생: {e}")
        exit(1)