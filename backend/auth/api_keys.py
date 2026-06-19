API_KEYS = {
    "facegate-dev-token": "android_client"
}

def validate_api_key(token: str):
    return token in API_KEYS