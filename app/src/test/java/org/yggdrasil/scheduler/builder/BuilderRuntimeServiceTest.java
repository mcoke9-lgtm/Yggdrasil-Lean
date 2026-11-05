package org.yggdrasil.scheduler.builder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BuilderRuntimeServiceTest {
    @Test public void healthRequestReturnsPersistentIdentityAndNineRoles() throws Exception {
        ServiceController<BuilderRuntimeService> controller =
                Robolectric.buildService(BuilderRuntimeService.class).create();
        BuilderRuntimeService service = controller.get();
        IBinder binder = service.onBind(new Intent(RuntimeEnvironment.getApplication(),
                BuilderRuntimeService.class));
        Messenger host = new Messenger(binder);

        final Bundle[] received = new Bundle[1];
        Messenger client = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message message) {
                received[0] = message.getData();
            }
        });
        Message request = Message.obtain(null, BuilderRuntimeProtocol.MESSAGE_GET_HEALTH);
        request.replyTo = client;
        host.send(request);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNotNull(received[0]);
        assertEquals(BuilderRuntimeProtocol.VERSION,
                received[0].getInt(BuilderRuntimeProtocol.KEY_PROTOCOL_VERSION));
        assertEquals("READY", received[0].getString(BuilderRuntimeProtocol.KEY_STATUS));
        assertNotNull(received[0].getString(BuilderRuntimeProtocol.KEY_RUNTIME_ID));
        ArrayList<String> roles = received[0].getStringArrayList(
                BuilderRuntimeProtocol.KEY_BUILDERS);
        assertNotNull(roles);
        assertEquals(9, roles.size());
        assertTrue(roles.contains("MARI"));
        ArrayList<String> capabilities = received[0].getStringArrayList(
                BuilderRuntimeProtocol.KEY_CAPABILITIES);
        assertNotNull(capabilities);
        assertTrue(capabilities.contains("PERSISTENT_RUNTIME_IDENTITY"));
        assertTrue(capabilities.contains("LOCAL_ANDROID_IPC"));

        controller.destroy();
    }
}
