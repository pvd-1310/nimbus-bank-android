from __future__ import annotations

import asyncio
import hashlib
import io
import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path

os.environ.setdefault("OPENCV_IO_MAX_IMAGE_PIXELS", "12000000")

import cv2
import numpy as np
import onnxruntime
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from insightface.app import FaceAnalysis
from PIL import Image, UnidentifiedImageError

MODEL = "buffalo_l"
WEIGHTS = ("det_10g.onnx", "w600k_r50.onnx")
MAX_BYTES = 5_000_000
MAX_PIXELS = 12_000_000
FORMATS = {"image/jpeg": "JPEG", "image/png": "PNG"}
logger = logging.getLogger("uvicorn.error")


def load_model():
    root = Path(os.getenv("FACE_MODEL_ROOT", "/opt/face-model")).resolve()
    directory = root / "models" / MODEL
    if {path.name for path in directory.glob("*.onnx")} != set(WEIGHTS):
        raise ValueError("Provide exactly the licensed detection and recognition weights")
    identity = hashlib.sha256()
    for name in WEIGHTS:
        with (directory / name).open("rb") as stream:
            identity.update(hashlib.file_digest(stream, "sha256").digest())
    options = onnxruntime.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    analysis = FaceAnalysis(
        name=MODEL, root=str(root), providers=["CPUExecutionProvider"],
        allowed_modules=["detection", "recognition"], sess_options=options,
    )
    if set(analysis.models) != {"detection", "recognition"}:
        raise ValueError("Model modules unavailable")
    analysis.prepare(ctx_id=-1, det_size=(640, 640), det_thresh=0.5)
    return analysis, "v1-" + identity.hexdigest()


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.analysis = None
    app.state.busy = False
    try:
        app.state.analysis, app.state.version = await asyncio.to_thread(load_model)
    except Exception:
        logger.exception("Face model initialization failed")
    yield


app = FastAPI(title="Face API", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


@app.get("/health")
def health(request: Request):
    if request.app.state.analysis is None:
        return JSONResponse({"status": "setup_required"}, status_code=503)
    return {"status": "ready", "model": MODEL, "version": request.app.state.version}


def extract(analysis: FaceAnalysis, data: bytes, mime: str):
    try:
        with Image.open(io.BytesIO(data)) as header:
            width, height = header.size
            if (header.format != FORMATS[mime] or min(width, height) < 160
                    or max(width, height) > 4096 or width * height > MAX_PIXELS):
                raise ValueError()
        image = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
        if image is None:
            raise ValueError()
    except (ValueError, OSError, UnidentifiedImageError, Image.DecompressionBombError, cv2.error):
        raise HTTPException(422, "INVALID_IMAGE") from None

    faces = analysis.get(image, max_num=0)
    if len(faces) != 1:
        raise HTTPException(422, "MULTIPLE_FACES" if faces else "FACE_NOT_FOUND")
    face = faces[0]
    x1, y1, x2, y2 = face.bbox.astype(int)
    height, width = image.shape[:2]
    if face.det_score < 0.65:
        raise HTTPException(422, "FACE_DETECTION_UNCERTAIN")
    if min(x2 - x1, y2 - y1) < 90:
        raise HTTPException(422, "FACE_TOO_SMALL")
    if x1 < 0 or y1 < 0 or x2 > width or y2 > height:
        raise HTTPException(422, "FACE_CROPPED")
    if face.kps is None or not np.isfinite(face.kps).all():
        raise HTTPException(422, "FACE_LANDMARKS_INVALID")
    gray = cv2.cvtColor(image[y1:y2, x1:x2], cv2.COLOR_BGR2GRAY)
    if not 35 <= gray.mean() <= 225:
        raise HTTPException(422, "FACE_EXPOSURE")
    if cv2.Laplacian(gray, cv2.CV_64F).var() < 40:
        raise HTTPException(422, "FACE_BLURRY")
    vector = face.normed_embedding
    if vector is None or vector.shape != (512,) or not np.isfinite(vector).all():
        raise HTTPException(422, "FACE_EMBEDDING_INVALID")
    return vector.astype(np.float32).tolist()


async def read_image(request: Request) -> bytes:
    data = bytearray()
    async for chunk in request.stream():
        if len(data) + len(chunk) > MAX_BYTES:
            raise HTTPException(413, "INVALID_BYTES")
        data.extend(chunk)
    return bytes(data)


@app.post("/embedding")
async def embedding(request: Request):
    state = request.app.state
    if state.analysis is None:
        raise HTTPException(503, "FACE_SETUP_REQUIRED")
    mime = request.headers.get("content-type", "").split(";")[0].lower().strip()
    if mime not in FORMATS:
        raise HTTPException(415, "INVALID_MIME")
    if state.busy:
        raise HTTPException(429, "FACE_BUSY")
    state.busy = True
    pending = None
    try:
        data = await asyncio.wait_for(read_image(request), 3)
        pending = asyncio.create_task(asyncio.to_thread(extract, state.analysis, data, mime))
        return {
            "model": MODEL, "version": state.version,
            "embedding": await asyncio.wait_for(asyncio.shield(pending), 6),
        }
    except TimeoutError:
        raise HTTPException(503, "FACE_TIMEOUT") from None
    finally:
        if pending is None or pending.done():
            state.busy = False
        else:
            def release(task):
                state.busy = False
                if not task.cancelled():
                    task.exception()
            pending.add_done_callback(release)
