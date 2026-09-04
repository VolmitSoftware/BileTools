package com.volmit.bile.config;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BileConfigEditorContractTest {
    @Test
    public void exposesEveryCanonicalSettingAndKeepsSecretsFileOnly() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/volmit/bile/config/BileConfigEditor.java")).replace("\r\n", "\n");
        for (String path : new String[]{
                "language", "archive-plugins", "metrics",
                "remote-deploy.slave.slave-enabled", "remote-deploy.slave.slave-port",
                "remote-deploy.slave.slave-payload", "remote-deploy.master.master-enabled",
                "remote-deploy.master.master-deploy-to", "remote-deploy.master.master-deploy-signatures",
                "remote-deploy.socket-timeout-ms", "remote-deploy.max-transfer-bytes",
                "watcher.idle-poll-ticks", "watcher.active-poll-ticks",
                "watcher.fingerprint-debounce-ticks", "watcher.ignore", "watcher.only",
                "observability.log-timings", "lifecycle.health-check"
        }) {
            assertTrue(path, source.contains("\"" + path + "\""));
        }
        assertTrue(source.contains("SETTING_SLAVE_PAYLOAD,\n                        Material.TRIPWIRE_HOOK, SettingKind.FILE_ONLY"));
        assertTrue(source.contains("SETTING_MASTER_TARGETS,\n                        Material.TRIPWIRE_HOOK, SettingKind.FILE_ONLY"));
        assertFalse(source.contains("getRemoteSlavePayload()"));
        assertFalse(source.contains("String.join(\", \", config.getRemoteMasterDeployTargets())"));
    }

    @Test
    public void centersCategorySettingsAndUsesConciseSaveFeedback() throws Exception {
        assertEquals(List.of(22), BileConfigEditor.settingSlots(1));
        assertEquals(List.of(21, 22), BileConfigEditor.settingSlots(2));
        assertEquals(List.of(21, 22, 23), BileConfigEditor.settingSlots(3));
        assertEquals(List.of(20, 21, 22, 23, 24), BileConfigEditor.settingSlots(5));
        assertEquals(2, BileConfigEditor.settingSlots(5).indexOf(22));
        assertEquals(-1, BileConfigEditor.settingSlots(5).indexOf(0));
        for (int slot : BileConfigEditor.settingSlots(35)) {
            assertTrue(slot >= 0 && slot < 45);
        }

        String source = Files.readString(Path.of(
                "src/main/java/com/volmit/bile/config/BileConfigEditor.java")).replace("\r\n", "\n");
        assertTrue(source.contains("sendSingleLine(player, BileMessages.CHANGE_SAVED"));
        assertTrue(source.indexOf(".untrusted(\"new\"") < source.indexOf(".untrusted(\"old\""));
        assertTrue(source.contains("send(player, BileMessages.CONFIG_SAVE_FAILED"));
        assertFalse(source.contains("DirectorMiniMenu"));
        assertFalse(source.contains("showResult("));
    }
}
