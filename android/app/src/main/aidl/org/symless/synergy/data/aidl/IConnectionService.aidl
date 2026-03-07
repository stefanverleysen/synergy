package org.symless.synergy.data.aidl;

import org.symless.synergy.data.aidl.ConnectionState;
import org.symless.synergy.data.aidl.ScreenState;
import org.symless.synergy.data.aidl.IConnectionServiceCallback;
import org.symless.synergy.data.aidl.Result;
interface IConnectionService {

		void registerCallback(IConnectionServiceCallback callback);
		void unregisterCallback(IConnectionServiceCallback callback);

        Result setLogForwardingLevel(in String levelName);

        Result setCaptureKeyMode(in boolean enabled);

        Result setEnabled(in boolean enabled);

        Result updateScreenState(in ScreenState screenState);

        Result updateScreenDimensions(int width, int height);

        Result setClipboardData(in Bundle bundle);

        Result regenerateClientCertificate();

        ConnectionState getState();
}
