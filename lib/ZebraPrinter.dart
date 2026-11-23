import 'package:flutter/services.dart';

enum EnumMediaType { Label, BlackMark, Journal }

class ZebraPrinter {
  final MethodChannel channel;

  Function? onPrinterFound;
  Function? onPrinterDiscoveryDone;
  Function? onDiscoveryError;
  Function? onChangePrinterStatus;
  Function? onPermissionDenied;

  bool isRotated = false;

  ZebraPrinter(
    String id,
    this.onPrinterFound,
    this.onPrinterDiscoveryDone,
    this.onDiscoveryError,
    this.onChangePrinterStatus, {
    this.onPermissionDenied,
  }) : channel = MethodChannel('ZebraPrinterObject$id') {
    channel.setMethodCallHandler(nativeMethodCallHandler);
  }

  /// Checks permissions and starts the discovery. Results arrive through the
  /// callbacks passed to the constructor.
  void discoveryPrinters() {
    channel.invokeMethod<bool>("checkPermission").then((granted) {
      if (granted == true) {
        channel.invokeMethod("discoverPrinters");
      } else {
        onPermissionDenied?.call();
      }
    });
  }

  Future<void> _sendSettings(String command) {
    return channel.invokeMethod<void>(
        "setSettings", {"SettingCommand": command});
  }

  static String _mediaTypeCommand(EnumMediaType mediaType) {
    switch (mediaType) {
      case EnumMediaType.BlackMark:
        return '! U1 setvar "media.type" "label"\n'
            '! U1 setvar "media.sense_mode" "bar"\n';
      case EnumMediaType.Journal:
        return '! U1 setvar "media.type" "journal"\n';
      case EnumMediaType.Label:
        return '! U1 setvar "media.type" "label"\n'
            '! U1 setvar "media.sense_mode" "gap"\n';
    }
  }

  Future<void> setDarkness(int darkness) {
    return _sendSettings('! U1 setvar "print.tone" "$darkness"');
  }

  Future<void> setMediaType(EnumMediaType mediaType) {
    return _sendSettings(_mediaTypeCommand(mediaType));
  }

  Future<void> calibratePrinter() {
    return _sendSettings('~jc^xa^jus^xz');
  }

  /// Returns true when the printer accepted the connection.
  Future<bool> connectToPrinter(String address) async {
    return (await channel
            .invokeMethod<bool>("connectToPrinter", {"Address": address})) ??
        false;
  }

  /// Returns true when the generic (raw socket, port 9100) printer connected.
  Future<bool> connectToGenericPrinter(String address) async {
    return (await channel.invokeMethod<bool>(
            "connectToGenericPrinter", {"Address": address})) ??
        false;
  }

  /// Sends ZPL to the connected printer. Returns true when the data was sent.
  Future<bool> print(String data) async {
    if (!data.contains("^PON")) data = data.replaceAll("^XA", "^XA^PON");

    if (isRotated) {
      data = data.replaceAll("^PON", "^POI");
    }
    return (await channel.invokeMethod<bool>("print", {"Data": data})) ??
        false;
  }

  Future<void> disconnect() {
    return channel.invokeMethod<void>("disconnect");
  }

  /// Returns true when the printer connection is alive.
  Future<bool> isPrinterConnected() async {
    return (await channel.invokeMethod<bool>("isPrinterConnected")) ?? false;
  }

  void rotate() {
    isRotated = !isRotated;
  }

  Future<dynamic> nativeMethodCallHandler(MethodCall methodCall) async {
    final Map<dynamic, dynamic> args =
        methodCall.arguments is Map ? methodCall.arguments as Map : {};

    switch (methodCall.method) {
      case "printerFound":
        onPrinterFound?.call(
            args["Name"], args["Address"], args["IsWifi"] == true);
        break;
      case "changePrinterStatus":
        onChangePrinterStatus?.call(args["Status"], args["Color"]);
        break;
      case "onPrinterDiscoveryDone":
        onPrinterDiscoveryDone?.call();
        break;
      case "onDiscoveryError":
        onDiscoveryError?.call(args["ErrorCode"], args["ErrorText"]);
        break;
    }
    return null;
  }
}
