"""Exercise concurrent local authentication modes on the running Compose instance."""
from base64 import b64encode
from http.client import HTTPConnection
from os import environ
from pathlib import Path


def basic(user, password):
    value = b64encode(f"{user}:{password}".encode()).decode()
    return {"Authorization": f"Basic {value}"}


def check(path, expected, headers=None):
    conn = HTTPConnection("127.0.0.1", int(environ.get("FRANK_HTTP_PORT", "8180")), timeout=10)
    try:
        conn.request("GET", f"/api/{path}", headers=headers or {})
        response = conn.getresponse()
        if response.status != expected:
            raise SystemExit(f"{path} returned {response.status}, expected {expected}")
    finally:
        conn.close()


if __name__ == "__main__":
    private = Path(__file__).resolve().parent / "private"
    creds = dict(line.split("=", 1) for line in
                 (private / "credentials.properties").read_text().splitlines() if line)
    login = basic(creds["hello/username"], creds["hello/password"])
    key = {"X-Customer-Key": (private / "partner.key").read_text().strip()}

    check("public/hello-world", 200)
    check("hello-world", 403)
    check("unknown/hello-world", 403, login)
    check("publicity/hello-world", 403)
    check("", 403)
    check("secret/hello-world", 401)
    check("secret/hello-world", 200, login)
    check("secret/hello-world", 401, key)
    check("yaml/hello-world", 401, login)
    check("psk/hello-world", 401, login)
    check("psk/hello-world", 401, {"X-Customer-Key": "incorrect"})
    check("psk/hello-world", 200, key)
    check("sealed/hello-world", 401, login)
    check("bearer-api/hello-world", 401, login)
    print("Local authentication checks passed")
