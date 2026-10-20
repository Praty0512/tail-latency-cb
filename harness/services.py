"""Starting, stopping and restarting the three services.

Three modes:

* ``local``  - spawn ``java -jar`` processes from the built fat jars (fast; used for sweeps on a laptop).
* ``docker`` - drive docker compose (full container isolation, CPU/memory limits from docker-compose.yml).
* ``attach`` - services are already running; the harness only talks to them (restart events are unavailable).
"""
from __future__ import annotations

import asyncio
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional

import aiohttp

PORTS = {"inventory-service": 8081, "payment-service": 8082, "order-service": 8080}
START_ORDER = ["inventory-service", "payment-service", "order-service"]


def base_url(service: str, host: str = "localhost") -> str:
    return f"http://{host}:{PORTS[service]}"


async def wait_healthy(service: str, timeout_s: float = 90.0, host: str = "localhost",
                       session: Optional[aiohttp.ClientSession] = None) -> float:
    """Polls ``/health`` until it answers 200; returns the seconds waited. Raises TimeoutError."""
    own = session is None
    session = session or aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=2))
    t0 = time.monotonic()
    try:
        while time.monotonic() - t0 < timeout_s:
            try:
                async with session.get(base_url(service, host) + "/health") as r:
                    if r.status == 200:
                        return time.monotonic() - t0
            except (aiohttp.ClientError, asyncio.TimeoutError, OSError):
                pass
            await asyncio.sleep(0.25)
        raise TimeoutError(f"{service} not healthy after {timeout_s}s")
    finally:
        if own:
            await session.close()


async def is_healthy(service: str, host: str = "localhost") -> bool:
    try:
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=1.5)) as s:
            async with s.get(base_url(service, host) + "/health") as r:
                return r.status == 200
    except (aiohttp.ClientError, asyncio.TimeoutError, OSError):
        return False


class ServiceController:
    """Common interface."""
    can_restart = True

    async def start_all(self) -> None:
        raise NotImplementedError

    async def stop_all(self) -> None:
        raise NotImplementedError

    async def restart(self, service: str) -> None:
        """Abruptly restarts one service. Returns once the restart has been *issued*; callers wait for health."""
        raise NotImplementedError

    async def ensure_healthy(self) -> None:
        for s in START_ORDER:
            await wait_healthy(s)


class LocalController(ServiceController):
    def __init__(self, repo_root: Path, logs_dir: Path, java_opts: Optional[List[str]] = None,
                 java: Optional[str] = None, cpu_source: str = "auto"):
        self.root = repo_root
        self.logs = logs_dir
        self.logs.mkdir(parents=True, exist_ok=True)
        # Heap sized like the docker-compose memory limit (768 MiB container => ~512 MiB heap).
        self.java_opts = java_opts if java_opts is not None else ["-Xms256m", "-Xmx512m", "-XX:+UseG1GC"]
        self.java = java or shutil.which("java") or "java"
        self.cpu_source = cpu_source
        self.procs: Dict[str, subprocess.Popen] = {}

    def _jar(self, service: str) -> Path:
        jar = self.root / service / "target" / f"{service}.jar"
        if not jar.exists():
            raise FileNotFoundError(f"{jar} not found - build first: mvn clean install -DskipTests")
        return jar

    def _spawn(self, service: str) -> None:
        log = open(self.logs / f"{service}.log", "ab")
        flags = subprocess.CREATE_NEW_PROCESS_GROUP if sys.platform == "win32" else 0
        self.procs[service] = subprocess.Popen(
            [self.java, *self.java_opts, "-jar", str(self._jar(service))],
            cwd=str(self.root), stdout=log, stderr=subprocess.STDOUT, env=self._env(), creationflags=flags)

    def _env(self) -> Dict[str, str]:
        env = os.environ.copy()
        env["METRICS_CPU_SOURCE"] = self.cpu_source  # Spring relaxed binding -> metrics.cpu.source
        return env

    async def start_all(self) -> None:
        for s in START_ORDER:
            if await is_healthy(s):
                raise RuntimeError(f"port {PORTS[s]} already serves a healthy {s}; stop it first or use --mode attach")
        for s in START_ORDER:
            self._spawn(s)
        for s in START_ORDER:
            await wait_healthy(s, timeout_s=120)

    def _kill(self, service: str) -> None:
        p = self.procs.get(service)
        if p and p.poll() is None:
            p.kill()
            try:
                p.wait(timeout=10)
            except subprocess.TimeoutExpired:
                pass

    async def restart(self, service: str) -> None:
        self._kill(service)
        self._spawn(service)

    async def stop_all(self) -> None:
        for s in reversed(START_ORDER):
            self._kill(s)
        self.procs.clear()


class DockerController(ServiceController):
    def __init__(self, repo_root: Path, build: bool = True):
        self.root = repo_root
        self.build = build

    def _compose(self, *args: str) -> None:
        subprocess.run(["docker", "compose", "-f", str(self.root / "docker-compose.yml"), *args],
                       cwd=str(self.root), check=True)

    async def start_all(self) -> None:
        loop = asyncio.get_running_loop()
        args = ["up", "-d"] + (["--build"] if self.build else [])
        await loop.run_in_executor(None, lambda: self._compose(*args))
        for s in START_ORDER:
            await wait_healthy(s, timeout_s=180)

    async def restart(self, service: str) -> None:
        loop = asyncio.get_running_loop()
        # `restart` stops the container (SIGTERM, 10 s grace) before starting it; use kill+start for an abrupt crash.
        await loop.run_in_executor(None, lambda: self._compose("kill", service))
        await loop.run_in_executor(None, lambda: self._compose("start", service))

    async def stop_all(self) -> None:
        loop = asyncio.get_running_loop()
        await loop.run_in_executor(None, lambda: self._compose("down"))


class AttachController(ServiceController):
    can_restart = False

    async def start_all(self) -> None:
        await self.ensure_healthy()

    async def stop_all(self) -> None:
        pass

    async def restart(self, service: str) -> None:
        raise RuntimeError("restart events need --mode local or --mode docker")


def make_controller(mode: str, repo_root: Path, logs_dir: Path, docker_build: bool = True,
                    cpu_source: str = "auto") -> ServiceController:
    if mode == "local":
        return LocalController(repo_root, logs_dir, cpu_source=cpu_source)
    if mode == "docker":
        return DockerController(repo_root, build=docker_build)
    if mode == "attach":
        return AttachController()
    raise ValueError(f"unknown mode {mode}")
