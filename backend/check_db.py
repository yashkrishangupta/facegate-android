import sqlite3

conn = sqlite3.connect("facegate.db")
cursor = conn.cursor()

cursor.execute("SELECT * FROM attendance_records")
rows = cursor.fetchall()

for row in rows:
    print(row)

conn.close()