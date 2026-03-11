package com.halder.prowled;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "WledDiscovery")
public class WledDiscoveryPlugin extends Plugin {

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private WifiManager.MulticastLock multicastLock;
    private final ConcurrentHashMap<String, Boolean> foundIps = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(20);

    @PluginMethod
    public void startScan(PluginCall call) {
        call.setKeepAlive(true);
        foundIps.clear();

        // Acquire multicast lock — required for mDNS on Android
        WifiManager wifi = (WifiManager) getContext().getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("WledDiscovery");
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();
        }

        nsdManager = (NsdManager) getContext().getSystemService(Context.NSD_SERVICE);

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                releaseMulticastLock();
                call.reject("Discovery start failed: " + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {}

            @Override
            public void onDiscoveryStarted(String serviceType) {
                JSObject status = new JSObject();
                status.put("status", "started");
                notifyListeners("scanStatus", status);
                call.resolve(new JSObject().put("started", true));
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                JSObject status = new JSObject();
                status.put("status", "stopped");
                notifyListeners("scanStatus", status);
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                        // Service disappeared — ignore
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo info) {
                        if (info.getHost() == null) return;
                        final String ip = info.getHost().getHostAddress();
                        if (ip == null || ip.equals("0.0.0.0")) return;
                        if (foundIps.putIfAbsent(ip, true) != null) return; // already seen

                        final String serviceName = info.getServiceName();

                        // Verify it's a WLED device — same as official DeviceDiscovery.cs
                        executor.submit(() -> {
                            try {
                                URL url = new URL("http://" + ip + "/json/info");
                                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                                conn.setConnectTimeout(2000);
                                conn.setReadTimeout(2000);
                                conn.setRequestMethod("GET");

                                if (conn.getResponseCode() == 200) {
                                    BufferedReader reader = new BufferedReader(
                                            new InputStreamReader(conn.getInputStream()));
                                    StringBuilder sb = new StringBuilder();
                                    String line;
                                    while ((line = reader.readLine()) != null) sb.append(line);
                                    reader.close();
                                    conn.disconnect();

                                    String body = sb.toString();
                                    if (body.contains("\"ver\"") || body.contains("WLED")) {
                                        JSObject device = new JSObject();
                                        device.put("ip", ip);
                                        device.put("name", serviceName != null ? serviceName : "WLED @ " + ip);
                                        device.put("info", body); // raw JSON — parsed in JS
                                        notifyListeners("deviceFound", device);
                                    }
                                } else {
                                    conn.disconnect();
                                }
                            } catch (Exception e) {
                                // Not reachable or not WLED — ignore
                            }
                        });
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {}
        };

        try {
            nsdManager.discoverServices("_http._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            releaseMulticastLock();
            call.reject("Failed to start NSD: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopScan(PluginCall call) {
        stopDiscovery();
        call.resolve();
    }

    @PluginMethod
    public void getLocalIp(PluginCall call) {
        try {
            WifiManager wifi = (WifiManager) getContext().getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            int ipInt = wifi.getConnectionInfo().getIpAddress();
            String ip = String.format("%d.%d.%d.%d",
                    (ipInt & 0xff),
                    (ipInt >> 8 & 0xff),
                    (ipInt >> 16 & 0xff),
                    (ipInt >> 24 & 0xff));
            call.resolve(new JSObject().put("ip", ip));
        } catch (Exception e) {
            call.resolve(new JSObject().put("ip", ""));
        }
    }

    private void stopDiscovery() {
        try {
            if (discoveryListener != null && nsdManager != null) {
                nsdManager.stopServiceDiscovery(discoveryListener);
            }
        } catch (Exception e) { /* ignore */ }
        discoveryListener = null;
        releaseMulticastLock();
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Exception e) { /* ignore */ }
        multicastLock = null;
    }

    @Override
    protected void handleOnDestroy() {
        stopDiscovery();
        executor.shutdownNow();
        super.handleOnDestroy();
    }
}
