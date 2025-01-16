-- 투자자 행동 분석 테이블
CREATE TABLE investor_behavior_analysis (
    user_id INT PRIMARY KEY,
    preferred_trading_hour INT,
    preferred_trading_day INT,
    preferred_platform VARCHAR(10),
    total_trades INT,
    total_traded_value DECIMAL(20,2),
    avg_trade_value DECIMAL(20,2),
    avg_buy_value DECIMAL(20,2),
    avg_sell_value DECIMAL(20,2),
    market_order_count INT,
    limit_order_count INT
);

-- 포트폴리오 리스크 분석 테이블
CREATE TABLE portfolio_risk_analysis (
    user_id INT PRIMARY KEY,
    total_portfolio_value DECIMAL(20,2),
    total_profit_loss DECIMAL(20,2),
    avg_profit_loss_pct DECIMAL(10,2),
    portfolio_beta DECIMAL(10,4),
    portfolio_volatility DECIMAL(10,4),
    portfolio_sharpe_ratio DECIMAL(10,4)
);

-- 기술적 분석 지표 테이블
CREATE TABLE trading_signals (
    stock_code VARCHAR(20),
    price_date DATE,
    prev_close DECIMAL(10,2),
    price_change DECIMAL(10,2),
    price_change_pct DECIMAL(10,2),
    5d_avg DECIMAL(10,2),
    20d_avg DECIMAL(10,2),
    volatility_20d DECIMAL(10,4),
    rsi_14d DECIMAL(10,2),
    PRIMARY KEY (stock_code, price_date)
);

-- 거래량 이상치 테이블
CREATE TABLE volume_anomalies (
    stock_code VARCHAR(20),
    trade_date DATE,
    daily_volume INT,
    avg_5d_volume DECIMAL(20,2),
    volume_ratio DECIMAL(10,2),
    PRIMARY KEY (stock_code, trade_date)
);

-- 가격 이상치 테이블
CREATE TABLE price_anomalies (
    stock_code VARCHAR(20),
    price_date DATE,
    close_price DECIMAL(10,2),
    price_change_pct DECIMAL(10,2),
    PRIMARY KEY (stock_code, price_date)
);