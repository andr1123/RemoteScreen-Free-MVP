from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse
from pathlib import Path
import logging

app = FastAPI(title="RemoteScreen Free MVP")
viewers = set()
phones = set()
text_clients = set()
latest_text = ""
HTML = Path(__file__).with_name("index.html").read_text(encoding="utf-8")

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("remotescreen")

@app.get("/")
async def index():
    return HTMLResponse(HTML)

@app.get("/health")
async def health():
    return {"ok": True, "phones": len(phones), "viewers": len(viewers), "text_clients": len(text_clients)}

@app.websocket("/ws/text")
async def text_phone(ws: WebSocket):
    global latest_text
    await ws.accept()
    text_clients.add(ws)
    log.info("TEXT PHONE CONNECTED: %s | clients=%d", ws.client, len(text_clients))
    try:
        await ws.send_text(latest_text)
        while True:
            text = await ws.receive_text()
            latest_text = text
            log.info("TEXT RECEIVED: %r", text)
            for viewer in list(text_clients):
                if viewer is ws:
                    continue
                try:
                    await viewer.send_text(text)
                except Exception:
                    text_clients.discard(viewer)
            for viewer in list(viewers):
                try:
                    await viewer.send_text("TEXT:" + text)
                except Exception:
                    viewers.discard(viewer)
    except WebSocketDisconnect:
        pass
    except Exception as exc:
        log.exception("TEXT PHONE ERROR: %s", exc)
    finally:
        text_clients.discard(ws)
        log.info("TEXT PHONE DISCONNECTED: %s | clients=%d", ws.client, len(text_clients))

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
                except Exception:
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
