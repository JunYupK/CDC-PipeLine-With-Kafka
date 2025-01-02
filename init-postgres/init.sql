-- 카테고리 테이블
CREATE TABLE categories (
    id SERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 뉴스 아티클 테이블
CREATE TABLE articles (
    id BIGSERIAL PRIMARY KEY,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    link TEXT NOT NULL,
    category_id INTEGER REFERENCES categories(id),
    source VARCHAR(100),
    author VARCHAR(100),
    published_at TIMESTAMP WITH TIME ZONE,
    stored_date CHAR(8) NOT NULL CHECK (stored_date ~ '^\d{8}

-- 멀티미디어 테이블
CREATE TABLE media (
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT REFERENCES articles(id),
    type VARCHAR(20) NOT NULL, -- 'image', 'video', 'audio' 등
    url TEXT NOT NULL,
    caption TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- CDC 추적을 위한 변경 이력 테이블
CREATE TABLE article_changes (
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT NOT NULL,
    operation VARCHAR(10) NOT NULL, -- INSERT, UPDATE, DELETE
    changed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    old_data JSONB,
    new_data JSONB
);

-- 업데이트 트리거 함수
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
CREATE INDEX idx_media_article ON media(article_id);
CREATE INDEX idx_article_changes_article ON article_changes(article_id);
),  -- YYYYMMDD 형식
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    version INTEGER DEFAULT 1,
    is_deleted BOOLEAN DEFAULT FALSE
);

-- 멀티미디어 테이블
CREATE TABLE media (
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT REFERENCES articles(id),
    type VARCHAR(20) NOT NULL, -- 'image', 'video', 'audio' 등
    url TEXT NOT NULL,
    caption TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- CDC 추적을 위한 변경 이력 테이블
CREATE TABLE article_changes (
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT NOT NULL,
    operation VARCHAR(10) NOT NULL, -- INSERT, UPDATE, DELETE
    changed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    old_data JSONB,
    new_data JSONB
);

-- 업데이트 트리거 함수
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
CREATE INDEX idx_media_article ON media(article_id);
CREATE INDEX idx_article_changes_article ON article_changes(article_id);