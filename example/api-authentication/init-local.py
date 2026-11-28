"""Create missing local credentials and assemble Frank credential aliases."""
import os
from pathlib import Path
import secrets
from tempfile import NamedTemporaryFile

dest = Path("private")
dest.mkdir(mode=0o700, exist_ok=True)
password = secrets.token_hex(32)
partner_key = secrets.token_hex(32)

# The private parent protects these files on the host. Bind-mounted files
# must also be readable by the unprivileged Tomcat user.
files = {
    "credentials.properties": f"hello/username=hello\nhello/password={password}\n",
    "localUsers.yml": f'users:\n  - username: yaml-reader\n    password: "{secrets.token_hex(32)}"\n    roles: [HelloReader]\n',
    "tomcat-users.xml": f'<tomcat-users><role rolename="HelloReader"/><user username="container-reader" password="{secrets.token_hex(32)}" roles="HelloReader"/></tomcat-users>\n',
    "oidc.properties": "oidc/username=replace-with-client-id\noidc/password=replace-with-client-secret\n",
    "partner.key": partner_key + "\n",
}
for name, content in files.items():
    target = dest / name
    if target.exists():
        continue
    target.write_text(content)
    os.chmod(target, 0o600 if name == "partner.key" else 0o644)

# Assemble the mounted credential aliases without replacing existing user secrets.
with NamedTemporaryFile(mode="w", dir=dest, delete=False) as merged:
    for source in ("credentials.properties", "oidc.properties"):
        merged.write((dest / source).read_text().rstrip() + "\n")
    merged.write("partner/username=partner\n")
    merged.write("partner/password=" + (dest / "partner.key").read_text().strip() + "\n")
os.chmod(merged.name, 0o644)
os.replace(merged.name, dest / "runtime.properties")
