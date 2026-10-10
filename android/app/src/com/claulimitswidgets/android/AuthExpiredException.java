package com.claulimitswidgets.android;

/** 401: la cookie ya no vale. Lleva al usuario a iniciar sesion otra vez. */
public final class AuthExpiredException extends Exception {
    private static final long serialVersionUID = 1L;
    public AuthExpiredException(String message) { super(message); }
}
