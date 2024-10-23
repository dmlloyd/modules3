package io.github.dmlloyd.modules.desc;

/**
 * A modifier for a module descriptor item.
 */
public sealed interface ModifierFlag permits Dependency.Modifier, Export.Modifier, ModuleDescriptor.Modifier, Open.Modifier {
}
