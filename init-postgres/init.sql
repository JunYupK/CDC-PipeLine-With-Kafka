CREATE TABLE trades (
    trade_id SERIAL PRIMARY KEY,
    user_id INTEGER,
    stock_code VARCHAR(20),
    trade_type VARCHAR(4),
    quantity INTEGER,
    price DECIMAL(10,2),
    trade_timestamp TIMESTAMP,
    order_type VARCHAR(10), -- 'MARKET', 'LIMIT'
    platform VARCHAR(10)    -- 'WEB', 'APP', 'API'
);
CREATE TABLE stock_price_history (
    stock_code VARCHAR(20),
    price_date DATE,
    open_price DECIMAL(10,2),
    high_price DECIMAL(10,2),
    low_price DECIMAL(10,2),
    close_price DECIMAL(10,2),
    volume INTEGER,
    PRIMARY KEY (stock_code, price_date)
);

CREATE TABLE user_portfolio (
    portfolio_id SERIAL PRIMARY KEY,
    user_id INTEGER,
    stock_code VARCHAR(20),
    quantity INTEGER,
    avg_purchase_price DECIMAL(10,2),
    last_updated TIMESTAMP
);

CREATE TABLE risk_metrics (
    stock_code VARCHAR(20),
    calc_date DATE,
    beta DECIMAL(10,4),
    volatility DECIMAL(10,4),
    sharpe_ratio DECIMAL(10,4),
    PRIMARY KEY (stock_code, calc_date)
);