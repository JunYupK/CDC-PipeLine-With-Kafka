-- 필요한 확장 설치
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
-- postgres_exporter 사용자 생성 및 권한 설정
CREATE USER postgres_exporter WITH PASSWORD 'password' SUPERUSER;
ALTER USER postgres_exporter SET search_path TO pg_catalog, public;

-- 시스템 카탈로그 뷰에 대한 명시적 권한 부여
GRANT SELECT ON pg_stat_database TO postgres_exporter;
GRANT SELECT ON pg_stat_bgwriter TO postgres_exporter;
GRANT SELECT ON pg_stat_archiver TO postgres_exporter;
GRANT SELECT ON pg_stat_activity TO postgres_exporter;
GRANT SELECT ON pg_stat_replication TO postgres_exporter;
GRANT SELECT ON pg_stat_wal_receiver TO postgres_exporter;
GRANT SELECT ON pg_stat_database_conflicts TO postgres_exporter;
GRANT SELECT ON pg_stat_user_tables TO postgres_exporter;
GRANT SELECT ON pg_stat_user_indexes TO postgres_exporter;
GRANT SELECT ON pg_statio_user_tables TO postgres_exporter;
GRANT SELECT ON pg_statio_user_indexes TO postgres_exporter;
GRANT SELECT ON pg_stat_statements TO postgres_exporter;

-- 모든 현재 및 미래의 테이블에 대한 SELECT 권한 부여
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO postgres_exporter;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO postgres_exporter;

-- 기존 테이블 생성 스크립트
-- 카테고리 테이블
CREATE TABLE categories (
   id SERIAL PRIMARY KEY,
   name VARCHAR(50) NOT NULL,
   created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
   updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 키워드 테이블
CREATE TABLE keywords (
   id SERIAL PRIMARY KEY,
   word VARCHAR(100) NOT NULL,
   created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
   UNIQUE(word)
);

-- 뉴스 아티클 테이블 (파티셔닝 적용)
CREATE TABLE articles (
   id BIGSERIAL,
   title TEXT NOT NULL,
   content TEXT NOT NULL,
   link TEXT NOT NULL,
   category_id INTEGER REFERENCES categories(id),
   category VARCHAR(100),
   source VARCHAR(100),
   author VARCHAR(100),
   published_at TIMESTAMP WITH TIME ZONE,
   stored_date CHAR(8) NOT NULL CHECK (stored_date ~ '^\d{8}$'),
   views_count INTEGER DEFAULT 0,
   sentiment_score FLOAT,
   article_text_length INTEGER,
   extracted_entities JSONB,
   created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
   updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
   version INTEGER DEFAULT 1,
   is_deleted BOOLEAN DEFAULT FALSE,
   PRIMARY KEY (id, stored_date)
) PARTITION BY RANGE (stored_date);

-- 초기 파티션 생성 (2025년 1월)
CREATE TABLE articles_202501 PARTITION OF articles
    FOR VALUES FROM ('20250101') TO ('20250201');

-- 멀티미디어 테이블
CREATE TABLE media (
   id BIGSERIAL PRIMARY KEY,
   article_id BIGINT,
   stored_date CHAR(8),
   type VARCHAR(20) NOT NULL,
   url TEXT NOT NULL,
   caption TEXT,
   created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
   updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
   FOREIGN KEY (article_id, stored_date) REFERENCES articles (id, stored_date)
);

-- CDC 추적을 위한 변경 이력 테이블
CREATE TABLE article_changes (
   id BIGSERIAL PRIMARY KEY,
   article_id BIGINT NOT NULL,
   operation VARCHAR(10) NOT NULL,
   changed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
   old_data JSONB,
   new_data JSONB
);

-- 기사-키워드 연결 테이블
CREATE TABLE article_keywords (
   article_id BIGINT,
   stored_date CHAR(8),
   keyword_id INTEGER REFERENCES keywords(id),
   frequency INTEGER NOT NULL DEFAULT 1,
   importance_score FLOAT,
   created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
   PRIMARY KEY (article_id, keyword_id),
   FOREIGN KEY (article_id, stored_date) REFERENCES articles (id, stored_date)
);

-- 요약 테이블
CREATE TABLE article_summaries (
   id BIGSERIAL PRIMARY KEY,
   article_id BIGINT,
   stored_date CHAR(8),
   summary_text TEXT NOT NULL,
   summary_type VARCHAR(20) NOT NULL,
   created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
   updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
   FOREIGN KEY (article_id, stored_date) REFERENCES articles (id, stored_date)
);

-- 관련 기사 테이블
CREATE TABLE related_articles (
   source_article_id BIGINT,
   source_stored_date CHAR(8),
   related_article_id BIGINT,
   related_stored_date CHAR(8),
   relation_type VARCHAR(50),
   similarity_score FLOAT,
   created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
   PRIMARY KEY (source_article_id, related_article_id),
   FOREIGN KEY (source_article_id, source_stored_date) REFERENCES articles (id, stored_date),
   FOREIGN KEY (related_article_id, related_stored_date) REFERENCES articles (id, stored_date)
);

-- update_timestamp 함수 생성
CREATE OR REPLACE FUNCTION update_timestamp()
RETURNS TRIGGER AS $$
BEGIN
   NEW.updated_at = CURRENT_TIMESTAMP;
   RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- articles 테이블 업데이트 트리거
CREATE TRIGGER update_article_timestamp
   BEFORE UPDATE ON articles
   FOR EACH ROW
   EXECUTE FUNCTION update_timestamp();

-- CDC 트리거 함수
CREATE OR REPLACE FUNCTION log_article_changes()
RETURNS TRIGGER AS $$
BEGIN
   IF (TG_OP = 'DELETE') THEN
       INSERT INTO article_changes (article_id, operation, old_data)
       VALUES (OLD.id, 'DELETE', to_jsonb(OLD));
   ELSIF (TG_OP = 'UPDATE') THEN
       INSERT INTO article_changes (article_id, operation, old_data, new_data)
       VALUES (NEW.id, 'UPDATE', to_jsonb(OLD), to_jsonb(NEW));
   ELSIF (TG_OP = 'INSERT') THEN
       INSERT INTO article_changes (article_id, operation, new_data)
       VALUES (NEW.id, 'INSERT', to_jsonb(NEW));
   END IF;
   RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- articles 테이블 CDC 트리거
CREATE TRIGGER article_changes_trigger
   AFTER INSERT OR UPDATE OR DELETE ON articles
   FOR EACH ROW
   EXECUTE FUNCTION log_article_changes();

-- 인덱스 생성
CREATE INDEX idx_articles_category ON articles(category_id);
CREATE INDEX idx_articles_published_at ON articles(published_at);
CREATE INDEX idx_articles_stored_date ON articles(stored_date);
CREATE INDEX idx_media_article ON media(article_id, stored_date);
CREATE INDEX idx_article_changes_article ON article_changes(article_id);
CREATE INDEX idx_article_keywords_keyword ON article_keywords(keyword_id);
CREATE INDEX idx_keywords_word ON keywords(word);

-- 기본 텍스트 검색 인덱스 생성 (english 설정 사용)
CREATE INDEX idx_articles_title_content ON articles USING gin(to_tsvector('english', title || ' ' || content));

-- 초기 카테고리 데이터 삽입
INSERT INTO categories (name) VALUES
    ('정치'),
    ('경제'),
    ('사회'),
    ('생활문화'),
    ('세계'),
    ('IT과학');

-- postgres_exporter 사용자에게 추가 테이블 권한 부여
GRANT SELECT ON ALL TABLES IN SCHEMA public TO postgres_exporter;
ALTER USER postgres_exporter SET SEARCH_PATH TO public;

      -- CDC 설정 자동화
DROP PUBLICATION IF EXISTS dbz_publication;
CREATE PUBLICATION dbz_publication FOR TABLE articles, media, article_changes;