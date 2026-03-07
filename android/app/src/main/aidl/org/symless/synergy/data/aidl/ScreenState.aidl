package org.symless.synergy.data.aidl;
import org.symless.synergy.data.aidl.ServerState;

parcelable ScreenState {

	String name;
	ServerState server;

	boolean isActive;
	int width;
	int height;
	boolean disconnectOnScreenOff;
}
