## 0.1.0

**Cambios incompatibles**

* Solo Android. Se eliminó la plataforma iOS (`ios/`).
* `print`, `connectToPrinter`, `connectToGenericPrinter`, `isPrinterConnected`, `setDarkness`, `setMediaType` y `calibratePrinter` ahora devuelven `Future`. `print` y `connectToGenericPrinter` devuelven `Future<bool>`.
* Se eliminaron el enum `Command` y el campo `id` de `ZebraPrinter`, que no se usaban.

**Mejoras**

* Actualizado el Zebra Link-OS Multiplatform SDK a la versión 2.17.5585 (`ZSDK_ANDROID_API.jar` y dependencias). Se quitaron las versiones anteriores de las dependencias.
* `build.gradle` compatible con AGP 9 (Kotlin integrado) y `BarcodeScannerLibrary` corregido.
* Tests Dart para comandos de ajuste, ajuste de `^PON`/`^POI` y callbacks nativos.

**Correcciones**

* Todas las llamadas del canal responden al Dart (ya no quedan `Future` sin resolver).
* `isPrinterConnected` comprueba la conexión real. Antes enviaba `"Test"` a la impresora.
* `connect()` ya no marca "conectado" cuando la conexión falla.
* El contador de reintentos del descubrimiento se reinicia en cada búsqueda. Antes solo reintentaba las dos primeras veces de la vida de la app.
* El listener de permisos responde solo a su propio `requestCode`. Antes respondía a cualquier permiso de la app.
* La comprobación de permisos de ubicación coincide con la que se pide. Se revisan todos los resultados.
* `ZebraPlugin` ya no crashea si se llama `getInstance` sin Activity adjunta.
* `setDarkness` ya no falla con `null` sin avisar.
* `print` ya no usa `Looper` en un hilo sin `Looper` propio.
* Se quitaron código muerto y comentarios viejos.

## 0.0.1

* Versión inicial.
