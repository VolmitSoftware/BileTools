package com.volmit.bile.paper;

import org.bukkit.plugin.InvalidPluginException;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class NativePaperContractTest {
    @Test
    public void nativeLoadingRequiresExplicitBooleanConsent() throws Exception {
        NativePaperContract contract = NativePaperContract.parse("name: Example\n");
        assertFalse(contract.requested());
        assertThrows(InvalidPluginException.class, contract::validate);
        assertThrows(InvalidPluginException.class, () -> NativePaperContract.parse(
                "name: Example\nbiletools:\n  runtime-load: 'true'\n"));
        NativePaperContract.parse("name: Example\nbiletools:\n  runtime-load: true\n").validate();
    }

    @Test
    public void bootstrapAndClasspathHaveSeparateCapabilityContracts() throws Exception {
        String descriptor = "name: Example\nbootstrapper: example.Bootstrap\nloader: example.Loader\n"
                + "biletools:\n  runtime-load: true\n";
        NativePaperContract noCapabilities = NativePaperContract.parse(descriptor);
        assertThrows(InvalidPluginException.class, noCapabilities::validate);
        NativePaperContract bootstrapOnly = NativePaperContract.parse(descriptor + "  runtime-bootstrap: true\n");
        assertThrows(InvalidPluginException.class, bootstrapOnly::validate);
        NativePaperContract complete = NativePaperContract.parse(descriptor
                + "  runtime-bootstrap: true\n  runtime-classpath: true\n");
        assertTrue(complete.bootstrapper());
        assertTrue(complete.loader());
        complete.validate();
    }

    @Test
    public void onlyValidatedServerBuildCanUseNativeRuntimeLoading() throws Exception {
        NativePaperSupport.validateVersion("26.3", 142, false);
        assertThrows(InvalidPluginException.class, () -> NativePaperSupport.validateVersion("26.3", 143, false));
        assertThrows(InvalidPluginException.class, () -> NativePaperSupport.validateVersion("26.2", 142, false));
        assertThrows(InvalidPluginException.class, () -> NativePaperSupport.validateVersion("26.3", 142, true));
    }
}
