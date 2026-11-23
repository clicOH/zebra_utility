package com.clicoh.zebra.plugin;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import androidx.annotation.NonNull;

import com.clicoh.zebra.R;
import com.zebra.sdk.comm.BluetoothConnection;
import com.zebra.sdk.comm.Connection;
import com.zebra.sdk.comm.ConnectionException;
import com.zebra.sdk.comm.TcpConnection;
import com.zebra.sdk.printer.ZebraPrinter;
import com.zebra.sdk.printer.ZebraPrinterFactory;
import com.zebra.sdk.printer.ZebraPrinterLanguageUnknownException;
import com.zebra.sdk.printer.discovery.DiscoveredPrinter;
import com.zebra.sdk.printer.discovery.DiscoveryHandler;
import com.zebra.sdk.printer.discovery.NetworkDiscoverer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;

public class Printer implements MethodChannel.MethodCallHandler {

    private static final int PERMISSIONS_REQUEST_CODE = 100020;
    private static final int ACCESS_COARSE_LOCATION_REQUEST_CODE = 10021;

    private static final int ON_DISCOVERY_ERROR_GENERAL = -1;
    private static final int ON_DISCOVERY_ERROR_BLUETOOTH = -2;
    private static final int ON_DISCOVERY_ERROR_LOCATION = -3;

    // Discovered printers are static on purpose: a new Dart ZebraPrinter instance
    // replays them after a discovery. countDiscovery/countEndScan are reset on each
    // user-initiated discovery (see discoveryPrinters).
    private static final ArrayList<DiscoveredPrinter> discoveredPrinters = new ArrayList<>();
    private static final ArrayList<DiscoveredPrinter> sendedDiscoveredPrinters = new ArrayList<>();
    private static int countDiscovery = 0;
    private static int countEndScan = 0;

    private Connection printerConnection;
    private ZebraPrinter printer;
    private final Context context;
    private final ActivityPluginBinding binding;
    private final MethodChannel methodChannel;
    private String selectedAddress = null;
    private String macAddress = null;
    private boolean isZebraPrinter = true;
    private Socketmanager socketmanager;
    private PluginRegistry.RequestPermissionsResultListener pendingPermissionListener;

    public Printer(ActivityPluginBinding binding, BinaryMessenger binaryMessenger) {
        this.context = binding.getActivity();
        this.binding = binding;
        this.methodChannel = new MethodChannel(binaryMessenger, "ZebraPrinterObject" + this.toString());
        methodChannel.setMethodCallHandler(this);
    }

    /** Starts a new user-initiated discovery (Bluetooth and network). */
    public static void discoveryPrinters(final Context context, final MethodChannel methodChannel) {
        countDiscovery = 0;
        startDiscovery(context, methodChannel);
    }

    private static void startDiscovery(final Context context, final MethodChannel methodChannel) {
        try {
            sendedDiscoveredPrinters.clear();
            for (DiscoveredPrinter dp : discoveredPrinters) {
                addNewDiscoverPrinter(dp, context, methodChannel);
            }
            countEndScan = 0;
            BluetoothDiscoverer.findPrinters(context, new DiscoveryHandler() {
                @Override
                public void foundPrinter(final DiscoveredPrinter discoveredPrinter) {
                    addNewDiscoverPrinter(discoveredPrinter, context, methodChannel);
                }

                @Override
                public void discoveryFinished() {
                    countEndScan++;
                    finishScanPrinter(context, methodChannel);
                }

                @Override
                public void discoveryError(String s) {
                    if (s.contains("Bluetooth radio is currently disabled"))
                        onDiscoveryError(context, methodChannel, ON_DISCOVERY_ERROR_BLUETOOTH, s);
                    else
                        onDiscoveryError(context, methodChannel, ON_DISCOVERY_ERROR_GENERAL, s);
                    countEndScan++;
                    finishScanPrinter(context, methodChannel);
                }
            });

            NetworkDiscoverer.findPrinters(new DiscoveryHandler() {
                @Override
                public void foundPrinter(DiscoveredPrinter discoveredPrinter) {
                    addNewDiscoverPrinter(discoveredPrinter, context, methodChannel);
                }

                @Override
                public void discoveryFinished() {
                    countEndScan++;
                    finishScanPrinter(context, methodChannel);
                }

                @Override
                public void discoveryError(String s) {
                    onDiscoveryError(context, methodChannel, ON_DISCOVERY_ERROR_GENERAL, s);
                    countEndScan++;
                    finishScanPrinter(context, methodChannel);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void onDiscoveryError(Context context, final MethodChannel methodChannel, final int errorCode, final String errorText) {
        ((Activity) context).runOnUiThread(() -> {
            HashMap<String, Object> arguments = new HashMap<>();
            arguments.put("ErrorCode", errorCode);
            arguments.put("ErrorText", errorText);
            methodChannel.invokeMethod("onDiscoveryError", arguments);
        });
    }

    private void checkPermission(final MethodChannel.Result result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            result.success(true);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissionsIfMissing(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, PERMISSIONS_REQUEST_CODE, result);
        } else {
            requestPermissionsIfMissing(new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, ACCESS_COARSE_LOCATION_REQUEST_CODE, result);
        }
    }

    private void requestPermissionsIfMissing(final String[] permissions, final int requestCode, final MethodChannel.Result result) {
        for (String permission : permissions) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                awaitPermissionResult(requestCode, result);
                ((Activity) context).requestPermissions(permissions, requestCode);
                return;
            }
        }
        result.success(true);
    }

    /** Listens only for the given request code and replies once with whether all were granted. */
    private void awaitPermissionResult(final int requestCode, final MethodChannel.Result result) {
        if (pendingPermissionListener != null) {
            binding.removeRequestPermissionsResultListener(pendingPermissionListener);
        }
        pendingPermissionListener = new PluginRegistry.RequestPermissionsResultListener() {
            @Override
            public boolean onRequestPermissionsResult(int code, String[] permissions, int[] grantResults) {
                if (code != requestCode) return false;
                final PluginRegistry.RequestPermissionsResultListener self = this;
                pendingPermissionListener = null;
                // Flutter iterates its listener set while dispatching this callback, so
                // removing the listener here throws ConcurrentModificationException.
                // Defer the removal until the dispatch loop has finished.
                new Handler(Looper.getMainLooper()).post(
                        () -> binding.removeRequestPermissionsResultListener(self));
                result.success(allGranted(grantResults));
                return true;
            }
        };
        binding.addRequestPermissionsResultListener(pendingPermissionListener);
    }

    private static boolean allGranted(int[] grantResults) {
        if (grantResults == null || grantResults.length == 0) return false;
        for (int grant : grantResults) {
            if (grant != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private static void addPrinterToDiscoveryPrinterList(DiscoveredPrinter discoveredPrinter) {
        for (DiscoveredPrinter dp : discoveredPrinters) {
            if (dp.address.equals(discoveredPrinter.address))
                return;
        }
        discoveredPrinters.add(discoveredPrinter);
    }

    private static void addNewDiscoverPrinter(final DiscoveredPrinter discoveredPrinter, Context context, final MethodChannel methodChannel) {
        addPrinterToDiscoveryPrinterList(discoveredPrinter);
        ((Activity) context).runOnUiThread(() -> {
            for (DiscoveredPrinter dp : sendedDiscoveredPrinters) {
                if (dp.address.equals(discoveredPrinter.address))
                    return;
            }
            sendedDiscoveredPrinters.add(discoveredPrinter);
            HashMap<String, Object> arguments = new HashMap<>();

            arguments.put("Address", discoveredPrinter.address);
            if (discoveredPrinter.getDiscoveryDataMap().get("SYSTEM_NAME") != null) {
                arguments.put("Name", discoveredPrinter.getDiscoveryDataMap().get("SYSTEM_NAME"));
                arguments.put("IsWifi", true);
            } else {
                arguments.put("Name", discoveredPrinter.getDiscoveryDataMap().get("FRIENDLY_NAME"));
                arguments.put("IsWifi", false);
            }
            methodChannel.invokeMethod("printerFound", arguments);
        });
    }

    private static void finishScanPrinter(final Context context, final MethodChannel methodChannel) {
        if (countEndScan == 2) {
            if (discoveredPrinters.size() == 0) {
                if (discoveryPrintersAgain(context, methodChannel))
                    return;
            }
            ((Activity) context).runOnUiThread(() ->
                    methodChannel.invokeMethod("onPrinterDiscoveryDone",
                            context.getResources().getString(R.string.done)));
        }
    }

    /** Retries the discovery once when nothing was found. */
    private static boolean discoveryPrintersAgain(Context context, MethodChannel methodChannel) {
        countDiscovery++;
        if (countDiscovery < 2) {
            startDiscovery(context, methodChannel);
            return true;
        }
        return false;
    }

    public void print(final String data, final MethodChannel.Result result) {
        new Thread(() -> {
            final boolean sent;
            try {
                sent = doConnectionTest(data);
            } catch (Exception e) {
                e.printStackTrace();
                ((Activity) context).runOnUiThread(() -> result.error("print_failed", e.getMessage(), null));
                return;
            }
            ((Activity) context).runOnUiThread(() -> result.success(sent));
        }).start();
    }

    private boolean doConnectionTest(String data) {
        if (!isZebraPrinter) {
            return printDataGenericPrinter(data);
        }
        if (printer == null) {
            disconnect();
            return false;
        }
        return printData(data);
    }

    private boolean printDataGenericPrinter(String data) {
        if (socketmanager == null) {
            setStatus(context.getString(R.string.disconnect), context.getString(R.string.disconnectColor));
            return false;
        }
        setStatus(context.getString(R.string.sending_data), context.getString(R.string.connectingColor));
        socketmanager.threadconnectwrite(convertDataToByte(data));
        sleep(100);

        byte[] sendCut = {0x0a, 0x0a, 0x1d, 0x56, 0x01};
        socketmanager.threadconnectwrite(sendCut);
        sleep(100);

        boolean sent = socketmanager.getIstate();
        if (sent) {
            setStatus(context.getString(R.string.done), context.getString(R.string.connectedColor));
        } else {
            setStatus(context.getString(R.string.disconnect), context.getString(R.string.disconnectColor));
        }
        return sent;
    }

    private boolean printData(String data) {
        try {
            byte[] bytes = convertDataToByte(data);
            setStatus(context.getString(R.string.sending_data), context.getString(R.string.connectingColor));
            printerConnection.write(bytes);
            DemoSleeper.sleep(1500);

            if (printerConnection instanceof BluetoothConnection) {
                DemoSleeper.sleep(500);
            }
            setStatus(context.getString(R.string.done), context.getString(R.string.connectedColor));
            return true;
        } catch (ConnectionException e) {
            disconnect();
            return false;
        }
    }

    public boolean connectToSelectPrinter(String address) {
        try {
            if (!isZebraPrinter && isConnectionAlive()) {
                disconnect(); // close the previous generic (socket) connection
            }
            isZebraPrinter = true;
            setStatus(context.getString(R.string.connecting), context.getString(R.string.connectingColor));
            selectedAddress = null;
            macAddress = null;
            boolean isBluetoothPrinter;
            if (address.contains(":")) {
                macAddress = address;
                isBluetoothPrinter = true;
            } else {
                selectedAddress = address;
                isBluetoothPrinter = false;
            }
            printer = connect(isBluetoothPrinter);
            return printer != null;
        } catch (Exception ex) {
            ex.printStackTrace();
            return false;
        }
    }

    /** True when the current connection (Zebra SDK or generic socket) is open. No data is sent. */
    private boolean isConnectionAlive() {
        if (isZebraPrinter) {
            return printerConnection != null && printerConnection.isConnected();
        }
        return socketmanager != null && socketmanager.getIstate();
    }

    /** Updates the status shown in Dart and returns whether the printer is connected. */
    private boolean refreshConnectionStatus() {
        boolean connected = isConnectionAlive();
        if (connected) {
            setStatus(context.getString(R.string.connected), context.getString(R.string.connectedColor));
        } else {
            setStatus(context.getString(R.string.disconnect), context.getString(R.string.disconnectColor));
        }
        return connected;
    }

    public boolean connectToGenericPrinter(String ipAddress) {
        this.isZebraPrinter = false;
        if (isConnectionAlive()) {
            disconnect();
            setStatus(context.getString(R.string.connecting), context.getString(R.string.connectingColor));
        }
        if (socketmanager == null)
            socketmanager = new Socketmanager(context);
        socketmanager.mPort = getGenericPortNumber();
        socketmanager.mstrIp = ipAddress;
        socketmanager.threadconnect();
        sleep(100);

        boolean connected = socketmanager.getIstate();
        if (connected) {
            setStatus(context.getString(R.string.connected), context.getString(R.string.connectedColor));
        } else {
            setStatus(context.getString(R.string.disconnect), context.getString(R.string.disconnectColor));
        }
        return connected;
    }

    /** Opens the Zebra SDK connection. Returns null (and reports disconnected) when it fails. */
    public ZebraPrinter connect(boolean isBluetoothPrinter) {
        if (isConnectionAlive()) {
            disconnect();
            setStatus(context.getString(R.string.connecting), context.getString(R.string.connectingColor));
        }

        printerConnection = null;
        if (isBluetoothPrinter) {
            printerConnection = new BluetoothConnection(getMacAddress());
        } else {
            try {
                printerConnection = new TcpConnection(getTcpAddress(), getTcpPortNumber());
            } catch (NumberFormatException e) {
                setStatus("Port Invalid", context.getString(R.string.disconnectColor));
                return null;
            }
        }

        try {
            printerConnection.open();
        } catch (ConnectionException e) {
            DemoSleeper.sleep(1000);
            disconnect();
            return null;
        }

        ZebraPrinter zebraPrinter = null;
        try {
            zebraPrinter = ZebraPrinterFactory.getInstance(printerConnection);
        } catch (ConnectionException e) {
            DemoSleeper.sleep(1000);
            disconnect();
            return null;
        } catch (ZebraPrinterLanguageUnknownException e) {
            DemoSleeper.sleep(1000);
            disconnect();
            return null;
        }

        setStatus(context.getString(R.string.connected), context.getString(R.string.connectedColor));
        return zebraPrinter;
    }

    private String getMacAddress() {
        return macAddress;
    }

    private String getTcpAddress() {
        return selectedAddress;
    }

    public void disconnect() {
        if (isZebraPrinter) {
            try {
                setStatus(context.getString(R.string.disconnecting), context.getString(R.string.connectingColor));
                if (printerConnection != null) {
                    printerConnection.close();
                }
            } catch (ConnectionException e) {
                e.printStackTrace();
            } finally {
                printer = null;
                setStatus(context.getString(R.string.disconnect), context.getString(R.string.disconnectColor));
            }
        } else {
            setStatus(context.getString(R.string.disconnecting), context.getString(R.string.connectingColor));
            if (socketmanager != null) {
                socketmanager.close();
            }
            setStatus(context.getString(R.string.disconnect), context.getString(R.string.disconnectColor));
        }
    }

    private void setStatus(final String message, final String color) {
        ((Activity) context).runOnUiThread(() -> {
            HashMap<String, Object> arguments = new HashMap<>();
            arguments.put("Status", message);
            arguments.put("Color", color + "");
            methodChannel.invokeMethod("changePrinterStatus", arguments);
        });
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int getTcpPortNumber() {
        return 6101;
    }

    private int getGenericPortNumber() {
        return 9100;
    }

    private byte[] convertDataToByte(String data) {
        return data.getBytes();
    }

    public static String getZplCode(Bitmap bitmap, Boolean addHeaderFooter, int rotation) {
        ZPLConverter zp = new ZPLConverter();
        zp.setCompressHex(true);
        zp.setBlacknessLimitPercentage(50);
        Bitmap grayBitmap = toGrayScale(bitmap, rotation);
        return zp.convertFromImage(grayBitmap, addHeaderFooter);
    }

    public static Bitmap toGrayScale(Bitmap bmpOriginal, int rotation) {
        int width, height;
        bmpOriginal = rotateBitmap(bmpOriginal, rotation);
        height = bmpOriginal.getHeight();
        width = bmpOriginal.getWidth();
        Bitmap grayScale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        grayScale.eraseColor(Color.WHITE);
        Canvas c = new Canvas(grayScale);
        Paint paint = new Paint();
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0);
        ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);
        paint.setColorFilter(f);
        c.drawBitmap(bmpOriginal, 0, 0, paint);
        return grayScale;
    }

    public static Bitmap rotateBitmap(Bitmap source, float angle) {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    public void setSettings(String settings, final MethodChannel.Result result) {
        print(settings, result);
    }

    public void setDarkness(int darkness, final MethodChannel.Result result) {
        String setting = "! U1 setvar \"print.tone\" \"" + darkness + "\"\n";
        setSettings(setting, result);
    }

    public void setMediaType(String mediaType, final MethodChannel.Result result) {
        String settings;
        if (mediaType.equals("Label")) {
            settings = "! U1 setvar \"media.type\" \"label\"\n" +
                    "! U1 setvar \"media.sense_mode\" \"gap\"\n" +
                    "~jc^xa^jus^xz";
        } else if (mediaType.equals("BlackMark")) {
            settings = "! U1 setvar \"media.type\" \"label\"\n" +
                    "! U1 setvar \"media.sense_mode\" \"bar\"\n" +
                    "~jc^xa^jus^xz";
        } else {
            settings = "! U1 setvar \"print.tone\" \"0\"\n" +
                    "! U1 setvar \"media.type\" \"journal\"\n";
        }
        setSettings(settings, result);
    }

    private void convertBase64ImageToZPLString(String data, int rotation, MethodChannel.Result result) {
        try {
            byte[] decodedString = Base64.decode(data, Base64.DEFAULT);
            Bitmap decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
            result.success(Printer.getZplCode(decodedByte, false, rotation));
        } catch (Exception e) {
            result.error("-1", "Error", null);
        }
    }

    @Override
    public void onMethodCall(@NonNull final MethodCall call, @NonNull final MethodChannel.Result result) {
        try {
            switch (call.method) {
                case "print": {
                    String data = call.argument("Data");
                    if (data == null) {
                        result.error("invalid_argument", "Data is required", null);
                        break;
                    }
                    print(data, result);
                    break;
                }
                case "checkPermission":
                    checkPermission(result);
                    break;
                case "convertBase64ImageToZPLString":
                    convertBase64ImageToZPLString(call.argument("data"),
                            Integer.parseInt(Objects.requireNonNull(call.argument("rotation")).toString()), result);
                    break;
                case "disconnect":
                    new Thread(() -> {
                        try {
                            disconnect();
                            ((Activity) context).runOnUiThread(() -> result.success(null));
                        } catch (Exception ex) {
                            ex.printStackTrace();
                            ((Activity) context).runOnUiThread(() -> result.error("disconnect_failed", ex.getMessage(), null));
                        }
                    }).start();
                    break;
                case "isPrinterConnected":
                    result.success(refreshConnectionStatus());
                    break;
                case "discoverPrinters":
                    if (checkIsLocationNetworkProviderIsOn()) {
                        discoveryPrinters(context, methodChannel);
                    } else {
                        onDiscoveryError(context, methodChannel, ON_DISCOVERY_ERROR_LOCATION, "Your location service is off.");
                    }
                    result.success(null);
                    break;
                case "setMediaType": {
                    String mediaType = call.argument("MediaType");
                    if (mediaType == null) {
                        result.error("invalid_argument", "MediaType is required", null);
                        break;
                    }
                    setMediaType(mediaType, result);
                    break;
                }
                case "setSettings": {
                    String settingCommand = call.argument("SettingCommand");
                    if (settingCommand == null) {
                        result.error("invalid_argument", "SettingCommand is required", null);
                        break;
                    }
                    setSettings(settingCommand, result);
                    break;
                }
                case "setDarkness": {
                    Integer darkness = call.argument("Darkness");
                    if (darkness == null) {
                        result.error("invalid_argument", "Darkness is required", null);
                        break;
                    }
                    setDarkness(darkness, result);
                    break;
                }
                case "connectToPrinter": {
                    final String address = call.argument("Address");
                    new Thread(() -> {
                        final boolean connected = address != null && connectToSelectPrinter(address);
                        ((Activity) context).runOnUiThread(() -> result.success(connected));
                    }).start();
                    break;
                }
                case "connectToGenericPrinter": {
                    String address = call.argument("Address");
                    if (address == null) {
                        result.error("invalid_argument", "Address is required", null);
                        break;
                    }
                    result.success(connectToGenericPrinter(address));
                    break;
                }
                default:
                    result.notImplemented();
                    break;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            result.error("native_error", ex.getMessage(), null);
        }
    }

    private boolean checkIsLocationNetworkProviderIsOn() {
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        try {
            return lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ex) {
            return false;
        }
    }
}
