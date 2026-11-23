package com.clicoh.zebra

import com.clicoh.zebra.plugin.Printer
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/** ZebraPlugin */
class ZebraPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    /// The MethodChannel that handles the communication between Flutter and native Android.
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity.
    private lateinit var channel: MethodChannel
    private lateinit var flutterPluginBinding: FlutterPlugin.FlutterPluginBinding

    /// Null while the plugin is not attached to an Activity (e.g. during a configuration change).
    private var activityBinding: ActivityPluginBinding? = null
    private val printers = ArrayList<Printer>()

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        this.flutterPluginBinding = flutterPluginBinding
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "zebra")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getPlatformVersion" -> result.success("Android ${android.os.Build.VERSION.RELEASE}")
            "getInstance" -> {
                val binding = activityBinding
                if (binding == null) {
                    result.error("no_activity", "The Zebra plugin is not attached to an Activity", null)
                    return
                }
                val printer = Printer(binding, flutterPluginBinding.binaryMessenger)
                printers.add(printer)
                result.success(printer.toString())
            }
            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        printers.clear()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.activityBinding = binding
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activityBinding = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        this.activityBinding = binding
    }

    override fun onDetachedFromActivity() {
        activityBinding = null
    }
}
