package org.symless.synergy.data.aidl;
import org.symless.synergy.data.aidl.ConnectionState;

oneway interface IConnectionServiceCallback {

		void onStateChanged(in ConnectionState state);

		void onMessage(in Bundle bundle);
}

