package com.halder.prowled;

import android.content.Intent;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.getcapacitor.PermissionState;

@CapacitorPlugin(
    name = "SoundReactive",
    permissions = {
        @Permission(alias = "microphone", strings = { android.Manifest.permission.RECORD_AUDIO }),
        @Permission(strings = { android.Manifest.permission.FOREGROUND_SERVICE })
    }
)
public class SoundReactivePlugin extends Plugin {

    // Saved call held across the permission dialog
    private PluginCall savedStartCall;

    @PluginMethod
    public void start(PluginCall call) {
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            // Save the call so we can resume after the user responds
            savedStartCall = call;
            requestPermissionForAlias("microphone", call, "micPermissionCallback");
            return;
        }
        doStart(call);
    }

    @PermissionCallback
    private void micPermissionCallback(PluginCall call) {
        if (getPermissionState("microphone") == PermissionState.GRANTED) {
            doStart(call);
        } else {
            call.reject("Microphone permission denied — cannot start Sound Reactive");
        }
    }

    private void doStart(PluginCall call) {
        String  wledIp        = call.getString("wledIp", "192.168.1.100");
        int     ledCount      = call.getInt("ledCount", 60);
        boolean useSystemAudio= Boolean.TRUE.equals(call.getBoolean("useSystemAudio", false));
        int     sensitivity   = call.getInt("sensitivity", 5);
        int     noiseFloor    = call.getInt("noiseFloor", 10);
        int     brightness    = call.getInt("brightness", 255);
        float   minLight      = call.getFloat("minLight", 1.0f);
        String  effect        = call.getString("effect", "geq");

        // Color mode fields
        String  colorMode     = call.getString("colorMode",   "single");
        int     colorR        = call.getInt("colorR", 255);
        int     colorG        = call.getInt("colorG", 0);
        int     colorB        = call.getInt("colorB", 0);
        String  comboColors   = call.getString("comboColors", "");
        int     gradR1        = call.getInt("gradR1", 255);
        int     gradG1        = call.getInt("gradG1", 0);
        int     gradB1        = call.getInt("gradB1", 0);
        int     gradR2        = call.getInt("gradR2", 0);
        int     gradG2        = call.getInt("gradG2", 0);
        int     gradB2        = call.getInt("gradB2", 255);

        Intent intent = new Intent(getContext(), SoundReactiveService.class);
        intent.setAction(SoundReactiveService.ACTION_START);
        intent.putExtra("wled_ip",      wledIp);
        intent.putExtra("led_count",    ledCount);
        intent.putExtra("sensitivity",  sensitivity);
        intent.putExtra("noise_floor",  noiseFloor);
        intent.putExtra("brightness",   brightness);
        intent.putExtra("min_light",    minLight);
        intent.putExtra("effect",       effect);
        intent.putExtra("colorMode",    colorMode);
        intent.putExtra("colorR",       colorR);
        intent.putExtra("colorG",       colorG);
        intent.putExtra("colorB",       colorB);
        intent.putExtra("comboColors",  comboColors);
        intent.putExtra("gradR1",       gradR1);
        intent.putExtra("gradG1",       gradG1);
        intent.putExtra("gradB1",       gradB1);
        intent.putExtra("gradR2",       gradR2);
        intent.putExtra("gradG2",       gradG2);
        intent.putExtra("gradB2",       gradB2);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            getContext().startForegroundService(intent);
        else
            getContext().startService(intent);

        JSObject ret = new JSObject();
        ret.put("wledIp",  wledIp);
        ret.put("effect",  effect);
        ret.put("colorMode", colorMode);
        call.resolve(ret);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Intent intent = new Intent(getContext(), SoundReactiveService.class);
        intent.setAction(SoundReactiveService.ACTION_STOP);
        getContext().startService(intent);
        call.resolve();
    }

    @PluginMethod
    public void setEffect(PluginCall call) {
        String effect     = call.getString("effect",    "geq");
        String colorMode  = call.getString("colorMode", "single");
        int    brightness = call.getInt("brightness",   255);
        float  minLight   = call.getFloat("minLight",   1.0f);
        int    sensitivity= call.getInt("sensitivity",  5);
        int    noiseFloor = call.getInt("noiseFloor",   10);
        int    colorR     = call.getInt("colorR", 255);
        int    colorG     = call.getInt("colorG", 0);
        int    colorB     = call.getInt("colorB", 0);
        String comboColors= call.getString("comboColors", "");
        int    gradR1     = call.getInt("gradR1", 255);
        int    gradG1     = call.getInt("gradG1", 0);
        int    gradB1     = call.getInt("gradB1", 0);
        int    gradR2     = call.getInt("gradR2", 0);
        int    gradG2     = call.getInt("gradG2", 0);
        int    gradB2     = call.getInt("gradB2", 255);

        Intent intent = new Intent(getContext(), SoundReactiveService.class);
        intent.setAction(SoundReactiveService.ACTION_UPDATE);
        intent.putExtra("effect",      effect);
        intent.putExtra("colorMode",   colorMode);
        intent.putExtra("brightness",  brightness);
        intent.putExtra("min_light",   minLight);
        intent.putExtra("sensitivity", sensitivity);
        intent.putExtra("noise_floor", noiseFloor);
        intent.putExtra("colorR",      colorR);
        intent.putExtra("colorG",      colorG);
        intent.putExtra("colorB",      colorB);
        intent.putExtra("comboColors", comboColors);
        intent.putExtra("gradR1",      gradR1);
        intent.putExtra("gradG1",      gradG1);
        intent.putExtra("gradB1",      gradB1);
        intent.putExtra("gradR2",      gradR2);
        intent.putExtra("gradG2",      gradG2);
        intent.putExtra("gradB2",      gradB2);

        getContext().startService(intent);
        call.resolve();
    }
}
