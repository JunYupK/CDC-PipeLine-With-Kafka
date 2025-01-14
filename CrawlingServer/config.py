# config.py
import os
from dotenv import load_dotenv

load_dotenv()


class Config:
    DB_NAME = os.getenv('DB_NAME')
    DB_USER = os.getenv('DB_USER')
    DB_PASSWORD = os.getenv('DB_PASSWORD')
    DB_HOST = os.getenv('DB_HOST')
    DB_PORT = os.getenv('DB_PORT')

    @classmethod
    def validate(cls):
        missing_vars = []
        for attr in dir(cls):
            if not attr.startswith('_') and getattr(cls, attr) is None:
                missing_vars.append(attr)

        if missing_vars:
            raise ValueError(f"Missing required environment variables: {', '.join(missing_vars)}")

# Config 클래스를 명시적으로 export
__all__ = ['Config']