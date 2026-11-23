# zebra

Plugin de Flutter para buscar e imprimir etiquetas ZPL en impresoras Zebra por Bluetooth o red, usando el SDK Link-OS Multiplatform.

**Solo Android.**

## Requisitos

* Flutter `>= 3.44.0`, Dart `^3.12.0`.
* Android `minSdk 21`.
* Zebra Link-OS Multiplatform SDK 2.17.5585 para Android. Los jars están en `android/libs/`.

## Instalación

```yaml
dependencies:
  zebra:
    git:
      url: https://github.com/clicOH/zebra_utility.git
      ref: master
```

## Uso

```dart
import 'package:zebra/ZebraPrinter.dart';
import 'package:zebra/zebra.dart';

final printer = await Zebra().getPrinterInstance(
  onPrinterFound: (name, address, isWifi) {
    // Impresora encontrada en el descubrimiento.
  },
  onPrinterDiscoveryDone: () {},
  onDiscoveryError: (code, text) {},
  onChangePrinterStatus: (status, color) {},
  onPermissionDenied: () {},
);

printer.discoveryPrinters();

final connected = await printer.connectToPrinter('AA:BB:CC:DD:EE:FF');
if (connected) {
  await printer.setDarkness(10);
  await printer.setMediaType(EnumMediaType.Label);
  await printer.print('^XA^FO50,50^A0N,50,50^FDHola^FS^XZ');
}

await printer.disconnect();
```

`print` agrega `^PON` después de `^XA` automáticamente, y usa `^POI` si se llamó a `rotate()`.

### Impresora genérica (socket, puerto 9100)

```dart
final ok = await printer.connectToGenericPrinter('192.168.1.50');
```

### Permisos

* Android 12 o superior: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` y `ACCESS_FINE_LOCATION`.
* Android 11 o inferior: `ACCESS_COARSE_LOCATION`.

Si el usuario no concede los permisos, se llama `onPermissionDenied`. La búsqueda por red requiere que el servicio de ubicación esté activo.

## Desarrollo

### Actualizar el SDK de Zebra

1. Descargar el Link-OS Multiplatform SDK para Android desde el sitio de Zebra.
2. Copiar todos los `.jar` de la carpeta `lib` del SDK a `android/libs/`.
3. Revisar que no queden versiones viejas de las mismas librerías.

### Tests

```bash
# Dart
flutter test

# Android (desde example/android, porque el plugin necesita las clases de Flutter)
./gradlew :zebra:testDebugUnitTest
```

Changelog: ver [CHANGELOG.md](CHANGELOG.md).
