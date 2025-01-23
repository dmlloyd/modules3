package io.github.dmlloyd.modules;

import java.io.Serial;

/**
 * An exception indicating that module was not found.
 */
public class ModuleNotFoundException extends ModuleLoadException {
    @Serial
    private static final long serialVersionUID = 1537142487227363108L;

    /**
     * Constructs a new {@code ModuleNotFoundException} instance.  The message is left blank ({@code null}), and no
     * cause is specified.
     */
    public ModuleNotFoundException() {
    }

    /**
     * Constructs a new {@code ModuleNotFoundException} instance with an initial message.  No
     * cause is specified.
     *
     * @param msg the message
     */
    public ModuleNotFoundException(final String msg) {
        super(msg);
    }

    /**
     * Constructs a new {@code ModuleNotFoundException} instance with an initial cause.  If
     * a non-{@code null} cause is specified, its message is used to initialize the message of this
     * {@code ModuleNotFoundException}; otherwise the message is left blank ({@code null}).
     *
     * @param cause the cause
     */
    public ModuleNotFoundException(final Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new {@code ModuleNotFoundException} instance with an initial message and cause.
     *
     * @param msg   the message
     * @param cause the cause
     */
    public ModuleNotFoundException(final String msg, final Throwable cause) {
        super(msg, cause);
    }

    ModuleNotFoundException withMessage(final String newMsg) {
        ModuleNotFoundException newEx = new ModuleNotFoundException(newMsg);
        newEx.setStackTrace(getStackTrace());
        return newEx;
    }
}
