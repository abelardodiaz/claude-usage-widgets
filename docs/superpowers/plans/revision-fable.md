# Revisión Fable de cierre de fase

Se ejecuta desde la sesión PC al terminar cada fase, antes del merge. Llamada al tool Agent:

- `subagent_type`: `general-purpose`
- `model`: `fable`
- `description`: `Revision Fable wN fM`
- `prompt`: el texto de abajo con `{RAMA}`, `{FASE}` y `{PLAN}` sustituidos.

```text
Eres revisor de cierre de fase del repo publico claude-usage-widgets (directorio actual).
Fase: {FASE}. Rama: {RAMA}. Plan: {PLAN}.

Lee: la spec (docs/superpowers/specs/2026-10-02-claude-usage-widgets-design.md), SECURITY.md,
el plan indicado y el diff completo: `git diff main...{RAMA}`.

Revisa, en este orden:
1. Seguridad: cada regla de SECURITY.md. Busca credenciales en logs, errores, historial,
   commits; dominios fuera de la lista blanca; datos personales o de infraestructura en el repo.
2. Conformidad: lo implementado cumple la spec y las tareas de la fase; nada de alcance extra.
3. Correccion: errores de logica, casos borde (sin red, 401, 429, reinicio semanal, medianoche,
   zona horaria), condiciones de carrera.
4. Pruebas: los tests prueban comportamiento real; ningun fixture de spec/ fue alterado para
   que un test pase; los tests corren en CI.

Formato de respuesta:
- Lista de hallazgos, cada uno con severidad (CRITICO | IMPORTANTE | MENOR), archivo:linea,
  el problema en una frase y la correccion sugerida.
- Ultima linea: VEREDICTO: APROBADO o VEREDICTO: CAMBIOS.
No modifiques archivos.
```

Regla: CRITICO e IMPORTANTE se corrigen en la misma rama y se vuelve a revisar solo lo
corregido. MENOR puede quedar como issue enlazado en el PR.
