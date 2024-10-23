package io.github.dmlloyd.modules.desc;

import java.util.List;

import io.smallrye.common.constraint.Assert;

/**
 *
 */
public record Provide(
    String serviceName,
    List<String> withClasses
) {
    public Provide {
        Assert.checkNotNullParam("serviceName", serviceName);
        withClasses = List.copyOf(withClasses);
    }
}
