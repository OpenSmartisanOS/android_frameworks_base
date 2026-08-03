package org.opensmartisanos.fakecall;

oneway interface IFakeCallCallback {
    void onStateChanged(int state, long triggerAtMillis);
}
