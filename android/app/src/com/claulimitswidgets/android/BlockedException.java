package com.claulimitswidgets.android;

/**
 * La peticion no llego al endpoint: 403, cabecera `cf-mitigated`, o HTML donde se esperaba JSON.
 * No es lo mismo que una sesion vencida (ahi hay que re-login) ni que un formato cambiado
 * (ahi hay que avisar al proyecto): aqui toca esperar y reintentar.
 */
public final class BlockedException extends Exception {
    private static final long serialVersionUID = 1L;
    public BlockedException(String message) { super(message); }
}
