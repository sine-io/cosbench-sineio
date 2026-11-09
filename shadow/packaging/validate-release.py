#!/usr/bin/env python3
"""REL-01: exercise an immutable production archive and image via Docker/HTTP.

Run from the host. Applications receive only release files, configuration,
secrets and isolated data copies. Reports, data and rollback archives survive.
"""

import argparse
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import socket
import ssl
import subprocess
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[2]
LEGACY_SHA = "1421f48a55673e42edd73c7b09a610b2b71ef94c70d46db3fb74dd3c34d22a06"
EXPORT = "--add-exports=java.xml/com.sun.org.apache.xml.internal.serialize=ALL-UNNAMED"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def snapshot(directory):
    return {str(p.relative_to(directory)): sha256(p)
            for p in sorted(directory.rglob("*")) if p.is_file()}


class ReleaseValidation:
    def __init__(self, args):
        self.args = args
        self.report = args.report.resolve()
        self.report.mkdir(parents=True, exist_ok=False, mode=0o700)
        self.run_id = "cosbench-rel22-" + uuid.uuid4().hex[:12]
        self.network = self.run_id
        self.image = self.run_id + ":production"
        self.images = []
        self.containers = []
        self.network_created = False
        self.secrets = Path(tempfile.mkdtemp(prefix=self.run_id + "-"))
        self.token = uuid.uuid4().hex
        self.password = uuid.uuid4().hex
        self.versions = dict(line.split("=", 1) for line in
                             (ROOT / "shadow/harness/versions.env").read_text().splitlines())
        self.urls = {}
        self.redactions = [self.token, self.password]
        self.data_root = self.report / "data"

    def save(self, name, value):
        path = self.report / name
        path.parent.mkdir(parents=True, exist_ok=True)
        if not isinstance(value, str):
            value = json.dumps(value, indent=2) + "\n"
        for secret in self.redactions:
            value = value.replace(secret, "[REDACTED]")
        path.write_text(value)

    def docker(self, *args, check=True):
        args = list(args)
        probe = None
        if args[0] == "run":
            if "--name" not in args:
                probe = self.run_id + "-probe-" + uuid.uuid4().hex[:6]
                self.containers.append(probe)
                args[1:1] = ["--name", probe]
            if "--label" not in args:
                args[1:1] = ["--label", f"cosbench.release={self.run_id}"]
        command = ["sudo", "docker", *map(str, args)]
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=900)
        with (self.report / "commands.log").open("a") as log:
            log.write(f"$ {shlex.join(command)}\nexit={result.returncode}\n")
        if check and result.returncode:
            self.save("failure-command.log", result.stdout)
            raise RuntimeError(f"Docker command failed ({result.returncode}); see failure-command.log")
        if probe and "--rm" in args and result.returncode == 0:
            self.containers.remove(probe)
        return result.stdout

    def request(self, url, body=None, headers=None, expected=200, opener=None):
        if isinstance(body, str):
            body = body.encode()
        request = urllib.request.Request(url, data=body, headers=headers or {})
        if opener is None:
            context = ssl.create_default_context(cafile=str(self.secrets / "certificate.crt"))
            opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=context))
        try:
            response = opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        data = response.read().decode()
        require(response.code == expected, f"HTTP {response.code}, expected {expected}: {url}: {data[:500]}")
        return data, response.headers

    def await_url(self, url, health=False):
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            try:
                body, _ = self.request(url)
                if not health or json.loads(body)["accepting"]:
                    return body
            except (OSError, RuntimeError, ValueError):
                time.sleep(0.5)
        raise RuntimeError(f"Readiness deadline: {url}")

    def setup(self):
        install = self.report / "installation"
        install.mkdir()
        with tarfile.open(self.args.archive) as archive:
            archive.extractall(install, filter="data")
        children = list(install.iterdir())
        require(len(children) == 1 and children[0].is_dir(), "Archive must contain one installation")
        self.package = children[0]
        self.save("archive.sha256", sha256(self.args.archive) + "\n")
        self.save("runner.sha256", sha256(Path(__file__)) + "  validate-release.py\n")
        # Compare complete executable JARs: their hashes cover every nested dependency.
        inputs = json.loads(self.args.inputs.read_text())
        require(inputs["integration"]["status"] == "passed" and
                inputs["performance"]["status"] == "profile-recorded" and
                inputs["performance"]["measured_runs"] >= 3, "Missing prerequisite acceptance")
        self.save("release-inputs.json", inputs)
        provenance = {}
        for role in ("controller", "driver", "operator-cli"):
            path = self.package / f"lib/cosbench-{role}.jar"
            digest = sha256(path)
            previous = inputs["applications"][role]
            require(digest == previous, f"{role} differs from #19; rerun affected integration before release")
            if role == "driver":
                require(inputs["performance"]["driver_sha256"] == digest,
                        "Driver differs from #20; repeat the three-run profile before release")
            provenance[role] = {"sha256": digest, "bytes": path.stat().st_size,
                                "integration": "identical", "performance": "identical" if role == "driver" else "not-profiled"}
        self.save("provenance.json", provenance)
        result = subprocess.run(["sha256sum", "-c", "SHA256SUMS"], cwd=self.package,
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.save("package-checksums.txt", result.stdout)
        require(result.returncode == 0, "Package checksum verification failed")
        for filename, value in (("token", self.token), ("password", self.password)):
            (self.secrets / filename).write_text(value)
        (self.secrets / "tls.env").write_text(
            f"SERVER_SSL_KEY_STORE_PASSWORD={self.password}\nSERVER_SSL_KEY_PASSWORD={self.password}\n")
        (self.secrets / "service.env").write_text(f"COSBENCH_SERVICE_TOKEN={self.token}\n")
        # Generated, test-only secrets are never put in the retained report.
        script = """set -e
keytool -genkeypair -alias release -keyalg RSA -validity 2 -dname CN=localhost \
  -ext SAN=dns:localhost,dns:driver,dns:driver2,ip:127.0.0.1 \
  -storetype PKCS12 -keystore /secrets/keystore.p12 \
  -storepass:file /secrets/password -keypass:file /secrets/password
keytool -exportcert -rfc -alias release -keystore /secrets/keystore.p12 \
  -storepass:file /secrets/password -file /secrets/certificate.crt
cp /secrets/keystore.p12 /secrets/truststore.p12
chmod 644 /secrets/*
"""
        self.save("certificate-generation.log", self.docker(
            "run", "--rm", "--user", f"{os.getuid()}:{os.getgid()}",
            "-v", f"{self.secrets}:/secrets", "--entrypoint", "bash",
            self.versions["MAVEN_IMAGE"], "-c", script))
        self.network_created = True
        self.docker("network", "create", "--label", f"cosbench.release={self.run_id}", self.network)
        self.images.append(self.image)
        version = (self.package / "VERSION").read_text().strip()
        self.save("image-build.log", self.docker("build", "-t", self.image,
                  "--build-arg", f"COSBENCH_VERSION={version}", "-f",
                  ROOT / "shadow/packaging/Dockerfile.production", self.package))
        self.save("production-image.json", json.loads(self.docker("image", "inspect", self.image)))

    def config(self, role, drivers=0):
        data = self.data_root / role
        first_install = not data.exists()
        (data / "conf").mkdir(parents=True, exist_ok=True)
        for directory in ("workspace", "archive", "mission", "log", "scripts", "run"):
            (data / directory).mkdir(exist_ok=True)
        if first_install:
            (data / "workspace/preserved.txt").write_text(f"preserved-{role}\n")
        app = "controller" if role == "controller" else "driver"
        text = (self.package / f"conf/{app}.yml").read_text()
        text = text.replace("env: COSBENCH_SERVICE_TOKEN", "file: /run/secrets/token")
        text = text.replace("cosbench-keystore", "keystore").replace("cosbench-truststore", "truststore")
        for key, value in (("workspace", "workspace"), ("archive", "archive"), ("mission", "mission")):
            text = text.replace(f"{key}: {value}\n", f"{key}: /var/lib/cosbench/{value}\n")
        text = text.replace("log/system.log", "/var/lib/cosbench/log/system.log")
        if drivers:
            entries = "drivers:\n" + "".join(
                f"  - name: {name}\n    url: https://{name}:18088/driver\n"
                for name in ("driver", "driver2")[:drivers])
            text = text.replace("drivers: []", entries.rstrip())
        (data / f"conf/{app}.yml").write_text(text)
        return data

    def start(self, role, mode, scenario, drivers=0, configure=True):
        app = "controller" if role == "controller" else "driver"
        data = self.config(role, drivers) if configure else self.data_root / role
        name = self.run_id + "-" + role
        self.containers.append(name)
        port = 19088 if app == "controller" else 18088
        args = ["run", "-d", "--name", name, "--label", f"cosbench.release={self.run_id}",
                "--network", self.network, "--network-alias", role,
                "--cpus=2", "--memory=1g", "--pids-limit=512",
                "--user", f"{os.getuid()}:{os.getgid()}", "-p", f"127.0.0.1::{port}",
                "-v", f"{data}:/var/lib/cosbench", "-v", f"{self.secrets}:/run/secrets:ro",
                "-v", f"{data / 'conf'}:/var/lib/cosbench/conf:ro",
                "-e", f"COSBENCH_ROLE={app}", "-e", "COSBENCH_TLS_STORE_PASSWORD_FILE=/run/secrets/password",
                "--env-file", self.secrets / "service.env",
                "-e", f"JAVA_OPTS={EXPORT}", "-w", "/var/lib/cosbench"]
        if app == "driver":
            args += ["--env-file", self.secrets / "tls.env"]
        if mode == "archive":
            args += ["-v", f"{self.package}:/opt/cosbench:ro", "--entrypoint", "bash",
                     self.versions["MAVEN_IMAGE"], f"/opt/cosbench/bin/cosbench-{app}"]
        else:
            args += [self.image, app]
        args += ["--config", f"/var/lib/cosbench/conf/{app}.yml", "--pid-file", f"/var/lib/cosbench/run/{app}.pid"]
        self.docker(*args)
        binding = json.loads(self.docker("inspect", name))[0]["NetworkSettings"]["Ports"][f"{port}/tcp"][0]
        url = f"{'http' if app == 'controller' else 'https'}://127.0.0.1:{binding['HostPort']}/{app}"
        self.urls[role] = url
        started = time.monotonic()
        self.save(f"{mode}/{scenario}/{role}-health.json", self.await_url(url + "/health", health=True))
        self.save(f"{mode}/{scenario}/{role}-container.json", json.loads(self.docker("inspect", name)))
        self.save(f"{mode}/{scenario}/{role}-startup.txt", f"ready_ms={int((time.monotonic()-started)*1000)}\n")
        if app == "driver":
            log = self.docker("logs", name)
            require("detected supported storage types: gdas, s3, sio, siov1" in log,
                    "Production Driver must discover exactly the four supported providers")
        return name

    def stop(self, role, mode, scenario):
        name = self.run_id + "-" + role
        app = "controller" if role == "controller" else "driver"
        started = time.monotonic()
        self.save(f"{mode}/{scenario}/{role}-stop.txt", self.docker("exec", name,
                  "/opt/cosbench/bin/cosbench-stop", app, "--pid-dir", "/var/lib/cosbench/run", "--timeout", "40"))
        self.docker("wait", name)
        state = json.loads(self.docker("inspect", name))[0]["State"]
        require(not state["Running"] and state["Pid"] == 0 and state["ExitCode"] in (0, 143),
                f"{role} did not stop cleanly: {state}")
        require(not (self.data_root / role / f"run/{app}.pid").exists(), "Owned PID file survived stop")
        self.save(f"{mode}/{scenario}/{role}-exit.json", {**state, "stop_ms": int((time.monotonic()-started)*1000)})
        log = self.docker("logs", name)
        self.save(f"{mode}/{scenario}/{role}.log", log)
        require(("Controller stopped: executor terminated" if app == "controller" else
                 "Driver stopped: mission executor terminated") in log, "Graceful service drain was not confirmed")
        self.docker("rm", name)
        self.containers.remove(name)
        address = urllib.parse.urlsplit(self.urls[role])
        with socket.socket() as listener:
            listener.settimeout(1)
            require(listener.connect_ex((address.hostname, address.port)) != 0, "Stopped listener remains open")

    def matrix(self):
        for mode in ("archive", "image"):
            self.data_root = self.report / f"{mode}-data"
            print(f"REL-01 {mode}: deployment matrix", flush=True)
            self.start("controller", mode, "controller-only")
            self.stop("controller", mode, "controller-only")
            self.start("driver", mode, "driver-only")
            self.stop("driver", mode, "driver-only")
            for scenario, count in (("both", 1), ("multi-driver", 2)):
                for role in ("driver", "driver2")[:count]:
                    self.start(role, mode, scenario)
                self.start("controller", mode, scenario, count)
                self.workloads(mode, scenario, count)
                if scenario == "both":
                    self.contracts(mode)
                self.stop("controller", mode, scenario)
                for role in ("driver", "driver2")[:count]:
                    self.stop(role, mode, scenario)
                    if role == "driver" and count == 2:
                        self.save(f"{mode}/{scenario}/driver2-after-driver-stop.json",
                                  self.await_url(self.urls["driver2"] + "/health", health=True))
            missions = snapshot(self.data_root / "driver/mission")
            for role in ("driver", "driver2"):
                self.start(role, mode, "restart")
            self.start("controller", mode, "restart", 2)
            self.history(self.urls["controller"], mode + "/restart",
                         ["integration-both-sio", "integration-multi-driver-s3"])
            require(snapshot(self.data_root / "driver/mission") == missions, "Mission logs changed across restart")
            for role in ("controller", "driver", "driver2"):
                require((self.data_root / role / "workspace/preserved.txt").read_text() == f"preserved-{role}\n",
                        "Restart lost workspace data")
                self.stop(role, mode, "restart")

    def workloads(self, mode, scenario, count):
        probe_report = self.report / mode
        script = ROOT / "shadow/harness/deployment/workloads.sh"
        self.save(f"{mode}/{scenario}/workload-probe.log", self.docker(
            "run", "--rm", "--network", self.network,
            "-v", f"{script}:/probe.sh:ro", "-v", f"{probe_report}:/reports",
            "-v", f"{self.secrets}:/run/secrets:ro",
            "-e", "COSBENCH_MINIO_ENDPOINT=http://minio:9000",
            "-e", "COSBENCH_MINIO_ACCESS_KEY_FILE=/run/secrets/minio-user",
            "-e", "COSBENCH_MINIO_SECRET_KEY_FILE=/run/secrets/minio-password",
            "-e", "COSBENCH_HTTP_TEST_CERT_FILE=/run/secrets/certificate.crt",
            "--entrypoint", "bash", self.versions["MAVEN_IMAGE"], "/probe.sh", scenario, count))

    def contracts(self, mode):
        base = self.urls["driver"]
        report = mode + "/http-contract"
        body, _ = self.request(base + "/i/ping.command", "1782057600000", expected=401)
        require(json.loads(body) == {"succ": False, "error": "service authentication failed"},
                "Missing-token response changed")
        headers = {"Authorization": "Bearer " + self.token, "Content-Type": "application/octet-stream"}
        body, response_headers = self.request(base + "/i/ping.command", "1782057600000", headers)
        self.save(report + "/ping.json", body)
        instance = response_headers["X-COSBench-Driver-Instance"]
        require(json.loads(body)["succ"], "Ping failed")

        def command(name, payload, key=None, expected=200):
            identity = key or uuid.uuid4().hex
            body, _ = self.request(base + f"/i/{name}.command", payload,
                       {**headers, "X-COSBench-Driver-Instance": instance,
                        "X-COSBench-Request-Id": identity, "Idempotency-Key": identity}, expected=expected)
            self.save(report + f"/{name}-{identity}.json", body)
            result = json.loads(body)
            if expected == 200:
                require(result["succ"], f"{name} failed: {body}")
            return result

        self.docker("exec", self.run_id + "-minio", "bash", "-c", """set -e
mc alias set release http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
mc mb --ignore-existing release/rel22-http1 >/dev/null
""")
        access = (self.secrets / "minio-user").read_text()
        secret = (self.secrets / "minio-password").read_text()
        xml = f"""<mission name="rel22-http" workers="1" totalWorkers="1" totalOps="10" interval="1">
<auth type="none"/>
<storage type="s3" config="endpoint=http://minio:9000;accesskey={access};secretkey={secret};path_style_access=true;timeout=3000"/>
<operation type="write" ratio="100" config="cprefix=rel22-http;containers=c(1);oprefix=obj;objects=c(1);sizes=c(64)B;content=zero"/>
</mission>"""
        key = uuid.uuid4().hex
        submitted = command("submit", xml, key)
        require(command("submit", xml, key) == submitted, "Identical submit was not replayed")
        mission_id = submitted["id"]
        command("login", mission_id)
        command("launch", mission_id)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            state = command("query", mission_id)
            if not state["running"]:
                break
            time.sleep(0.2)
        closed = command("close", mission_id)
        require(closed["state"] == "ACCOMPLISHED" and closed["errorStatistics"] == {}, "Mission did not accomplish")
        require(closed["report"][0]["sampleCount"] == 10 and closed["report"][0]["byteCount"] == 640,
                "S3 command mission counters differ")
        checksum = self.docker("exec", self.run_id + "-minio", "bash", "-c",
                               "set -o pipefail; mc cat release/rel22-http1/obj1 | sha256sum")
        require(checksum.startswith(hashlib.sha256(bytes(64)).hexdigest()), "Stored object bytes differ")
        for page in ("index.html", "submit.html", f"mission.html?id={mission_id}",
                     f"workers.html?id={mission_id}", f"download-log.do?id={mission_id}", "resources/cosbench.css"):
            page_body, _ = self.request(base + "/" + page)
            require("Internal Error" not in page_body and "404 Not Found" not in page_body,
                    "Driver page failed: " + page)
            self.save(report + "/page-" + page.split("?")[0], page_body)
        trigger = self.data_root / "driver/scripts/release-trigger.sh"
        trigger.write_text("#!/bin/sh\nprintf 'release-trigger-ok\\n'\n")
        trigger.chmod(0o755)
        triggered = command("trigger", "enableTrigger,release-trigger.sh,1")
        require(triggered["scriptLog"] == "release-trigger-ok\n", "Trigger output changed")
        command("trigger", f"killTrigger,{triggered['pid']},release-trigger.sh")
        long_mission = xml.replace('totalOps="10"', 'totalOps="100000000"')
        abort_id = command("submit", long_mission)["id"]
        command("login", abort_id)
        command("launch", abort_id)
        require(command("query", abort_id)["running"], "Abort case was not active")
        command("abort", abort_id)
        require(not command("query", abort_id)["running"], "Abort left mission running")
        self.save(report + "/summary.json", {"status": "passed", "commands": ["ping", "submit", "login", "launch", "query", "close", "abort", "trigger"],
                  "storage": "production-s3", "auth": "TLS/Bearer", "idempotent_submit": True,
                  "object_sha256": hashlib.sha256(bytes(64)).hexdigest(), "samples": 10, "bytes": 640})
        controller = self.urls["controller"]
        submission = (self.report / mode / "deployment-integration/both/sio-submit.txt").read_text()
        current_id = re.search(r"Accepted with ID: (w\d+)", submission).group(1)
        opener = self.page_session(controller)
        for page in ("index.html", "matrix.html?type=histo", "submit.html", "config.html", "advanced-config.html",
                     f"workload.html?id={current_id}", f"download-log.do?id={current_id}", f"download-config.do?id={current_id}",
                     "resources/cosbench.css", "prometheus"):
            page_body, _ = self.request(controller + "/" + page, opener=opener)
            require(not re.search(r"Internal Error|404 Not Found|400 Bad Request", page_body),
                    "Controller page failed: " + page)
            self.save(report + "/controller-" + page.split("?")[0], page_body)
        workload_page, _ = self.request(controller + f"/workload.html?id={current_id}", opener=opener)
        link = re.search(rf"stage\.html\?wid={current_id}(?:&amp;|&)sid=([^\"&]+)", workload_page)
        require(link is not None, "Missing stage link on workload page")
        for page in ("stage.html", "timeline.html", "timeline.csv"):
            page_body, _ = self.request(controller + f"/{page}?wid={current_id}&sid={link.group(1)}", opener=opener)
            require("Internal Error" not in page_body, "Controller export failed: " + page)
            if page == "timeline.csv":
                require(page_body.startswith("Timestamp,Op-Count"), "CSV contract changed")
            self.save(report + "/controller-" + page, page_body)
        workload = (f'<workload name="rel22-cli"><auth type="none"/>'
                    f'<storage type="s3" config="endpoint=http://minio:9000;accesskey={access};secretkey={secret};path_style_access=true"/>'
                    '<workflow><workstage name="read"><work name="read" workers="1" runtime="120" interval="1">'
                    '<operation type="read" ratio="100" config="cprefix=rel22-http;containers=c(1);oprefix=obj;objects=c(1)"/>'
                    '</work></workstage></workflow></workload>')
        for action in ("submit", "config"):
            workload_id = self.submit(controller, workload, action)
            state, _ = self.request(controller + f"/cli/workload.action?username=anonymous&password=cosbench&id={workload_id}")
            require(workload_id in state, "CLI workload query lost ID")
            body, _ = self.request(controller + f"/cli/cancel.action?username=anonymous&password=cosbench&id={workload_id}")
            require(body.startswith(workload_id + "\t"), "CLI cancel contract changed")
            self.save(report + f"/cli-{action}-cancel.txt", body)
        self.save(report + "/web-cli-summary.json", {"status": "passed", "cli": ["index", "submit", "config", "workload", "cancel"],
                  "controller_pages": "pages,stage,timeline,CSV,downloads,Prometheus", "driver_pages": "pages,workers,mission,download"})
        busy_id = command("submit", long_mission)["id"]
        command("login", busy_id)
        command("launch", busy_id)
        require(command("query", busy_id)["running"], "Graceful stop fixture is not active")
        self.save(report + "/busy-stop.json", {"mission": busy_id, "running_before_stop": True})

    def minio(self):
        for filename, value in (("minio-user", "cosbench-release-user"),
                                ("minio-password", uuid.uuid4().hex)):
            (self.secrets / filename).write_text(value)
            (self.secrets / filename).chmod(0o644)
            self.redactions.append(value)
        (self.secrets / "minio.env").write_text(
            "MINIO_ROOT_USER=" + (self.secrets / "minio-user").read_text() + "\n" +
            "MINIO_ROOT_PASSWORD=" + (self.secrets / "minio-password").read_text() + "\n")
        data = self.report / "minio-data"
        data.mkdir(mode=0o777)
        data.chmod(0o777)
        name = self.run_id + "-minio"
        self.containers.append(name)
        self.docker("run", "-d", "--name", name, "--network", self.network,
                    "--network-alias", "minio", "--env-file", self.secrets / "minio.env",
                    "--tmpfs", "/certs", "-v", f"{data}:/bitnami/minio/data", self.versions["MINIO_IMAGE"])
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            result = self.docker("exec", name, "curl", "-fsS", "http://127.0.0.1:9000/minio/health/ready", check=False)
            if not result.strip():
                return
            time.sleep(1)
        raise RuntimeError("MinIO readiness deadline")

    def submit(self, base, xml, action="submit"):
        boundary = uuid.uuid4().hex
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"config\"; filename=\"workload.xml\"\r\n"
                f"Content-Type: application/xml\r\n\r\n{xml}\r\n--{boundary}--\r\n")
        response, _ = self.request(base + f"/cli/{action}.action?username=anonymous&password=cosbench", body,
                                   {"Content-Type": f"multipart/form-data; boundary={boundary}"})
        match = re.search(r"Accepted with ID: (w\d+)", response)
        require(match is not None, f"Workload not accepted: {response}")
        return match.group(1)

    def finished(self, base, workload_id):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            body, _ = self.request(base + f"/cli/workload.action?username=anonymous&password=cosbench&id={workload_id}")
            require(not re.search(r"FAILED|TERMINATED|CANCELLED", body), f"Workload failed: {body}")
            if "FINISHED" in body:
                return body
            time.sleep(0.2)
        raise RuntimeError(f"Workload deadline: {workload_id}")

    def page_session(self, base):
        cookies = http.cookiejar.CookieJar()
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
        self.request(base + "/index.html", opener=opener)
        self.request(base + "/j_security_check", "j_username=anonymous&j_password=cosbench",
                     {"Content-Type": "application/x-www-form-urlencoded"}, opener=opener)
        return opener

    def history(self, base, report, expected):
        opener = self.page_session(base)
        body, _ = self.request(base + "/index.html?loadArch=true", opener=opener)
        self.save(report + "/history.html", body)
        for name in expected:
            require(name in body, f"Missing historical workload: {name}")
        for suffix in ("workload.html?id=w1", "download-config.do?id=w1", "download-log.do?id=w1"):
            body, _ = self.request(base + "/" + suffix, opener=opener)
            require("Internal Error" not in body and "404 Not Found" not in body,
                    f"Historical result unreadable: {suffix}")
            self.save(report + "/" + suffix.split("?")[0], body)

    def legacy_start(self, name, restore=False):
        args = ["run", "-di", "--name", name, "--network", self.network,
                "--cpus=2", "--memory=1536m", "--pids-limit=512",
                "-p", "127.0.0.1::19088", "-p", "127.0.0.1::18088"]
        if restore:
            original = self.report / "legacy-original"
            for item in ("conf", "archive", "log", "workspace", "start", "stop"):
                args += ["-v", f"{original / item}:/cosbench/{item}"]
            # cosbench-start consumes the retained Driver INI directly. The legacy
            # start-driver wrapper regenerates it, so it is deliberately bypassed.
            command = """set -e
VERSION=$(cat VERSION)
bundles="cosbench-log_${VERSION} cosbench-tomcat_${VERSION} cosbench-config_${VERSION} cosbench-http_${VERSION} cosbench-core_${VERSION} cosbench-core-web_${VERSION} cosbench-api_${VERSION} cosbench-mock_${VERSION} cosbench-driver_${VERSION} cosbench-driver-web_${VERSION}"
bash cosbench-start.sh driver "$bundles" 18089
bash start-controller.sh
exec bash
"""
        else:
            command = "bash /cosbench/start-cosbench.sh"
        self.containers.append(name)
        self.docker(*args, "--entrypoint", "bash", self.legacy_image, "-c", command)
        ports = json.loads(self.docker("inspect", name))[0]["NetworkSettings"]["Ports"]
        base = f"http://127.0.0.1:{ports['19088/tcp'][0]['HostPort']}/controller"
        self.await_url(base + "/index.html")
        return base

    def legacy_stop(self, name, report):
        # The old shell wrapper checks only three seconds after sending exit.
        # Keep its actual status in commands.log and independently bound the drain.
        self.save(report + "/stop.txt", self.docker("exec", name, "bash", "stop-all.sh", check=False))
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            remaining = self.docker("exec", name, "bash", "-c", "ps -eo args | grep '[j]ava.*[o]sgi' || true")
            if not remaining.strip():
                break
            time.sleep(1)
        require(not remaining.strip(), "Legacy Java process survived the bounded stop")
        self.save(report + "/container.log", self.docker("logs", name))

    def old_data_and_rollback(self):
        print("REL-01 legacy: generate fixture with pinned previous release", flush=True)
        rollback = self.report / "rollback"
        rollback.mkdir()
        shutil.copyfile(self.args.legacy_archive, rollback / "cosbench-sineio.tar.gz")
        self.save("rollback/archive.sha256", LEGACY_SHA + "  cosbench-sineio.tar.gz\n")
        for filename in ("Dockerfile", "start-cosbench.sh"):
            shutil.copyfile(ROOT / ".scratch/scheme3-migration/baseline" / filename, rollback / filename)
        self.legacy_image = self.run_id + ":legacy"
        self.images.append(self.legacy_image)
        self.save("rollback/image-build.log", self.docker("build", "--build-arg", "VERSION=0.6.0.2",
                  "-t", self.legacy_image, rollback))
        self.save("rollback/image.json", json.loads(self.docker("image", "inspect", self.legacy_image)))
        name = self.run_id + "-legacy"
        base = self.legacy_start(name)
        xml = (ROOT / ".scratch/scheme3-migration/baseline/fixtures/mock-noop.xml").read_text()
        xml = xml.replace("baseline-noop", "rel22-legacy-fixture")
        self.save("legacy-fixture.xml", xml)
        workload_id = self.submit(base, xml)
        require(workload_id == "w1", "Legacy fixture must start in an empty installation")
        self.save("legacy-finished.txt", self.finished(base, workload_id))
        self.history(base, "legacy-before", ["rel22-legacy-fixture"])
        self.docker("exec", name, "bash", "-c", "mkdir -p workspace; printf 'preserved legacy workspace\\n' > workspace/rel22.txt")
        self.legacy_stop(name, "legacy-before")
        original = self.report / "legacy-original"
        original.mkdir()
        for item in ("conf", "archive", "log", "workspace", "start", "stop"):
            self.docker("cp", f"{name}:/cosbench/{item}", original / item)
        self.docker("rm", "-f", name)
        self.containers.remove(name)
        original_hashes = snapshot(original)
        self.save("legacy-original.sha256.json", original_hashes)
        # Work only with copies during migration. Original INI and original layout
        # remain available to the previous complete distribution.
        self.data_root = self.report / "migration-data"
        for role in ("controller", "driver"):
            data = self.config(role)
            for item in ("archive", "log", "workspace", "start", "stop"):
                source = original / item
                if source.is_dir():
                    shutil.copytree(source, data / item, dirs_exist_ok=True)
                else:
                    shutil.copyfile(source, data / item)
            shutil.copyfile(original / "conf" / ("controller.conf" if role == "controller" else "driver_1.conf"),
                            data / f"conf/{role}.conf")
            (data / f"conf/{role}.yml").unlink()
            self.save(f"migration/{role}-command.txt", self.docker(
                "run", "--rm", "--user", f"{os.getuid()}:{os.getgid()}",
                "-v", f"{data}:/var/lib/cosbench", "-e", f"COSBENCH_ROLE={role}",
                "--entrypoint", "/opt/cosbench/bin/cosbench-config-migrate", self.image,
                "--source", f"/var/lib/cosbench/conf/{role}.conf",
                "--destination", f"/var/lib/cosbench/conf/{role}.yml",
                "--report", "/var/lib/cosbench/conf/migration.json",
                "--driver-key-store", "/run/secrets/keystore.p12",
                "--controller-trust-store", "/run/secrets/truststore.p12"))
            yaml = data / f"conf/{role}.yml"
            self.save(f"migration/{role}-converted.yml", yaml.read_text())
            yaml.write_text(yaml.read_text().replace("host: 127.0.0.1", "host: 0.0.0.0")
                            .replace("https://127.0.0.1:18088/driver", "https://driver:18088/driver"))
            self.save(f"migration/{role}-docker.yml", yaml.read_text())
        self.save("migration/docker-adjustments.json", {"listen.host": "0.0.0.0 for bridge network",
                  "driver_url": "https://driver:18088/driver", "token": "protected service.env; no YAML secret",
                  "original_ini": "unchanged", "original_conversion": "*-converted.yml"})
        migrated_archive = snapshot(self.data_root / "controller/archive")
        migrated_missions = snapshot(self.data_root / "driver/log/mission")
        require(migrated_missions, "Previous release did not produce mission logs")
        for attempt in ("before-restart", "after-restart"):
            self.start("driver", "image", attempt, configure=False)
            self.start("controller", "image", attempt, configure=False)
            self.history(self.urls["controller"], "migration/" + attempt, ["rel22-legacy-fixture"])
            require(snapshot(self.data_root / "controller/archive") == migrated_archive,
                    "Reading old archives modified their names or bytes")
            require(snapshot(self.data_root / "driver/log/mission") == migrated_missions,
                    "Restart modified old mission logs")
            for filename, digest in migrated_missions.items():
                output = self.docker("exec", self.run_id + "-driver", "sha256sum",
                                     "/var/lib/cosbench/log/mission/" + filename)
                require(output.startswith(digest), "Old mission log is unreadable in the production container")
            self.stop("controller", "image", attempt)
            self.stop("driver", "image", attempt)
        require(snapshot(original) == original_hashes, "Migration changed original legacy data")
        print("REL-01 rollback: stop new processes, start complete previous distribution", flush=True)
        name = self.run_id + "-rollback"
        base = self.legacy_start(name, restore=True)
        self.history(base, "rollback/after", ["rel22-legacy-fixture"])
        self.legacy_stop(name, "rollback/after")
        self.docker("rm", "-f", name)
        self.containers.remove(name)
        after = snapshot(original)
        cache_changes = []
        for filename, digest in original_hashes.items():
            if filename.startswith("conf/") and "/org.eclipse.osgi/" in filename:
                if after.get(filename) != digest:
                    cache_changes.append(filename)
                continue
            if filename.startswith(("archive/", "workspace/", "conf/", "log/mission/")):
                require(after.get(filename) == digest, f"Rollback altered user data: {filename}")
        self.save("rollback/runtime-cache-changes.json", {"regenerated_legacy_osgi_cache": cache_changes,
                  "user_config_archive_workspace_mission": "unchanged"})
        self.save("rollback/summary.json", {"status": "passed", "version": "0.6.0.2-sineio",
                  "archive_sha256": LEGACY_SHA, "fixture": "generated by the previous release; not customer data",
                  "original_config_and_data": "preserved", "mission_logs": "readable; RAM mission history is not restored"})

    def cleanup(self, status):
        failures = []

        def attempt(*args):
            try:
                return self.docker(*args)
            except (OSError, subprocess.SubprocessError, RuntimeError) as error:
                failures.append(f"{shlex.join(map(str, args))}: {error}")
                return None

        containers_gone = True
        for name in reversed(self.containers):
            found = attempt("ps", "-aq", "--filter", f"name=^/{name}$")
            if found is None:
                containers_gone = False
                continue
            if found.strip():
                log = attempt("logs", name)
                if log is not None:
                    self.save(f"cleanup/{name}.log", log)
                attempt("rm", "-f", name)
                remaining = attempt("ps", "-aq", "--filter", f"name=^/{name}$")
                if remaining is None or remaining.strip():
                    containers_gone = False
                    failures.append(name)
        if self.network_created:
            found = attempt("network", "ls", "-q", "--filter", f"name=^{self.network}$",
                            "--filter", f"label=cosbench.release={self.run_id}")
            if found and found.strip():
                attempt("network", "rm", self.network)
                remaining = attempt("network", "ls", "-q", "--filter", f"name=^{self.network}$")
                if remaining is None or remaining.strip():
                    failures.append(self.network)
        for image in self.images:
            found = attempt("image", "ls", "-q", image)
            if found and found.strip():
                attempt("image", "rm", image)
                remaining = attempt("image", "ls", "-q", image)
                if remaining is None or remaining.strip():
                    failures.append(image)
        if containers_gone:
            try:
                shutil.rmtree(self.secrets)
            except OSError as error:
                failures.append(f"temporary secrets: {error}")
        self.save("cleanup-status.json", {"run_status": status, "resources_remaining": failures,
                  "data_and_reports": "retained", "shared_resources": "untouched",
                  "retained_runtime": None if containers_gone else str(self.secrets)})
        require(not failures, f"Cleanup failed: {failures}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--legacy-archive", type=Path, required=True)
    parser.add_argument("--inputs", type=Path, default=ROOT / "shadow/packaging/release-inputs.json",
                        help="Versioned #19/#20 candidate hashes; changed runtime inputs require new evidence")
    parser.add_argument("--report", type=Path, required=True, help="New report directory; retained at exit")
    args = parser.parse_args()
    require(sha256(args.legacy_archive) == LEGACY_SHA, "Legacy archive checksum differs from #01")
    validation = ReleaseValidation(args)
    def interrupted(signum, frame):
        raise SystemExit(128 + signum)
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    status = 1
    try:
        validation.setup()
        validation.minio()
        validation.matrix()
        validation.old_data_and_rollback()
        status = 0
    except BaseException as error:
        validation.save("summary.json", {"status": "failed", "error": str(error)})
        raise
    finally:
        validation.cleanup(status)
    validation.save("summary.json", {"status": "passed", "deployments": ["controller-only", "driver-only", "both", "multi-driver"],
                                     "artifacts": ["archive", "image"], "providers": ["sio", "siov1", "gdas", "s3"],
                                     "old_data_and_rollback": "passed", "cleanup": "passed"})


if __name__ == "__main__":
    main()
