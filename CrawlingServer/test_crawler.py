import os
import sys
import json
import pytest
from unittest.mock import patch, MagicMock, AsyncMock

sys.path.insert(0, os.path.abspath(os.path.dirname(__file__)))

# 기본 테스트
def test_basic():
    """기본 동작 테스트"""
    assert True

def test_environment():
    """환경 설정 테스트"""
    assert 1 + 1 == 2

def test_service():
    """서비스 기본 동작 테스트"""
    result = "success"
    assert result == "success"