
module io.github.dmlloyd.modules {
    requires transitive java.xml;
    requires java.logging;

    requires org.jboss.logging;

    requires io.smallrye.classfile;

    requires io.smallrye.common.constraint;
    requires io.smallrye.common.cpu;
    requires io.smallrye.common.os;
    requires transitive io.smallrye.common.resource;

    exports io.github.dmlloyd.modules;
    exports io.github.dmlloyd.modules.desc;

    uses java.util.logging.LogManager;
}
