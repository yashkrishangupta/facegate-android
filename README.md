# FaceGate — Offline Face Recognition Attendance

An Android tablet app for school and camp attendance using real-time face recognition. Works fully offline — no internet required during attendance sessions. All biometric data stays on-device and never leaves the hardware.

---

## What it does

- **Students** walk up to the tablet → face is detected, quality-checked, aligned, embedded, matched → attendance marked automatically in under 1 second
- **Admin** can enrol students (5-shot averaged embedding), view live dashboard stats, manually mark attendance by class, resolve ambiguous matches, and view attendance reports with class-wise breakdowns
- **Conflict queue** captures any ambiguous match (two students too similar to distinguish) for manual admin review, so no silent misidentification

---

## Tech Stack

| Component | Choice | Version |
|---|---|---|
| Language | Kotlin | 1.9.24 |
| Min SDK | 26 (Android 8.0) | |
| Face detection | ML Kit Face Detection | 16.1.5 |
| Face alignment | OpenCV (`org.opencv`) | 4.10.0 |
| Face recognition | MobileFaceNet via ONNX Runtime | 1.19.2 |
| Storage | Room + SQLite | 2.6.1 |
| Encryption (ready) | SQLCipher AES-256 | 4.5.4 |
| Camera | CameraX | 1.3.1 |
| Dependency injection | Hilt | 2.51.1 |
| Background jobs | WorkManager | 2.9.0 |
| Coroutines | kotlinx-coroutines | 1.7.3 |
| Annotation processing | KSP | 1.9.24-1.0.20 |
| Build system | Gradle + AGP | 8.5.2 |

---

## Full Project Structure

```
FaceGate/
├── gradle/
│   └── libs.versions.toml              Version catalog — all dependency versions in one place
│
├── build.gradle.kts                    Root build file — declares plugins (AGP, KSP, Hilt)
├── settings.gradle.kts                 Project name + include :app
├── gradle.properties                   JVM args, Kotlin code style
├── gradle/wrapper/
│   └── gradle-wrapper.properties       Gradle 9.3.1
│
├── scripts/
│   └── convert_mobilefacenet_to_onnx.py   Converts pretrained .pt weights to ONNX
│
└── app/
    ├── build.gradle.kts                App-level build — dependencies, KSP config, NDK ABIs
    ├── proguard-rules.pro
    │
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml     CAMERA + INTERNET + WAKE_LOCK permissions
        │   │
        │   ├── assets/
        │   │   └── models/
        │   │       └── mobilefacenet.onnx   Real MobileFaceNet weights (Apache 2.0)
        │   │
        │   └── java/com/facegate/
        │       │
        │       ├── FaceGateApp.kt          @HiltAndroidApp — initialises OpenCV + warms up pipeline
        │       ├── MainActivity.kt         Single activity, loads student or admin nav graph
        │       │
        │       ├── pipeline/
        │       │   ├── PipelineModels.kt   SHARED CONTRACT — all data classes + PipelineConfig
        │       │   └── AttendancePipeline.kt  8-stage orchestrator, enrollment, session lifecycle
        │       │
        │       ├── quality/
        │       │   └── QualityChecker.kt   Blur (Laplacian) · brightness · face size · pose · landmark confidence
        │       │
        │       ├── alignment/
        │       │   └── FaceAligner.kt      OpenCV affine warp using 5 landmarks → 112×112 crop
        │       │
        │       ├── recognition/
        │       │   └── FaceEmbedder.kt     ONNX Runtime inference → 128-D L2-normalised embedding
        │       │
        │       ├── similarity/
        │       │   └── SimilaritySearch.kt Cosine dot-product loop over in-memory templates
        │       │
        │       ├── decision/
        │       │   └── AttendanceDecisionEngine.kt   Thresholds → Accept/Reject/Ambiguous/AlreadyMarked
        │       │
        │       ├── storage/
        │       │   ├── FaceGateDatabase.kt     Room @Database (v1, 4 entities)
        │       │   ├── TemplateRepository.kt   Single DB interface — all queries the pipeline needs
        │       │   ├── Database.kt             (placeholder)
        │       │   ├── dao/
        │       │   │   ├── StudentDao.kt        getAllStudents, getByClass, getCount, delete
        │       │   │   ├── AttendanceDao.kt     insert, getUnsynced, getTodayAttendance, getClassWise (JOIN)
        │       │   │   ├── ConflictDao.kt       insert, getUnresolved, getCount, markResolved
        │       │   │   └── SyncLogDao.kt        insert, getAll
        │       │   └── entity/
        │       │       ├── StudentEntity.kt     studentId · name · studentClass · embedding (csv)
        │       │       ├── AttendanceEntity.kt  studentId · timeStamp · synced
        │       │       ├── ConflictEntity.kt    both candidates · scores · reason · sessionId · resolved
        │       │       └── SyncLogEntity.kt
        │       │
        │       ├── sync/
        │       │   ├── AttendanceSyncWorker.kt  WorkManager job — reads unsynced records, posts to backend
        │       │   └── SyncRepository.kt        HTTP layer (endpoint URL pending)
        │       │
        │       ├── benchmark/
        │       │   └── PipelineBenchmark.kt     Per-stage latency, reports mean/p95
        │       │
        │       ├── di/
        │       │   └── AppModule.kt             Hilt: DB → Repository → Pipeline (singleton chain)
        │       │
        │       └── ui/
        │           ├── attendance/
        │           │   ├── AttendanceFragment.kt    CameraX Preview + ImageAnalysis → pipeline
        │           │   └── AttendanceViewModel.kt   PipelineFrameStatus → ScanState StateFlow
        │           │
        │           ├── enrollment/
        │           │   ├── EnrollmentFragment.kt    CameraX ImageCapture, per-shot feedback, student info dialog
        │           │   └── EnrollmentViewModel.kt   checkCaptureQuality → 5 shots → averages embeddings → saves
        │           │
        │           └── admin/
        │               ├── AdminDashboard.kt              Live stats from DB (students, present, absent, conflicts)
        │               ├── AdminDashboardViewModel.kt
        │               ├── StudentsFragment.kt            Full student list from DB, delete with confirmation
        │               ├── StudentsViewModel.kt
        │               ├── ManualAttendanceFragment.kt    Students by class, tap to mark present
        │               ├── ManualAttendanceViewModel.kt
        │               ├── ConflictQueueFragment.kt       Unresolved conflicts from DB, resolve button
        │               ├── ConflictQueueViewModel.kt
        │               ├── HolidaysFragment.kt
        │               └── reports/
        │                   ├── AttendanceReportFragment.kt  Today's % + class-wise breakdown (SQL JOIN)
        │                   └── ReportViewModel.kt
        │
        ├── res/
        │   ├── anim/
        │   │   ├── fade_in.xml / fade_out.xml      Screen transition animations
        │   │   ├── scan_line.xml                   Scanning line sweep animation
        │   │   ├── result_pop.xml                  Accept/reject result pop-in
        │   │   └── slide_up.xml
        │   │
        │   ├── drawable/
        │   │   ├── oval_face_guide.xml             Idle oval (white stroke)
        │   │   ├── oval_face_scanning.xml          Scanning oval (teal stroke, animated)
        │   │   ├── oval_face_success.xml           Accept oval (green fill)
        │   │   ├── oval_face_fail.xml              Reject oval (red fill)
        │   │   ├── oval_face_admin.xml             Admin enrollment oval
        │   │   ├── chip_present/absent/active/pending.xml   Student status chips
        │   │   ├── bg_*.xml                        Gradient backgrounds
        │   │   ├── btn_camera_*.xml                Camera button backgrounds
        │   │   ├── card_*.xml                      Card view backgrounds
        │   │   ├── icon_*.xml                      Icon tile + action backgrounds
        │   │   └── scan_line_*.xml                 Scan line drawables
        │   │
        │   ├── layout/
        │   │   ├── activity_main.xml               Root container with dual NavHostFragment
        │   │   ├── fragment_attendance.xml         Camera preview, oval overlay, status cards
        │   │   ├── fragment_enrollment.xml         Camera preview, shot dots, shutter button
        │   │   ├── fragment_admin_dashboard.xml    Stats tiles, clock, nav bar
        │   │   ├── fragment_student.xml            Student list + Enrol button
        │   │   ├── fragment_manual_attendance.xml  Class tabs + student list
        │   │   ├── fragment_conflict_queue.xml     Conflict cards
        │   │   ├── fragment_attendance_report.xml  Stats + class breakdown
        │   │   ├── dialog_student_info.xml         Name / ID / Class input dialog
        │   │   └── view_face_oval_overlay.xml      Reusable oval camera overlay
        │   │
        │   ├── navigation/
        │   │   ├── nav_graph.xml                   Admin navigation (dashboard → all admin screens)
        │   │   └── student_nav_graph.xml            Student navigation (attendance / enrollment)
        │   │
        │   └── values/
        │       ├── colors.xml
        │       ├── strings.xml
        │       ├── dimens.xml
        │       └── themes.xml (+ values-night/)
        │
        └── test/
            ├── SimilarityDecisionTest.kt     Unit tests — cosine search + decision engine
            └── quality/
                └── QualityCheckerTest.kt      Unit tests — quality check logic

```

---

## ML Pipeline (Attendance)

Every camera frame goes through 8 stages. Detection and quality run on every frame (cheap). Alignment and embedding run once on the best buffered frame (expensive).

```
Camera (CameraX ImageAnalysis, 10 fps)
    ↓
[1] ML Kit Face Detection
    ↓
[2] Face Count Check  →  0 faces: NoFace  /  2+ faces: MultipleFaces
    ↓
[3] Quality Check     →  fail: QualityFailed(reasons)
       · Blur:  Laplacian variance ≥ 80.0
       · Brightness: histogram 60–220
       · Face size: area ≥ 5% of frame area
       · Pose: yaw ±30°, pitch ±20°, roll ±15°
       · Landmark confidence ≥ 0.70
    ↓
[4] Frame Buffer      →  collect 8 frames, select highest quality score
                         → Buffering(framesCollected, framesNeeded)
    ↓
[5] Face Alignment    →  OpenCV affine warp using 5 landmarks
                         → 112×112 canonical face crop
    ↓
[6] MobileFaceNet     →  ONNX Runtime → 128-D L2-normalised embedding
                         → ~150–400ms on mid-range tablet
    ↓
[7] Cosine Search     →  dot product loop over in-memory templates
                         → top-1 + top-2 similarity scores
    ↓
[8] Decision Engine   →  see Decision Logic below
    ↓
Room DB write (Accept → attendance_records, Ambiguous → conflict_queue)
```

## Enrollment Pipeline

```
5× ImageCapture (front camera, full resolution)
    ↓ per shot:
[1] Scale to max 640px                     high-res capture safety
[2] ML Kit detection + face count check
[3] Quality gate (checkCaptureQuality)     immediate per-shot feedback
    ↓ after 5 accepted shots:
[4] Align + embed each shot (5 embeddings)
[5] Average the 5 embeddings               blended template, more robust
[6] Duplicate risk check                   cosine sim against existing templates
[7] Save to Room (embedding as csv string)
[8] Add to in-memory session cache
```

---

## Decision Logic

```
similarity < 0.40                              →  Reject
0.40 ≤ similarity < 0.60                       →  Ambiguous (gray zone → conflict queue)
top-1 minus top-2 < 0.12 (twin margin)        →  Ambiguous (lookalike → conflict queue)
student already marked this session            →  AlreadyMarked
similarity ≥ 0.60 and clear winner            →  Accept
```

---

## Key Configuration (`PipelineModels.kt → PipelineConfig`)

| Constant | Value | Tunable? |
|---|---|---|
| `MODEL_INPUT_SIZE` | 112 px | No — fixed by MobileFaceNet |
| `EMBEDDING_SIZE` | 128 | No — fixed by MobileFaceNet |
| `ANALYSIS_FPS` | 10 | Yes |
| `FRAME_BUFFER_SIZE` | 8 | Yes — reduce to 3 for faster testing |
| `THRESHOLD_ACCEPT` | 0.60 | Yes — tune after real-world testing |
| `THRESHOLD_REJECT` | 0.40 | Yes |
| `AMBIGUITY_MARGIN` | 0.12 | Yes |
| `MIN_FACE_SIZE_RATIO` | 0.05 | Yes |
| `MAX_YAW_DEGREES` | ±30° | Yes |
| `MAX_PITCH_DEGREES` | ±20° | Yes |
| `MAX_ROLL_DEGREES` | ±15° | Yes |
| `MIN_LAPLACIAN_VARIANCE` | 80.0 | Yes |
| `MIN_BRIGHTNESS` | 60 | Yes |
| `MAX_BRIGHTNESS` | 220 | Yes |

---

## Database Schema (Room, version 1)

**`students`**

| Column | Type | Notes |
|---|---|---|
| studentId | TEXT PK | Roll number or unique ID |
| name | TEXT | Display name |
| studentClass | TEXT | e.g. "9-B" |
| embedding | TEXT | 128 floats, comma-separated |

**`attendance_records`**

| Column | Type | Notes |
|---|---|---|
| id | INTEGER PK autoincrement | |
| studentId | TEXT | |
| timeStamp | INTEGER | Unix epoch ms |
| synced | INTEGER | 0 = pending sync |

**`conflict_queue`**

| Column | Type | Notes |
|---|---|---|
| id | INTEGER PK autoincrement | |
| topStudentId / Name / Score | | Best match |
| secondStudentId / Name / Score | | Runner-up |
| reason | TEXT | Human-readable |
| sessionId | TEXT | Which session |
| timestamp | INTEGER | |
| resolved | INTEGER | 0 = pending admin review |

**`sync_log`** — backend sync history

---

## Setup from Scratch

**Step 1 — Get real MobileFaceNet weights (Apache 2.0)**

```bash
# Download from foamliu/MobileFaceNet (Apache 2.0 licensed)
# Then convert:
pip install torch onnx onnxruntime onnxscript numpy
python3 scripts/convert_mobilefacenet_to_onnx.py

# Copy output to assets:
cp mobilefacenet.onnx app/src/main/assets/models/mobilefacenet.onnx
```

**Step 2 — Open in Android Studio**

Open the `FaceGate/` folder (the one containing `build.gradle.kts`). Android Studio will detect it as a Gradle project. Run **File → Sync Project with Gradle Files** and wait for sync to complete with no errors.

**Step 3 — Run on device**

Connect an Android device (API 26+) with USB Debugging enabled. The emulator works for UI navigation but the ML pipeline needs a real camera for meaningful testing.

```bash
./gradlew assembleDebug
# or just click Run in Android Studio
```

**Step 4 — First run checklist**

- Open app → tap **Admin** → tap **Enrol Student**
- Capture 5 photos of one person (each shot validated immediately)
- Fill in name, ID, class in the dialog → confirm enrolled
- Go back → tap **Student Attendance**
- Point camera at the enrolled person → should Accept within 1-2 seconds
- Point camera at an unenrolled person → should Reject

**Step 5 — Benchmark on target device**

Run `PipelineBenchmark.kt` on the actual tablet you plan to deploy on. Total p95 latency should be under 1000ms. If over, either:
- Reduce `FRAME_BUFFER_SIZE` to 3
- Enable NNAPI acceleration in `FaceEmbedder.kt` (uncomment `addNnapi()`)
- Use quantised INT8 model instead of FP32

---

## Git Workflow

```
main     — stable, tested
dev      — integration branch (merge here from feature branches)

feature/quality-alignment        Person 1 — quality/ + alignment/
feature/recognition-benchmark    Person 2 — recognition/ + benchmark/
feature/similarity-decision      Person 3 — similarity/ + decision/
feature/storage-sync             Person 4 — storage/ + sync/
feature/pipeline-core            Person 5 — pipeline/ + di/
feature/frontend-backend         Person 6 — ui/ + backend
```

**Critical rule:** `PipelineModels.kt` is the shared contract. Everyone imports from it. Never edit it without a team sync — changes here break every other module simultaneously.

---

## Pending (backend required)

`AttendanceSyncWorker` and `SyncRepository` are fully built. When a backend endpoint is ready:

1. Add the endpoint URL and Bearer token to `SyncRepository`
2. Call `AttendanceSyncWorker.schedule(context)` from `AttendancePipeline.endSession()`
3. Records currently sitting in Room with `synced = false` will be uploaded automatically

Export to Excel/PDF in `AttendanceReportFragment` is stubbed with TODO comments — pending the same backend or a local file-generation approach.

---

## Permissions

| Permission | Why |
|---|---|
| `CAMERA` | Face detection and enrollment capture |
| `INTERNET` | Backend sync (when backend is ready) |
| `ACCESS_NETWORK_STATE` | Check connectivity before sync attempt |
| `WAKE_LOCK` | Keep device awake during WorkManager sync job |

---

## Security notes

- Biometric embeddings **never leave the device** — only attendance metadata (studentId, timestamp) is synced to backend
- `endSession()` clears all embeddings from RAM immediately when the session ends
- SQLCipher AES-256 dependency is included and ready — to activate, replace `Room.databaseBuilder` in `AppModule` with a SQLCipher-backed builder and derive the passphrase from Android Keystore
- No embedding is included in the sync payload — `AttendanceSyncWorker` only posts attendance records

---

## Team - Interns of Reagvis Labs
Yash Krishan Gupta  
Mahima  
Mahi Garg  
Krish Bansal  
Pragati Dinkar Kharat  
Anmol Yadav
