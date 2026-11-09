#!/usr/bin/env python3
"""Exercise UI navigation on a disposable emulator with contacts permission granted.

Usage: python3 tools/verify-flow.py emulator-PORT
Creates uniquely marked contacts and removes only those contacts in finally.
"""
import re
import shlex
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

PACKAGE = 'pe.gustavo.contactoslite'
BASE = 'content://com.android.contacts'


def run(*args, timeout=30):
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'{shlex.join(args)}: {result.stdout}{result.stderr}')
    return result.stdout


def main():
    if len(sys.argv) != 2 or not re.fullmatch(r'emulator-\d+', sys.argv[1]):
        raise SystemExit('Usage: python3 tools/verify-flow.py emulator-PORT')
    serial = sys.argv[1]

    def shell(*args):
        return run('adb', '-s', serial, 'shell', shlex.join(map(str, args)))

    if shell('getprop', 'ro.kernel.qemu').strip() != '1':
        raise RuntimeError('Selected device is not an emulator')

    suffix = uuid.uuid4().hex[:10]
    markers = [f'contactoslite-flow-{suffix}-{i}' for i in range(20)]
    prefix = f'Flujo{suffix}'
    ids = []
    old_auto = shell('settings', 'get', 'system', 'accelerometer_rotation').strip()
    old_rotation = shell('settings', 'get', 'system', 'user_rotation').strip()

    def query(uri, projection, where):
        return shell('content', 'query', '--uri', uri, '--projection', projection, '--where', where)

    def marked(marker):
        return [int(x) for x in re.findall(r'(?:^|[ ,])_id=(\d+)',
                query(BASE + '/raw_contacts', '_id', f"sourceid='{marker}'"))]

    def insert(uri, *bindings):
        args = ['content', 'insert', '--uri', uri]
        for binding in bindings:
            args += ['--bind', binding]
        shell(*args)

    def seed(index):
        marker = markers[index]
        insert(BASE + '/raw_contacts', 'aggregation_mode:i:0', f'sourceid:s:{marker}')
        found = marked(marker)
        if len(found) != 1:
            raise AssertionError(f'Expected one raw contact for {marker}: {found}')
        raw = found[0]
        ids.append(raw)
        name = f'{prefix} {index:02d}' + (' Álvarez' if index == 19 else '')
        insert(BASE + '/data', f'raw_contact_id:i:{raw}',
               'mimetype:s:vnd.android.cursor.item/name', f'data1:s:{name}')
        for number in ([f'910{index:06d}', '987654321'] if index == 19 else [f'910{index:06d}']):
            insert(BASE + '/data', f'raw_contact_id:i:{raw}',
                   'mimetype:s:vnd.android.cursor.item/phone_v2', f'data1:s:{number}', 'data2:i:2')
        return name, raw

    def snapshot():
        shell('uiautomator', 'dump', '/sdcard/contactoslite-flow.xml')
        xml = shell('cat', '/sdcard/contactoslite-flow.xml')
        return ET.fromstring(xml[xml.index('<?xml'):])

    def texts(root):
        return [n.get('text', '') for n in root.iter('node')]

    def wait(predicate, description):
        deadline = time.monotonic() + 20
        last = []
        while time.monotonic() < deadline:
            root = snapshot()
            last = texts(root)
            if predicate(root, last):
                return root
            time.sleep(.25)
        raise AssertionError(f'Timed out waiting for {description}: {last}')

    def has(value):
        return lambda root, values: any(value.casefold() == x.casefold() for x in values)

    def tap_node(node):
        bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
        if not bounds:
            raise AssertionError('Missing bounds')
        x1, y1, x2, y2 = map(int, bounds.groups())
        shell('input', 'tap', (x1 + x2) // 2, (y1 + y2) // 2)

    def tap(label):
        root = wait(has(label), label)
        tap_node(next(n for n in root.iter('node') if n.get('text', '').casefold() == label.casefold()))

    def edit(value, index=0):
        root = wait(lambda r, t: any(n.get('class') == 'android.widget.EditText' for n in r.iter('node')), 'input')
        fields = [n for n in root.iter('node') if n.get('class') == 'android.widget.EditText']
        tap_node(fields[index])
        shell('input', 'keyevent', 'KEYCODE_MOVE_END')
        # Delete the current value without depending on a keyboard-specific selection UI.
        length = len(fields[index].get('text', ''))
        if length:
            shell('input', 'keyevent', *(['KEYCODE_DEL'] * length))
        shell('input', 'text', value)
        shell('input', 'keyevent', 'KEYCODE_BACK')  # hide keyboard

    def launch(*args):
        output = shell('am', 'start', '-W', *args)
        if 'Error:' in output or 'unable to resolve' in output.lower():
            raise RuntimeError(output)

    def back():
        shell('input', 'keyevent', 'KEYCODE_BACK')

    def settings():
        launch('-a', 'android.settings.SETTINGS')
        wait(lambda r, t: any(n.get('package', '').startswith('com.android.settings') for n in r.iter('node')), 'Settings')

    def returned_settings():
        wait(lambda r, t: any(n.get('package', '').startswith('com.android.settings') for n in r.iter('node')), 'return to Settings')

    try:
        contacts = [seed(i) for i in range(len(markers))]
        name, raw = contacts[-1]
        contact = re.search(r'contact_id=(\d+)', query(BASE + '/raw_contacts', 'contact_id', f'_id={raw}'))
        if not contact:
            raise AssertionError('Synthetic contact has no aggregate ID')
        uri = BASE + '/contacts/' + contact.group(1)
        launch('-n', PACKAGE + '/.MainActivity', '-a', 'android.intent.action.MAIN')
        wait(has('Contactos'), 'contact list')
        edit('alvarez')
        tap(name)
        wait(has('Información de contacto'), 'search detail')
        back()
        root = wait(has(name), 'search restored')
        assert any(n.get('text', '').casefold() == 'alvarez' for n in root.iter('node'))
        edit('987654321')
        tap(name)
        wait(has('Información de contacto'), 'secondary phone result')
        tap('Editar contacto')
        wait(has('Guardar contacto'), 'editor')
        edit('BorradorTemporal')
        shell('settings', 'put', 'system', 'accelerometer_rotation', '0')
        shell('settings', 'put', 'system', 'user_rotation', '1')
        wait(lambda r, t: r.get('rotation') == '1' and 'BorradorTemporal' in t, 'landscape draft')
        # Hide the keyboard only if it is visible, so Back reaches the editor.
        root = snapshot()
        if any('inputmethod' in n.get('package', '').lower() for n in root.iter('node')):
            back()
        back()
        tap('Seguir editando')
        wait(has('BorradorTemporal'), 'retained draft')
        back()
        tap('Descartar')
        wait(has('Información de contacto'), 'detail after discard')
        assert name in query(BASE + '/data', 'data1', f"raw_contact_id={raw} AND mimetype='vnd.android.cursor.item/name'")
        print('OK: search by accent and secondary phone; rotated draft retained and discarded', flush=True)

        # Return to portrait before exercising a long list.
        shell('settings', 'put', 'system', 'user_rotation', '0')
        wait(lambda r, t: r.get('rotation') == '0' and 'Información de contacto' in t, 'portrait detail')
        # Check that the restored list retains the query when returning from detail.
        back()
        root = wait(has(name), 'search after detail')
        assert '987654321' in texts(root)
        edit(prefix)
        wait(has(contacts[0][0]), 'full synthetic list')
        # Scroll within the list using screen-relative bounds, then return from a visible row.
        root = snapshot()
        lists = [n for n in root.iter('node') if n.get('class') == 'android.widget.ListView']
        if not lists:
            raise AssertionError('Contact list not found')
        b = list(map(int, re.findall(r'\d+', lists[0].get('bounds', ''))))
        shell('input', 'swipe', (b[0]+b[2])//2, b[1]+(b[3]-b[1])*4//5,
              (b[0]+b[2])//2, b[1]+(b[3]-b[1])//5, 500)
        root = wait(lambda r, t: any(x in {c[0] for c in contacts[1:]} for x in t), 'scrolled row')
        visible = [x for x in texts(root) if x in {c[0] for c in contacts[1:]}]
        row = visible[-1]
        tap(row)
        wait(has('Información de contacto'), 'scrolled detail')
        back()
        root = wait(has(row), 'restored scroll position')
        assert prefix in texts(root)
        print('OK: search and scroll position survive opening and returning from detail', flush=True)

        shell('am', 'force-stop', PACKAGE)
        settings()
        launch('-a', 'android.provider.action.QUICK_CONTACT', '-p', PACKAGE, '-d', uri)
        wait(has('Información de contacto'), 'external quick contact')
        back()
        returned_settings()
        shell('am', 'force-stop', PACKAGE)
        settings()
        launch('-a', 'android.intent.action.EDIT', '-p', PACKAGE, '-d', uri, '-t', 'vnd.android.cursor.item/contact')
        wait(has('Guardar contacto'), 'external edit')
        back()
        tap('Descartar')
        returned_settings()
        print('OK: external QUICK_CONTACT and canceled EDIT return to caller', flush=True)

        launch('-n', PACKAGE + '/.MainActivity', '-a', 'android.intent.action.MAIN')
        wait(has('Contactos'), 'list')
        edit('987654321')
        edit('alvarez')
        tap(name)
        wait(has('Información de contacto'), 'latest search detail')
        time.sleep(1)
        root = snapshot()
        assert name in texts(root) and 'Información de contacto' in texts(root)
        print('OK: rapid search and navigation finish on the requested detail', flush=True)
    finally:
        errors = []
        for key, value in [('accelerometer_rotation', old_auto), ('user_rotation', old_rotation)]:
            try:
                shell('settings', 'delete' if value == 'null' else 'put', 'system', key, *([] if value == 'null' else [value]))
            except Exception as exc:
                errors.append(f'{key}: {exc}')
        for marker in markers:
            try:
                ids.extend(marked(marker))
            except Exception as exc:
                errors.append(f'{marker}: {exc}')
        for raw in sorted(set(ids)):
            try:
                shell('content', 'delete', '--uri', f'{BASE}/raw_contacts/{raw}?caller_is_syncadapter=true')
            except Exception as exc:
                errors.append(f'{raw}: {exc}')
        if errors:
            raise RuntimeError('Cleanup failed: ' + '; '.join(errors))


if __name__ == '__main__':
    main()
