import os
import socket
import time

from fastapi import FastAPI


app = FastAPI()
DEFAULT_DELAY = float(os.getenv("DEFAULT_DELAY", "0.2"))


@app.get("/health")
def health():
    return {
        "status": "ok",
        "host": socket.gethostname(),
    }


@app.get("/infer")
def infer(prompt: str = "hello"):
    time.sleep(DEFAULT_DELAY)
    return {
        "server": socket.gethostname(),
        "prompt": prompt,
        "result": f"AI response for: {prompt}",
        "delay": DEFAULT_DELAY,
    }
