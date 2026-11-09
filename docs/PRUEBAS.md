# Pruebas de ContactosLite

Usar exclusivamente un emulador desechable, sin cuentas ni contactos personales. Las pruebas modifican el proveedor real de contactos del emulador; nunca ejecutarlas en un teléfono físico. Comprobar `adb devices -l` antes de cualquier comando y seleccionar explícitamente el serial `emulator-...`. Conceder READ_CONTACTS y WRITE_CONTACTS a la aplicación desde Ajustes antes de ejecutar las pruebas instrumentadas. No hay que borrar la agenda del dispositivo: cada prueba elimina solo los contactos sintéticos que creó.

Con el emulador preparado, instalar el APK de depuración y el APK de pruebas con `adb -s emulator-5554 install -r ...` (sustituir el serial por el comprobado). Conceder ambos permisos mediante Ajustes o `adb -s emulator-5554 shell pm grant pe.gustavo.contactoslite android.permission.READ_CONTACTS` y el comando equivalente para `android.permission.WRITE_CONTACTS`. Ejecutar exclusivamente en ese serial:

```sh
adb -s emulator-5554 shell am instrument -w pe.gustavo.contactoslite.test/android.test.InstrumentationTestRunner
```

Comprobar el resultado de instrumentación y el estado final de los contactos sintéticos. Si una interrupción impide la limpieza, eliminarlos manualmente por su prefijo `ContactosLiteTest_` en el emulador. La limpieza automatizada usa acceso de adaptador de sincronización solo sobre los ID concretos de las fuentes sintéticas, para permitir retirar también la fila de solo lectura creada por la prueba.

Para verificar la apertura de contactos desde `QUICK_CONTACT` y `VIEW`, con la aplicación instalada y ambos permisos concedidos, ejecutar en el emulador seleccionado:

```sh
python3 tools/verify-quick-contact.py emulator-5554
```

El script crea dos contactos sintéticos y los elimina al terminar; comprobar la salida y la limpieza si se interrumpe. Rechaza dispositivos que no sean emuladores.

Para verificar la versión 0.1.5 y el tratamiento de metadatos en intents de creación, con la aplicación instalada y ambos permisos concedidos, ejecutar en el emulador seleccionado:

```sh
python3 tools/verify-insert-metadata.py emulator-5554
```

Los cinco casos comprueban la inserción base, `tracing_intent_id` como `Long`, el mismo extra con otro tipo, un correo no admitido e `INSERT_OR_EDIT` con el token. El token con tipo incorrecto y el correo deben conservar el aviso; el token `Long` no debe provocarlo. No se guardan contactos. La salida de la verificación de 0.1.5 está en [`resultados/insert-metadata-0.1.5.txt`](resultados/insert-metadata-0.1.5.txt). Comprobar también que descartar una creación externa `ACTION_INSERT` devuelve la cancelación al invocador, mientras `INSERT_OR_EDIT` conserva su recorrido de lista y detalle.

## Flujo de navegación y restauración

Con el APK instalado y los permisos concedidos en un emulador desechable, ejecutar:

```sh
python3 tools/verify-flow.py emulator-5554
```

Sustituir el serial por el comprobado. El script exige un serial `emulator-...` y verifica que el dispositivo sea un emulador. Crea contactos sintéticos con marcadores únicos y elimina solo las fuentes identificadas por esos marcadores; también guarda y restaura los ajustes de rotación en el bloque de limpieza. Si se interrumpe el proceso, comprobar manualmente la limpieza de los contactos marcados y la restauración de la rotación antes de reutilizar el emulador.

## Recorrido manual

1. En una instalación limpia, denegar lectura y comprobar que se explica la falta de acceso sin cerrar la aplicación. Conceder lectura desde Ajustes y volver; la lista debe cargar. Repetir con escritura denegada y comprobar que no se afirma que un cambio se guardó.
2. Crear un contacto local con nombre y dos teléfonos; localizarlo por nombre y número, abrir el detalle y verificar los valores. Cambiar nombre y teléfonos, volver a abrir y verificar persistencia. Eliminarlo, confirmar la acción y comprobar que ya no aparece.
3. Crear desde la aplicación de contactos del emulador un contacto sintético con correo, nota y teléfono. Editar únicamente nombre y teléfono en ContactosLite; comprobar en la aplicación de contactos que el correo y la nota siguen intactos.
4. Con un emulador de prueba que tenga fuentes sincronizadas y de solo lectura, abrir un contacto agregado con varias fuentes. Confirmar que se distingue la fuente modificada, que las fuentes de solo lectura no ofrecen escritura, y que editar o borrar una fuente no altera las otras. Evitar cuentas reales.
5. Rotar la pantalla y volver desde Ajustes o desde otra aplicación durante una búsqueda y durante una edición. Comprobar que no se pierden cambios sin advertencia y que la lista refleja cambios externos al regresar.
6. Probar lista vacía, consulta sin resultados, teléfono vacío o repetido, cancelación de edición y confirmación/cancelación de borrado. Comprobar además teléfono de fuente con etiqueta personalizada y nombres no ASCII.

## Compatibilidad futura

El [anuncio de Android del 21 de octubre de 2026](https://developer.android.com/blog/posts/reliable-way-to-add-and-edit-contacts-android?hl=en) indica que la restricción de escritura directa se aplicará al apuntar al próximo lanzamiento de Android previsto para 2027; las aplicaciones con target anterior conservan su comportamiento. `MANAGE_CONTACTS` se anuncia para Android 17 QPR2 como acceso especial; un gestor de contactos dedicado probablemente califique. Verificar implementación y política antes de subir el target: los intents de edición no proporcionan borrado y la publicación en Play puede exigir declaración.
