package org.telegram.messenger;

public class PushProviderDelegate {
    public static PushListenerController.IPushListenerServiceProvider getProvider() {
        return GooglePushListenerServiceProvider.INSTANCE;
    }
}
