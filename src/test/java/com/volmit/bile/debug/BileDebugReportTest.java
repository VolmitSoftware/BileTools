package com.volmit.bile.debug;

import art.arcane.volmlib.util.crypto.Ed25519;
import com.volmit.bile.config.BileConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.security.PublicKey;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BileDebugReportTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void followsSectionedFormatAndRedactsRemoteDeployTrustSettings() throws Exception {
        PublicKey trusted = Ed25519.generateKeyPair().getPublic();
        String trustedText = Ed25519.encodePublicKeyText(trusted);
        BileConfig config = BileConfig.defaults().toBuilder()
                .remoteSlaveTrustedKeys(List.of(trustedText, "not-a-key"))
                .remoteSlaveAllowedAddresses(List.of("10.20.0.0/16"))
                .remoteSlaveBindAddress("10.20.0.5")
                .remoteMasterDeployTargets(List.of("receiver.example.org:9876"))
                .remoteMasterTrustedKeys(List.of(trustedText))
                .build();
        BileDebugSnapshot snapshot = new BileDebugSnapshot(
                "Folia global and entity schedulers", "en_US", List.of("de_DE", "en_US"),
                "ready", "main", config, 4, 1, 9L, 12L,
                true, true, true, temporaryFolder.getRoot().toPath());

        String report = BileDebugReport.create(snapshot);

        assertTrue(report.contains("== BileTools services =="));
        assertTrue(report.contains("== Effective BileTools configuration =="));
        assertTrue(report.contains("== BileTools files =="));
        assertTrue(report.contains("remote-deploy.slave.trusted-keys: 2 entries (redacted)"));
        assertTrue(report.contains("remote-deploy.slave.allowed-addresses: 1 entry (redacted)"));
        assertTrue(report.contains("remote-deploy.master.master-deploy-to: 1 entry (redacted)"));
        assertTrue(report.contains("remote-deploy.master.trusted-keys: 1 entry (redacted)"));
        assertFalse(report.contains(trustedText));
        assertFalse(report.contains(Ed25519.fingerprint(trusted)));
        assertFalse(report.contains("10.20."));
        assertFalse(report.contains("receiver.example.org"));
    }
}
