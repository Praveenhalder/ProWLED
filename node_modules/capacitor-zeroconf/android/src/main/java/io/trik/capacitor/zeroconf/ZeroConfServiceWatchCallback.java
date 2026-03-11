package io.trik.capacitor.zeroconf;

import javax.jmdns.ServiceInfo;

public interface ZeroConfServiceWatchCallback {
    String ADDED = "added";
    String REMOVED = "removed";
    String RESOLVED = "resolved";

    void serviceBrowserEvent(String action, ServiceInfo service);
}
