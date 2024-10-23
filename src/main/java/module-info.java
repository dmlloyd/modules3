import io.github.dmlloyd.modules.NativeAccess;

@NativeAccess
module io.github.dmlloyd.modules {
    requires org.jboss.logging;

    requires io.github.dmlloyd.classfile;
    requires io.smallrye.common.constraint;
    requires io.smallrye.common.resource;

    exports io.github.dmlloyd.modules;
    exports io.github.dmlloyd.modules.desc;
}
