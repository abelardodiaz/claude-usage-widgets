# Backlog

Detalles conocidos que no bloquean una versión. Cada uno dice de dónde salió y qué arreglo se
propone. Al arreglarlo, se borra de aquí y se menciona en el PR.

## Lite (Windows, `desktop/lite/`)

Salen de la re-revisión del PR #43 (2026-10-10). Menores: ninguno impidió publicar v0.1.4.

1. **Una segunda instancia no trae la ventana al frente.** Si el lite ya está corriendo y se abre
   otra vez, la nueva instancia avisa a la viva con `PostMessage`, pero Windows le niega el
   `SetForegroundWindow` porque el foco lo tiene otro proceso. Arreglo: la segunda instancia llama
   `AllowSetForegroundWindow(pid)` con el pid de la viva antes del `PostMessage`.
2. **Al despertar el equipo puede salir un error unos 2 minutos.** En `WM_POWERBROADCAST` el lite
   fuerza una consulta de inmediato, y el Wi-Fi todavía no reconecta, así que falla y espera al
   siguiente intervalo. Arreglo: esperar 10-15 s después de reanudar antes de forzar la consulta, o
   reintentar una vez a los 15 s si la primera falla por red.
3. **Con "Siempre encima" apagado, un clic en la bandeja oculta la ventana aunque esté tapada.** Lo
   esperado es que, si otra ventana la cubre, el clic la traiga al frente, y solo la oculte si ya
   está a la vista. Arreglo: en el clic de bandeja, si la ventana es visible pero no es la de
   primer plano, traerla al frente en vez de ocultarla.
4. **El autoarranque apunta al exe viejo al bajar una versión nueva.** El archivo del release lleva
   la versión en el nombre (`claude-usage-widgets-lite_<v>_x64.exe`). Si alguien lo ejecuta desde
   Descargas y activa "Iniciar con Windows", el valor de `Run` queda con esa ruta; al bajar la
   siguiente versión con otro nombre, Windows sigue abriendo la anterior. `instalar.ps1` ya evita
   esto copiando a una ruta fija. Arreglo: al arrancar, si el autoarranque está activo y apunta a
   otro exe, actualizar el valor de `Run` a la ruta actual; o publicar el exe sin versión en el
   nombre.
