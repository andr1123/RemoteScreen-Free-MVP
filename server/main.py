from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse
from pathlib import Path
import logging

app = FastAPI(title="RemoteScreen Free MVP")
viewers = set()
phones = set()
HTML = Path(__file__).with_name("index.html").read_text(encoding="utf-8")

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("remotescreen")

@app.get("/")
async def index():
    return HTMLResponse(HTML)

@app.get("/health")
async def health():
    return {"ok": True, "phones": len(phones), "viewers": len(viewers)}

@app.websocket("/ws/phone")
async def phone(ws: WebSocket):
    await ws.accept()
    phones.add(ws)
    log.info("PHONE CONNECTED: %s | phones=%d", ws.client, len(phones))
    try:
        while True:
            data = await ws.receive_bytes()
            log.debug("FRAME RECEIVED: %d bytes | viewers=%d", len(data), len(viewers))
            for viewer in list(viewers):
                try:
                    await viewer.send_bytes(data)
                except Exception as exc:
                    log.warning("Viewer send failed: %s", exc)
                    viewers.discard(viewer)
    except WebSocketDisconnect:
        log.info("PHONE DISCONNECTED: %s", ws.client)
    except Exception as exc:
        log.exception("PHONE ERROR: %s", exc)
    finally:
        phones.discard(ws)
        log.info("PHONE REMOVED: %s | phones=%d", ws.client, len(phones))

@app.websocket("/ws/view")
async def view(ws: WebSocket):
    await ws.accept()
    viewers.add(ws)
    log.info("VIEWER CONNECTED: %s | viewers=%d", ws.client, len(viewers))
    try:
        while True:
            await ws.receive_text()
    except WebSocketDisconnect:
        log.info("VIEWER DISCONNECTED: %s", ws.client)
    except Exception as exc:
        log.exception("VIEWER ERROR: %s", exc)
    finally:
        viewers.discard(ws)
        log.info("VIEWER REMOVED: %s | viewers=%d", ws.client, len(viewers))
