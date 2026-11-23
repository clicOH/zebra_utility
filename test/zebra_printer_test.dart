import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:zebra/ZebraPrinter.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('ZebraPrinterObject1');
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  late List<MethodCall> calls;
  late bool printResult;
  late bool connectedResult;
  late ZebraPrinter printer;

  setUp(() {
    calls = [];
    printResult = true;
    connectedResult = true;
    messenger.setMockMethodCallHandler(channel, (MethodCall call) async {
      calls.add(call);
      switch (call.method) {
        case 'print':
          return printResult;
        case 'isPrinterConnected':
          return connectedResult;
        default:
          return null;
      }
    });
    printer = ZebraPrinter('1', null, null, null, null);
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  group('settings', () {
    test('setDarkness sends print.tone', () async {
      await printer.setDarkness(10);

      expect(calls.single.method, 'setSettings');
      expect(calls.single.arguments,
          {'SettingCommand': '! U1 setvar "print.tone" "10"'});
    });

    test('calibratePrinter sends calibration command', () async {
      await printer.calibratePrinter();

      expect(calls.single.arguments, {'SettingCommand': '~jc^xa^jus^xz'});
    });

    test('setMediaType BlackMark uses bar sense mode', () async {
      await printer.setMediaType(EnumMediaType.BlackMark);

      expect(
        calls.single.arguments,
        {
          'SettingCommand': '! U1 setvar "media.type" "label"\n'
              '! U1 setvar "media.sense_mode" "bar"\n'
        },
      );
    });

    test('setMediaType Label uses gap sense mode', () async {
      await printer.setMediaType(EnumMediaType.Label);

      expect(
        calls.single.arguments,
        {
          'SettingCommand': '! U1 setvar "media.type" "label"\n'
              '! U1 setvar "media.sense_mode" "gap"\n'
        },
      );
    });

    test('setMediaType Journal sets journal type', () async {
      await printer.setMediaType(EnumMediaType.Journal);

      expect(
        calls.single.arguments,
        {'SettingCommand': '! U1 setvar "media.type" "journal"\n'},
      );
    });
  });

  group('print', () {
    test('adds ^PON after ^XA', () async {
      final sent = await printer.print('^XA^FDHola^FS^XZ');

      expect(sent, isTrue);
      expect(calls.single.arguments, {'Data': '^XA^PON^FDHola^FS^XZ'});
    });

    test('does not duplicate ^PON', () async {
      await printer.print('^XA^PON^FDHola^FS^XZ');

      expect(calls.single.arguments, {'Data': '^XA^PON^FDHola^FS^XZ'});
    });

    test('uses ^POI when the label is rotated', () async {
      printer.rotate();
      await printer.print('^XA^FD1^FS^XZ');

      expect(calls.single.arguments, {'Data': '^XA^POI^FD1^FS^XZ'});
    });

    test('rotate twice goes back to ^PON', () async {
      printer.rotate();
      printer.rotate();
      await printer.print('^XA^FD1^FS^XZ');

      expect(printer.isRotated, isFalse);
      expect(calls.single.arguments, {'Data': '^XA^PON^FD1^FS^XZ'});
    });

    test('returns false when native reports it was not sent', () async {
      printResult = false;

      expect(await printer.print('^XA^XZ'), isFalse);
    });
  });

  group('connection', () {
    test('isPrinterConnected returns the native state', () async {
      connectedResult = false;

      expect(await printer.isPrinterConnected(), isFalse);
      expect(calls.single.method, 'isPrinterConnected');
    });

    test('isPrinterConnected returns false when native returns null', () async {
      messenger.setMockMethodCallHandler(channel, (_) async => null);

      expect(await printer.isPrinterConnected(), isFalse);
    });
  });

  group('native callbacks', () {
    test('printerFound passes name, address and wifi flag', () async {
      String? name;
      String? address;
      bool? isWifi;
      final withCallback = ZebraPrinter('1', (String n, String a, bool w) {
        name = n;
        address = a;
        isWifi = w;
      }, null, null, null);

      await withCallback.nativeMethodCallHandler(const MethodCall(
        'printerFound',
        {'Name': 'ZD421', 'Address': 'AA:BB:CC:DD:EE:FF', 'IsWifi': false},
      ));

      expect(name, 'ZD421');
      expect(address, 'AA:BB:CC:DD:EE:FF');
      expect(isWifi, isFalse);
    });

    test('missing callbacks do not throw', () async {
      await printer.nativeMethodCallHandler(
          const MethodCall('onPrinterDiscoveryDone', 'Done'));
      await printer.nativeMethodCallHandler(const MethodCall(
          'onDiscoveryError', {'ErrorCode': -1, 'ErrorText': 'x'}));
      await printer.nativeMethodCallHandler(const MethodCall(
          'changePrinterStatus', {'Status': 'Connected', 'Color': 'green'}));
    });
  });
}
