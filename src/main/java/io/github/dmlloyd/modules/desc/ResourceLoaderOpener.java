package io.github.dmlloyd.modules.desc;

import java.io.IOException;

import io.smallrye.common.resource.ResourceLoader;

/**
 * An opener for a resource loader.
 * Resource loaders are opened when a module is defined and closed when a module is unloaded.
 * The openers may be executed under a lock, so they should not block for long periods of time or depend on other locks.
 */
public interface ResourceLoaderOpener {
    /**
     * Open the resource loader.
     *
     * @return the resource loader (must not be {@code null})
     * @throws IOException if the resource loader cannot be opened due to an I/O error
     * @throws RuntimeException if the resource loader cannot be opened for some other reason
     */
    ResourceLoader open() throws IOException, RuntimeException;

    /**
     * An opener that just returns the given loader always.
     *
     * @param loader the loader (must not be {@code null})
     * @return the opener (not {@code null})
     */
    static ResourceLoaderOpener forLoader(ResourceLoader loader) {
        return () -> loader;
    }
}
