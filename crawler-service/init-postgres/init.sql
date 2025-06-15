-- CDC용 PostgreSQL 설정
ALTER SYSTEM SET wal_level = logical;
ALTER SYSTEM SET max_wal_senders = 10;
ALTER SYSTEM SET max_replication_slots = 10;

-- 카테고리 테이블
CREATE TABLE categories (
                            id SERIAL PRIMARY KEY,
                            name VARCHAR(50) NOT NULL,
                            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                            updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 뉴스 아티클 테이블 (파티셔닝 제거)
CREATE TABLE articles (
                          id BIGSERIAL PRIMARY KEY,
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
   created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
   updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
   version INTEGER DEFAULT 1,
   is_deleted BOOLEAN DEFAULT FALSE
);

-- 미디어 테이블 (FK 수정)
CREATE TABLE media (
                       id BIGSERIAL PRIMARY KEY,
                       article_id BIGINT REFERENCES articles(id),
                       stored_date CHAR(8),
                       type VARCHAR(20) NOT NULL,
                       url TEXT NOT NULL,
                       caption TEXT,
                       created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                       updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- CDC 트리거 함수
CREATE OR REPLACE FUNCTION log_article_changes()
RETURNS TRIGGER AS $$
BEGIN
   -- 단순화: 변경 내용만 로깅
   RAISE NOTICE 'Article % was %', NEW.id, TG_OP;
RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- CDC 트리거
CREATE TRIGGER article_changes_trigger
    AFTER INSERT OR UPDATE OR DELETE ON articles
    FOR EACH ROW
    EXECUTE FUNCTION log_article_changes();

-- 인덱스
CREATE INDEX idx_articles_category ON articles(category);
CREATE INDEX idx_articles_stored_date ON articles(stored_date);
CREATE INDEX idx_articles_link ON articles(link);

-- 초기 데이터
INSERT INTO categories (name) VALUES
                                  ('정치'), ('경제'), ('사회'), ('생활문화'), ('세계'), ('IT과학');

-- CDC 설정
CREATE PUBLICATION dbz_publication FOR TABLE articles, media;