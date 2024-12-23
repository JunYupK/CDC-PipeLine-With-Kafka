from pyspark.sql import SparkSession, Window
from pyspark.sql.functions import (
    sum, count, avg, stddev, max, min, col, expr, 
    when, datediff, rank, lag, date_format, 
    hour, dayofweek, month, year, lit,
    abs as spark_abs
)
import pyspark.sql.functions as F

def create_spark_session():
    return SparkSession.builder \
        .appName("Advanced Stock Trading Analysis") \
        .config("spark.jars", "./jars/postgresql-42.2.18.jar,./jars/mysql-connector-java-8.0.23.jar") \
        .config("spark.sql.session.timeZone", "Asia/Seoul") \
        .getOrCreate()


def extract_data(spark):
    # 공통 데이터베이스 설정
    postgres_options = {
        "url": "jdbc:postgresql://localhost:5432/source_db",
        "user": "kjy",
        "password": "home",
        "driver": "org.postgresql.Driver"
    }

    try:
        # 각 테이블 추출
        trades_df = spark.read.format("jdbc") \
            .options(**postgres_options) \
            .option("dbtable", "trades") \
            .load()

        print("trades table loaded successfully")

        stock_price_df = spark.read.format("jdbc") \
            .options(**postgres_options) \
            .option("dbtable", "stock_price_history") \
            .load()

        print("stock_price_history table loaded successfully")

        portfolio_df = spark.read.format("jdbc") \
            .options(**postgres_options) \
            .option("dbtable", "user_portfolio") \
            .load()

        print("user_portfolio table loaded successfully")

        risk_df = spark.read.format("jdbc") \
            .options(**postgres_options) \
            .option("dbtable", "risk_metrics") \
            .load()

        print("risk_metrics table loaded successfully")

        return trades_df, stock_price_df, portfolio_df, risk_df

    except Exception as e:
        print(f"Error in extract_data: {str(e)}")
        return None, None, None, None

def calculate_user_investment_behavior(trades_df):
    """사용자별 투자 행동 패턴 분석"""
    # 거래 시간대별 선호도
    time_window = Window.partitionBy('user_id')
    
    return trades_df \
        .withColumn('trade_hour', hour('trade_timestamp')) \
        .withColumn('trade_day', dayofweek('trade_timestamp')) \
        .withColumn('trade_value', col('quantity') * col('price')) \
        .groupBy('user_id') \
        .agg(
            # 거래 패턴
            F.mode('trade_hour').alias('preferred_trading_hour'),
            F.mode('trade_day').alias('preferred_trading_day'),
            F.mode('platform').alias('preferred_platform'),
            
            # 거래 통계
            count('trade_id').alias('total_trades'),
            sum('trade_value').alias('total_traded_value'),
            avg('trade_value').alias('avg_trade_value'),
            
            # 거래 스타일
            avg(when(col('trade_type') == 'BUY', col('trade_value')).otherwise(0)).alias('avg_buy_value'),
            avg(when(col('trade_type') == 'SELL', col('trade_value')).otherwise(0)).alias('avg_sell_value'),
            sum(when(col('order_type') == 'MARKET', 1).otherwise(0)).alias('market_order_count'),
            sum(when(col('order_type') == 'LIMIT', 1).otherwise(0)).alias('limit_order_count')
        )

def analyze_portfolio_risk(portfolio_df, risk_df, stock_price_df):
    """포트폴리오 리스크 분석"""
    # 최신 주가 데이터 가져오기
    latest_prices = stock_price_df \
        .groupBy('stock_code') \
        .agg(
            max('price_date').alias('latest_date')
        ) \
        .join(stock_price_df, ['stock_code']) \
        .where(col('price_date') == col('latest_date'))

    # 포트폴리오 가치 및 리스크 계산
    portfolio_risk = portfolio_df \
        .join(latest_prices, 'stock_code') \
        .join(risk_df, 'stock_code') \
        .withColumn('current_value', col('quantity') * col('close_price')) \
        .withColumn('profit_loss', (col('close_price') - col('avg_purchase_price')) * col('quantity')) \
        .withColumn('profit_loss_pct', (col('close_price') - col('avg_purchase_price')) / col('avg_purchase_price') * 100) \
        .groupBy('user_id') \
        .agg(
            sum('current_value').alias('total_portfolio_value'),
            sum('profit_loss').alias('total_profit_loss'),
            avg('profit_loss_pct').alias('avg_profit_loss_pct'),
            avg('beta').alias('portfolio_beta'),
            avg('volatility').alias('portfolio_volatility'),
            avg('sharpe_ratio').alias('portfolio_sharpe_ratio')
        )
    
    return portfolio_risk


def calculate_trading_signals(stock_price_df):
    """기술적 분석 지표 계산"""
    window_spec = Window.partitionBy('stock_code').orderBy('price_date')

    # 기본 지표 계산
    df = stock_price_df \
        .withColumn('prev_close', lag('close_price', 1).over(window_spec)) \
        .withColumn('price_change', col('close_price') - col('prev_close')) \
        .withColumn('price_change_pct', col('price_change') / col('prev_close') * 100) \
        .withColumn('5d_avg', avg('close_price').over(window_spec.rowsBetween(-4, 0))) \
        .withColumn('20d_avg', avg('close_price').over(window_spec.rowsBetween(-19, 0))) \
        .withColumn('volatility_20d', stddev('price_change_pct').over(window_spec.rowsBetween(-19, 0)))

    # RSI 계산을 위한 이동평균 계산
    df = df \
        .withColumn('gain', when(col('price_change') > 0, col('price_change')).otherwise(0)) \
        .withColumn('loss', when(col('price_change') < 0, spark_abs(col('price_change'))).otherwise(0)) \
        .withColumn('avg_gain', avg('gain').over(window_spec.rowsBetween(-13, 0))) \
        .withColumn('avg_loss', avg('loss').over(window_spec.rowsBetween(-13, 0))) \
        .withColumn('rs', col('avg_gain') / col('avg_loss')) \
        .withColumn('rsi_14d',
                    when(col('avg_loss') == 0, 100)
                    .when(col('avg_gain') == 0, 0)
                    .otherwise(100 - (100 / (1 + col('rs'))))
                    )

    return df


def identify_trading_anomalies(trades_df, stock_price_df):
    """비정상 거래 패턴 식별"""
    # 일별 거래량 급증 패턴
    daily_volume = trades_df \
        .groupBy('stock_code', date_format('trade_timestamp', 'yyyy-MM-dd').alias('trade_date')) \
        .agg(sum('quantity').alias('daily_volume'))

    window_spec = Window.partitionBy('stock_code').orderBy('trade_date')
    volume_anomalies = daily_volume \
        .withColumn('avg_5d_volume', avg('daily_volume').over(window_spec.rowsBetween(-4, 0))) \
        .withColumn('volume_ratio', col('daily_volume') / col('avg_5d_volume')) \
        .where(col('volume_ratio') > 3)  # 거래량이 5일 평균의 3배 이상

    # 가격 급등락 패턴
    price_window_spec = Window.partitionBy('stock_code').orderBy('price_date')
    price_changes = stock_price_df \
        .withColumn('price_change_pct',
                    (col('close_price') - lag('close_price', 1).over(price_window_spec)) /
                    lag('close_price', 1).over(price_window_spec) * 100
                    ) \
        .where(spark_abs(col('price_change_pct')) > 10)  # abs() -> spark_abs() 로 변경

    return volume_anomalies, price_changes

def load_to_mysql(df, table_name):
    df.write \
        .format("jdbc") \
        .option("url", "jdbc:mysql://localhost:13306/target_db") \
        .option("dbtable", table_name) \
        .option("user", "kjy") \
        .option("password", "home") \
        .option("driver", "com.mysql.cj.jdbc.Driver") \
        .mode("overwrite") \
        .save()

def main():
    spark = create_spark_session()
    try:
        # 데이터 추출
        trades_df, stock_price_df, portfolio_df, risk_df = extract_data(spark)

        # # 투자자 행동 분석
        investor_behavior = calculate_user_investment_behavior(trades_df)
        load_to_mysql(investor_behavior, "investor_behavior_analysis")
        #
        # # 포트폴리오 리스크 분석
        portfolio_risk = analyze_portfolio_risk(portfolio_df, risk_df, stock_price_df)
        load_to_mysql(portfolio_risk, "portfolio_risk_analysis")
        #
        # # 기술적 분석 지표
        trading_signals = calculate_trading_signals(stock_price_df)
        load_to_mysql(trading_signals, "trading_signals")
        #
        # # 이상 거래 탐지
        volume_anomalies, price_anomalies = identify_trading_anomalies(trades_df, stock_price_df)

        load_to_mysql(volume_anomalies, "volume_anomalies")
        load_to_mysql(price_anomalies, "price_anomalies")
        
        print("ETL job completed successfully!")
        
    except Exception as e:
        print(f"Error in ETL job: {str(e)}")
        raise
    finally:
        spark.stop()

if __name__ == "__main__":
    main()