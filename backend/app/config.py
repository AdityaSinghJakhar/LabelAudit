from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    app_name: str = "Label Compliance Audit API"
    app_version: str = "0.1.0"
    database_url: str = "postgresql://labelaudit:labelaudit@localhost:5432/labelaudit"

    class Config:
        env_file = ".env"


settings = Settings()
