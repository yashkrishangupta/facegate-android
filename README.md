# FaceGate — Offline Face Recognition Attendance

An Android tablet app for school and camp attendance using real-time face recognition. Works fully offline — no internet required during attendance sessions. All biometric data stays on device.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Face detection | ML Kit Face Detection (offline, free) |
| Face alignment | OpenCV 4.10.0 |
| Face recognition | MobileFaceNet via ONNX Runtime Android |
| Embedding size | 128-D, L2-normalized |
| Storage | Room + SQLite (AES-256 ready via SQLCipher) |
| DI | Hilt |
| Camera | CameraX |
| Background sync | WorkManager (pending backend) |
| Min SDK | 26 (Android 8.0) |

---

## Pipeline

Every camera frame goes through 8 stages:

```
Camera (CameraX, 10 fps)
    ↓
[1] ML Kit Face Detection
    ↓
[2] Face Count Check  →  0 faces: NoFace  /  2+ faces: MultipleFaces
    ↓
[3] Quality Check     →  blur / brightness / size / pose / landmark confidence
    ↓
[4] Frame Buffer      →  collect 8 best-quality frames, pick top 1
    ↓
[5] Face Alignment    →  OpenCV affine warp → 112×112 canonical crop
    ↓
[6] MobileFaceNet     →  ONNX Runtime → 128-D L2-normalized embedding
    ↓
[7] Cosine Search     →  dot product loop over enrolled templates in memory
    ↓
[8] Decision Engine   →  Accept / Reject / Ambiguous / AlreadyMarked
    ↓
Room DB write + (future) backend sync
```

**Enrollment** uses the same pipeline but with a dedicated per-shot quality gate (`checkCaptureQuality()`). Each shutter press is validated immediately — rejected shots show a specific reason and do not consume a slot, so the user retakes that position. Only 5 quality-verified shots proceed to embedding. Their 128-D vectors are averaged element-wise and L2-normalised into a single blended template before saving to DB, making recognition more robust to lighting and pose variation.

---

## Key Configuration (`PipelineModels.kt`)

| Constant | Value | Notes |
|---|---|---|
| `MODEL_INPUT_SIZE` | 112 px | Fixed by MobileFaceNet |
| `EMBEDDING_SIZE` | 128 | Fixed by MobileFaceNet |
| `FRAME_BUFFER_SIZE` | 8 | Frames collected before processing |
| `THRESHOLD_ACCEPT` | 0.60 | Cosine similarity → Accept |
| `THRESHOLD_REJECT` | 0.40 | Cosine similarity → Reject |
| `MIN_FACE_SIZE_RATIO` | 0.05 | Face area / frame area |
| `MAX_YAW_DEGREES` | ±30° | Head turn limit |
| `MAX_PITCH_DEGREES` | ±20° | Head tilt limit |
| `MAX_ROLL_DEGREES` | ±15° | Head rotation limit |
| `MIN_LAPLACIAN_VARIANCE` | 80.0 | Blur threshold |
| `MIN_BRIGHTNESS` | 60 | Luminance range |
| `MAX_BRIGHTNESS` | 220 | Luminance range |
| `AMBIGUITY_MARGIN` | 0.12 | Twin-problem margin |

`PipelineModels.kt` also contains `QualityFailReason.toUserMessage()` — the single source of truth for quality failure strings used by both Attendance and Enrollment UIs. Do not duplicate these strings elsewhere.

---

## Project Structure

```
app/src/main/java/com/facegate/
├── FaceGateApp.kt                    Application class, OpenCV init, pipeline init
├── MainActivity.kt                   Single activity, dual nav graph (student / admin)
│
├── pipeline/
│   ├── PipelineModels.kt             Shared data classes + PipelineConfig constants
│   └── AttendancePipeline.kt         8-stage orchestrator, enrollment, session lifecycle
│
├── quality/
│   └── QualityChecker.kt             5 quality checks per frame (blur, brightness, pose, size, landmarks)
│
├── alignment/
│   └── FaceAligner.kt                OpenCV affine warp → 112×112 aligned crop
│
├── recognition/
│   └── FaceEmbedder.kt               ONNX Runtime inference → 128-D embedding
│
├── similarity/
│   └── SimilaritySearch.kt           Cosine dot-product search over in-memory templates
│
├── decision/
│   └── AttendanceDecisionEngine.kt   Threshold logic → Accept/Reject/Ambiguous/AlreadyMarked
│
├── storage/
│   ├── FaceGateDatabase.kt           Room database definition (v1)
│   ├── TemplateRepository.kt         Single DB interface for the pipeline
│   ├── dao/
│   │   ├── StudentDao.kt
│   │   ├── AttendanceDao.kt
│   │   ├── ConflictDao.kt
│   │   └── SyncLogDao.kt
│   └── entity/
│       ├── StudentEntity.kt          studentId, name, studentClass, embedding (csv string)
│       ├── AttendanceEntity.kt       studentId, timeStamp, synced
│       ├── ConflictEntity.kt         both candidates + scores + reason + sessionId
│       └── SyncLogEntity.kt
│
├── sync/
│   ├── AttendanceSyncWorker.kt       WorkManager job (backend URL pending)
│   └── SyncRepository.kt
│
├── benchmark/
│   └── PipelineBenchmark.kt          Per-stage latency measurement
│
├── di/
│   └── AppModule.kt                  Hilt providers: DB → Repository → Pipeline
│
└── ui/
    ├── attendance/
    │   ├── AttendanceFragment.kt     CameraX preview + ImageAnalysis → pipeline
    │   └── AttendanceViewModel.kt    PipelineFrameStatus → ScanState
    ├── enrollment/
    │   ├── EnrollmentFragment.kt     CameraX ImageCapture, per-shot feedback, student dialog
    │   └── EnrollmentViewModel.kt    checkCaptureQuality → 5 shots → enrollStudentFromEmbeddings
    └── admin/
        ├── AdminDashboard.kt
        ├── AdminDashboardViewModel.kt  Real DB stats (students, present, absent, conflicts)
        ├── StudentsFragment.kt         Full student list from DB with edit and delete
        ├── StudentsViewModel.kt
        ├── ManualAttendanceFragment.kt Class-filtered student list, tap to toggle present/absent
        ├── ManualAttendanceViewModel.kt
        ├── ConflictQueueFragment.kt    Unresolved conflicts from DB, resolve button
        ├── ConflictQueueViewModel.kt
        └── reports/
            ├── AttendanceReportFragment.kt  Today's stats + class-wise breakdown from DB JOIN
            └── ReportViewModel.kt
```

---

## Database Schema

**`students`**
| Column | Type | Notes |
|---|---|---|
| studentId | TEXT PK | Roll number or unique ID |
| name | TEXT | Display name |
| studentClass | TEXT | Class / section e.g. "9-B" |
| embedding | TEXT | 128 floats, comma-separated — blended average of 5 enrollment shots |

**`attendance_records`**
| Column | Type | Notes |
|---|---|---|
| id | INTEGER PK | Auto-generated |
| studentId | TEXT | FK → students.studentId |
| timeStamp | INTEGER | Unix epoch ms |
| synced | INTEGER | 0 = pending sync, 1 = synced |

**`conflict_queue`**
| Column | Type | Notes |
|---|---|---|
| id | INTEGER PK | Auto-generated |
| topStudentId / topStudentName / topScore | | Best match candidate |
| secondStudentId / secondStudentName / secondScore | | Runner-up candidate |
| reason | TEXT | Human-readable explanation |
| sessionId | TEXT | Session when this occurred |
| timestamp | INTEGER | Unix epoch ms |
| resolved | INTEGER | 0 = pending, 1 = resolved by admin |

**`sync_log`** — tracks backend sync history

---

## Decision Logic

```
similarity < 0.40         → Reject
already marked this session → AlreadyMarked
0.40 ≤ similarity < 0.60 → Ambiguous (gray zone, goes to conflict queue)
top-1 and top-2 within 0.12 margin → Ambiguous (twin problem)
similarity ≥ 0.60 and clear winner → Accept
```

---

## Enrollment Flow

1. Admin opens Enrollment screen
2. Camera preview starts (front camera)
3. Admin taps shutter up to 5 times — each shot is validated **immediately** via `checkCaptureQuality()`:
   - Scaled to max 640px
   - Checked for: single face, blur, brightness, pose, face size
   - Rejected shots show a specific reason ("Too blurry — retake") and **do not consume a slot** — the user retakes that same position
   - Accepted shots fill in the progress dots
4. After 5 accepted shots, a dialog asks for name, student ID, and class
5. Pipeline runs alignment + embedding on all 5 verified shots
6. All 5 embeddings are **averaged element-wise and L2-normalised** into a single blended 128-D template — more robust than storing a single shot
7. Duplicate check against already-enrolled students
8. Saved to Room DB and added to in-memory session cache

---

## Attendance Flow

1. Teacher opens Attendance screen → `startSession()` loads enrolled students from DB into memory
2. Camera feeds frames at 10fps via `ImageAnalysis`
3. Each frame runs through all 8 pipeline stages
4. UI updates live: oval color, badge text, and status message
5. On Accept: attendance record written to DB, student shown for 3s, auto-reset
6. On Ambiguous: written to conflict queue for admin review
7. Teacher presses End → `endSession()` clears biometric data from RAM

---

## Admin Screens

| Screen | Data source | Notes |
|---|---|---|
| Dashboard | Live DB counts (students, present today, absent, conflict count) | |
| Students | Full student list from DB | Edit name/class (embedding preserved) + delete with confirmation |
| Manual Attendance | Students filtered by class | Tap to toggle present ↔ absent; today's record added or removed from DB |
| Conflict Queue | Unresolved conflicts from DB | Resolve button |
| Attendance Report | Today's % + class-wise present count (SQL JOIN) | |

---

## Pending (Backend Required)

- `AttendanceSyncWorker` is built and ready — it reads unsynced records from Room and marks them synced after upload
- To activate: add the backend endpoint URL and auth token, call `AttendanceSyncWorker.schedule(context)` from `endSession()`
- Export to Excel / PDF in Attendance Report (TODOs marked in `AttendanceReportFragment`)

---

## Setup

**1. Model**
The file `app/src/main/assets/models/mobilefacenet.onnx` must be the real MobileFaceNet weights. A placeholder is included for compilation. Replace it with a real model converted from `foamliu/MobileFaceNet` (Apache 2.0 licensed):

```bash
python3 scripts/convert_mobilefacenet_to_onnx.py
cp mobilefacenet.onnx app/src/main/assets/models/mobilefacenet.onnx
```

**2. Build**
```bash
./gradlew assembleDebug
```

**3. Run on device**
Connect an Android device (API 26+), enable USB debugging, then run from Android Studio or:
```bash
./gradlew installDebug
```

**4. Benchmark**
Run `PipelineBenchmark.kt` on the actual target tablet before using in production. Total latency should be under 1000ms. If over, reduce `FRAME_BUFFER_SIZE` or enable NNAPI acceleration in `FaceEmbedder`.

---

## Team - Interns of Reagvis Labs
Yash Krishan Gupta  
Mahima  
Mahi Garg  
Krish Bansal  
Pragati Dinkar Kharat  
Anmol Yadav
