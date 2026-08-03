package org.opensmartisanos.fakecall;

import org.opensmartisanos.fakecall.IFakeCallCallback;

interface IFakeCallService {
    int getState();
    long getTriggerAtMillis();
    void schedule(long delayMillis, String callerName, String callerNumber);
    void cancel();
    void answer();
    void registerCallback(IFakeCallCallback callback);
    void unregisterCallback(IFakeCallCallback callback);
}
