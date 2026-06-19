from fastapi import FastAPI
from api.attendance_endpoint import router

app = FastAPI(title="FaceGate Backend")

app.include_router(router)

@app.get("/")
def root():
    return {"message": "FaceGate Backend Running"}