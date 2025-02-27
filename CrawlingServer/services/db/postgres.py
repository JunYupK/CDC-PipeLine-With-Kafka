import os
from typing import List, Dict, Any, Optional
import time
import logging
from contextlib import contextmanager

import psycopg2
from psycopg2.pool import ThreadedConnectionPool
import backoff

from config import Config

# 로거 설정
logger = logging.getLogger(__name__)

# 전역 커넥션 풀
_pool: Optional[ThreadedConnectionPool] = None


def get_connection_pool() -> ThreadedConnectionPool:
    """
    데이터베이스 연결 풀을 생성하거나 기존 풀을 반환합니다.
    
    Returns:
        ThreadedConnectionPool: 데이터베이스 연결 풀
    """
    global _pool
    if _pool is None:
        # 최소 및 최대 연결 설정
        minconn = 1
        maxconn = 10
        
        _pool = ThreadedConnectionPool(
            minconn,
            maxconn,
            dbname=Config.DB_NAME,
            user=Config.DB_USER,
            password=Config.DB_PASSWORD,
            host=Config.DB_HOST,
            port=Config.DB_PORT
        )
        logger.info(f"DB 연결 풀 생성 완료 (minconn={minconn}, maxconn={maxconn})")
    
    return _pool


@contextmanager
def get_db_connection():
    """
    데이터베이스 연결을 풀에서 가져오고 자동으로 반환하는 컨텍스트 매니저
    
    Yields:
        connection: 데이터베이스 연결 객체
    """
    pool = get_connection_pool()
    conn = pool.getconn()
    try:
        yield conn
    finally:
        pool.putconn(conn)


def insert_multiple_articles(articles: List[Dict[str, Any]]) -> int:
    """
    여러 기사를 데이터베이스에 삽입합니다.
    
    Args:
        articles: 삽입할 기사 목록
        
    Returns:
        int: 삽입된 기사 수
        
    Raises:
        Exception: 데이터베이스 작업 중 오류 발생 시
    """
    start_time = time.time()
    
    with get_db_connection() as conn:
        try:
            cur = conn.cursor()

            insert_query = """
            INSERT INTO articles 
            (title, content, link, stored_date, category, img)
            VALUES (%s, %s, %s, %s, %s, %s)
            """

            article_data = [(
                article["title"],
                article["content"],
                article["link"],
                article["stored_date"],
                article.get("category", "정치"),
                article.get("img")
            ) for article in articles]

            cur.executemany(insert_query, article_data)
            conn.commit()
            
            elapsed_time = time.time() - start_time
            logger.info(f"{len(articles)}개 기사 삽입 완료 (소요시간: {elapsed_time:.2f}초)")
            
            return len(articles)

        except Exception as e:
            conn.rollback()
            logger.error(f"DB 삽입 중 오류 발생: {e}")
            raise
        finally:
            cur.close()


@backoff.on_exception(
    backoff.expo,
    Exception,
    max_tries=3,
    max_time=30
)
def save_to_db_with_retry(articles: List[Dict[str, Any]]) -> int:
    """
    재시도 로직을 적용하여 기사를 데이터베이스에 저장합니다.
    
    Args:
        articles: 저장할 기사 목록
        
    Returns:
        int: 저장된 기사 수
    """
    return insert_multiple_articles(articles)