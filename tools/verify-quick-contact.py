#!/usr/bin/env python3
"""Verify contact deep links on an explicitly selected Android emulator.

Usage: python3 tools/verify-quick-contact.py emulator-5554
The app must already be installed with contacts permissions granted.
"""
import re
import shlex
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from urllib.parse import quote

PACKAGE = "pe.gustavo.contactoslite"
AUTHORITY = "com.android.contacts"
BASE = "content://" + AUTHORITY
QUICK = "android.provider.action.QUICK_CONTACT"
VIEW = "android.intent.action.VIEW"


def run(*args, timeout=20, check=True):
    result = subprocess.run(args, text=True, capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"Command failed: {shlex.join(args)}\n{result.stdout}{result.stderr}")
    return result


def main():
    if len(sys.argv) != 2 or not re.fullmatch(r"emulator-\d+", sys.argv[1]):
        raise SystemExit("Usage: python3 tools/verify-quick-contact.py emulator-PORT")
    serial = sys.argv[1]

    def adb(*args, **kwargs):
        return run("adb", "-s", serial, *args, **kwargs)

    def shell(*args, **kwargs):
        # adb shell joins arguments into a remote shell command; quote each argument.
        return adb("shell", shlex.join(args), **kwargs)

    if shell("getprop", "ro.kernel.qemu").stdout.strip() != "1":
        raise RuntimeError("Refusing to write: selected device is not an Android emulator")

    raw_ids = []
    suffix = uuid.uuid4().hex
    markers = [f"contactoslite-quick-test-{suffix}-{i}" for i in range(2)]
    contacts = [(f"QC Alpha {suffix}", "910123401"),
                (f"QC Beta {suffix}", "910123402")]

    def insert(uri, *bindings):
        args = ["content", "insert", "--uri", uri]
        for binding in bindings:
            args.extend(("--bind", binding))
        return shell(*args).stdout

    def query(uri, projection, where=None):
        args = ["content", "query", "--uri", uri, "--projection", projection]
        if where:
            args.extend(("--where", where))
        return shell(*args).stdout

    def marked_ids(marker):
        row = query(BASE + "/raw_contacts", "_id", f"sourceid='{marker}'")
        return [int(value) for value in re.findall(r"(?:^|[ ,])_id=(\d+)", row)]

    def seed(name, number, marker):
        insert(BASE + "/raw_contacts", "aggregation_mode:i:0", f"sourceid:s:{marker}")
        ids = marked_ids(marker)
        if len(ids) != 1:
            raise RuntimeError(f"Expected one synthetic raw contact for {marker}, found {ids}")
        raw_id = ids[0]
        raw_ids.append(raw_id)
        insert(BASE + "/data", f"raw_contact_id:i:{raw_id}",
               "mimetype:s:vnd.android.cursor.item/name", f"data1:s:{name}")
        insert(BASE + "/data", f"raw_contact_id:i:{raw_id}",
               "mimetype:s:vnd.android.cursor.item/phone_v2", f"data1:s:{number}", "data2:i:2")
        for _ in range(30):
            row = query(BASE + "/raw_contacts", "contact_id", f"_id={raw_id}")
            match = re.search(r"contact_id=(\d+)", row)
            if match:
                contact_id = int(match.group(1))
                row = query(BASE + "/contacts", "lookup", f"_id={contact_id}")
                lookup = re.search(r"lookup=([^,\r\n]+)", row)
                if lookup and lookup.group(1) != "NULL":
                    return contact_id, lookup.group(1).strip()
            time.sleep(0.3)
        raise RuntimeError(f"Contact aggregation did not finish for raw ID {raw_id}")

    def launch(action, uri=None):
        args = ["am", "start", "-W", "-a", action, "-p", PACKAGE]
        if uri is not None:
            args += ["-d", uri]
        result = shell(*args, check=False, timeout=30)
        output = result.stdout + result.stderr
        if result.returncode or "Error:" in output or "unable to resolve" in output.lower():
            raise RuntimeError(f"Intent failed to resolve: {output}")

    def ui_text():
        result = shell("uiautomator", "dump", "/sdcard/window-quick-contact.xml", check=False, timeout=30)
        if result.returncode:
            return ""
        xml = shell("cat", "/sdcard/window-quick-contact.xml", check=False).stdout
        try:
            root = ET.fromstring(xml[xml.index("<?xml"):])
        except (ValueError, ET.ParseError):
            return ""
        return "\n".join(node.attrib.get("text", "") for node in root.iter())

    def assert_detail(action, uri, expected, other):
        launch(action, uri)
        deadline = time.monotonic() + 15
        text = ""
        while time.monotonic() < deadline:
            text = ui_text()
            if expected[0] in text and expected[1] in text and "Información de contacto" in text:
                if other[0] in text or "Editar contacto" not in text:
                    raise AssertionError(f"Unexpected contact/list for {uri}:\n{text}")
                return
            time.sleep(0.4)
        raise AssertionError(f"Expected detail for {expected[0]} via {uri}; screen:\n{text}")

    try:
        resolved = [seed(*contact, marker) for contact, marker in zip(contacts, markers)]
        if resolved[0][0] == resolved[1][0]:
            raise AssertionError("Synthetic contacts unexpectedly aggregated")
        contact_id, lookup = resolved[1]
        item_uri = f"{BASE}/contacts/{contact_id}"
        lookup_uri = f"{BASE}/contacts/lookup/{quote(lookup, safe='')}/{contact_id}"
        assert_detail(QUICK, item_uri, contacts[1], contacts[0])
        assert_detail(QUICK, lookup_uri, contacts[1], contacts[0])
        assert_detail(QUICK, lookup_uri + "?directory=0", contacts[1], contacts[0])
        assert_detail(VIEW, lookup_uri, contacts[1], contacts[0])
        print("OK: QUICK_CONTACT por ID, lookup y directory=0; VIEW por lookup")
    finally:
        errors = []
        # Look up every exact marker, including a raw contact whose insert succeeded
        # but whose response or subsequent query failed before its ID was recorded.
        for marker in markers:
            try:
                raw_ids.extend(marked_ids(marker))
            except Exception as exc:
                errors.append(f"marker {marker}: {exc}")
        for raw_id in sorted(set(raw_ids)):
            try:
                # Sync-adapter deletion physically removes only the raw contact we created.
                shell("content", "delete", "--uri", f"{BASE}/raw_contacts/{raw_id}?caller_is_syncadapter=true")
            except Exception as exc:
                errors.append(f"{raw_id}: {exc}")
        if errors:
            raise RuntimeError("Could not clean synthetic raw contacts: " + "; ".join(errors))


if __name__ == "__main__":
    main()
