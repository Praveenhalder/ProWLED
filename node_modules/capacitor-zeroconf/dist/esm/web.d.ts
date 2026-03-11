import { WebPlugin } from '@capacitor/core';
import type { CallbackID, ZeroConfPlugin, ZeroConfRegisterRequest, ZeroConfUnregisterRequest, ZeroConfUnwatchRequest, ZeroConfWatchCallback, ZeroConfWatchRequest } from './definitions';
export declare class ZeroConfWeb extends WebPlugin implements ZeroConfPlugin {
    getHostname(): Promise<{
        hostname: string;
    }>;
    register(_request: ZeroConfRegisterRequest): Promise<void>;
    unregister(_request: ZeroConfUnregisterRequest): Promise<void>;
    stop(): Promise<void>;
    watch(_request: ZeroConfWatchRequest, _callback: ZeroConfWatchCallback): Promise<CallbackID>;
    unwatch(_request: ZeroConfUnwatchRequest): Promise<void>;
    close(): Promise<void>;
}
