# Contactos Lite

- El usuario pidió usar subagentes `gpt-6.1-terra` para este proyecto. Al delegar, selecciona ese modelo con contexto propio.
- App Android nativa en Java, sin dependencias de producción externas. Mantener pequeño el APK y medir el release después de cambios relevantes.
- La única fuente de contactos es Contacts Provider. No copiar la agenda a una base privada ni añadir permisos de red, telemetría o servicios permanentes.
- No sustituir ni borrar datos ajenos a los campos editados. Respetar fuentes agregadas, cuentas y filas de solo lectura.
- Ejecutar pruebas de escritura solo en emulador con datos sintéticos. No modificar el teléfono real ni desinstalar componentes sin una instrucción del usuario para esa acción.
- Verificar `assembleRelease`, `lint` y las pruebas funcionales afectadas. Diferenciar pruebas de emulador de compatibilidad pendiente en el Redmi.
- Conservar `.signing/`: contiene la clave privada local para actualizar el APK. No publicar ni adjuntar esa carpeta.
