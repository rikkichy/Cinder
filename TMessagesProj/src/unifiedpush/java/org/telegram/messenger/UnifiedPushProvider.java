package org.telegram.messenger;

import android.content.Context;
import android.os.SystemClock;
import android.text.TextUtils;

import org.unifiedpush.android.connector.UnifiedPush;

import java.util.List;

public final class UnifiedPushProvider implements PushListenerController.IPushListenerServiceProvider {
    public static final UnifiedPushProvider INSTANCE = new UnifiedPushProvider();

    private UnifiedPushProvider() {}

    @Override
    public String getLogTitle() {
        return "UnifiedPush";
    }

    @Override
    public int getPushType() {
        return PushListenerController.PUSH_TYPE_UNIFIED_PUSH;
    }

    @Override
    public void onRequestPushToken() {
        Context context = ApplicationLoader.applicationContext;
        SharedConfig.pushStringGetTimeStart = SystemClock.elapsedRealtime();

        List<String> distributors = UnifiedPush.INSTANCE.getDistributors(context);
        if (distributors.isEmpty()) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("UnifiedPush: no distributor found");
            }
            SharedConfig.pushStringStatus = "__NO_UNIFIEDPUSH_DISTRIBUTOR__";
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();
            PushListenerController.sendRegistrationToServer(getPushType(), null);
            return;
        }

        String currentDistributor = UnifiedPush.INSTANCE.getAckDistributor(context);
        if (TextUtils.isEmpty(currentDistributor)) {
            UnifiedPush.INSTANCE.saveDistributor(context, distributors.get(0));
        }

        UnifiedPush.INSTANCE.register(context, null, null, null);
    }

    @Override
    public boolean hasServices() {
        try {
            List<String> distributors = UnifiedPush.INSTANCE.getDistributors(ApplicationLoader.applicationContext);
            return !distributors.isEmpty();
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }
}
