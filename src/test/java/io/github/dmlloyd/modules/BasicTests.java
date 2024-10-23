package io.github.dmlloyd.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;

import io.github.dmlloyd.modules.desc.Dependency;
import io.github.dmlloyd.modules.desc.Export;
import io.github.dmlloyd.modules.desc.Modifiers;
import io.github.dmlloyd.modules.desc.ModuleDescriptor;
import org.junit.jupiter.api.Test;

public final class BasicTests {

    @Test
    public void basics() throws ClassNotFoundException {
        ModuleLoader ml = new DelegatingModuleLoader("test", new ModuleFinder() {
            public ModuleDescriptor findModule(final String name) {
                return name.equals("hello") ? new ModuleDescriptor(
                    "hello",
                    Optional.of("1.2.3"),
                    Optional.of("test"),
                    Modifiers.of(),
                    Optional.empty(),
                    ModuleClassLoader::new,
                    List.of(
                        new Dependency(
                            "java.base",
                            Modifiers.of(Dependency.Modifier.MANDATORY, Dependency.Modifier.SYNTHETIC),
                            Optional.empty()
                        )
                    ),
                    Set.of(new Export("test.foobar", Modifiers.of(), Optional.empty())),
                    Set.of(),
                    Set.of(RandomGenerator.class.getName()),
                    Set.of(),
                    List.of(),
                    Set.of("test.foobar", "test.foobar.impl")
                ) : null;
            }
        }, ModuleLoader.BOOT);
        Module resolved = ml.loadModule("hello");
        assertNotNull(resolved);
        assertNotNull(resolved.getClassLoader().loadClass("java.lang.Object"));
        assertEquals("1.2.3", resolved.getDescriptor().rawVersion().orElseThrow(NullPointerException::new));
    }

    @Test
    public void moduleDescriptorParsing() throws IOException {
        Module myMod = getClass().getModule();
        InputStream mi = myMod.getResourceAsStream("module-info.class");
        assertNotNull(mi);
        byte[] bytes;
        try (mi) {
            bytes = mi.readAllBytes();
        }
        ModuleDescriptor desc = ModuleDescriptor.fromModuleInfo(bytes, myMod::getPackages);
        assertEquals(myMod.getName(), desc.name());
    }
}
