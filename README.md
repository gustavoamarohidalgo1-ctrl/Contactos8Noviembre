# Contactos Lite

Agenda Android nativa, escrita en Java y sin bibliotecas de producción externas. Usa el **almacenamiento de contactos compartido de Android**: los contactos se guardan donde pueden leerlos Teléfono y otras aplicaciones con permiso. No mantiene una copia privada de la agenda.

## Funciones

- Lista con separación de 8 dp entre contactos que recicla sus filas para reducir memoria, búsqueda por nombre sin distinguir tildes y por cualquiera de los números.
- Crear, consultar, editar nombre y varios teléfonos, y eliminar con confirmación.
- Elegir una fuente editable cuando Android agrupa contactos de varias cuentas.
- Preservar correos, fotos, notas y otros campos que esta interfaz todavía no edita.
- Rechazar cambios si el contacto fue modificado desde otra aplicación mientras estaba abierto.
- Proteger fuentes y teléfonos de solo lectura.
- Abrir el marcador con `ACTION_DIAL`; no realiza llamadas automáticamente.
- Recibir `ACTION_VIEW`, `ACTION_EDIT`, `ACTION_INSERT`, `ACTION_INSERT_OR_EDIT` y `QUICK_CONTACT` para contactos.
- Interfaz en español, estética negra permanente y conservación del borrador y la cuenta seleccionada al girar la pantalla.
- Conservación de la búsqueda y la posición de la lista al volver o girar la pantalla.
- Icono adaptativo negro con personaje blanco.

## APK e instalación

El archivo instalable está en [`dist/ContactosLite-0.1.7.apk`](dist/ContactosLite-0.1.7.apk). Es una compilación release optimizada con R8 y firmada con una clave local propia. `dist/SHA256SUMS.txt` contiene su huella SHA-256.

Puedes copiar el APK al teléfono e instalarlo, concediendo a la aplicación desde la que lo abras permiso para instalar apps si Android lo solicita. Después abre Contactos Lite y concede acceso a contactos. La aplicación puede convivir con Contactos de Google durante las pruebas.

En este Mac, con el teléfono conectado y la depuración USB autorizada, identifica primero su serial y úsalo explícitamente:

```sh
~/Library/Android/sdk/platform-tools/adb devices -l
~/Library/Android/sdk/platform-tools/adb -s SERIAL_DEL_TELEFONO install -r dist/ContactosLite-0.1.7.apk
```

La versión 0.1.7 se instaló como actualización en el Redmi Note 14 4G con Android 16. Desde Teléfono de Google, el historial abrió la ficha correcta y «Ver contactos» abrió la agenda; en ambos casos Atrás volvió a Teléfono. La creación desde el teclado conservó el número sintético sin aviso y se comprobó la cancelación. No se guardaron ni borraron contactos reales, el recuento de la agenda no cambió y el número de prueba se retiró del teclado. Por instrucción expresa del usuario se borraron anteriormente los datos y la caché de Contactos de Google y se desinstaló com.google.android.contacts del usuario 0; el proveedor de contactos permanece instalado. La sincronización y las llamadas de red reales en el Redmi siguen pendientes de comprobación.

## Cuentas y alcance

«Dispositivo» crea un contacto local. La app no implementa sincronización propia; Google u otro proveedor puede sincronizar sus contactos mediante los componentes ya instalados en Android.

Por ahora, el selector ofrece cuentas detectadas a partir de contactos existentes y de adaptadores que admiten subida de cambios. Una cuenta vacía puede no aparecer. No se solicitan credenciales ni permiso para enumerar todas las cuentas. La sincronización real con Google queda pendiente de validar en el Redmi.

Esta versión edita nombre y teléfonos. No incluye todavía importación/exportación VCF, edición de fotos/correos/notas, grupos ni herramientas de duplicados. Los campos existentes fuera del editor se conservan. No se promete compatibilidad con todos los intents privados o específicos de una versión de Teléfono de Google/HyperOS.

Si otra aplicación envía datos adicionales que este formulario no admite, se avisa antes de continuar: esos datos entrantes no se añadirán. El identificador técnico `tracing_intent_id` de tipo `Long` no se trata como un dato de contacto. Al descartar una creación o edición externa `ACTION_INSERT` o `ACTION_EDIT`, se devuelve la cancelación a la aplicación invocadora. Los correos/notas/fotos que ya existan en la agenda se conservan.

`minSdk 28` (Android 9), `targetSdk 35` y `compileSdk 35`. La verificación inicial se realizó en Android 15/API 35; la apertura de la lista y de una ficha desde Teléfono se comprobó también en el Redmi Note 14 4G. La configuración actual es para distribución personal del APK; una publicación futura en Google Play deberá revisar el target y las políticas vigentes.

## Privacidad y tamaño

La aplicación solicita únicamente `READ_CONTACTS` y `WRITE_CONTACTS`. No solicita Internet, llamadas directas ni historial de llamadas. No incorpora anuncios, analítica, sincronizador propio, receptores de arranque ni servicios permanentes. No escribe la agenda en archivos privados.

El tamaño del APK no es el tamaño total instalado: Android también ocupa espacio con código compilado y datos del sistema. Tampoco equivale al espacio recuperable al desinstalar una aplicación preinstalada.

## Compilar en este Mac

```sh
./tools/build.sh --offline
```

El script carga la configuración local ignorada `.local.env`, prepara la firma si aún no existe y ejecuta release y Lint. Para otra máquina: JDK 17, SDK Android 35, Build Tools 35.0.0, Gradle 8.11.1 (wrapper incluido) y acceso inicial a los repositorios de Google/Maven. Configura `JAVA_HOME` y `sdk.dir` en `local.properties`; omite `--offline` en la primera descarga de dependencias.

**Conserva la carpeta privada `.signing/`** para firmar futuras actualizaciones. No está incluida en el control de versiones y no debe compartirse junto al APK. Si se pierde la clave, no se podrá actualizar sobre la instalación existente con una firma diferente.

## Verificación

Consulta [`docs/PRUEBAS.md`](docs/PRUEBAS.md), las salidas en `docs/resultados/` y las capturas con contactos ficticios en `docs/capturas/`. Las pruebas de escritura se ejecutan exclusivamente en un emulador aislado; nunca sobre una agenda personal. `tools/smoke-ui.py` comprueba el recorrido visual y rechaza seriales de dispositivos físicos.

La compilación 0.1.7, código 8, ocupa **55 696 bytes** (aproximadamente 55,7 KB). Release y Lint terminaron sin incidencias, se verificaron la firma y los dos permisos, y pasaron 8 pruebas instrumentadas y el recorrido visual en emulador. También pasaron las verificaciones de flujo, Quick Contact y metadatos en emulador. La versión se instaló y verificó en el Redmi; la sincronización y las llamadas de red reales siguen pendientes. El [informe de verificación](docs/RESULTADOS.md) conserva los resultados anteriores y distingue lo probado en emulador de lo pendiente en el teléfono.

## Referencias de implementación

- [Contacts Provider de Android](https://developer.android.com/identity/providers/contacts-provider)
- [ContactsProvider AOSP, rama Android 15](https://android.googlesource.com/platform/packages/providers/ContactsProvider/+/refs/heads/android15-release/src/com/android/providers/contacts/ContactsProvider2.java)
- [Reducción del tamaño de aplicaciones](https://developer.android.com/topic/performance/reduce-apk-size)
- [Cambios futuros en permisos de contactos](https://developer.android.com/blog/posts/reliable-way-to-add-and-edit-contacts-android)

La implementación y revisión se repartieron entre subagentes GPT-6.1 Terra según la preferencia indicada para este proyecto.
