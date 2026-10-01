# CheckMyKnowledge

App Android de exámenes de opción múltiple para alumnos, con reglas anti-trampa.
El profesor administra preguntas y ve resultados desde un panel web (`admin.html`).

## Cómo está armado

| Archivo | Qué es |
|---|---|
| `src/main/java/.../MainActivity.java` | Cascarón nativo: abre un WebView con la app y hace cumplir las reglas anti-trampa. |
| `src/main/assets/index.html` | La app del alumno (HTML + JS puro, sin librerías). Habla con Supabase. |
| `admin.html` | Panel del profesor (login, CRUD de preguntas, importar examen por script, resultados). Se abre en un navegador; no va dentro del APK. |
| `src/main/AndroidManifest.xml` | Permisos y bloqueo de orientación / multiventana. |
| `build.gradle.kts`, `settings.gradle.kts` | Compilación Gradle. |
| `.github/workflows/build.yml` | Compila el APK en GitHub Actions. |

Backend: Supabase (tablas `materias`, `preguntas`, `resultados`; función RPC `calificar_examen`).

## Reglas anti-trampa (en `MainActivity.java`)

Mientras el examen está activo, la app se **cierra por completo** y el alumno empieza de cero si:

- pierde el foco (notificaciones, ventanas flotantes),
- va a Inicio, Recientes o cambia de app,
- el sensor de proximidad queda tapado 800 ms seguidos (celular pegado a la pantalla).

Además: capturas y grabación de pantalla bloqueadas (`FLAG_SECURE`), botón Atrás desactivado,
sin orientación horizontal ni multiventana, sin navegación a otros sitios.

### Sensor de proximidad: detalles y límites

- Se considera "cerca" una lectura menor a `min(rangoMáximoDelSensor, 5 cm)` (`DISTANCIA_CERCA_CM`), el mismo criterio que usa Android para apagar la pantalla en llamadas.
- Solo detecta algo pegado al **borde superior** de la pantalla (donde está el sensor, junto a la bocina). Un segundo celular puesto al lado o más abajo **no** se detecta: es una defensa parcial, no una garantía.
- Si el equipo no tiene sensor, la defensa simplemente no se activa en ese equipo.

### Modo depuración del sensor (`DEPURAR_SENSOR`)

Agregado el 1/oct/2026 porque en una prueba real al poner otro celular encima no pasó nada y no había forma de saber la causa.

Con `DEPURAR_SENSOR = true` en `MainActivity.java`, la app muestra avisos (Toast) con:

- el sensor detectado, su rango máximo y si se registró bien (o `SIN SENSOR DE PROXIMIDAD`),
- cada lectura (`valor`, `umbral`, `cerca`, `examenActivo`).

Cómo interpretarlo con el examen empezado y tapando el sensor:

| Qué ves | Causa |
|---|---|
| No aparece ninguna lectura | El sensor no entrega eventos o no se está tapando el sensor correcto |
| `cerca=true examenActivo=false` | Falla el puente JS (`iniciarExamen` no llega) |
| `cerca=true examenActivo=true` y no se cierra | Bug en el temporizador |

Los Toast no roban el foco, así que no anulan el examen por sí mismos.

**⚠ Antes de repartir el APK a los alumnos, poner `DEPURAR_SENSOR = false`.**

## Reglas para quien modifique el código (persona o IA)

1. **Comenta el código.** Todo cambio debe explicar el *porqué*, no solo el qué. Los comentarios son obligatorios; este repo prioriza comentarios claros para que cualquiera pueda retomarlo.
2. **Entrega archivos completos y listos para descargar**, no fragmentos.
3. **Actualiza este README** en cada cambio relevante, para mantener informado el estado del proyecto.
4. **Nunca se envía la respuesta correcta al cliente.** `index.html` solo pide `texto, opciones, contexto, seccion`; la calificación la hace la función `calificar_examen` en Supabase. No agregar `correcta` al `select` del cliente.
5. **Cualquier regla anti-trampa nueva debe terminar en `anularExamen()`** de `MainActivity.java`.
6. **Los links externos romperían el examen**: abrir otra app (p. ej. YouTube) hace que la app pierda el foco y se anule. Por eso los videos van *embebidos* (iframe), nunca como link.
7. **El puente JS↔Java** (`Puente`, expuesto como `window.Android`) tiene tres métodos: `iniciarExamen`, `terminarExamen`, `salir`. Si cambias nombres, cámbialos en `index.html` también.
8. **No volver a agregar pasos al workflow que hagan commit/push** al repo (causaron un run fallido; ver nota en `build.yml`).
9. La clave `anon` de Supabase en `index.html` y `admin.html` es pública por diseño; la seguridad real está en las políticas RLS de la base de datos.
10. **`DEPURAR_SENSOR` debe estar en `false`** en cualquier APK que se reparta a alumnos.

## Compilar

Push a `main` (o "Run workflow" en la pestaña Actions). El APK queda como artifact `checkmyknowledge-apk`.

## Historial de cambios

- **1/oct/2026** — Modo depuración del sensor de proximidad (`DEPURAR_SENSOR`), umbral de "cerca" alineado a `min(rangoMax, 5 cm)`, y reglas nuevas 1–3 y 10 en este README.
