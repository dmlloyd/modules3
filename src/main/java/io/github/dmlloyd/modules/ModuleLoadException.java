package io.github.dmlloyd.modules;

import java.io.Serial;

/**
 *
 */
public class ModuleLoadException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = - 8455560757184556454L;

    /**
     * Constructs a new {@code ModuleLoadException} instance.  The message is left blank ({@code null}), and no
     * cause is specified.
     */
    public ModuleLoadException() {
    }

    /**
     * Constructs a new {@code ModuleLoadException} instance with an initial message.  No
     * cause is specified.
     *
     * @param msg the message
     */
    public ModuleLoadException(final String msg) {
        super(msg);
    }

    /**
     * Constructs a new {@code ModuleLoadException} instance with an initial cause.  If
     * a non-{@code null} cause is specified, its message is used to initialize the message of this
     * {@code ModuleLoadException}; otherwise the message is left blank ({@code null}).
     *
     * @param cause the cause
     */
    public ModuleLoadException(final Throwable cause) {
        super(cause);
    }

    /**
     * Constructs a new {@code ModuleLoadException} instance with an initial message and cause.
     *
     * @param msg   the message
     * @param cause the cause
     */
    public ModuleLoadException(final String msg, final Throwable cause) {
        super(msg, cause);
    }

    ModuleLoadException withMessage(final String newMsg) {
        ModuleLoadException newEx = new ModuleLoadException(newMsg);
        newEx.setStackTrace(getStackTrace());
        return newEx;
    }
}
