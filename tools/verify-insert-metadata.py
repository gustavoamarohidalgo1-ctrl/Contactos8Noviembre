#!/usr/bin/env python3
"""Verify INSERT metadata handling on an emulator with the app already installed.

Usage: python3 tools/verify-insert-metadata.py emulator-5554
Contacts permissions must already be granted. No contact is saved or deleted.
"""
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "pe.gustavo.contactoslite"
NAME = "Insercion Prueba"
PHONE = "910123456"
WARNING = "Esta versión solo puede añadir nombre y teléfonos."


def run(*args, timeout=20):
    result = subprocess.run(args, text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Command failed: {shlex.join(args)}\n{result.stdout}{result.stderr}")
    return result.stdout


def main():
    if len(sys.argv) != 2 or not re.fullmatch(r"emulator-\d+", sys.argv[1]):
        raise SystemExit("Usage: python3 tools/verify-insert-metadata.py emulator-PORT")
    serial = sys.argv[1]

    def shell(*args, timeout=20):
        # adb joins its shell arguments; quote the complete remote command.
        return run("adb", "-s", serial, "shell", shlex.join(args), timeout=timeout)

    if shell("getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("Selected device is not an Android emulator")

    def snapshot():
        shell("uiautomator", "dump", "/sdcard/window-insert-metadata.xml", timeout=30)
        xml = shell("cat", "/sdcard/window-insert-metadata.xml")
        return ET.fromstring(xml[xml.index("<?xml"):])

    def texts(root):
        return [node.get("text", "") for node in root.iter()]

    def tap(root, label):
        for node in root.iter():
            if node.get("text", "").casefold() == label.casefold():
                bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
                if bounds:
                    x1, y1, x2, y2 = map(int, bounds.groups())
                    shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                    return
        raise AssertionError(f"Could not tap {label!r}: {texts(root)}")

    def wait_for(predicate, description):
        deadline = time.monotonic() + 20
        last = []
        while time.monotonic() < deadline:
            root = snapshot()
            last = texts(root)
            if predicate(root, last):
                return root
            time.sleep(0.3)
        raise AssertionError(f"Timed out waiting for {description}: {last}")

    def launch(extra, insert_or_edit=False):
        action = "android.intent.action.INSERT_OR_EDIT" if insert_or_edit else "android.intent.action.INSERT"
        mime = "vnd.android.cursor.item/contact" if insert_or_edit else "vnd.android.cursor.dir/contact"
        args = ["am", "start", "-W", "-a", action, "-p", PACKAGE,
                "-t", mime, "--es", "name", NAME,
                "--es", "phone", PHONE, *extra]
        output = shell(*args, timeout=30)
        if "Error:" in output or "unable to resolve" in output.lower():
            raise RuntimeError(f"INSERT failed: {output}")

    cases = [
        ("name + phone", [], False, False),
        ("tracing_intent_id Long", ["--el", "tracing_intent_id", "123"], False, False),
        ("tracing_intent_id String", ["--es", "tracing_intent_id", "123"], True, False),
        ("email", ["--es", "email", "test@example.invalid"], True, False),
        ("INSERT_OR_EDIT tracing_intent_id Long", ["--el", "tracing_intent_id", "123"], False, True),
    ]
    for label, extra, expects_warning, insert_or_edit in cases:
        try:
            launch(extra, insert_or_edit)
            if insert_or_edit:
                root = wait_for(lambda r, t: any(WARNING in s for s in t)
                                or any(s.casefold() == "+  nuevo contacto" for s in t),
                                "warning or contact selector")
                if any(WARNING in s for s in texts(root)):
                    raise AssertionError(f"{label}: unexpected warning: {texts(root)}")
                tap(root, "+  Nuevo contacto")
            root = wait_for(lambda r, t: any(WARNING in s for s in t)
                            or "Nuevo contacto" in t or "Guardar en cuenta" in t,
                            "warning, account selector or editor")
            shown = any(WARNING in s for s in texts(root))
            if shown != expects_warning:
                raise AssertionError(f"{label}: warning={shown}, expected {expects_warning}: {texts(root)}")
            if shown:
                tap(root, "Continuar con nombre y teléfonos")
                root = wait_for(lambda r, t: "Nuevo contacto" in t or "Guardar en cuenta" in t,
                                "account selector or editor")
            if "Guardar en cuenta" in texts(root):
                tap(root, "Dispositivo")
                root = wait_for(lambda r, t: "Nuevo contacto" in t, "editor")
            values = [node.get("text", "") for node in root.iter()
                      if node.get("class") == "android.widget.EditText"]
            if NAME not in values or PHONE not in values or "Guardar contacto" not in texts(root):
                raise AssertionError(f"{label}: missing prefilled editor fields: {texts(root)}")
            print(f"OK: {label}")
        finally:
            # Back out and explicitly discard; never invoke the save action.
            shell("input", "keyevent", "KEYCODE_BACK")
            root = snapshot()
            if "Nuevo contacto" in texts(root) and "¿Descartar cambios?" not in texts(root):
                shell("input", "keyevent", "KEYCODE_BACK")
                root = snapshot()
            if "¿Descartar cambios?" in texts(root):
                tap(root, "Descartar")
                wait_for(lambda r, t: "¿Descartar cambios?" not in t, "discard")
            elif any(WARNING in s for s in texts(root)):
                tap(root, "Cancelar")


if __name__ == "__main__":
    main()
