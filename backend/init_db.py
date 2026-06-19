import sqlite3

conn = sqlite3.connect("facegate.db")

with open("db/schema.sql", "r") as f:
    conn.executescript(f.read())

conn.commit()
conn.close()

print("Database initialized!")