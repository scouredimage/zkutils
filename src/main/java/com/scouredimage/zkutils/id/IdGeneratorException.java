package com.scouredimage.zkutils.id;

public class IdGeneratorException extends RuntimeException {

    private static final long serialVersionUID = 5355385287608433524L;

    public enum Code {
        RETRIES_EXHAUSTED,
        COUNTER_MISSING,
        COUNTER_EXISTS,
        TRANSACTION_INTERRUPTED,
        UNKNOWN,
    }
    
    private final Code code;

    public IdGeneratorException(final Code code, final String msg) {
        super(msg);
        this.code = code;
    }
    
    public IdGeneratorException(final Code code, final Throwable cause) {
        super(cause);
        this.code = code;
    }

    public IdGeneratorException(final Code code, final String msg, final Throwable cause) {
        super(msg, cause);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }

}
