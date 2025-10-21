import io.github.dmlloyd.modules.NativeAccess;

@NativeAccess
module io.github.dmlloyd.modules {
    requires org.jboss.logging;

    requires io.github.dmlloyd.classfile;
    requires io.smallrye.common.constraint;
    requires transitive io.smallrye.common.resource;
    requires transitive java.xml;
    requires java.logging;

    // todo: only do this on packaging
    requires java.se;
    requires jdk.unsupported;

    exports io.github.dmlloyd.modules;
    exports io.github.dmlloyd.modules.desc;

    uses java.util.logging.LogManager;
}
