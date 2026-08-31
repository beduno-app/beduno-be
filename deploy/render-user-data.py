#!/usr/bin/env python3
"""Expand the __FILES__ placeholder in user-data.sh into heredocs that write the deploy files.

The three deploy files stay reviewable in git instead of being pasted into a cloud-init blob by
hand. Quoted heredoc delimiters are essential: the file contents are full of ${...} and {$...}
sequences meant for docker compose and Caddy, none of which the provisioning shell may expand.
"""
import pathlib
import sys

here = pathlib.Path(__file__).resolve().parent
template = (here / "user-data.sh").read_text()

FILES = ["docker-compose.prod.yml", "Caddyfile", "boot.sh"]

blocks = []
for name in FILES:
    body = (here / name).read_text()
    delim = "BEDUNO_EOF_" + name.replace(".", "_").replace("-", "_").upper()
    if delim in body:
        sys.exit(f"delimiter {delim} collides with the contents of {name}")
    blocks.append(f'cat > "$APP_DIR/{name}" <<\'{delim}\'\n{body}{delim}\n')

# Match the placeholder only as a line of its own -- the comment above it in user-data.sh names
# the placeholder too, and must survive into the rendered script rather than being expanded.
lines = template.splitlines(keepends=True)
marks = [i for i, line in enumerate(lines) if line.strip() == "__FILES__"]
if len(marks) != 1:
    sys.exit(f"expected exactly one __FILES__ line in user-data.sh, found {len(marks)}")

lines[marks[0]] = "\n".join(blocks)
rendered = "".join(lines)

# EC2 rejects user data above 16 KB. Fail here rather than at run-instances.
size = len(rendered.encode())
if size > 16 * 1024:
    sys.exit(f"user data is {size} bytes, over the 16384 byte limit")
print(rendered, end="")
