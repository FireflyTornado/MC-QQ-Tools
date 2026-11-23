"""Resolve MC_AUTH_ENABLED as the installed API unit would on its next start."""

import json
import subprocess
import sys
from pathlib import Path
from tempfile import TemporaryDirectory

SYSTEMD = "org.freedesktop.systemd1"
PROBE = """
import json
import os
import sys

for assignment in json.load(sys.stdin):
    key, separator, value = assignment.partition("=")
    if not separator or os.environ.get(key) == value:
        os.environ.pop(key, None)

sys.path.insert(0, sys.argv[1])
from app_config import load_config, read_switch

print(int(read_switch(load_config(), "MC_AUTH_ENABLED", default=True)))
"""


def bus_data(*args):
    result = subprocess.run(
        ["busctl", "--system", "--json=short", *args],
        capture_output=True, check=True, text=True, encoding="utf-8", timeout=10,
    )
    return json.loads(result.stdout)["data"]


def service_auth_mode(unit: str) -> str:
    path, = bus_data("call", SYSTEMD, "/org/freedesktop/systemd1", f"{SYSTEMD}.Manager", "LoadUnit", "s", unit)

    def property_value(name):
        return bus_data("get-property", SYSTEMD, path, f"{SYSTEMD}.Service", name)

    environment = property_value("Environment")
    files = property_value("EnvironmentFiles")
    passed = property_value("PassEnvironment")
    unset = property_value("UnsetEnvironment")
    user = property_value("User")
    group = property_value("Group")
    command = ["systemd-run", "--system", "--quiet", "--wait", "--pipe", "--collect", "--expand-environment=no"]
    if user:
        command.append(f"--property=User={user}")
    if group:
        command.append(f"--property=Group={group}")
    if passed:
        command.append(f"--property=PassEnvironment={' '.join(passed)}")

    # Use a private file to keep Environment= values out of process arguments.
    # Put it first, so the installed unit's EnvironmentFile= entries override it.
    with TemporaryDirectory(prefix="mc-qq-auth-mode-") as temporary:
        settings = Path(temporary) / "environment"
        with settings.open("w", encoding="utf-8") as output:
            for assignment in environment:
                key, value = assignment.split("=", 1)
                escaped = value.replace("\\", "\\\\").replace('"', '\\"')
                output.write(f'{key}="{escaped}"\n')
        command.append(f"--property=EnvironmentFile={settings}")
        for filename, optional in files:
            command.append(f"--property=EnvironmentFile={'-' if optional else ''}{filename}")
        bot_dir = Path(__file__).resolve().parents[1] / "bot"
        result = subprocess.run(
            [*command, "--", sys.executable, "-I", "-B", "-c", PROBE, str(bot_dir)],
            input=json.dumps(unset), capture_output=True, check=True, text=True, encoding="utf-8", timeout=20,
        )
    mode = result.stdout.strip()
    if mode not in {"0", "1"}:
        raise ValueError("Unexpected authorization mode")
    return mode


if __name__ == "__main__":
    try:
        print(service_auth_mode(sys.argv[1]))
    except (OSError, subprocess.SubprocessError, LookupError, ValueError, TypeError):
        sys.exit("Unable to resolve the installed API unit's authorization mode.")
