from pyspark.sql import SparkSession
from pyspark.sql.functions import sum, count, date_format

def create_spark_session():
    return SparkSession.builder \
        .appName("Orders ETL") \
        .config("spark.jars", "/opt/spark-scripts/postgresql-42.2.18.jar,/opt/spark-scripts/mysql-connector-java-8.0.23.jar") \
        .getOrCreate()

def extract_from_postgres(spark):
    return spark.read \
        .format("jdbc") \
        .option("url", "jdbc:postgresql://postgres:5432/source_db") \
        .option("dbtable", "orders") \
        .option("user", "source_user") \
        .option("password", "source_password") \
        .option("driver", "org.postgresql.Driver") \
        .load()

def transform_data(df):
    return df.groupBy(
        date_format('order_date', 'yyyy-MM-dd').alias('date'),
        'product_id'
    ).agg(
        sum('quantity').alias('total_quantity'),
        sum('price').alias('total_revenue'),
        count('order_id').alias('order_count')
    )

def load_to_mysql(df):
    df.write \
        .format("jdbc") \
        .option("url", "jdbc:mysql://mysql:3306/target_db") \
        .option("dbtable", "daily_product_summary") \
        .option("user", "target_user") \
        .option("password", "target_password") \
        .option("driver", "com.mysql.cj.jdbc.Driver") \
        .mode("overwrite") \
        .save()

def main():
    spark = create_spark_session()
    try:
        # Extract
        orders_df = extract_from_postgres(spark)
        
        # Transform
        summary_df = transform_data(orders_df)
        
        # Load
        load_to_mysql(summary_df)
        
        print("ETL job completed successfully!")
        
    except Exception as e:
        print(f"Error in ETL job: {str(e)}")
    finally:
        spark.stop()

if __name__ == "__main__":
    main()