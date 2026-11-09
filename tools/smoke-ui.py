#!/usr/bin/env python3
"""Smoke-test the installed APK on an emulator only, using synthetic UI-created contacts."""
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = str(Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk')) / 'platform-tools/adb')
SERIAL = sys.argv[1] if len(sys.argv) > 1 else 'emulator-5580'
PACKAGE = 'pe.gustavo.contactoslite'
if not SERIAL.startswith('emulator-'):
    raise SystemExit('Solo se permiten emuladores.')

def adb(*args):
    return subprocess.check_output([ADB, '-s', SERIAL, *args], text=True)

def shell(*args):
    return adb('shell', shlex.join(map(str, args)))

assert shell('getprop', 'ro.kernel.qemu').strip() == '1', 'No es un emulador'

def nodes():
    shell('uiautomator', 'dump', '/sdcard/contactoslite-smoke.xml')
    return list(ET.fromstring(shell('cat', '/sdcard/contactoslite-smoke.xml')).iter('node'))

def wait_text(text, exact=True, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        tree = nodes()
        for node in tree:
            value = node.get('text', '')
            if (value.casefold() == text.casefold() if exact else text.casefold() in value.casefold()):
                return node
    raise AssertionError('No apareció: ' + text)

def wait_editor_closed(timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not any(n.get('text') == 'Guardar contacto' for n in nodes()):
            return
    screenshot('error-guardado.png')
    raise AssertionError('El editor no cerró después de guardar')

def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    shell('input', 'tap', (x1+x2)//2, (y1+y2)//2)

def click(text, exact=True):
    tap(wait_text(text, exact))

def start(action='android.intent.action.MAIN', mime=None, extras=None):
    args = ['am', 'start', '-n', PACKAGE+'/.MainActivity', '-a', action]
    if mime:
        args += ['-t', mime]
    for key, value in (extras or {}).items():
        args += ['--es', key, value]
    shell(*args)

def screenshot(name):
    directory = Path(os.environ.get('CONTACTOSLITE_CAPTURE_DIR', ROOT / 'docs/capturas'))
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / name).open('wb') as output:
        subprocess.run([ADB, '-s', SERIAL, 'exec-out', 'screencap', '-p'], stdout=output, check=True)

def raw_ids(name):
    result = shell('content', 'query', '--uri', 'content://com.android.contacts/raw_contacts',
                   '--projection', '_id', '--where', "display_name='"+name.replace("'", "''")+"'")
    return set(map(int, re.findall(r'_id=(\d+)', result)))

demo = [('Ana Torres', '+12025550101'), ('Carlos Rivera', '+12025550102'), ('Lucia Mendoza', '+12025550103')]
before = {name: raw_ids(name) for name, _ in demo}
google_contacts_enabled = 'package:com.google.android.contacts' in shell('pm', 'list', 'packages', '-e', 'com.google.android.contacts')
try:
    shell('am', 'force-stop', PACKAGE)
    shell('pm', 'revoke', PACKAGE, 'android.permission.READ_CONTACTS')
    shell('pm', 'revoke', PACKAGE, 'android.permission.WRITE_CONTACTS')
    start()
    wait_text('Tus contactos, a mano')
    screenshot('permiso.png')
    click('Permitir acceso')
    tree = nodes()
    allow = next(n for n in tree if n.get('resource-id', '').endswith('permission_allow_button'))
    tap(allow)
    wait_text('Contactos')
    print('OK: permiso de lectura y acceso a agenda', flush=True)
    shell('pm', 'grant', PACKAGE, 'android.permission.WRITE_CONTACTS')
    for name, number in demo:
        start('android.intent.action.INSERT', 'vnd.android.cursor.dir/contact', {'name': name, 'phone': number})
        wait_text(name)
        wait_text(number)
        if name == demo[0][0]:
            screenshot('nuevo-contacto.png')
        click('Guardar contacto')
        wait_editor_closed()
        start()
        wait_text(name)
    print('OK: crear tres contactos desde intent externo', flush=True)
    screenshot('contactos.png')
    click('Ana Torres')
    wait_text('Información de contacto')
    screenshot('detalle.png')
    click('+12025550101', exact=False)
    tree = nodes()
    assert any('dialer' in n.get('package', '') for n in tree), 'No abrió el marcador'
    screenshot('marcador.png')
    print('OK: abrir marcador (sin llamar)', flush=True)
    if google_contacts_enabled:
        shell('pm', 'disable-user', '--user', '0', 'com.google.android.contacts')
    try:
        # Virtual modem event only: no real call or external communication is made.
        adb('emu', 'gsm', 'call', '+12025550101')
        shell('cmd', 'statusbar', 'expand-notifications')
        wait_text('Ana Torres', exact=False)
        screenshot('llamada-simulada.png')
        print('OK: Teléfono de Google identifica a Ana en llamada virtual sin Contactos de Google activo', flush=True)
    finally:
        adb('emu', 'gsm', 'cancel', '+12025550101')
        shell('cmd', 'statusbar', 'collapse')
    start('android.intent.action.INSERT_OR_EDIT', 'vnd.android.cursor.item/contact', {'phone': '+12025550104'})
    wait_text('Elige un contacto o crea uno nuevo.')
    click('Ana Torres')
    wait_text('Editar contacto')
    wait_text('+12025550104')
    click('Guardar contacto')
    wait_editor_closed()
    start()
    click('Ana Torres')
    wait_text('Información de contacto')
    wait_text('+12025550104', exact=False)
    print('OK: añadir número desde INSERT_OR_EDIT', flush=True)
    click('Eliminar contacto')
    click('Cancelar')
    wait_text('Información de contacto')
    click('Eliminar contacto')
    click('Eliminar')
    wait_text('Contactos')
    assert not raw_ids('Ana Torres') - before['Ana Torres']
    print('OK: cancelar y confirmar borrado', flush=True)
    shell('cmd', 'uimode', 'night', 'yes')
    start()
    wait_text('Contactos')
    screenshot('modo-oscuro.png')
    print('OK: modo oscuro', flush=True)
finally:
    if google_contacts_enabled:
        shell('pm', 'enable', 'com.google.android.contacts')
    shell('cmd', 'uimode', 'night', 'no')
    for name, _ in demo:
        for raw_id in raw_ids(name) - before[name]:
            shell('content', 'delete', '--uri', f'content://com.android.contacts/raw_contacts/{raw_id}')
