package com.volmit.bile.fixture;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.JarLibrary;

public final class NativeClasspath implements PluginLoader {
    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        classpathBuilder.addLibrary(new JarLibrary(classpathBuilder.getContext()
                .getDataDirectory().resolve("fixture-library.jar")));
    }
}
