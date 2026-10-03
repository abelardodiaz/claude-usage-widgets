package com.claudewidgets.core;

/**
 * La respuesta no tiene la forma que el contrato espera. Nunca se inventa un 0 % (R1): es
 * preferible no mostrar nada a mostrar un dato falso.
 */
public final class UnrecognizedFormatException extends Exception {
    private static final long serialVersionUID = 1L;

    public UnrecognizedFormatException(String message) { super(message); }

    public UnrecognizedFormatException(String message, Throwable cause) { super(message, cause); }
}
