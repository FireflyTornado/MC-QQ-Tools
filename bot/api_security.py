import secrets

from fastapi import HTTPException

from app_config import MAX_TOKEN_LENGTH, is_bearer_token


MAX_AUTHORIZATION_LENGTH = MAX_TOKEN_LENGTH + 128


def verify_bearer(authorization: list[str] | None, expected_token: bytes) -> bool:
    if not authorization or len(authorization) != 1:
        return False
    header = authorization[0]
    if len(header) > MAX_AUTHORIZATION_LENGTH:
        return False

    scheme, _, token = header.strip(" \t").partition(" ")
    token = token.lstrip(" ")
    return (
        scheme.lower() == "bearer"
        and is_bearer_token(token)
        and secrets.compare_digest(token.encode("ascii"), expected_token)
    )


def require_bearer(authorization: list[str] | None, expected_token: bytes) -> None:
    if not verify_bearer(authorization, expected_token):
        raise HTTPException(
            status_code=401,
            detail="Unauthorized",
            headers={"WWW-Authenticate": "Bearer"},
        )
