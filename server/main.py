from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse
from pathlib import Path

app = FastAPI(title="RemoteScreen Free MVP")
viewers = set()
phones = set()
HTML = Path(__file__).with_name("index.html").read_text(encoding="utf-8")

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
    try:
        while True:
            data = await ws.receive_bytes()
            for viewer in list(viewers):
                try:
                    await viewer.send_bytes(data)
                except Exception:
                    viewers.discard(viewer)
    except WebSocketDisconnect:
        pass
    finally:
        phones.discard(ws)

@app.websocket("/ws/view")
async def view(ws: WebSocket):
    await ws.accept()
    viewers.add(ws)
    try:
        while True:
            await ws.receive_text()
    except WebSocketDisconnect:
        pass
    finally:
        viewers.discard(ws)
