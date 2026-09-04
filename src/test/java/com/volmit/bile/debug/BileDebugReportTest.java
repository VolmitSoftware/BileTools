package com.volmit.bile.debug;

import com.volmit.bile.config.BileConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BileDebugReportTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void followsSectionedFormatAndRedactsRemoteSecrets() throws Exception {
        BileConfig config = BileConfig.defaults().toBuilder()
                .remoteSlavePayload("receiver-secret")
                .remoteMasterDeployTargets(List.of("example.org:9876:target-secret"))
                .build();
        BileDebugSnapshot snapshot = new BileDebugSnapshot(
                "Folia global and entity schedulers", "en_US", List.of("de_DE", "en_US"),
                "ready", "main", config, 4, 1, 9L, 12L,
                true, true, true, temporaryFolder.getRoot().toPath());

        String report = BileDebugReport.create(snapshot);

        assertTrue(report.contains("== BileTools services =="));
        assertTrue(report.contains("== Effective BileTools configuration =="));
        assertTrue(report.contains("== BileTools files =="));
        assertTrue(report.contains("example.org:9876:redacted"));
        assertTrue(report.contains("remote-deploy.slave.slave-payload: redacted"));
        assertFalse(report.contains("receiver-secret"));
        assertFalse(report.contains("target-secret"));
    }
}
