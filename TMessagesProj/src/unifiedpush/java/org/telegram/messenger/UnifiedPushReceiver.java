package org.telegram.messenger;

import androidx.annotation.NonNull;

import org.unifiedpush.android.connector.PushService;
import org.unifiedpush.android.connector.data.PushEndpoint;
import org.unifiedpush.android.connector.data.PushMessage;
import org.unifiedpush.android.connector.FailedReason;

import android.os.SystemClock;

public class UnifiedPushReceiver extends PushService {

    @Override
    public void onNewEndpoint(@NonNull PushEndpoint endpoint, @NonNull String instance) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("UnifiedPush: new endpoint = " + endpoint.getUrl());
        }
        SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();
        SharedConfig.pushStringStatus = "";
        PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_UNIFIED_PUSH, endpoint.getUrl());
    }

    @Override
    public void onRegistrationFailed(@NonNull FailedReason reason, @NonNull String instance) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("UnifiedPush: registration failed: " + reason);
        }
        SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();
        SharedConfig.pushStringStatus = "__UNIFIEDPUSH_REGISTRATION_FAILED__";
        PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_UNIFIED_PUSH, null);
    }

    @Override
    public void onUnregistered(@NonNull String instance) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("UnifiedPush: unregistered");
        }
        SharedConfig.pushString = "";
        SharedConfig.pushStringStatus = "__UNIFIEDPUSH_UNREGISTERED__";
        SharedConfig.saveConfig();
    }

    @Override
    public void onMessage(@NonNull PushMessage message, @NonNull String instance) {
        String data = new String(message.getContent());
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("UnifiedPush: message received");
        }
        PushListenerController.processRemoteMessage(PushListenerController.PUSH_TYPE_UNIFIED_PUSH, data, System.currentTimeMillis());
    }
}
