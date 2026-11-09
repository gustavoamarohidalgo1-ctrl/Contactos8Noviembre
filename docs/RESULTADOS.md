# Verificación de Contactos Lite

## Actualización 0.1.7: fluidez y continuidad

- Versión 0.1.7, código 8: APK release de **55 696 bytes**. Release y Lint sin incidencias; firma y declaración de los dos permisos verificadas.
- Se reutiliza el patrón de normalización y el detalle consulta cinco veces al proveedor con independencia del número de fuentes, capturando sus versiones antes de los datos. Se evitan cargas duplicadas y consultas de lista pendientes, y se protegen las respuestas asíncronas frente a navegación posterior.
- La búsqueda y la posición de la lista se conservan al volver o girar; el borrador y la cuenta seleccionada se preservan durante la recreación. Se ajustó el retorno al invocador externo, incluido Teléfono, y la cancelación de `EDIT` e `INSERT`. La estética negra y la separación de 8 dp permanecen.
- En emulador pasaron las [8 pruebas instrumentadas](resultados/instrumentation-0.1.7.txt) y el [recorrido visual completo](resultados/ui-smoke-0.1.7.txt), incluidos creación, edición y borrado, `INSERT_OR_EDIT`, llamada simulada y modo nocturno. Se conservaron las [capturas de 0.1.7](capturas/0.1.7/).
- La [verificación de flujo](resultados/flow-0.1.7.txt) pasó sus cuatro bloques: búsqueda por tilde y teléfono secundario con rotación y descarte; búsqueda y posición al volver; retorno de `QUICK_CONTACT` y cancelación de `EDIT` al invocador; y navegación rápida. También pasaron los [cuatro casos de Quick Contact](resultados/quick-contact-0.1.7.txt) y los [cinco casos de metadatos](resultados/insert-metadata-0.1.7.txt). El emulador terminó con cero fuentes y se apagó.
- Instalada y verificada en el Redmi Note 14 4G con Android 16/API 36: versión 0.1.7, código 8. Desde Teléfono, el historial abrió la ficha correcta y Atrás volvió a Teléfono; «Ver contactos» abrió la agenda y Atrás regresó a Teléfono. La creación desde el teclado conservó el número sintético sin aviso, y cancelar y Atrás funcionaron. No se guardaron ni borraron contactos reales; el recuento permaneció igual y se borró el número de prueba del teclado. Véase el [resultado en Redmi](resultados/redmi-0.1.7.txt).
- La sincronización y las llamadas de red reales en el Redmi siguen pendientes.

## Actualización 0.1.6: separación de contactos

- Versión 0.1.6, código 7: APK release de **52 560 bytes**, 112 bytes más que la 0.1.5. Release y Lint sin incidencias; firma verificada.
- Separación transparente de 8 dp entre filas, con fondo negro y reciclaje de filas intactos; sin dependencias nuevas.
- En el emulador se revisó la [lista con tres contactos sintéticos](capturas/0.1.6/lista-emulador.png): la separación observada fue de 21 píxeles, equivalentes a 8 dp en ese dispositivo. Al pulsar la segunda fila se abrió el contacto correcto. Los datos de prueba se limpiaron.
- Instalada como actualización en el Redmi Note 14 4G y verificada la versión 0.1.6, código 7. La revisión visual y de la estructura mostró 23 píxeles de separación entre todas las filas visibles, equivalentes a 8 dp según la densidad del teléfono. No se leyeron nombres en la salida ni se modificaron contactos.

## Actualización 0.1.5: creación desde Teléfono de Google

- Versión 0.1.5, código 6: APK release de **52 448 bytes**, frente a 52 312 bytes de la 0.1.4. Release y Lint sin incidencias; firma verificada.
- El identificador técnico `tracing_intent_id` solo se admite sin aviso cuando es de tipo `Long`; los datos de contacto no admitidos y los extras desconocidos siguen provocando la advertencia. Al descartar un editor abierto mediante `ACTION_INSERT`, se devuelve `RESULT_CANCELED` y se vuelve al invocador.
- Cinco casos pasaron en emulador con `tools/verify-insert-metadata.py`: inserción base, token `Long`, token de tipo incorrecto, correo y `INSERT_OR_EDIT` con token `Long`. No se guardaron contactos y el proveedor terminó con cero fuentes. La [salida completa](resultados/insert-metadata-0.1.5.txt) recoge los resultados.
- Instalada en el Redmi Note 14 4G. Desde el teclado de Teléfono de Google no apareció el aviso falso; Android concedió el permiso de escritura solicitado. El formulario mostró correctamente el número sintético inicial y, en una segunda creación, el nuevo número. Ambos borradores se descartaron sin guardar y el recuento del proveedor permaneció igual.
- La sincronización y las llamadas de red reales en el Redmi siguen pendientes.

## Actualización 0.1.4: contacto desde el historial de llamadas

- Versión 0.1.4, código 5: APK release de **52 312 bytes**, frente a 51 920 bytes de la 0.1.3. Release y Lint sin incidencias; firma verificada.
- Instalada en el Redmi Note 14 4G. Al pulsar el contacto en el historial de Teléfono de Google, Contactos Lite abrió «Información de contacto» y el nombre coincidió exactamente con el de la fila seleccionada. No se modificó la agenda.
- Cuatro casos pasaron en el emulador mediante `tools/verify-quick-contact.py`: `QUICK_CONTACT` por ID, por lookup y por lookup con `?directory=0`, y `VIEW` por lookup. Se usaron dos contactos sintéticos distintos y se verificó su limpieza al terminar.
- La sincronización y las llamadas de red reales en el Redmi siguen pendientes.

## Actualización 0.1.3: lista desde Teléfono de Google

- Versión 0.1.3, código 4: APK release de **51 920 bytes** (aproximadamente 51,9 KB), frente a 50 588 bytes de la 0.1.2. Release y Lint sin incidencias; firma verificada. Solo declara `READ_CONTACTS` y `WRITE_CONTACTS`.
- Icono adaptativo negro con personaje blanco. Los intents `VIEW` de lista de contactos abren la agenda; las rutas de detalle se validan antes de consultar el proveedor.
- Revisión visual del icono completada en el [lanzador del Redmi](capturas/0.1.3/icono-redmi.png) y en [información de la app del emulador](capturas/0.1.3/icono-emulador.png) con el APK final. En el Redmi se observa negro sin borde blanco y con personaje blanco.
- Instalada en el Redmi Note 14 4G. El botón «Ver contactos» de Teléfono de Google abrió la lista de Contactos Lite con buscador, sin el aviso anterior de que no había apps de contactos disponibles.
- Ocho pruebas de rutas UI en emulador pasaron: ambos MIME de lista sin URI, URI canónica, barra final y URI antigua; rechazo de filtro `?directory=0`, autoridad externa y `EDIT` de colección. No se modificaron datos personales.
- La sincronización y las llamadas de red reales en el Redmi siguen pendientes.

## Actualización 0.1.2: acceso general a la agenda

- Separado el acceso del lanzador del filtro `MAIN + DEFAULT + APP_CONTACTS` para responder a la apertura implícita de la agenda.
- Release de 50 588 bytes y firma verificada.
- Instalada en el Redmi. La apertura general de la agenda inicia Contactos Lite, y crear/editar/agregar a contacto resuelven a su actividad.
- Por autorización explícita del usuario se ejecutaron `pm clear --user 0 com.google.android.contacts` y `pm uninstall --user 0 com.google.android.contacts`, ambos con resultado Success. El proveedor de contactos permanece instalado; el recuento de contactos antes y después no cambió.

## Actualización 0.1.1: estética negra

- Logo vectorial negro con figura blanca; interfaz y diálogos monocromáticos, tanto con sistema claro como oscuro.
- APK: **50 564 bytes**, frente a 51 292 bytes de la versión anterior. Sin dependencias ni imágenes rasterizadas nuevas.
- Release y Android Lint correctos, firma verificada y capturas visuales revisadas en `capturas/0.1.1/`.
- Instalada como actualización en el Redmi con la misma firma. No se ejecutaron pruebas de escritura sobre su agenda.

## Validación funcional inicial: versión 0.1.0

Fecha: 8 de noviembre de 2026.

- APK release: **51 292 bytes** (51,3 KB decimales; 50,1 KiB).
- Compilación optimizada con R8 y reducción de recursos: correcta.
- Android Lint: sin incidencias.
- Firma APK: verificada. La clave privada queda en `.signing/`, fuera de los archivos para compartir.
- Permisos declarados: lectura y escritura de contactos. Sin permiso de Internet.
- Entorno: emulador aislado `ContactosLite_API35`, Android 15/API 35, Google APIs, ARM64.
- Teléfono físico: no conectado ni modificado.

## Pruebas instrumentadas: 8 de 8 correctas

1. Crear, buscar, editar conservando correo y eliminar.
2. Conservar fuentes independientes de un contacto agregado.
3. Rechazar una edición basada en una versión obsoleta.
4. Buscar sin tildes, sin distinguir mayúsculas y por un teléfono secundario con otro formato.
5. Preservar una fila de teléfono preexistente vacía y rechazar borrar un número sin quitar su fila explícitamente.
6. Añadir un teléfono conservando el original, guardar sin cambios y modificar solo el nombre.
7. Respetar una fila real del proveedor marcada como solo lectura.
8. Rechazar modificaciones de una fuente protegida.

La salida completa está en [`resultados/instrumentation.txt`](resultados/instrumentation.txt). Los datos de prueba tienen identificadores propios y se limpian al terminar.

## Integración con Teléfono de Google

En el emulador, Contactos Lite creó a «Ana Torres» con un número ficticio. Se deshabilitó temporalmente **solo** `com.google.android.contacts`, se mantuvo el proveedor del sistema y se simuló una llamada entrante con el módem virtual. **Teléfono de Google mostró «Ana Torres»**. No hubo ninguna llamada de red real. La [captura de la llamada simulada](capturas/llamada-simulada.png) documenta el resultado.

El script restaura Contactos de Google y elimina únicamente sus contactos ficticios al terminar.

## Recorrido visual del APK release: correcto

Se verificaron el permiso de lectura, la creación desde intents externos, la apertura del marcador, la identificación de la llamada virtual, añadir un número desde `INSERT_OR_EDIT`, volver al detalle actualizado, cancelar/confirmar el borrado y el modo oscuro. La salida final está en [`resultados/ui-smoke.txt`](resultados/ui-smoke.txt). Las capturas en `capturas/` contienen únicamente contactos ficticios.

## Límites de esta validación

El tamaño corresponde al archivo APK, no al total instalado ni al ahorro recuperable al retirar una app de fábrica. Se comprobó el botón «Ver contactos» en el Redmi Note 14 4G; la sincronización de la cuenta de Google y las llamadas de red reales siguen pendientes de verificación.
