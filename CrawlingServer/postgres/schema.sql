-- news schema 생성
CREATE SCHEMA IF NOT EXISTS news;

-- 뉴스 테이블 생성
CREATE TABLE news.articles (
    id SERIAL PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    link VARCHAR(500) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    crawled_at TIMESTAMP NOT NULL
);

-- link에 대한 unique 인덱스 생성 (중복 방지)
CREATE UNIQUE INDEX idx_articles_link ON news.articles(link);

-- created_at에 대한 인덱스 생성 (시간 기반 쿼리 최적화)
CREATE INDEX idx_articles_created_at ON news.articles(created_at);

-- 파티셔닝을 위한 테이블 재정의 (선택사항)
CREATE TABLE news.articles_partitioned (
    id SERIAL,
    title VARCHAR(500) NOT NULL,
    link VARCHAR(500) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    crawled_at TIMESTAMP NOT NULL
) PARTITION BY RANGE (created_at);

-- 월별 파티션 생성 예시
CREATE TABLE news.articles_202501 PARTITION OF news.articles_partitioned
    FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');

CREATE TABLE news.articles_202502 PARTITION OF news.articles_partitioned
    FOR VALUES FROM ('2025-02-01') TO ('2025-03-01');

-- 파티션 테이블에 대한 인덱스
CREATE UNIQUE INDEX idx_articles_partitioned_link
    ON news.articles_partitioned(link);

CREATE INDEX idx_articles_partitioned_created_at
    ON news.articles_partitioned(created_at);

-- 뉴스 통계를 위한 materialized view (선택사항)
CREATE MATERIALIZED VIEW news.daily_stats AS
SELECT
    DATE(created_at) as date,
    COUNT(*) as total_articles,
    COUNT(DISTINCT link) as unique_articles
FROM news.articles
GROUP BY DATE(created_at)
WITH DATA;

-- materialized view를 위한 인덱스
CREATE UNIQUE INDEX idx_daily_stats_date ON news.daily_stats(date);