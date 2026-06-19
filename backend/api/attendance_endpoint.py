from fastapi import APIRouter, Body
import sqlite3

router = APIRouter()

@router.post("/api/attendance/sync")
async def sync_attendance(records: list = Body(...)):

    conn = sqlite3.connect("facegate.db")
    cursor = conn.cursor()

    for record in records:
        cursor.execute(
            """
            INSERT INTO attendance_records
            (student_id, session_id, timestamp, status, synced)
            VALUES (?, ?, ?, ?, ?)
            """,
            (
                record["student_id"],
                record["session_id"],
                record["timestamp"],
                record["status"],
                1
            )
        )

    conn.commit()
    conn.close()

    return {
        "success": True,
        "records_received": len(records)
    }