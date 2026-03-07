package org.symless.synergy.data.aidl;
import org.symless.synergy.data.aidl.ScreenState;

parcelable ConnectionState {
	boolean isEnabled;
	boolean isConnected;
	boolean ackReceived;
    long connectionTimestamp;
    ScreenState screen;

    boolean isCaptureKeyModeEnabled;

}
