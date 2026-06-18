FaceGate — Project File Structure
1. File Structure
FaceGateApp/
├── app/
│ └── src/main/
│ ├── assets/models/
│ │ └── mobilefacenet.onnx
│ ├── java/com.facegate/
│ │ ├── pipeline/
│ │ │ ├── PipelineModels.kt
│ │ │ └── AttendancePipeline.kt
│ │ ├── quality/
│ │ │ └── QualityChecker.kt
│ │ ├── alignment/
│ │ │ └── FaceAligner.kt
│ │ ├── recognition/
│ │ │ └── FaceEmbedder.kt
│ │ ├── similarity/
│ │ │ └── SimilaritySearch.kt
│ │ ├── decision/
│ │ │ └── AttendanceDecisionEngine.kt
│ │ ├── storage/
│ │ │ └── Database.kt
│ │ ├── sync/
│ │ │ └── AttendanceSyncWorker.kt
│ │ ├── benchmark/
│ │ │ └── PipelineBenchmark.kt
│ │ ├── ui/
│ │ │ ├── attendance/
│ │ │ ├── enrollment/
│ │ │ ├── admin/
│ │ │ ├── reports/
│ │ │ └── common/
│ │ └── di/
│ │ └── AppModule.kt
│ ├── res/
│ │ ├── layout/
│ │ ├── drawable/
│ │ ├── values/
│ │ └── navigation/
│ └── AndroidManifest.xml
├── backend/
│ ├── api/attendance_endpoint.py
│ ├── auth/api_keys.py
│ └── db/schema.sql
├── build.gradle.kts
└── README.md
2. Folder by Folder
pipeline/ — core ML orchestration
File What it does
PipelineModels.kt All data classes (DetectedFace, QualityResult, FaceEmbedding,
AttendanceDecision, etc.) + PipelineConfig with every tunable
constant.
AttendancePipeline.kt Main orchestrator. Wires all 8 stages together. Handles frame
buffering, session lifecycle, enrollment.
quality/ — frame quality checks
File What it does
QualityChecker.kt Runs 5 checks per frame: blur (Laplacian), brightness, face size,
head pose (yaw/pitch/roll), landmark confidence. Outputs a
composite quality score.
alignment/ — face normalization
File What it does
FaceAligner.kt OpenCV affine warp using 5 landmarks → 112×112 aligned face
crop, matching MobileFaceNet's expected input format.
recognition/ — embedding model
File What it does
FaceEmbedder.kt Loads mobilefacenet.onnx, runs ONNX Runtime inference.
Converts bitmap → tensor, returns 128-D L2-normalized
embedding.
similarity/ — matching
File What it does
SimilaritySearch.kt Cosine similarity (dot product) search over enrolled templates in
memory. Returns top-1 and top-2 matches. Also checks duplicateenrollment risk.
decision/ — attendance logic
File What it does
AttendanceDecisionEngine.kt Threshold logic: Accept / Reject / Ambiguous / AlreadyMarked.
Handles the 'twin problem' via ambiguity margin check.
storage/ — local database
File What it does
Database.kt Room + SQLCipher (AES-256). Tables: enrolled_templates,
attendance_records, conflict_queue, sync_log. Includes
TemplateRepository, the single interface the pipeline uses.
sync/ — backend sync
File What it does
AttendanceSyncWorker.kt WorkManager job. Offline-first: syncs unsynced attendance
records to backend when online. Embeddings never sync, only
attendance metadata.
benchmark/ — performance testing
File What it does
PipelineBenchmark.kt Measures latency of each stage (quality, alignment, inference,
cosine search) on the actual device. Validates <1s total budget.
assets/models/ — ML model file
File What it does
mobilefacenet.onnx The converted face recognition model. Loaded by
FaceEmbedder.kt at app startup.
root — build configuration
File What it does
build.gradle.kts All dependencies: ML Kit, ONNX Runtime, OpenCV, Room,
SQLCipher, CameraX, WorkManager, Coroutines
README.md Setup guide, architecture overview, tunable parameters,
integration steps.
ui/ — frontend screens
File What it does
attendance/AttendanceFragment.kt Camera screen: preview, oval guide, live status,
accept/reject feedback.
attendance/AttendanceViewModel.kt Connects pipeline.processFrame() to the UI via
StateFlow.
enrollment/EnrollmentFragment.kt Admin screen to capture and enroll a new student.
enrollment/EnrollmentViewModel.kt Calls pipeline.enrollStudent(), shows duplicate
warnings.
admin/AdminDashboard.kt Session management, student list overview.
admin/ConflictQueueFragment.kt Lists ambiguous cases for manual review.
reports/AttendanceReportFragment.kt Per-session and per-student attendance reports.
common/CameraPreviewView.kt Reusable CameraX preview component.
common/FaceOvalOverlay.kt Drawable oval guide that changes color by status.
di/ — dependency injection
File What it does
AppModule.kt Hilt module providing FaceGateDatabase, TemplateRepository,
AttendancePipeline to ViewModels.
backend/ — separate service
File What it does
api/attendance_endpoint.py POST /api/attendance/sync — receives batched records from the
sync worker.
auth/api_keys.py Validates Bearer token from sync requests.
db/schema.sql Server-side mirror of the attendance schema.
FaceGate — Team Assignment & Git Setup
Branch Assignment Table
# Person Folders owned Branch name
1 Quality & Alignment (Pragati) quality/, alignment/ feature/quality-alignment
2 Recognition & Benchmark (Krish) recognition/, benchmark/ feature/recognition-benchmark
3 Similarity & Decision (Anmol) similarity/, decision/ feature/similarity-decision
4 Storage & Sync (Mahi) storage/, sync/ feature/storage-sync
5 Pipeline & Integration (Yash) pipeline/, di/ feature/pipeline-core
6 Frontend & Backend (Mahima) ui/*, backend service feature/frontend-backend
Instructions for each teammate
Replace <your-branch-name> with their assigned name from the table above:
Creating Branch :
git clone https://github.com/yashkrishangupta/FaceGate.git
cd FaceGate
git checkout dev
git pull origin dev
git checkout -b <their-branch-name>
git push -u origin <their-branch-name>
Reusing Bramch :
git clone https://github.com/<your-username>/FaceGate.git
cd FaceGate
git checkout <your-branch-name>
git pull origin <branch-name>
Rules to follow
1. Only work inside your assigned folder(s). If you need to touch a file outside it,
message the group first.
2. Do not edit pipeline/PipelineModels.kt without team approval. It serves as the
shared contract that other modules depend on. If you need a new field, constant, or
model change, discuss it in the group chat first.
3. Before pushing, make sure your code compiles:
./gradlew assembleDebug
4. To push your work:
git add .
git commit -m "[yourarea] short description of change"
git push
5. When your part is ready for integration, open a Pull Request from your branch into
dev and tag the team lead for review.
6. Do not push directly to main or dev. All changes must go through a Pull Request.